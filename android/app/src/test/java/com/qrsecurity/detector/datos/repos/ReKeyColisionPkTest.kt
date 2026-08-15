package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.bloquearLocal
import com.qrsecurity.detector.datos.repositorios.procesarPendingOp
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
 * Audit fix (reKey PK collision) — regresion del PUSH de urls_bloqueadas.
 *
 * Bug auditado: si el POST al backend devolvia un id de servidor que YA
 * existia localmente (la misma fila llego antes por PULL — p.ej. bloqueada
 * desde otro dispositivo), el `UPDATE ... SET id = :idNuevo` del reKey
 * violaba la PK con `SQLiteConstraintException`. El catch generico lo
 * clasificaba como transitorio → el op se reintentaba INFINITAMENTE (nunca
 * `marcarFallida`, bloqueando la cola oldest-first del outbox).
 *
 * Fix: catch especifico de `SQLiteConstraintException` en `procesarCreate`
 * — elimina la fila local (client UUID) + borra el op; el PULL ya
 * inserto/reemplazara la fila con el id del servidor.
 *
 * Escenario del test:
 *  1. `bloquearLocal("evil.com")` → fila dirty id=clientUUID + op CREATE.
 *  2. Simular PULL: insertar la fila del servidor id=serverUUID (misma URL).
 *  3. MockWebServer responde 201 con id=serverUUID (distinto del client).
 *  4. `procesarPendingOp` debe devolver true (op procesado), eliminar la
 *     fila clientUUID y dejar viva la del serverUUID; op fuera de cola.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ReKeyColisionPkTest {

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

    @Test
    fun `colision de PK en reKey resuelve como exito sin retry infinito`() = runTest(testDispatcher) {
        // 1. Write local: fila dirty con client UUID + op CREATE.
        val idCliente = repo.bloquearLocal("evil.com", "phishing")
        val opId = db.pendingOpDao().minPendingId()!!
        val op = db.pendingOpDao().getById(opId)!!

        // 2. Simular PULL previo: la fila del servidor (misma URL, otro id)
        //    ya vive localmente — el reKey va a colisionar por PK.
        val ahora = System.currentTimeMillis()
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = "server-uuid-9",
                url = "evil.com",
                razon = "phishing",
                creadoEnMillis = ahora - 1,
                dirty = false,
                syncedAtMillis = ahora - 1
            )
        )

        // 3. El backend responde 201 con el id del servidor (distinto).
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"id":"server-uuid-9","url":"evil.com","razon":"phishing","creado_en":"2026-01-01T00:00:00Z"}"""
            )
        )

        // 4. El op debe procesarse con exito (true), NO quedar en retry.
        val exito = repo.procesarPendingOp(op, "test-token")
        assertTrue("la colision de PK en reKey debe resolverse como exito", exito)

        // La fila del client UUID fue eliminada; la del servidor vive.
        assertNull("fila client UUID eliminada", db.urlBloqueadaDao().obtenerPorId(idCliente))
        assertNotNull("fila server UUID viva", db.urlBloqueadaDao().obtenerPorId("server-uuid-9"))
        assertEquals("la URL sigue bloqueada (una sola fila)", 1, db.urlBloqueadaDao().todosLosIds().size)

        // El op salio de la cola.
        assertNull("el op debe salir de la cola", db.pendingOpDao().minPendingId())
    }
}
