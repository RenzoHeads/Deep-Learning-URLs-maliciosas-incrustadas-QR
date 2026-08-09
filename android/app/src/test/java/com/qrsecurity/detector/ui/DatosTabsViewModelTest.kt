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
import com.qrsecurity.detector.datos.sync.FakeMediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
     * Suscribe los 7 StateFlows del ViewModel para que WhileSubscribed(5_000)
     * mantenga el upstream activo y Room emita cambios reactivos.
     */
    private fun subscribirTodos() {
        collectorJobs += viewModel.viewModelScope.launch { viewModel.historialTodos.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.historialSeguros.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.historialMaliciosos.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.totalEscaneos.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.amenazas.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.ultimos7Dias.collect { } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.urlsBloqueadas.collect { } }
    }

    // ── Estado inicial — todos los StateFlows arrancan con initialValue ──

    @Test
    fun estadoInicial_historialTodos_emptyList() {
        assertEquals(emptyList<EscaneoEntity>(), viewModel.historialTodos.value)
    }

    @Test
    fun estadoInicial_historialSeguros_emptyList() {
        assertEquals(emptyList<EscaneoEntity>(), viewModel.historialSeguros.value)
    }

    @Test
    fun estadoInicial_historialMaliciosos_emptyList() {
        assertEquals(emptyList<EscaneoEntity>(), viewModel.historialMaliciosos.value)
    }

    @Test
    fun estadoInicial_totalEscaneos_null() {
        // Bug 3 fix: StateFlow<Int?> inicia en null para distinguir
        // "cargando" de "0 real". Cuando Room emite, sera 0 (DB vacia).
        assertNull(viewModel.totalEscaneos.value)
    }

    @Test
    fun estadoInicial_amenazas_null() {
        assertNull(viewModel.amenazas.value)
    }

    @Test
    fun estadoInicial_ultimos7Dias_null() {
        assertNull(viewModel.ultimos7Dias.value)
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
    fun historialSeguros_emiteSoloEscaneosSeguros() = runTest(testDispatcher) {
        subscribirTodos()
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "safe-1",
                urlOriginal = "https://benign.example.com",
                urlLimpia = "benign.example.com",
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "mal-1",
                urlOriginal = "https://evil.example.com",
                urlLimpia = "evil.example.com",
                probabilidad = 0.95f,
                nivelAlerta = "MALICIOSO",
                delegado = "NNAPI",
                esMalicioso = true,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
        drenarRoomYDispatcher()

        assertEquals(
            "historialSeguros debe emitir solo 1 escaneo seguro (no el malicioso)",
            1,
            viewModel.historialSeguros.value.size
        )
        assertEquals("safe-1", viewModel.historialSeguros.value[0].id)
    }

    @Test
    fun historialMaliciosos_emiteSoloEscaneosMaliciosos() = runTest(testDispatcher) {
        subscribirTodos()
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "safe-2",
                urlOriginal = "https://benign.example.com",
                urlLimpia = "benign.example.com",
                probabilidad = 0.1f,
                nivelAlerta = "SEGURO",
                delegado = null,
                esMalicioso = false,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "mal-2",
                urlOriginal = "https://evil.example.com",
                urlLimpia = "evil.example.com",
                probabilidad = 0.95f,
                nivelAlerta = "MALICIOSO",
                delegado = "NNAPI",
                esMalicioso = true,
                creadoEnMillis = System.currentTimeMillis(),
                dirty = false,
                syncedAtMillis = System.currentTimeMillis()
            )
        )
        drenarRoomYDispatcher()

        assertEquals(
            "historialMaliciosos debe emitir solo 1 escaneo malicioso",
            1,
            viewModel.historialMaliciosos.value.size
        )
        assertEquals("mal-2", viewModel.historialMaliciosos.value[0].id)
    }

    @Test
    fun totalEscaneos_emiteContadorTrasInsertMultiple() = runTest(testDispatcher) {
        subscribirTodos()
        db.escaneoDao().insertarTodos(
            listOf(
                EscaneoEntity(
                    id = "e-1",
                    urlOriginal = "https://a.example.com",
                    urlLimpia = "a.example.com",
                    probabilidad = 0.1f,
                    nivelAlerta = "SEGURO",
                    delegado = null,
                    esMalicioso = false,
                    creadoEnMillis = System.currentTimeMillis(),
                    dirty = false,
                    syncedAtMillis = System.currentTimeMillis()
                ),
                EscaneoEntity(
                    id = "e-2",
                    urlOriginal = "https://b.example.com",
                    urlLimpia = "b.example.com",
                    probabilidad = 0.9f,
                    nivelAlerta = "MALICIOSO",
                    delegado = "NNAPI",
                    esMalicioso = true,
                    creadoEnMillis = System.currentTimeMillis(),
                    dirty = false,
                    syncedAtMillis = System.currentTimeMillis()
                )
            )
        )
        drenarRoomYDispatcher()

        assertEquals("totalEscaneos debe emitir 2 tras insert dos escaneos", 2, viewModel.totalEscaneos.value)
    }

    @Test
    fun amenazas_emiteContadorSoloMaliciosos() = runTest(testDispatcher) {
        subscribirTodos()
        db.escaneoDao().insertarTodos(
            listOf(
                EscaneoEntity(
                    id = "s-1",
                    urlOriginal = "https://safe.example.com",
                    urlLimpia = "safe.example.com",
                    probabilidad = 0.1f,
                    nivelAlerta = "SEGURO",
                    delegado = null,
                    esMalicioso = false,
                    creadoEnMillis = System.currentTimeMillis(),
                    dirty = false,
                    syncedAtMillis = System.currentTimeMillis()
                ),
                EscaneoEntity(
                    id = "m-1",
                    urlOriginal = "https://mal.example.com",
                    urlLimpia = "mal.example.com",
                    probabilidad = 0.9f,
                    nivelAlerta = "MALICIOSO",
                    delegado = "NNAPI",
                    esMalicioso = true,
                    creadoEnMillis = System.currentTimeMillis(),
                    dirty = false,
                    syncedAtMillis = System.currentTimeMillis()
                )
            )
        )
        drenarRoomYDispatcher()

        assertEquals("amenazas debe emitir 1 (solo el malicioso)", 1, viewModel.amenazas.value)
    }

    @Test
    fun ultimos7Dias_emiteContadorEscaneosRecientes() = runTest(testDispatcher) {
        subscribirTodos()
        val ahora = System.currentTimeMillis()
        // Reciente (dentro de 7 dias)
        db.escaneoDao().insertar(
            EscaneoEntity(
                id = "rec-1",
                urlOriginal = "https://recent.example.com",
                urlLimpia = "recent.example.com",
                probabilidad = 0.5f,
                nivelAlerta = "SOSPECHOSO",
                delegado = "NNAPI",
                esMalicioso = true,
                creadoEnMillis = ahora,
                dirty = false,
                syncedAtMillis = ahora
            )
        )
        drenarRoomYDispatcher()

        assertTrue(
            "ultimos7Dias debe emitir >= 1 (el escaneo reciente). Fue: ${viewModel.ultimos7Dias.value}",
            (viewModel.ultimos7Dias.value ?: 0) >= 1
        )
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
}
