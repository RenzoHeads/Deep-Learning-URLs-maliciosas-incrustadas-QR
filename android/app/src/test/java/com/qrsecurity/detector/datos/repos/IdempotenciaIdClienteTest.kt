package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
 * Bug A5 (fix aplicado) — TDD regression.
 *
 * El PUSH sync debe enviar la clave de idempotencia `id_cliente` (= idLocal
 * del pending op CREATE) en el body de los 3 POST de escritura. El backend
 * hace fetch-or-create por (id_usuario, id_cliente): si el proceso muere
 * entre un POST exitoso y el re-key local, el replay del mismo op devuelve
 * la fila existente (mismo id) en vez de insertar una duplicada (U-C).
 *
 * Estos tests verifican el contrato cliente→servidor: el body del POST
 * contiene `"id_cliente":"<idLocal>"`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class IdempotenciaIdClienteTest {

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

    private fun assertBodyContieneIdCliente(rutaEsperada: String, idClienteEsperado: String) {
        val request: RecordedRequest = server.takeRequest()
        assertNotNull("debe haberse hecho el POST", request)
        assertEquals("ruta del POST", rutaEsperada, request.path)
        val body = request.body.readUtf8()
        assertTrue(
            "el body del POST debe incluir id_cliente=$idClienteEsperado, got: $body",
            body.contains("\"id_cliente\":\"$idClienteEsperado\"")
        )
    }

    @Test
    fun `escaneos - POST incluye id_cliente igual al idLocal del op CREATE`() =
        runTest(testDispatcher) {
            val repo = RepositorioEscaneos(db = db, backend = backend(), json = json, ioDispatcher = testDispatcher)

            val idLocal = repo.registrarLocal(
                urlOriginal = "http://evil.com/phish",
                urlLimpia = "evil.com/phish",
                probabilidad = 0.99f,
                nivelAlerta = "MALICIOSO"
            )
            val opId = db.pendingOpDao().minPendingId()
            assertNotNull(opId)
            val op = db.pendingOpDao().getById(opId!!)
            assertNotNull(op)
            assertEquals("el idLocal del op debe ser el idLocal devuelto por registrarLocal", idLocal, op!!.idLocal)

            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"uuid-servidor-B","url_original":"http://evil.com/phish","url_limpia":"evil.com/phish","probabilidad":0.99,"nivel_alerta":"MALICIOSO","es_malicioso":true,"creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val exito = repo.procesarPendingOp(op, "test-token")
            assertTrue("el CREATE del escaneo debe exitarse", exito)

            assertBodyContieneIdCliente("/escaneos", idLocal)
        }

    @Test
    fun `urls bloqueadas - POST incluye id_cliente igual al idLocal del op CREATE`() =
        runTest(testDispatcher) {
            val repo = RepositorioUrlsBloqueadas(db = db, backend = backend(), json = json, ioDispatcher = testDispatcher)

            val idLocal = repo.bloquearLocal("evil.com", "phishing")
            val opId = db.pendingOpDao().minPendingId()
            assertNotNull(opId)
            val op = db.pendingOpDao().getById(opId!!)
            assertNotNull(op)
            assertEquals("el idLocal del op debe ser el idLocal devuelto por bloquearLocal", idLocal, op!!.idLocal)

            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"uuid-servidor-B","url":"evil.com","razon":"phishing","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val exito = repo.procesarPendingOp(op, "test-token")
            assertTrue("el CREATE del bloqueo debe exitarse", exito)

            assertBodyContieneIdCliente("/urls-bloqueadas", idLocal)
        }

    @Test
    fun `denuncias - POST incluye id_cliente igual al idLocal del op CREATE`() =
        runTest(testDispatcher) {
            val repo = RepositorioDenuncias(db = db, backend = backend(), json = json, ioDispatcher = testDispatcher)

            // Die FK denuncias.idCategoria → categorias_denuncia.id (RESTRICT)
            // exige que la categoria exista localmente antes de crearLocal.
            db.categoriaDao().upsertAll(
                listOf(CategoriaDenunciaEntity(id = 1, nombre = "Phishing", descripcion = null))
            )

            val idLocal = repo.crearLocal("https://scam.example.com/offer", 1, "estafa")
            val opId = db.pendingOpDao().minPendingId()
            assertNotNull(opId)
            val op = db.pendingOpDao().getById(opId!!)
            assertNotNull(op)
            assertEquals("el idLocal del op debe ser el idLocal devuelto por crearLocal", idLocal, op!!.idLocal)

            server.enqueue(MockResponse().setResponseCode(201).setBody(
                """{"id":"uuid-servidor-B","url":"https://scam.example.com/offer","id_categoria":1,"nombre_categoria":"Phishing","descripcion":"estafa","estado":"PENDIENTE","creado_en":"2026-01-01T00:00:00Z"}"""
            ))
            val exito = repo.procesarPendingOp(op, "test-token")
            assertTrue("el CREATE de la denuncia debe exitarse", exito)

            assertBodyContieneIdCliente("/denuncias", idLocal)
        }
}