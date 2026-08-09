package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bug C1 — TDD regression (fix aplicado).
 *
 * Reproduce el race entre `eliminarLocal`/`desbloquearLocal` (rama dirty) y
 * `procesarCreate` cuando el POST ya fue enviado al backend:
 *
 *   1. registrarLocal/bloquearLocal → fila U-A dirty + pending op CREATE.
 *   2. SyncWorker reclama el op; el POST sale hacia el backend.
 *   3. Mientras el POST esta en vuelo, el usuario elimina la fila local
 *      (rama dirty: borra fila + op CREATE, NO encola DELETE).
 *   4. El POST devuelve 201 con id servidor U-B. El re-key/marcarSincronizado
 *      afecta 0 filas (la fila U-A ya no existe localmente), pero el backend
 *      YA persistio la fila bajo U-B.
 *   5. SIN el fix: pending_ops queda vacia → el proximo PULL trae U-B
 *      (dirty=false) → la fila "eliminada" resucita como fantasma.
 *   6. CON el fix: `procesarCreate` detecta 0 filas afectadas y encola un
 *      DELETE con el **id de servidor** (U-B). El proximo PUSH lo borra del
 *      backend y el PULL no reintroduce el fantasma.
 *
 * Este test verifica el comportamento 6: tras el race, pending_ops contiene
 * DELETE(idLocal=U-B) y procesarlo limpia la cola y el backend.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class RaceEliminarDurantePostTest {

    private lateinit var server: MockWebServer
    private lateinit var db: BaseDatosSeguridad
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
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun backend() = ClienteBackend(
        baseUrl = server.url("/").toString(),
        tokenProvider = { "test-token" }
    )

    @Test
    fun `escaneos - fila eliminada durante POST encola DELETE con id del servidor`() =
        runTest(testDispatcher) {
            val repo = RepositorioEscaneos(db = db, backend = backend(), json = json, ioDispatcher = testDispatcher)
            val idServidor = "uuid-servidor-B"

            // ── 1. registrarLocal → fila U-A dirty + CREATE op ──
            val idLocal = repo.registrarLocal(
                urlOriginal = "http://evil.com/phish",
                urlLimpia = "evil.com/phish",
                probabilidad = 0.99f,
                nivelAlerta = "MALICIOSO"
            )
            assertNotNull(db.escaneoDao().obtenerPorId(idLocal))

            // ── 2. SyncWorker captura el CREATE op (POST en vuelo) ──
            val opId = db.pendingOpDao().minPendingId()
            assertNotNull(opId)
            val opCreate = db.pendingOpDao().getById(opId!!)
            assertNotNull(opCreate)

            // ── 3. Mientras el POST esta en vuelo, el usuario elimina la fila ──
            // La rama dirty borra fila + op CREATE sin encolar DELETE.
            repo.eliminarLocal(idLocal)
            assertNull(db.escaneoDao().obtenerPorId(idLocal))
            assertNull("el op CREATE debe haber sido borrado por eliminarLocal", db.pendingOpDao().minPendingId())

            // ── 4. El POST responde 201 con id servidor U-B. reKey afecta 0 filas ──
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"$idServidor","url_original":"http://evil.com/phish","url_limpia":"evil.com/phish","probabilidad":0.99,"nivel_alerta":"MALICIOSO","es_malicioso":true,"creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val exitoCreate = repo.procesarPendingOp(opCreate!!, "test-token")
            assertTrue("CREATE debe procesarse como exito aun con fila eliminada en vuelo", exitoCreate)

            // ── 5. El fix: pending_ops debe contener DELETE(idLocal=U-B) ──
            val deleteOpId = db.pendingOpDao().minPendingId()
            assertNotNull("el fix debe encolar DELETE con el id del servidor", deleteOpId)
            val deleteOp = db.pendingOpDao().getById(deleteOpId!!)
            assertNotNull(deleteOp)
            assertEquals("DELETE", deleteOp!!.tipoOperacion)
            assertEquals("escaneos", deleteOp.tabla)
            assertEquals("el DELETE debe apuntar al id del servidor (U-B), no al id local", idServidor, deleteOp.idLocal)
            assertNull(deleteOp.payloadJson)
            assertNull("la fila U-A no debe existir", db.escaneoDao().obtenerPorId(idLocal))

            // ── 6. Procesar el DELETE contra el backend → 204 → cola limpia ──
            server.enqueue(MockResponse().setResponseCode(204))
            val exitoDelete = repo.procesarPendingOp(deleteOp, "test-token")
            assertTrue("DELETE compensatorio debe exitar", exitoDelete)
            assertNull("pending_ops debe quedar vacia", db.pendingOpDao().minPendingId())

            // El DELETE debe haber ido a /escaneos/{U-B} (id del servidor).
            server.takeRequest() // POST /escaneos
            val reqDelete = server.takeRequest()
            assertNotNull(reqDelete)
            assertTrue(
                "la peticion DELETE debe apuntar a /escaneos/{U-B}",
                reqDelete.path!!.contains("/escaneos/$idServidor", ignoreCase = true)
            )
        }

    @Test
    fun `urls bloqueadas - fila eliminada durante POST encola DELETE con id del servidor`() =
        runTest(testDispatcher) {
            val repo = RepositorioUrlsBloqueadas(db = db, backend = backend(), json = json, ioDispatcher = testDispatcher)
            val idServidor = "uuid-servidor-B"
            val url = "evil.com"

            // ── 1. bloquearLocal → fila U-A dirty + CREATE op ──
            val idLocal = repo.bloquearLocal(url, "test")
            assertEquals(1, db.urlBloqueadaDao().todosLosIds().size)

            // ── 2. SyncWorker captura el CREATE op (POST en vuelo) ──
            val opId = db.pendingOpDao().minPendingId()
            assertNotNull(opId)
            val opCreate = db.pendingOpDao().getById(opId!!)
            assertNotNull(opCreate)

            // ── 3. Mientras el POST esta en vuelo, el usuario desbloquea la URL ──
            // La rama dirty borra fila + op CREATE sin encolar DELETE.
            repo.desbloquearLocal(idLocal)
            assertEquals(0, db.urlBloqueadaDao().todosLosIds().size)
            assertNull("el op CREATE debe haber sido borrado por desbloquearLocal", db.pendingOpDao().minPendingId())

            // ── 4. El POST responde 201 con id servidor U-B. reKey afecta 0 filas ──
            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"$idServidor","url":"$url","razon":"test","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val exitoCreate = repo.procesarPendingOp(opCreate!!, "test-token")
            assertTrue("CREATE debe procesarse como exito aun con fila eliminada en vuelo", exitoCreate)

            // ── 5. El fix: pending_ops debe contener DELETE(idLocal=U-B) ──
            val deleteOpId = db.pendingOpDao().minPendingId()
            assertNotNull("el fix debe encolar DELETE con el id del servidor", deleteOpId)
            val deleteOp = db.pendingOpDao().getById(deleteOpId!!)
            assertNotNull(deleteOp)
            assertEquals("DELETE", deleteOp!!.tipoOperacion)
            assertEquals("urls_bloqueadas", deleteOp.tabla)
            assertEquals("el DELETE debe apuntar al id del servidor (U-B), no al id local", idServidor, deleteOp.idLocal)
            assertNull(deleteOp.payloadJson)
            assertEquals("la fila U-A no debe existir", 0, db.urlBloqueadaDao().todosLosIds().size)

            // ── 6. Procesar el DELETE contra el backend → 204 → cola limpia ──
            server.enqueue(MockResponse().setResponseCode(204))
            val exitoDelete = repo.procesarPendingOp(deleteOp, "test-token")
            assertTrue("DELETE compensatorio debe exitar", exitoDelete)
            assertNull("pending_ops debe quedar vacia", db.pendingOpDao().minPendingId())

            // El DELETE debe haber ido a /urls-bloqueadas/{U-B} (id del servidor).
            server.takeRequest() // POST /urls-bloqueadas
            val reqDelete = server.takeRequest()
            assertNotNull(reqDelete)
            assertTrue(
                "la peticion DELETE debe apuntar a /urls-bloqueadas/{U-B}",
                reqDelete.path!!.contains("/urls-bloqueadas/$idServidor", ignoreCase = true)
            )
        }
}