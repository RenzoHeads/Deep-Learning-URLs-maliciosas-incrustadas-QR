package com.qrsecurity.detector.datos.sync

import androidx.work.ListenableWorker
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S3 — TDD red phase.
 *
 * Bug: en 401/403 del PULL, [SyncWorker] llamaba
 * `sesionUsuario.cerrarSesion()` (logout debil: solo token). La DB quedaba
 * llena con datos y pending_ops del usuario A + initial_sync_completed=true;
 * al loguearse el usuario B, decidirModoSync entraba en SOLO_PUSH y los
 * pending_ops de A se pusheaban a la cuenta de B (cruce de identidad — Bug
 * H7 documentado en [LogoutCoordinator]).
 *
 * Fix: usar el logout completo `LogoutCoordinator.logout()` (cancela workers,
 * clearAllTables, resetea initial_sync_completed/ultimo_sync, limpia caches,
 * cierra sesion) y devolver Result.failure().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class SyncWorkerLogoutAuthTest {

    private val fixture = FixtureSyncWorker()

    @Before
    fun setUp() {
        fixture.iniciar()
    }

    @After
    fun tearDown() {
        fixture.cerrar()
    }

    @Test
    fun `pull 401 ejecuta logout completo y devuelve failure`() =
        runTest(fixture.testDispatcher) {
            // Given: datos del usuario en Room, prefs de sync completadas y
            // sesion activa. ultimo_sync=0 → modo PULL_Y_PUSH (no OMITIR).
            fixture.db.escaneoDao().insertar(
                EscaneoEntity(
                    id = "esc-1",
                    urlOriginal = "https://evil.com",
                    urlLimpia = "evil.com",
                    probabilidad = 0.99f,
                    nivelAlerta = "MALICIOSO",
                    delegado = null,
                    esMalicioso = true,
                    creadoEnMillis = 1_000L
                )
            )
            fixture.escribirPrefsSync(
                initialSyncCompleted = true,
                ultimoSyncMs = 0L
            )

            // When: el primer PULL responde 401 (token expirado/invalido).
            fixture.server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no autorizado"}"""))
            val resultado = fixture.construirWorker().doWork()

            // Then: Result.failure + logout COMPLETO:
            //  - DB vacia (clearAllTables — sin datos heredados del usuario A)
            assertEquals(ListenableWorker.Result.failure(), resultado)
            assertEquals(emptyList<String>(), fixture.db.escaneoDao().todosLosIds())
            //  - prefs de sync reseteadas (fuerzan full pull del siguiente usuario)
            val prefs = fixture.appContext
                .getSharedPreferences(SyncWorker.PREFS_SYNC, android.content.Context.MODE_PRIVATE)
            assertFalse(
                "initial_sync_completed debe resetearse a false",
                prefs.getBoolean(SyncWorker.KEY_INITIAL_SYNC_COMPLETED, true)
            )
            assertEquals(
                "ultimo_sync debe resetearse a 0",
                0L,
                prefs.getLong(SyncWorker.KEY_ULTIMO_SYNC, -1L)
            )
            //  - work cancelado y sesion cerrada
            assertTrue(fixture.mediador.cancelarTodoLlamado)
            assertTrue(fixture.sesionUsuario.cerrarSesionLlamado)
            assertNull(fixture.sesionUsuario.obtenerToken())
        }
}
