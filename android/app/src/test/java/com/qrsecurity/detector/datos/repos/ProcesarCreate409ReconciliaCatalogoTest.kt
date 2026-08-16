package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.sha256Hex
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.procesarPendingOp
import com.qrsecurity.detector.datos.repositorios.registrarLocal
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
 * R3 — TDD red phase.
 *
 * Bug: la rama 409 (Decision.Success) de [ProcesadorPendingOps.procesarCreate]
 * elimina la fila local con `tabla.eliminarPorId(op.idLocal)` SIN reconciliar
 * `urls_catalogo` — a diferencia de [RepositorioEscaneos.eliminarLocal] que SI
 * llama `db.reconciliarUrlsCatalogo(fila.urlLimpia)`. La entrada de
 * urls_catalogo queda huerfana y el dedup responde "URL ya escaneada" con
 * historial vacio hasta el proximo PULL. La rama SQLiteConstraintException
 * (re-key con PK collision) tiene el mismo hueco.
 *
 * Fix: eliminar la fila local a traves de un hook `eliminarLocalConReconciliacion`
 * de [TablaOutbox] que, para escaneos, lee la fila, la elimina y reconcilia
 * urls_catalogo dentro de la misma transaccion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ProcesarCreate409ReconciliaCatalogoTest {

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

    private suspend fun opPendiente() =
        db.pendingOpDao().getById(db.pendingOpDao().minPendingId()!!)!!

    @Test
    fun `CREATE 409 en escaneo elimina fila y reconcilia urls_catalogo`() =
        runTest(testDispatcher) {
            // Given: escaneo registrado localmente (fila dirty + entrada en
            // urls_catalogo + op CREATE encolado).
            repo.registrarLocal(
                urlOriginal = "https://evil.com",
                urlLimpia = "evil.com",
                probabilidad = 0.99f,
                nivelAlerta = "MALICIOSO"
            )
            assertNotNull(
                "entrada de urls_catalogo debe existir tras registrarLocal",
                db.urlCatalogoDao().buscarPorHash(sha256Hex("evil.com"))
            )

            // When: el backend responde 409 (ya tiene la fila via idCliente).
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"duplicado"}"""))
            val op = opPendiente()
            val exito = repo.procesarPendingOp(op, "test-token")

            // Then: op procesado, fila local eliminada Y entrada de
            // urls_catalogo eliminada (sin escaneos vivos de esa URL).
            assertTrue("409 debe ser exito idempotente", exito)
            assertEquals(emptyList<String>(), db.escaneoDao().todosLosIds())
            assertNull(
                "urls_catalogo no debe quedar huerfana tras el 409",
                db.urlCatalogoDao().buscarPorHash(sha256Hex("evil.com"))
            )
        }

    @Test
    fun `reKey con colision PK elimina fila y reconcilia urls_catalogo`() =
        runTest(testDispatcher) {
            // Given: fila synced con id de servidor (llego por PULL) con la
            // MISMA urlLimpia que un escaneo local dirty recien registrado.
            // El POST del CREATE devuelve el id de servidor → el re-key
            // (client UUID → server UUID) choca con la PK de la fila viva.
            val ahora = System.currentTimeMillis()
            db.escaneoDao().insertar(
                EscaneoEntity(
                    id = "srv-1",
                    urlOriginal = "https://evil.com",
                    urlLimpia = "evil.com",
                    probabilidad = 0.9f,
                    nivelAlerta = "MALICIOSO",
                    delegado = null,
                    esMalicioso = true,
                    creadoEnMillis = ahora,
                    dirty = false,
                    syncedAtMillis = ahora
                )
            )
            repo.registrarLocal(
                urlOriginal = "https://evil.com",
                urlLimpia = "evil.com",
                probabilidad = 0.99f,
                nivelAlerta = "MALICIOSO"
            )

            // When: POST → 201 con id="srv-1" (resurrect) → reKey colisiona.
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"id":"srv-1","url_original":"https://evil.com",""" +
                        """"url_limpia":"evil.com","probabilidad":0.99,""" +
                        """"nivel_alerta":"MALICIOSO","es_malicioso":true,""" +
                        """"creado_en":"2026-01-01T00:00:00Z"}"""
                )
            )
            val op = opPendiente()
            val exito = repo.procesarPendingOp(op, "test-token")

            // Then: la fila client-UUID se elimina (la de servidor queda),
            // y urls_catalogo queda reconciliada con la fila viva (1 escaneo).
            assertTrue("PK collision debe resolverse como exito", exito)
            assertEquals(listOf("srv-1"), db.escaneoDao().todosLosIds())
            val catalogo = db.urlCatalogoDao().buscarPorHash(sha256Hex("evil.com"))
            assertNotNull(
                "urls_catalogo debe conservar la entrada de la fila viva",
                catalogo
            )
            assertEquals(1, catalogo!!.vecesEscaneada)
        }
}
