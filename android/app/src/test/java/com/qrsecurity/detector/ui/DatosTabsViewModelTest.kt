package com.qrsecurity.detector.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.*
import com.qrsecurity.detector.datos.sync.FakeMediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitarios JVM de [DatosTabsViewModel] — arquitectura NowInAndroid.
 *
 * Estrategia (fix dispatcher):
 *  - `StandardTestDispatcher` para `setMain` → viewModelScope.launch
 *    despacha a Main (testDispatcher), advanceUntilIdle lo drena.
 *  - `Dispatchers.Unconfined` para `ioDispatcher` del repo →
 *    withContext inline, withTransaction commit sincrono.
 *  - `FakeMediadorSincronizacion` (no-op) → elimina WorkManager del path.
 *  - `drenarRoomYDispatcher()` → drena multiples hops:
 *    la continuation despues de db.withTransaction (Unconfined) vuelve
 *    a Main (testDispatcher) async en multiples rondas.
 *  - StateFlow WhileSubscribed(5_000): collectorJobs mantienen suscriptores
 *    activos para que los Flows emitan desde Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DatosTabsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repoEscaneos: RepositorioEscaneos
    private lateinit var repoUrls: RepositorioUrlsBloqueadas
    private lateinit var mediadorSync: FakeMediadorSincronizacion
    private lateinit var viewModel: DatosTabsViewModel
    private val collectorJobs = mutableListOf<Job>()

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
        repoEscaneos = RepositorioEscaneos(db, backend, json, Dispatchers.Unconfined)
        repoUrls = RepositorioUrlsBloqueadas(db, backend, json, Dispatchers.Unconfined)
        mediadorSync = FakeMediadorSincronizacion(context)
        viewModel = DatosTabsViewModel(repoEscaneos, repoUrls, mediadorSync)
    }

    @After
    fun tearDown() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
        Dispatchers.resetMain()
        db.close()
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

    /**
     * Suscribe los StateFlows del ViewModel para que el upstream Eagerly
     * mantenga Room emitiendo cambios reactivos.
     *
     * Audit fix M1: totalEscaneos/amenazas eliminados del VM (flows eager
     * sin consumidor en la UI) — sus subscriptores y tests tambien se
     * retiraron.
     */
    private fun subscribirTodos() {
        collectorJobs += viewModel.viewModelScope.launch { viewModel.historialTodos.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.urlsBloqueadas.collect { } }
    }

    // ── Estado inicial — todos los StateFlows arrancan con initialValue ──

    @Test
    fun estadoInicial_historialTodos_emptyList() {
        assertEquals(emptyList<EscaneoEntity>(), viewModel.historialTodos.value)
    }

    @Test
    fun estadoInicial_urlsBloqueadas_emptyList() {
        assertEquals(emptyList<UrlBloqueadaEntity>(), viewModel.urlsBloqueadas.value)
    }

    // ── Emision reactiva desde Room ──

    @Test
    fun historialTodos_emiteTrasInsert() = runTest(testDispatcher) {
        subscribirTodos()
        val escaneo = EscaneoEntity(
            id = "esc-1",
            urlOriginal = "https://malware.example.com",
            urlLimpia = "malware.example.com",
            probabilidad = 0.9f,
            nivelAlerta = "MALICIOSO",
            delegado = "NNAPI",
            esMalicioso = true,
            creadoEnMillis = System.currentTimeMillis(),
            dirty = false,
            syncedAtMillis = System.currentTimeMillis()
        )
        db.escaneoDao().insertar(escaneo)
        drenarRoomYDispatcher()

        assertEquals("historialTodos debe emitir 1 escaneo tras insert", 1, viewModel.historialTodos.value.size)
        assertEquals("esc-1", viewModel.historialTodos.value[0].id)
    }

    @Test
    fun historialUiState_emiteInmediatoSinEsperarMedianoche() = runTest(testDispatcher) {
        subscribirTodos()
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "esc-ui-1",
                urlOriginal = "https://ui.example.com",
                urlLimpia = "ui.example.com",
                probabilidad = 0.5f,
                nivelAlerta = "SOSPECHOSO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
        // Drena Room + dispatcher SIN avanzar el tiempo virtual: el ticker de
        // medianoche tiene un delay de horas; si el combine depende de su
        // primer emit, historialUiState queda congelado en el valor inicial
        // (totalTodos=null) y el historial se pintaria vacio en la UI.
        repeat(5) {
            Thread.sleep(50)
            runCurrent()
        }
        runCurrent()

        val estado = viewModel.historialUiState.value
        assertEquals("historialUiState debe emitir totalTodos sin esperar a medianoche", 1, estado.totalTodos)
        assertEquals(1, estado.grupos.sumOf { it.escaneos.size })
    }

    @Test
    fun urlsBloqueadas_emiteTrasInsert() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = "url-1",
                url = "evil.example.com",
                razon = "Malicioso (probabilidad 90%)",
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
        drenarRoomYDispatcher()

        assertEquals("urlsBloqueadas debe emitir 1 tras insert", 1, viewModel.urlsBloqueadas.value.size)
        assertEquals("evil.example.com", viewModel.urlsBloqueadas.value[0].url)
    }

    @Test
    fun filtrarHistorial_aplicaFiltroDeBloqueadasYBusquedaSinDistinguirMayusculas() {
        val items = listOf(
            EscaneoEntity(
                id = "evil-1",
                urlOriginal = "https://evil.example.com/login",
                urlLimpia = "evil.example.com/login",
                probabilidad = 0.99f,
                nivelAlerta = "MALICIOSO",
                delegado = "NNAPI",
                esMalicioso = true,
                creadoEnMillis = 3L,
                dirty = false,
                syncedAtMillis = 3L
            ),
            EscaneoEntity(
                id = "evil-2",
                urlOriginal = "https://other.example.com/login",
                urlLimpia = "other.example.com/login",
                probabilidad = 0.95f,
                nivelAlerta = "MALICIOSO",
                delegado = "NNAPI",
                esMalicioso = true,
                creadoEnMillis = 2L,
                dirty = false,
                syncedAtMillis = 2L
            ),
            EscaneoEntity(
                id = "safe-1",
                urlOriginal = "https://evil.example.com/about",
                urlLimpia = "evil.example.com/about",
                probabilidad = 0.01f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = 1L,
                dirty = false,
                syncedAtMillis = 1L
            )
        )

        val result = filtrarHistorial(
            historial = items,
            filtro = "BLOQUEADAS",
            busqueda = "EVIL",
            bloqueadasUrls = setOf("evil.example.com/login", "safe.example.com")
        )

        assertEquals(listOf("evil-1"), result.map { it.id })
    }

    @Test
    fun agruparHistorial_fechasFuturasVanAlGrupoHoy() {
        // Fechas-futuras fix: un creadoEnMillis futuro (reloj del device
        // atrasado contra el servidor) daba diasDeDiferencia negativo y la
        // fila caia fuera de los 3 grupos — sumaba en totalTodos pero nunca
        // se pintaba.
        val ahora = System.currentTimeMillis()
        val enUnaHora = ahora + 3_600_000L
        val haceTresDias = ahora - 3L * 24 * 3_600_000L
        val entidades = listOf(
            EscaneoEntity(
                id = "futuro",
                urlOriginal = "https://futuro.example.com",
                urlLimpia = "futuro.example.com",
                probabilidad = 0.5f,
                nivelAlerta = "SOSPECHOSO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = enUnaHora,
                dirty = false,
                syncedAtMillis = enUnaHora
            ),
            EscaneoEntity(
                id = "viejo",
                urlOriginal = "https://viejo.example.com",
                urlLimpia = "viejo.example.com",
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = haceTresDias,
                dirty = false,
                syncedAtMillis = haceTresDias
            )
        )

        val grupos = agruparHistorialPorFecha(entidades, ahora = ahora)

        assertEquals(listOf("Hoy", "Anteriores"), grupos.map { it.titulo })
        assertEquals(
            "La fila con fecha futura debe pintarse en Hoy",
            listOf("futuro"),
            grupos.first { it.titulo == "Hoy" }.escaneos.map { it.id }
        )
    }
}
