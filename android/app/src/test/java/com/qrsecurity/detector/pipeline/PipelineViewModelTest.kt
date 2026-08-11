package com.qrsecurity.detector.pipeline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Tests de [PipelineViewModel] — cubren el fix C-09 (concurrencia
 * estructurada del Job del escaneo) y el comportamiento observable de
 * [PipelineViewModel.reiniciar] sin instanciar el pipeline completo
 * (TFLite + Room + ClienteBackend).
 *
 * Cobertura:
 *  - `reiniciar()` cambia `estado` a `Pipeline.Estado.Escaneando`.
 *  - `analizar(payloadCrudo = "")` lleva el estado a `Error` (path
 *    rapido: el `ExtractorUrls` mapea payload vacio a
 *    `Extraido.Vacio` sin tocar TFLite ni Room).
 *  - Inicializacion: el `estado` inicial es `Inicializando`.
 *
 * Estrategia:
 *  - `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33],
 *    application = TestApplication::class)`.
 *  - **Dispatchers.setMain(testDispatcher) OBLIGATORIO**: el `analizar`
 *    suspend lanza `viewModelScope.launch { ... }` que usa
 *    `Dispatchers.Main` por defecto. Sin `setMain`, el Job se queda
 *    pendiente y `runTest` lanza `UncompletedCoroutinesError` al final.
 *    `StandardTestDispatcher` da control manual via `advanceUntilIdle()`.
 *  - `PipelineViewModel(application)` construye `Pipeline(application)`
 *    directamente. `MotorInferencia(application)` solo guarda
 *    `application.assets` y NO carga TFLite (placeholder).
 *  - El path vacio y el path no-URL NO invocan `registrarEscaneoLocal`,
 *    asi que Room no se toca para esos casos.
 *
 * NOTA: este test NO cubre la race condition real C-09 (lanzar dos
 * `analizar` concurrentes y verificar cancelacion del primer Job) porque
 * eso requeriria injectar un `Pipeline` fake con latencia controlada —
 * y el `pipeline` inside `PipelineViewModel` es `val` final, no
 * inyectable. La cobertura indirecta via `analizar` secuencial x2
 * verifica que `cancelAndJoin` no deadlocka con Jobs ya terminados.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PipelineViewModelTest {

    private lateinit var viewModel: PipelineViewModel
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var db: com.qrsecurity.detector.datos.local.BaseDatosSeguridad

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            androidx.work.Configuration.Builder()
                .setMinimumLoggingLevel(android.util.Log.DEBUG)
                .build()
        )
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            app,
            com.qrsecurity.detector.datos.local.BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()
        val backend = com.qrsecurity.detector.api.ClienteBackend()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val repoEscaneos = com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos(db, backend, json, testDispatcher)
        val repoUrlsBloqueadas = com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas(db, backend, json, testDispatcher)
        val mediadorSync = com.qrsecurity.detector.datos.sync.MediadorSincronizacion(app)
        val pipeline = com.qrsecurity.detector.pipeline.Pipeline(app, db, backend, json, repoEscaneos, repoUrlsBloqueadas, mediadorSync)
        // PipelineViewModel requiere SavedStateHandle (Hilt lo inyecta en prod;
        // en test pasamos uno vacío — no usamos resultadoCacheado aquí).
        val savedState = androidx.lifecycle.SavedStateHandle()
        viewModel = PipelineViewModel(pipeline, savedState)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    // ──────────────────────────────────────────────────────────────
    // Estado inicial
    // ──────────────────────────────────────────────────────────────

    @Test
    fun estadoInicial_esInicializando() {
        assertEquals(
            "Pipeline recien creado debe estar en Inicializando",
            Pipeline.Estado.Inicializando,
            viewModel.estado.value
        )
    }

    // ──────────────────────────────────────────────────────────────
    // reiniciar() — API publica sincrona
    // ──────────────────────────────────────────────────────────────

    @Test
    fun reiniciar_cambiaEstadoAEscaneando() {
        viewModel.reiniciar()
        assertEquals(
            "reiniciar debe llevar el estado a Escaneando",
            Pipeline.Estado.Escaneando,
            viewModel.estado.value
        )
    }

    @Test
    fun reiniciar_esIdempotente() {
        viewModel.reiniciar()
        viewModel.reiniciar()
        assertEquals(
            "reiniciar repetido debe dejar estado en Escaneando",
            Pipeline.Estado.Escaneando,
            viewModel.estado.value
        )
    }

    // ──────────────────────────────────────────────────────────────
    // analizar(payload vacio) — path rapido que no toca TFLite/Room
    // ──────────────────────────────────────────────────────────────

    @Test
    fun analizar_payloadVacio_llegaAError() = runTest(testDispatcher) {
        // When: analizar con payload vacio.
        viewModel.analizar("")

        // Avanzamos el reloj virtual para que el Job lanzado en
        // viewModelScope (Main = testDispatcher) pueda correr.
        advanceUntilIdle()

        // Then: el extractor mapea "" a Extraido.Vacio -> Estado.Error.
        val estadoFinal = viewModel.estado.value
        assertTrue(
            "Payload vacio debe llevar a Error, no a Escaneando atascado. Estado actual: $estadoFinal",
            estadoFinal is Pipeline.Estado.Error
        )
        val mensajeError = (estadoFinal as Pipeline.Estado.Error).mensaje
        assertNotNull("Mensaje de error no debe ser null", mensajeError)
        assertTrue(
            "Mensaje de error debe mencionar 'vacio' o similar. Fue: '$mensajeError'",
            mensajeError.contains("vacio", ignoreCase = true) ||
            mensajeError.contains("Vac", ignoreCase = true)
        )
    }

    // ──────────────────────────────────────────────────────────────
    // analizar con texto que NO es URL — path NoUrl (no TFLite, no Room)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun analizar_payloadNoUrl_llegaAResultadoListoNoUrl() = runTest(testDispatcher) {
        viewModel.analizar("BEGIN:VCARD\nFN:Test\nEND:VCARD")
        advanceUntilIdle()

        val estadoFinal = viewModel.estado.value
        assertTrue(
            "Payload no-URL debe llegar a ResultadoListo. Estado actual: $estadoFinal",
            estadoFinal is Pipeline.Estado.ResultadoListo
        )
        val resultado = (estadoFinal as Pipeline.Estado.ResultadoListo).resultado
        assertTrue(
            "Resultado debe ser ResultadoAnalisis.NoUrl. Fue: $resultado",
            resultado is Pipeline.ResultadoAnalisis.NoUrl
        )
    }

    // ──────────────────────────────────────────────────────────────
    // analizar multiple veces secuencialmente — verifica cancelAndJoin
    // ──────────────────────────────────────────────────────────────

    @Test
    fun analizar_dosInvocacionesSecuenciales_noDeadlocks() = runTest(testDispatcher) {
        // Si cancelAndJoin del fix C-09/M5 deadlocka, este test se cuelga.
        viewModel.analizar("")
        advanceUntilIdle()
        viewModel.analizar("")
        advanceUntilIdle()

        // Solo verificamos que llegamos aqui sin excepcion ni timeout.
        assertTrue(
            "Dos invocaciones secuenciales no deben deadlockar",
            true
        )
    }
}
