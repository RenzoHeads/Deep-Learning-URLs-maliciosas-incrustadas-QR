package com.qrsecurity.detector.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.FakeMediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ResultadoMaliciosoViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repoUrls: RepositorioUrlsBloqueadas
    private lateinit var mediadorSync: FakeMediadorSincronizacion
    private lateinit var viewModel: ResultadoMaliciosoViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        db = Room.inMemoryDatabaseBuilder(
            context,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val backend = ClienteBackend()
        // Dispatchers.Unconfined para ioDispatcher: withContext ejecuta inline,
        // withTransaction commit sincrono. La continuation despues de
        // withTransaction vuelve a Main (testDispatcher) — advanceUntilIdle()
        // la drena en multiples rondas.
        repoUrls = RepositorioUrlsBloqueadas(db, backend, json, Dispatchers.Unconfined)
        mediadorSync = FakeMediadorSincronizacion(context)
        viewModel = ResultadoMaliciosoViewModel(repoUrls, mediadorSync)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun estadoInicial_bloqueandoFalse_bloqueadaOkNull_errorNull() {
        val state = viewModel.uiState.value
        assertFalse("bloqueando debe ser false al inicio", state.bloqueando)
        assertNull("bloqueadaOk debe ser null al inicio", state.bloqueadaOk)
        assertNull("error debe ser null al inicio", state.error)
    }

    /**
     * Helper: drena Room's executor + test dispatcher en multiple rondas.
     *
     * `db.withTransaction` corre en Room's real ThreadPoolExecutor (OS thread).
     * La continuation despues de withTransaction vuelve a Main (testDispatcher)
     * async. `Thread.sleep` da tiempo al OS thread para completar la tx y
     * encolar la continuation; `advanceUntilIdle` la drena.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.drenarRoomYDispatcher() {
        repeat(5) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        advanceUntilIdle()
    }

    @Test
    fun onAction_bloquearUrl_urlValida_bloqueadaOkTrue_bloqueandoVuelveAFalse() = runTest(testDispatcher) {
        viewModel.onAction(
            ResultadoMaliciosoAction.BloquearUrl(
                urlLimpia = "evil-phishing.example.com/login",
                probabilidad = 0.95f
            )
        )
        drenarRoomYDispatcher()

        val state = viewModel.uiState.value
        assertFalse("bloqueando debe volver a false", state.bloqueando)
        assertTrue("bloqueadaOk debe ser true tras bloquear local", state.bloqueadaOk == true)
        assertNull("error debe ser null tras exito", state.error)
    }

    @Test
    fun onAction_bloquearUrl_persisteEnRoom_offlineFirst() = runTest(testDispatcher) {
        val url = "malware.example.com/stolen"
        viewModel.onAction(
            ResultadoMaliciosoAction.BloquearUrl(
                urlLimpia = url,
                probabilidad = 0.88f
            )
        )
        drenarRoomYDispatcher()

        val urlsBloqueadas = db.urlBloqueadaDao().observarTodos().first()
        assertEquals("Debe haber 1 URL bloqueada en Room", 1, urlsBloqueadas.size)
        assertEquals(url, urlsBloqueadas[0].url)
        assertTrue(
            "La razon debe incluir el porcentaje",
            urlsBloqueadas[0].razon!!.contains("88")
        )
    }

    @Test
    fun onAction_bloquearUrl_razonIncluyeProbabilidad() = runTest(testDispatcher) {
        viewModel.onAction(
            ResultadoMaliciosoAction.BloquearUrl(
                urlLimpia = "test.example.com",
                probabilidad = 0.72f
            )
        )
        drenarRoomYDispatcher()

        val urlsBloqueadas = db.urlBloqueadaDao().observarTodos().first()
        assertEquals(1, urlsBloqueadas.size)
        val razon = urlsBloqueadas[0].razon!!
        assertTrue(
            "Razon debe contener '72%' (de probabilidad 0.72f). Fue: $razon",
            razon.contains("72")
        )
        assertTrue(
            "Razon debe contener 'Malicioso'. Fue: $razon",
            razon.contains("Malicioso")
        )
    }

    @Test
    fun onAction_dobleDisparoMientrasBloquea_noInsertaDoble() = runTest(testDispatcher) {
        val url = "evil-a.example.com"
        viewModel.onAction(
            ResultadoMaliciosoAction.BloquearUrl(url, 0.9f)
        )
        viewModel.onAction(
            ResultadoMaliciosoAction.BloquearUrl("evil-b.example.com", 0.9f)
        )
        drenarRoomYDispatcher()

        val urls = db.urlBloqueadaDao().observarTodos().first()
        assertEquals(
            "Doble disparo mientras bloqueando no debe insertar 2",
            1,
            urls.size
        )
        assertEquals(url, urls[0].url)
    }

    @Test
    fun consumirError_limpiaError() = runTest(testDispatcher) {
        viewModel.consumirError()
        assertNull(
            "consumirError debe dejar error en null",
            viewModel.uiState.value.error
        )
    }

    @Test
    fun onAction_bloquearUrl_probabilidadCero_razonIncluyeCero() = runTest(testDispatcher) {
        viewModel.onAction(
            ResultadoMaliciosoAction.BloquearUrl(
                urlLimpia = "edge.example.com",
                probabilidad = 0.0f
            )
        )
        drenarRoomYDispatcher()

        val urls = db.urlBloqueadaDao().observarTodos().first()
        assertEquals(1, urls.size)
        assertTrue(
            "Razon con probabilidad 0.0f debe incluir '0%'. Fue: ${urls[0].razon}",
            urls[0].razon!!.contains("0")
        )
        assertTrue(viewModel.uiState.value.bloqueadaOk == true)
    }
}
