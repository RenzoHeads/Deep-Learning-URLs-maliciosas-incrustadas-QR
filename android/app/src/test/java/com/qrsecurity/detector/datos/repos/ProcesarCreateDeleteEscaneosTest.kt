package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
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
 * m10 (FASE 5 del plan de correccion): cobertura de
 * [RepositorioEscaneos.procesarPendingOp] — el PUSH de escaneos del
 * SyncWorker — frente a codigos HTTP permanentes vs transitorios.
 *
 * Bug auditado: [RepositorioEscaneos.procesarCreate] trataba SOLO el 409
 * como exito y devolvia `false` para cualquier otro codigo. Un 400
 * (peticion invalida permanente, p.ej. URL > 2048 chars o payload
 * malformado) quedaba en cola reintentandose hasta agotar
 * MAX_INTENTOS_OP — un retry loop infinito sin posibilidad de exito.
 * Con el fix (espejo de RepositorioDenuncias m8), el 400 marca el op
 * como `fallida` permanente y lo saca de la cola.
 *
 * Este test usa MockWebServer y un op con `payloadJson` serializado
 * (SIN fila local) para aislar el comportamiento de la cola del estado
 * de la tabla room.
 *
 * Cubre:
 *   1. CREATE 400 → [RED] true + op marcado fallida (sale de la cola).
 *   2. CREATE 500 → false, el op sigue pendiente (retry con backoff).
 *   3. DELETE 404 → true idempotente (el row ya no existe en backend),
 *      op consumido de la cola.
 *   4. DELETE 500 → false, el op sigue pendiente (retry).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ProcesarCreateDeleteEscaneosTest {

    private lateinit var server: MockWebServer
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repo: RepositorioEscaneos
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
        repo = RepositorioEscaneos(
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

    private suspend fun encolarOp(tipoOperacion: String, idLocal: String, payloadJson: String?): PendingOpEntity {
        val op = PendingOpEntity(
            tabla = "escaneos",
            tipoOperacion = tipoOperacion,
            idLocal = idLocal,
            payloadJson = payloadJson,
            creadoEnMillis = 1_000L
        )
        db.pendingOpDao().insertar(op)
        return db.pendingOpDao().getById(db.pendingOpDao().minPendingId()!!)!!
    }

    private val payloadCreate =
        """{"id":"esc-local-1","urlOriginal":"http://evil.com","urlLimpia":"evil.com",""" +
            """"probabilidad":0.99,"nivelAlerta":"MALICIOSO","delegado":null,""" +
            """"esMalicioso":true,"creadoEnMillis":1000,"dirty":true,""" +
            """"syncedAtMillis":null,"notasAnalisis":null}"""

    @Test
    fun `CREATE 400 - op marcado fallida permanente y sacado de la cola`() =
        runTest(testDispatcher) {
            val op = encolarOp("CREATE", "esc-local-1", payloadCreate)
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"url invalida"}"""))

            val exito = repo.procesarPendingOp(op, "test-token")

            assertTrue("un 400 permanente debe dejar de reintentarse (true)", exito)
            assertEquals("la peticion debe haberse intentado contra el backend", 1, server.requestCount)
            val opFinal = db.pendingOpDao().getById(op.id)
            assertNotNull(opFinal)
            assertTrue("el op debe quedar marcado como fallida permanente", opFinal!!.fallida)
            assertNull(
                "un op fallido no debe volver a la cola de reintentos (minPendingId filtra fallida=0)",
                db.pendingOpDao().minPendingId()
            )
        }

    @Test
    fun `CREATE 500 - transitorio, el op sigue pendiente para retry`() =
        runTest(testDispatcher) {
            val op = encolarOp("CREATE", "esc-local-1", payloadCreate)
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"error interno"}"""))

            val exito = repo.procesarPendingOp(op, "test-token")

            assertFalse("un 500 es transitorio: procesarPendingOp debe devolver false", exito)
            assertEquals(1, server.requestCount)
            val opFinal = db.pendingOpDao().getById(op.id)
            assertNotNull("el op debe permanecer en la cola para reintentar", opFinal)
            assertFalse("el op NO debe marcarse fallida ante un 500", opFinal!!.fallida)
            assertEquals("el op debe seguir siendo el mas viejo pendiente", op.id, db.pendingOpDao().minPendingId())
        }

    @Test
    fun `DELETE 404 - idempotente, op consumido de la cola`() =
        runTest(testDispatcher) {
            val op = encolarOp("DELETE", "esc-server-1", null)
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"no existe"}"""))

            val exito = repo.procesarPendingOp(op, "test-token")

            assertTrue("el 404 de DELETE es idempotente: el efecto deseado ya esta alcanzado", exito)
            val req = server.takeRequest()
            assertTrue(
                "la peticion DELETE debe apuntar a /escaneos/{id}",
                req.path!!.contains("/escaneos/esc-server-1", ignoreCase = true)
            )
            assertNull("el op debe consumirse de la cola", db.pendingOpDao().minPendingId())
        }

    @Test
    fun `DELETE 500 - transitorio, el op sigue pendiente para retry`() =
        runTest(testDispatcher) {
            val op = encolarOp("DELETE", "esc-server-1", null)
            server.enqueue(MockResponse().setResponseCode(500).setBody("""{"detail":"error interno"}"""))

            val exito = repo.procesarPendingOp(op, "test-token")

            assertFalse("un 500 de DELETE es transitorio: false", exito)
            val opFinal = db.pendingOpDao().getById(op.id)
            assertNotNull(opFinal)
            assertFalse(opFinal!!.fallida)
            assertEquals(op.id, db.pendingOpDao().minPendingId())
        }
}