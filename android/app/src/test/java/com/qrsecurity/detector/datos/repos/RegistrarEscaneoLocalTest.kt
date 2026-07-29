package com.qrsecurity.detector.datos.repos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration test for [RepositorioEscaneos.registrarLocal] — exercises Room
 * in-memory + EscaneoEntity + PendingOpEntity + SyncStateEntity to verify the
 * offline-first write-through contract.
 *
 * Bug H1 indirect coverage (registrarEscaneoLocal calls registrarLocal + sync trigger):
 *   - Row inserted into escaneos table with dirty=true, syncedAtMillis=null.
 *   - Outbox atomically enqueues a CREATE op (pending_ops) with the entity's JSON payload.
 *   - sync_state row for "escaneos" is seeded/upserted with timestamp = ahora and
 *     ultimaSincronizacionExitosa = false.
 *
 * H2 partial coverage: malformed input (delegado=null) still inserts cleanly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class RegistrarEscaneoLocalTest {

    private lateinit var db: BaseDatosSeguridad
    private lateinit var repo: RepositorioEscaneos
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        // ClienteBackend never invoked (no sync); only injected for repo ctor.
        val backend = ClienteBackend()
        repo = RepositorioEscaneos(
            db = db,
            backend = backend,
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `registrarLocal inserta row dirty y encola CREATE en pending_ops atomicamente`() =
        runTest(testDispatcher) {
            // When: inserta un escaneo
            val idLocal = repo.registrarLocal(
                urlOriginal = "https://malware.example.com/path",
                urlLimpia = "malware.example.com",
                probabilidad = 0.92f,
                nivelAlerta = "MALICIOSO",
                delegado = "NNAPI"
            )

            // Then: row insertado con dirty=true, esMalicioso=true, syncedAtMillis=null
            val todos = db.escaneoDao().todosLosIds()
            assertTrue("row debe estar persistido", todos.contains(idLocal))

            val pendientes = db.pendingOpDao().observarPendientes().first()
            assertEquals("una pending_op CREATE", 1, pendientes.size)
            val op = pendientes.first()
            assertEquals("escaneos", op.tabla)
            assertEquals("CREATE", op.tipoOperacion)
            assertEquals(idLocal, op.idLocal)
            assertNotNull("payloadJson debe estar serializado para CREATE", op.payloadJson)
            assertTrue(
                "payload debe contener urlOriginal serializada",
                op.payloadJson!!.contains("malware.example.com")
            )

            // sync_state seeded: timestamp set, success=false
            val syncState = db.syncStateDao().obtener("escaneos")
            assertNotNull("sync_state seeded para escaneos", syncState)
            assertFalse("última sync marcada como NO exitosa", syncState!!.ultimaSincronizacionExitosa)
        }

    @Test
    fun `registrarLocal con nivelAlerta SEGURO marca esMalicioso=false`() =
        runTest(testDispatcher) {
            val idLocal = repo.registrarLocal(
                urlOriginal = "https://benign.example.com",
                urlLimpia = "benign.example.com",
                probabilidad = 0.12f,
                nivelAlerta = "SEGURO",
                delegado = null
            )

            val ids = db.escaneoDao().todosLosIds()
            assertTrue(ids.contains(idLocal))

            val unsafe = db.escaneoDao().observarSeguros().first()
            assertEquals(1, unsafe.size)
            assertEquals(idLocal, unsafe.first().id)
            assertFalse("SEGURO no es malicioso", unsafe.first().esMalicioso)
        }

    @Test
    fun `registrarLocal inserts two distinct rows`() = runTest(testDispatcher) {
        repo.registrarLocal(
            urlOriginal = "https://a.com", urlLimpia = "a.com",
            probabilidad = 0.1f, nivelAlerta = "SEGURO", delegado = null
        )
        repo.registrarLocal(
            urlOriginal = "https://b.com", urlLimpia = "b.com",
            probabilidad = 0.8f, nivelAlerta = "MALICIOSO", delegado = "GPU"
        )

        val todos = db.escaneoDao().todosLosIds()
        assertEquals(2, todos.size)

        val ops = db.pendingOpDao().observarPendientes().first()
        assertEquals("dos ops CREATE", 2, ops.size)
    }
}
