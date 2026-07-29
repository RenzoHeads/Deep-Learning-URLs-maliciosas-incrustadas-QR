package com.qrsecurity.detector.datos.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
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
 * Tests de idempotencia de [MediadorSincronizacion].
 *
 * Cobertura (Zone 3 — sync/network, riesgo HIGH, 0 coverage previo):
 *  - **M12 fix (revisado)**: `dispararSyncUnica()` usa
 *    [ExistingWorkPolicy.APPEND] — un segundo disparo se ENCADENA detras
 *    del primero (no lo descarta). Verificable via
 *    `WorkManager.getWorkInfosForUniqueWork(NOMBRE_TRABAJO)` que devuelve
 *    exactamente 2 WorkInfo tras 2 invocaciones (APPEND encadena).
 *  - `programarSyncPeriodica()` registra exactamente 1 trabajo periodico
 *    bajo `NOMBRE_TRABAJO + "_periodica"` (tambien dedup).
 *  - `cancelarTodo()` deja 0 trabajos activos bajo ambos nombres.
 *
 * Estrategia:
 *  - `WorkManagerTestInitHelper.initializeTestWorkManager(context)` en
 *    `@Before` provee un WorkManager en memoria (sin persistencia). Como
 *    `MediadorSincronizacion` llama `WorkManager.getInstance(context)` en
 *    su constructor, esto es obligatorio.
 *  - Tras cada `dispararSyncUnica()` / `programarSyncPeriodica()` /
 *    `cancelarTodo()`, se inspecciona
 *    `WorkManager.getWorkInfosForUniqueWork(...)` (sincrono via `.get()`)
 *    para contar cuantas WorkInfos existen bajo ese unique name.
 *  - `@Config(application = TestApplication::class)` evita que
 *    Robolectric instancie `AppSeguridadQR` cuyo `onCreate` llamaria a
 *    `programarSyncPeriodica()` con un WorkManager no inicializado.
 *
 * Notas sobre el TestDriver:
 *  - `WorkManagerTestInitHelper.getTestDriver(context)` permite forzar
 *    constraints (red, battery) y timing, pero aqui no lo usamos porque
 *    los tests son de **encolado**, no de ejecucion. APPEND/UPDATE
 *    se aplican al `enqueueUniqueWork`, no a la ejecucion per se.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class MediadorSincronizacionTest {

    private lateinit var context: Context
    private lateinit var mediador: MediadorSincronizacion
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        mediador = MediadorSincronizacion(context)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() {
        // Limpieza: cancelar todo tras cada test para evitar cross-test
        // contamination (aunque WorkManagerTestInitHelper es en memoria y
        // se reinicializa en @Before, esto es defensivo).
        workManager.cancelUniqueWork(SyncWorker.NOMBRE_TRABAJO)
        workManager.cancelUniqueWork(SyncWorker.NOMBRE_TRABAJO + "_periodica")
    }

    // ──────────────────────────────────────────────────────────────
    // M12 fix (revisado) — APPEND en one-shot: encadena, no descarta
    // ──────────────────────────────────────────────────────────────

    @Test
    fun dispararSyncUnica_dosInvocaciones_encadenaDosTrabajos() {
        // When: dos invocaciones consecutivas.
        mediador.dispararSyncUnica()
        mediador.dispararSyncUnica()

        // Then: bajo el unique name NOMBRE_TRABAJO hay exactamente 2
        // WorkInfo (APPEND encadena el segundo detras del primero).
        // Esto valida el fix M12 revisado — antes con KEEP, el segundo
        // disparo era DESCARTADO si el primero estaba en cola/retry,
        // bloqueando la sincronizacion de nuevas pending_ops durante
        // el backoff exponencial de un retry anterior.
        val workInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO)
            .get()
        assertNotNull("getWorkInfosForUniqueWork no debe ser null", workInfos)
        assertEquals(
            "APPEND debe encadenar: 2 invocaciones -> 2 WorkInfo (no 1)",
            2,
            workInfos.size
        )
    }

    @Test
    fun dispararSyncUnica_unaInvocacion_registraTrabajo() {
        mediador.dispararSyncUnica()

        val workInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO)
            .get()
        assertEquals(
            "Una invocacion debe registrar exactamente 1 trabajo",
            1,
            workInfos.size
        )
    }

    // ──────────────────────────────────────────────────────────────
    // programarSyncPeriodica —单一 periodic work bajo _periodica
    // ──────────────────────────────────────────────────────────────

    @Test
    fun programarSyncPeriodica_dosInvocaciones_dedupAUnTrabajoPeriodico() {
        // When: dos invocaciones.
        mediador.programarSyncPeriodica()
        mediador.programarSyncPeriodica()

        // Then: bajo el unique name "_periodica" hay 1 WorkInfo.
        // ExistingPeriodicWorkPolicy.UPDATE dedup por unique name —
        // no duplica el schedule, solo actualiza constraints/interval
        // si cambiaron (que en este test no cambian).
        val workInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO + "_periodica")
            .get()
        assertNotNull(workInfos)
        assertEquals(
            "UPDATE debe dedup: 2 invocaciones -> 1 WorkInfo periodico",
            1,
            workInfos.size
        )
    }

    @Test
    fun programarSyncPeriodica_noColisionaConOneShot() {
        // When: invocamos ambas.
        mediador.dispararSyncUnica()
        mediador.programarSyncPeriodica()

        // Then: cada nombre tiene su propio WorkInfo (no comparten
        // namespace porque los unique names son distintos).
        val oneShotInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO)
            .get()
        val periodicInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO + "_periodica")
            .get()
        assertEquals(1, oneShotInfos.size)
        assertEquals(1, periodicInfos.size)
    }

    // ──────────────────────────────────────────────────────────────
    // cancelarTodo — limpia ambos trabajos
    // ──────────────────────────────────────────────────────────────

    @Test
    fun cancelarTodo_dejaSinTrabajosActivos() {
        // Given: encolamos ambos.
        mediador.dispararSyncUnica()
        mediador.programarSyncPeriodica()

        // When: cancelarTodo.
        mediador.cancelarTodo()

        // Then: ambos trabajos existen pero estan CANCELLED (no se
        // eliminan de WorkManager, solo cambian de estado).
        val oneShotInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO)
            .get()
        val periodicInfos = workManager
            .getWorkInfosForUniqueWork(SyncWorker.NOMBRE_TRABAJO + "_periodica")
            .get()
        assertEquals(1, oneShotInfos.size)
        assertEquals(1, periodicInfos.size)
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            oneShotInfos.first().state
        )
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            periodicInfos.first().state
        )
    }
}
