package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug M3 — TDD regression (fix aplicado).
 *
 * Verifica que `eliminarLocal`/`desbloquearLocal` con un id que NO existe
 * localmente NO encolen un DELETE op huerfano.
 *
 * ANTES del fix: `fila == null` caia al `else` → encolaba DELETE para un
 * UUID que no existe local → POST al backend daba 404 → tratado como success.
 * Wasteful (DELETE sin efecto), ensuciaba la cola con ops que se reintentan.
 *
 * DESPUES del fix: `fila == null` → early-return, sin encolar nada.
 *
 * Tambien se prueba el control positivo (fila synced EXISTENTE → SI encola
 * DELETE) para garantizar que el fix no rompio la rama normal de eliminacion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class EliminarIdInexistenteTest {

    private lateinit var db: BaseDatosSeguridad
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Escaneos ──────────────────────────────────────────────────────

    @Test
    fun `escaneos - eliminarLocal con id inexistente no encola DELETE`() = runTest(testDispatcher) {
        val repo = RepositorioEscaneos(db = db, backend = ClienteBackend(), json = json, ioDispatcher = testDispatcher)
        // Given: un escaneo synced existente (control: no debe borrarse)
        val idExistente = "escaneo-existente"
        db.escaneoDao().insertar(
            escaneoSynced(id = idExistente, url = "https://sana.com", urlLimpia = "sana.com")
        )

        // When: eliminar un id que no existe
        repo.eliminarLocal("uuid-inexistente")

        // Then: la cola queda vacia — NO se encolo DELETE huerfano
        assertNull(
            "eliminarLocal con id inexistente NO debe encolar DELETE op",
            db.pendingOpDao().minPendingId()
        )
        // Y el escaneo existente sigue intacto
        assertEquals(listOf(idExistente), db.escaneoDao().todosLosIds())
    }

    @Test
    fun `escaneos - eliminarLocal de fila synced existente SI encola DELETE - control positivo`() =
        runTest(testDispatcher) {
            val repo = RepositorioEscaneos(db = db, backend = ClienteBackend(), json = json, ioDispatcher = testDispatcher)
            val id = "escaneo-synced"
            db.escaneoDao().insertar(escaneoSynced(id = id, url = "https://malicioso.com", urlLimpia = "malicioso.com"))

            // When: eliminar una fila synced real
            repo.eliminarLocal(id)

            // Then: SI se encola DELETE (la fila existia y estaba synced)
            val opId = db.pendingOpDao().minPendingId()
            assertTrue("control: eliminar fila synced existente debe encolar DELETE", opId != null)
            val op = db.pendingOpDao().getById(opId!!)
            assertEquals("DELETE", op!!.tipoOperacion)
            assertEquals("escaneos", op.tabla)
            assertEquals(id, op.idLocal)
            // Y la fila local se borra
            assertNull(db.escaneoDao().obtenerPorId(id))
        }

    @Test
    fun `escaneos - eliminarLocalPorUrlLimpia con url sin filas no encola DELETE`() = runTest(testDispatcher) {
        val repo = RepositorioEscaneos(db = db, backend = ClienteBackend(), json = json, ioDispatcher = testDispatcher)
        // Given: un escaneo con urlLimpia distinta a la que se eliminara
        db.escaneoDao().insertar(escaneoSynced(id = "otro", url = "https://otra.com", urlLimpia = "otra.com"))

        // When: eliminar por urlLimpia que no tiene filas (idInesxistente en el listado)
        repo.eliminarLocalPorUrlLimpia("url-sin-filas")

        // Then: no se encola nada
        assertNull(
            "eliminarLocalPorUrlLimpia sin filas no debe encolar DELETE",
            db.pendingOpDao().minPendingId()
        )
        assertEquals(listOf("otro"), db.escaneoDao().todosLosIds())
    }

    // ── URLs bloqueadas ───────────────────────────────────────────────

    @Test
    fun `urls - desbloquearLocal con id inexistente no encola DELETE`() = runTest(testDispatcher) {
        val repo = RepositorioUrlsBloqueadas(db = db, backend = ClienteBackend(), json = json, ioDispatcher = testDispatcher)
        // Given: una URL synced existente (control: no debe borrarse)
        val idExistente = "url-existente"
        db.urlBloqueadaDao().insertar(urlSynced(id = idExistente, url = "sana.com"))

        // When: desbloquear un id que no existe
        repo.desbloquearLocal("uuid-inexistente")

        // Then: la cola queda vacia
        assertNull(
            "desbloquearLocal con id inexistente NO debe encolar DELETE op",
            db.pendingOpDao().minPendingId()
        )
        assertEquals(listOf(idExistente), db.urlBloqueadaDao().todosLosIds())
    }

    @Test
    fun `urls - desbloquearLocal de fila synced existente SI encola DELETE - control positivo`() =
        runTest(testDispatcher) {
            val repo = RepositorioUrlsBloqueadas(db = db, backend = ClienteBackend(), json = json, ioDispatcher = testDispatcher)
            val id = "url-synced"
            db.urlBloqueadaDao().insertar(urlSynced(id = id, url = "maliciosa.com"))

            // When: desbloquear una URL synced real
            repo.desbloquearLocal(id)

            // Then: SI se encola DELETE
            val opId = db.pendingOpDao().minPendingId()
            assertTrue("control: desbloquear fila synced existente debe encolar DELETE", opId != null)
            val op = db.pendingOpDao().getById(opId!!)
            assertEquals("DELETE", op!!.tipoOperacion)
            assertEquals("urls_bloqueadas", op.tabla)
            assertEquals(id, op.idLocal)
            assertNull(db.urlBloqueadaDao().obtenerPorId(id))
        }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun escaneoSynced(id: String, url: String, urlLimpia: String): EscaneoEntity =
        EscaneoEntity(
            id = id,
            urlOriginal = url,
            urlLimpia = urlLimpia,
            probabilidad = 0.1f,
            nivelAlerta = "SEGURO",
            delegado = null,
            esMalicioso = false,
            creadoEnMillis = System.currentTimeMillis(),
            dirty = false,
            syncedAtMillis = System.currentTimeMillis()
        )

    private fun urlSynced(id: String, url: String): UrlBloqueadaEntity =
        UrlBloqueadaEntity(
            id = id,
            url = url,
            razon = "test",
            creadoEnMillis = System.currentTimeMillis(),
            dirty = false,
            syncedAtMillis = System.currentTimeMillis()
        )
}