package com.qrsecurity.detector.datos.sync

import androidx.work.ListenableWorker
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.repositorios.procesarPendingOp
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
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
 * S7 — TDD red phase.
 *
 * Bug: 401/403 en el PUSH (decidirResultadoPushCreate/Delete) caian en
 * `else -> Retry`. En modo SOLO_PUSH (pull omitido) un token expirado
 * reintentaba 10 veces y marcarFallida descartaba los writes del usuario
 * silenciosamente sin re-auth.
 *
 * Fix: 401/403 → [DecisionPush.Decision.AuthError] → [ExcepcionAuthPush] →
 * SyncWorker hace logout completo (LogoutCoordinator) + Result.failure().
 * El op NO se marca fallida.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class SyncWorkerPushAuthLogoutTest {

    private val fixture = FixtureSyncWorker()

    private val payloadCreate =
        """{"id":"esc-local-1","urlOriginal":"http://evil.com","urlLimpia":"evil.com",""" +
            """"probabilidad":0.99,"nivelAlerta":"MALICIOSO","delegado":null,""" +
            """"esMalicioso":true,"creadoEnMillis":1000,"dirty":true,""" +
            """"syncedAtMillis":null,"notasAnalisis":null}"""

    @Before
    fun setUp() {
        fixture.iniciar()
    }

    @After
    fun tearDown() {
        fixture.cerrar()
    }

    @Test
    fun `push 401 ejecuta logout completo, devuelve failure y NO marca el op fallida`() =
        runTest(fixture.testDispatcher) {
            // Given: sync inicial completa y reciente + pending op → modo
            // SOLO_PUSH (el PULL se omite: el 401 solo puede detectarse en
            // el POST del CREATE).
            fixture.escribirPrefsSync(
                initialSyncCompleted = true,
                ultimoSyncMs = System.currentTimeMillis()
            )
            fixture.db.pendingOpDao().insertar(
                PendingOpEntity(
                    tabla = PendingOpEntity.TABLA_ESCANEOS,
                    tipoOperacion = PendingOpEntity.OP_CREATE,
                    idLocal = "esc-local-1",
                    payloadJson = payloadCreate,
                    creadoEnMillis = 1_000L
                )
            )

            // When: el POST del CREATE responde 401.
            fixture.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no autorizado"}"""))
            val resultado = fixture.construirWorker().doWork()

            // Then: failure (con el bug era retry) + logout completo.
            assertEquals(ListenableWorker.Result.failure(), resultado)
            assertTrue(fixture.mediador.cancelarTodoLlamado)
            assertTrue(fixture.sesionUsuario.cerrarSesionLlamado)
            assertEquals(
                "clearAllTables debe vaciar escaneos (logout completo)",
                emptyList<String>(),
                fixture.db.escaneoDao().todosLosIds()
            )
            val prefs = fixture.appContext
                .getSharedPreferences(SyncWorker.PREFS_SYNC, android.content.Context.MODE_PRIVATE)
            assertFalse(prefs.getBoolean(SyncWorker.KEY_INITIAL_SYNC_COMPLETED, true))
            assertEquals(0L, prefs.getLong(SyncWorker.KEY_ULTIMO_SYNC, -1L))
            // El POST se intento exactamente una vez (sin bucle de reintentos
            // dentro del mismo run — con el bug el worker procesaba en loop).
            assertEquals(1, fixture.server.requestCount)
        }

    @Test
    fun `procesarPendingOp con 401 senala error de auth y deja el op sin marcar fallida`() =
        runTest(fixture.testDispatcher) {
            // Given: un op CREATE encolado con payload.
            fixture.db.pendingOpDao().insertar(
                PendingOpEntity(
                    tabla = PendingOpEntity.TABLA_ESCANEOS,
                    tipoOperacion = PendingOpEntity.OP_CREATE,
                    idLocal = "esc-local-1",
                    payloadJson = payloadCreate,
                    creadoEnMillis = 1_000L
                )
            )
            val op = fixture.db.pendingOpDao().getById(fixture.db.pendingOpDao().minPendingId()!!)!!

            // When: el POST responde 401. Con el fix esto LANZA la senal de
            // auth (ExcepcionAuthPush) que el SyncWorker traduce en logout;
            // con el bug devolvia false (retry silencioso).
            fixture.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no autorizado"}"""))
            val senaloAuth = try {
                fixture.repoEscaneos.procesarPendingOp(op, "test-token")
                false
            } catch (e: RuntimeException) {
                true
            }

            // Then: senal de auth emitida (sin marcar fallida)...
            assertTrue(
                "un 401 en push debe senalar error de auth (excepcion), no un retry silencioso",
                senaloAuth
            )
            // ...y el op sigue en la cola, NO marcado fallida.
            val opFinal = fixture.db.pendingOpDao().getById(op.id)
            assertNotNull("el op no debe descartarse de la cola", opFinal)
            assertFalse("el op NO debe marcarse fallida en un 401", opFinal!!.fallida)
        }
}
