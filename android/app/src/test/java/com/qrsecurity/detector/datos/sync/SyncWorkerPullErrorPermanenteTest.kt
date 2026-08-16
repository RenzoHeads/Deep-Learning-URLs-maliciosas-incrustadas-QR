package com.qrsecurity.detector.datos.sync

import androidx.work.ListenableWorker
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S1 — TDD red phase.
 *
 * Bug: la rama `ResultadoSync.Fallido` del PULL solo manejaba 401/403, 422 y
 * Retry. Cuando `decidirResultadoPull` devolvia Failure (4xx!=401/403/429 y
 * 3xx — ej. 400/404 fijo del backend), el codigo NO asignaba `estado`, que
 * quedaba en su valor entrante `EstadoPulls.Ok`. SyncWorker escribia
 * KEY_INITIAL_SYNC_COMPLETED=true y KEY_ULTIMO_SYNC con el pull fallido
 * permanente y la app nunca volvia a intentar pull.
 *
 * Fix: nuevo [EstadoPulls.ErrorPermanente]; doWorkInternal NO escribe ninguna
 * pref de sync en ese estado y devuelve Result.failure().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class SyncWorkerPullErrorPermanenteTest {

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
    fun `pull fallido 400 no escribe prefs de sync y devuelve failure`() =
        runTest(fixture.testDispatcher) {
            // Given: primer sync (initial=false, ultimo_sync=0) sin pending
            // ops → modo PULL_Y_PUSH. El backend responde 400 fijo al primer
            // GET delta (urls bloqueadas).
            fixture.server.enqueue(MockResponse().setResponseCode(400).setBody("""{"detail":"request invalido"}"""))

            // When
            val resultado = fixture.construirWorker().doWork()

            // Then: failure y NINGUNA pref de sync escrita — con el bug el
            // estado quedaba Ok, el run "completaba" y escribia
            // KEY_ULTIMO_SYNC (+ initial_sync_completed si aplicara).
            assertEquals(ListenableWorker.Result.failure(), resultado)
            val prefs = fixture.appContext
                .getSharedPreferences(SyncWorker.PREFS_SYNC, android.content.Context.MODE_PRIVATE)
            assertFalse(
                "initial_sync_completed NO debe escribirse con un pull fallido permanente",
                prefs.getBoolean(SyncWorker.KEY_INITIAL_SYNC_COMPLETED, false)
            )
            assertEquals(
                "ultimo_sync NO debe escribirse con un pull fallido permanente",
                -1L,
                prefs.getLong(SyncWorker.KEY_ULTIMO_SYNC, -1L)
            )
        }
}
