package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.bloquearLocal
import com.qrsecurity.detector.datos.repositorios.desbloquearLocal
import com.qrsecurity.detector.datos.repositorios.procesarPendingOp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S6 — TDD red phase.
 *
 * Bug: [bloquearLocal] hace early-return si `obtenerPorUrl(url) != null`, pero
 * ese lookup NO excluye filas con DELETE pendiente (a diferencia de
 * observarTodos). Escenario: usuario desbloquea X (DELETE encolado) → el PULL
 * resucita X (fila dirty=0, DELETE sigue en cola) → el usuario re-bloquea X →
 * bloquearLocal devuelve el id existente SIN encolar nada → el DELETE se
 * pushea y el bloqueo nuevo del usuario se pierde en ambos lados.
 *
 * Fix: si la fila existente tiene un DELETE pendiente, CANCELAR ese op y
 * re-encolar un CREATE (fila dirty=1) para que el push re-cree en servidor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class RebloqueoConDeletePendienteTest {

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
        repo = RepositorioUrlsBloqueadas(
            db = db,
            backend = ClienteBackend(
                baseUrl = server.url("/").toString(),
                tokenProvider = { "test-token" }
            ),
            json = json,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    /** Simula el PULL que resucita la fila borrada localmente (INSERT OR REPLACE, dirty=0). */
    private suspend fun resucitarPorPull(id: String, url: String) {
        val ahora = System.currentTimeMillis()
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = id,
                url = url,
                razon = "original",
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
    }

    @Test
    fun `re-bloquear URL con DELETE pendiente cancela el DELETE y encola CREATE`() =
        runTest(testDispatcher) {
            val url = "evil.com"
            val idServidor = "srv-1"

            // ── 1. Fila synced viva ──
            resucitarPorPull(idServidor, url)

            // ── 2. Desbloquear: fila fuera, DELETE(srv-1) encolado ──
            repo.desbloquearLocal(idServidor)
            assertEquals(0, db.urlBloqueadaDao().todosLosIds().size)
            assertNotNull(
                db.pendingOpDao().findExisting(
                    tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                    idLocal = idServidor,
                    tipoOperacion = PendingOpEntity.OP_DELETE
                )
            )

            // ── 3. PULL resucita la fila (backend aun no proceso el DELETE) ──
            resucitarPorPull(idServidor, url)

            // ── 4. Re-bloquear sobre la fila resucitada con DELETE pendiente ──
            val idRetornado = repo.bloquearLocal(url, "re-bloqueado")

            // Then: el DELETE fue cancelado (no hay op DELETE)...
            assertNull(
                "el op DELETE del desbloqueo debe cancelarse al re-bloquear",
                db.pendingOpDao().findExisting(
                    tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                    idLocal = idServidor,
                    tipoOperacion = PendingOpEntity.OP_DELETE
                )
            )
            // ...y hay un CREATE que revierte la fila a dirty=1.
            assertNotNull(
                "debe encolarse un CREATE para re-crear el bloqueo en servidor",
                db.pendingOpDao().findExisting(
                    tabla = PendingOpEntity.TABLA_URLS_BLOQUEADAS,
                    idLocal = idRetornado,
                    tipoOperacion = PendingOpEntity.OP_CREATE
                )
            )
            assertEquals(idServidor, idRetornado)
            assertEquals(1, db.urlBloqueadaDao().todosLosIds().size)
            assertTrue(
                "la fila resucitada debe volver a dirty=1",
                db.urlBloqueadaDao().obtenerPorId(idServidor)!!.dirty
            )

            // ── 5. Push simulado: el CREATE llega al backend → bloqueo sobrevive ──
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id":"srv-1","url":"$url","razon":"re-bloqueado","creado_en":"2026-01-01T00:00:00Z"}"""
                )
            )
            val op = db.pendingOpDao().getById(db.pendingOpDao().minPendingId()!!)!!
            val exito = repo.procesarPendingOp(op, "test-token")
            assertTrue("el CREATE del re-bloqueo debe exitar", exito)

            val filaFinal = db.urlBloqueadaDao().obtenerPorId(idServidor)
            assertNotNull("el bloqueo debe sobrevivir al push", filaFinal)
            assertFalse("tras el push la fila queda synced", filaFinal!!.dirty)
            assertEquals(0, db.pendingOpDao().observarPendientes().first().size)
        }
}
