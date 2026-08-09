package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug re-bloqueo — TDD red phase.
 *
 * Reproduce el flujo completo offline-first que falla en produccion:
 *  1. bloquearLocal("evil.com") → inserta UUID-C1, dirty=true, encola CREATE
 *  2. procesarCreate(C1) → POST → backend resurrect → 201 id=UUID-B → reKey(C1→B)
 *  3. desbloquearLocal(B) → eliminarPorId(B), encola DELETE(B)
 *  4. bloquearLocal("evil.com") → inserta UUID-C2, dirty=true, encola CREATE
 *  5. sincronizarDesdeBackend → PULL: GET trae UUID-B (aun viva) → insertarTodos([B])
 *     Tabla local ahora tiene: [B(dirty=false), C2(dirty=true)]
 *  6. procesarDelete(B) → DELETE → 204 → eliminarPorId(B)
 *     Tabla local: [C2(dirty=true)]
 *  7. procesarCreate(C2) → POST → backend resurrect → 201 id=UUID-B
 *     → reKey(C2→B): UPDATE SET id=B WHERE id=C2
 *     B NO existe (fue borrada en paso 6) → deberia funcionar
 *
 * Pero si el PULL del paso 5 trae UUID-B y el PUSH DELETE del paso 6 NO la
 * elimina (porque el DELETE ya fue procesado en un ciclo anterior y no
 * esta en pending_ops), entonces UUID-B sigue en la tabla cuando el
 * reKey(C2→B) intenta cambiar C2→B → conflicto de PK → fallo.
 *
 * Escenario alternativo probado: el DELETE ya fue procesado en sync anterior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class RebloqueoResurrectTest {

    private lateinit var server: MockWebServer
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repo: RepositorioUrlsBloqueadas
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        val backend = ClienteBackend(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" }
        )
        repo = RepositorioUrlsBloqueadas(
            db = db,
            backend = backend,
            json = json,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    /**
     * Escenario completo: bloquear → sync (POST) → desbloquear → re-bloquear
     * → sync (PULL + PUSH DELETE + PUSH CREATE con resurrect).
     *
     * Este es el caso que EL USUARIO REPORTA COMO FALLIDO.
     */
    @Test
    fun `re-bloquear tras desbloquear con PULL intermedio hace reKey sin conflicto PK`() =
        runTest(testDispatcher) {
            val url = "evil.com"
            val idServidor = "uuid-servidor-B"

            // ── 1. bloquearLocal ──
            repo.bloquearLocal(url, "test")
            assertEquals(1, db.urlBloqueadaDao().todosLosIds().size)

            // ── 2. procesarCreate → POST → 201 id=uuid-servidor-B → reKey ──
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"$idServidor","url":"$url","razon":"test","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val op1 = db.pendingOpDao().minPendingId()
            assertNotNull(op1)
            val pendingOp1 = db.pendingOpDao().getById(op1!!)
            val exito1 = repo.procesarPendingOp(pendingOp1!!, "test-token")
            assertTrue("CREATE inicial debe exitar", exito1)

            // Verificar reKey: la fila ahora tiene id=uuid-servidor-B, dirty=false
            val idsTrasBlock = db.urlBloqueadaDao().todosLosIds()
            assertEquals(1, idsTrasBlock.size)
            assertEquals(idServidor, idsTrasBlock.first())

            // ── 3. desbloquearLocal(uuid-servidor-B) ──
            repo.desbloquearLocal(idServidor)
            assertEquals(0, db.urlBloqueadaDao().todosLosIds().size)
            // pending_ops: [DELETE(uuid-servidor-B)]

            // ── 4. re-bloquearLocal("evil.com") ──
            val idLocal2 = repo.bloquearLocal(url, "re-bloqueado")
            assertEquals(1, db.urlBloqueadaDao().todosLosIds().size)
            // pending_ops: [DELETE(uuid-servidor-B), CREATE(idLocal2)]

            // ── 5. PULL: GET /urls-bloqueadas → backend aun tiene uuid-servidor-B viva ──
            // El DELETE pendiente no se ha procesado, asi que el backend devuelve la fila.
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """[{"id":"$idServidor","url":"$url","razon":"test","creado_en":"2026-01-01T00:00:00Z"}]"""
            ))
            val pullResult = repo.sincronizarDesdeBackend("test-token")
            assertTrue("PULL debe ser exitoso", pullResult is com.qrsecurity.detector.datos.repositorios.ResultadoSync.Exitoso)

            // Despues del PULL: la tabla tiene [uuid-servidor-B (dirty=false), idLocal2 (dirty=true)]
            val idsTrasPull = db.urlBloqueadaDao().todosLosIds()
            assertEquals(2, idsTrasPull.size)
            assertTrue(idsTrasPull.contains(idServidor))
            assertTrue(idsTrasPull.contains(idLocal2))

            // ── 6. procesarDelete(uuid-servidor-B) → DELETE → 204 ──
            server.enqueue(MockResponse().setResponseCode(204))
            // Buscar el DELETE en pending_ops
            val deleteOpId = db.pendingOpDao().minPendingId()
            assertNotNull(deleteOpId)
            val deleteOp = db.pendingOpDao().getById(deleteOpId!!)
            // El op mas viejo deberia ser el DELETE (se encolo antes que el CREATE del re-bloqueo)
            val exitoDelete = repo.procesarPendingOp(deleteOp!!, "test-token")
            assertTrue("DELETE debe exitar", exitoDelete)

            // Verificar que uuid-servidor-B fue eliminada
            val idsTrasDelete = db.urlBloqueadaDao().todosLosIds()
            assertEquals(1, idsTrasDelete.size)
            assertEquals(idLocal2, idsTrasDelete.first())

            // ── 7. procesarCreate(idLocal2) → POST → 201 id=uuid-servidor-B (resurrect) ──
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"$idServidor","url":"$url","razon":"re-bloqueado","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val createOpId = db.pendingOpDao().minPendingId()
            assertNotNull(createOpId)
            val createOp = db.pendingOpDao().getById(createOpId!!)
            val exitoCreate = repo.procesarPendingOp(createOp!!, "test-token")

            // ESTA ES LA AFIRMACION QUE DEBE FALLAR SI EL BUG EXISTE:
            assertTrue("re-bloqueo CREATE debe exitar tras resurrect", exitoCreate)

            // La fila local debe tener id=uuid-servidor-B, dirty=false
            val idsFinal = db.urlBloqueadaDao().todosLosIds()
            assertEquals(1, idsFinal.size)
            assertEquals(idServidor, idsFinal.first())

            // pending_ops debe estar vacia
            assertEquals(null, db.pendingOpDao().minPendingId())
        }

    /**
     * Escenario: el DELETE ya fue procesado en un sync anterior.
     * El backend ya tiene uuid-servidor-B como tombstone.
     * El PULL no trae la fila (filtrada por deleted_at IS NULL).
     * El re-bloqueo POST resurrecta → 201 id=uuid-servidor-B → reKey.
     */
    @Test
    fun `re-bloquear cuando DELETE ya fue procesado y PULL no trae tombstone`() =
        runTest(testDispatcher) {
            val url = "evil.com"
            val idServidor = "uuid-servidor-B"

            // Estado inicial: la fila ya esta synced con id=uuid-servidor-B
            val ahora = System.currentTimeMillis()
            val entidad = com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity(
                id = idServidor,
                url = url,
                razon = "test",
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
            db.urlBloqueadaDao().insertar(entidad)

            // 1. Desbloquear
            repo.desbloquearLocal(idServidor)
            assertEquals(0, db.urlBloqueadaDao().todosLosIds().size)

            // 2. Procesar DELETE → 204
            server.enqueue(MockResponse().setResponseCode(204))
            val deleteOpId = db.pendingOpDao().minPendingId()
            val deleteOp = db.pendingOpDao().getById(deleteOpId!!)
            val exitoDelete = repo.procesarPendingOp(deleteOp!!, "test-token")
            assertTrue(exitoDelete)
            assertEquals(0, db.urlBloqueadaDao().todosLosIds().size)
            assertEquals(null, db.pendingOpDao().minPendingId())

            // 3. Re-bloquear
            repo.bloquearLocal(url, "re-bloqueado")
            assertEquals(1, db.urlBloqueadaDao().todosLosIds().size)

            // 4. PULL: GET → backend filtra deleted_at IS NULL → no trae nada
            server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
            val pullResult = repo.sincronizarDesdeBackend("test-token")
            assertTrue(pullResult is com.qrsecurity.detector.datos.repositorios.ResultadoSync.Exitoso)

            // orphan cleanup: idLocal2 tiene dirty=true → no se borra
            repo.limpiarHuerfanos(emptyList())
            assertEquals(1, db.urlBloqueadaDao().todosLosIds().size)

            // 5. procesarCreate → POST → resurrect → 201 id=uuid-servidor-B
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"$idServidor","url":"$url","razon":"re-bloqueado","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val createOpId = db.pendingOpDao().minPendingId()
            val createOp = db.pendingOpDao().getById(createOpId!!)
            val exitoCreate = repo.procesarPendingOp(createOp!!, "test-token")

            assertTrue("re-bloqueo CREATE debe exitar (resurrect sin conflicto PK)", exitoCreate)

            val idsFinal = db.urlBloqueadaDao().todosLosIds()
            assertEquals(1, idsFinal.size)
            assertEquals(idServidor, idsFinal.first())
            assertEquals(null, db.pendingOpDao().minPendingId())
        }

    /**
     * Escenario Critico: PULL reinserta uuid-servidor-B DESPUES de que el
     * DELETE ya fue procesado pero ANTES de que el CREATE del re-bloqueo
     * se procese. Esto ocurre cuando hay dos ciclos de sync:
     *
     * Ciclo 1: PULL trae B (viva) → insertarTodos → procesarDelete(B) → 204
     *          → eliminarPorId(B) → procesarCreate(C2) → POST → resurrect
     *          → 201 id=B → reKey(C2→B) → B fue borrada → OK
     *
     * Pero si el Worker se cancela entre el DELETE y el CREATE:
     * Ciclo 1: PULL → insertarTodos([B]) → procesarDelete(B) → 204 → eliminarPorId(B)
     *          → Worker cancelado (isStopped) → CREATE(C2) queda en cola
     * Ciclo 2: PULL → GET → backend ya hizo soft-delete de B → no la trae
     *          → orphan cleanup no borra C2 (dirty=true) → procesarCreate(C2)
     *          → POST → resurrect → 201 id=B → reKey(C2→B) → OK
     *
     *Pero que pasa si el backend resurrecta B en el Ciclo 1 (porque el
     * POST del CREATE se envio antes del DELETE) y luego el DELETE del
     * Ciclo 1 la vuelve a soft-deletelear? El orden del SyncWorker es
     * PUSH oldest-first, asi que DELETE(B) va antes que CREATE(C2).
     */
    @Test
    fun `re-bloquear con PULL que reinserta B y reKey colisiona con B existente`() =
        runTest(testDispatcher) {
            val url = "evil.com"
            val idServidor = "uuid-servidor-B"

            // Estado inicial: B ya synced, tabla local tiene B
            val ahora = System.currentTimeMillis()
            db.urlBloqueadaDao().insertar(
                com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity(
                    id = idServidor, url = url, razon = "test",
                    creadoEnMillis = ahora, dirty = false, syncedAtMillis = ahora
                )
            )

            // 1. Desbloquear B
            repo.desbloquearLocal(idServidor)
            // Tabla: []  pending_ops: [DELETE(B)]

            // 2. Re-bloquear "evil.com"
            val idLocal2 = repo.bloquearLocal(url, "re-bloqueado")
            // Tabla: [C2(dirty=true)]  pending_ops: [DELETE(B), CREATE(C2)]

            // 3. PULL: GET → backend aun tiene B viva (DELETE no procesado)
            //    → insertarTodos([B]) → Tabla: [B(dirty=false), C2(dirty=true)]
            server.enqueue(MockResponse().setResponseCode(200).setBody(
                """[{"id":"$idServidor","url":"$url","razon":"test","creado_en":"2026-01-01T00:00:00Z"}]"""
            ))
            repo.sincronizarDesdeBackend("test-token")
            repo.limpiarHuerfanos(listOf(idServidor)) // B esta en servidor → no orphan

            val idsTrasPull = db.urlBloqueadaDao().todosLosIds()
            assertEquals(2, idsTrasPull.size)

            // 4. PUSH DELETE(B): DELETE → 204 → eliminarPorId(B)
            server.enqueue(MockResponse().setResponseCode(204))
            val opDeleteId = db.pendingOpDao().minPendingId()
            val opDelete = db.pendingOpDao().getById(opDeleteId!!)
            repo.procesarPendingOp(opDelete!!, "test-token")

            // Tabla: [C2(dirty=true)]
            val idsTrasDelete = db.urlBloqueadaDao().todosLosIds()
            assertEquals(1, idsTrasDelete.size)
            assertEquals(idLocal2, idsTrasDelete.first())

            // 5. PUSH CREATE(C2): POST → resurrect → 201 id=B → reKey(C2→B)
            //    B fue eliminada en paso 4 → reKey no colisiona → OK
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"$idServidor","url":"$url","razon":"re-bloqueado","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val opCreateId = db.pendingOpDao().minPendingId()
            val opCreate = db.pendingOpDao().getById(opCreateId!!)
            val exito = repo.procesarPendingOp(opCreate!!, "test-token")

            assertTrue("re-bloqueo con reKey tras PULL debe exitar", exito)
            assertEquals(idServidor, db.urlBloqueadaDao().todosLosIds().first())
        }
}
