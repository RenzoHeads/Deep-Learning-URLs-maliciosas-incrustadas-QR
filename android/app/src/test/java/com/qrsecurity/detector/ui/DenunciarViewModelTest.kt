package com.qrsecurity.detector.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.CategoriaDenunciaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioCategorias
import com.qrsecurity.detector.datos.repositorios.RepositorioDenuncias
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitarios JVM de [DenunciarViewModel] — arquitectura NowInAndroid.
 *
 * Estrategia (fix dispatcher):
 *  - `StandardTestDispatcher` para `setMain` → viewModelScope.launch
 *    despacha a Main (testDispatcher), advanceUntilIdle lo drena.
 *  - `Dispatchers.Unconfined` para `ioDispatcher` del repo →
 *    withContext inline, withTransaction commit sincrono.
 *  - `FakeMediadorSincronizacion` (no-op) → elimina WorkManager del path.
 *  - `drenarRoomYDispatcher()` → Thread.sleep + advanceUntilIdle en rondas:
 *    Room's TransactionExecutor es un OS thread real; la continuation
 *    despues de withTransaction vuelve a Main (testDispatcher) async.
 *    Thread.sleep da tiempo al OS thread; advanceUntilIdle drena la continuation.
 *  - StateFlow WhileSubscribed(5_000): collectorJob mantiene suscriptor
 *    activo para que los Flows emitan desde Room.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class DenunciarViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repoDenuncias: RepositorioDenuncias
    private lateinit var repoCategorias: RepositorioCategorias
    private lateinit var mediadorSync: FakeMediadorSincronizacion
    private lateinit var viewModel: DenunciarViewModel
    private var collectorJob: Job? = null

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
        repoDenuncias = RepositorioDenuncias(db, backend, json, Dispatchers.Unconfined)
        repoCategorias = RepositorioCategorias(db, backend, Dispatchers.Unconfined)
        mediadorSync = FakeMediadorSincronizacion(context)
        viewModel = DenunciarViewModel(repoDenuncias, repoCategorias, mediadorSync)

        // Seed categorias_denuncia — la denuncias table tiene FK a
        // categorias_denuncia(id) (migration MIGRATION_1_2). Sin este seed,
        // crearLocal(idCategoria=1) falla con FK constraint violation dentro
        // de db.withTransaction (OS thread), la continuation nunca vuelve al
        // testDispatcher, y el test se cuelga.
        //
        //	usamos nombre="_fk_seed" (no "Phishing") para no colisionar con
        // el UNIQUE INDEX idx_categorias_denuncia_nombre: varios tests insertan
        // sus propias categorias con nombre="Phishing" y distintos ids.
        kotlinx.coroutines.runBlocking {
            db.categoriaDao().upsertAll(
                listOf(
                    CategoriaDenunciaEntity(id = 1, nombre = "_fk_seed", descripcion = null, syncedAtMillis = 0L)
                )
            )
        }
    }

    @After
    fun tearDown() {
        collectorJob?.cancel()
        Dispatchers.resetMain()
        db.close()
    }

    private fun subscribirCategorias() {
        collectorJob = viewModel.viewModelScope.launch {
            viewModel.categorias.collect { }
        }
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

    // ── Estado inicial ──

    @Test
    fun estadoInicial_enviandoFalse_errorNull_exitoFalse() {
        val state = viewModel.uiState.value
        assertFalse("enviando debe ser false al inicio", state.enviando)
        assertNull("error debe ser null al inicio", state.error)
        assertFalse("exito debe ser false al inicio", state.exito)
        assertEquals("idCategoriaPhishing default es 1", 1, state.idCategoriaPhishing)
    }

    // ── onAction(EnviarDenuncia) — offline-first exitoso ──

    @Test
    fun onAction_enviarDenuncia_urlValida_exitoTrue_enviandoVuelveAFalse() = runTest(testDispatcher) {
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia(
                url = "http://evil-phishing.example.com/login",
                idCategoria = 1,
                descripcion = "URL de phishing detectada al escanear QR"
            )
        )
        drenarRoomYDispatcher()

        val state = viewModel.uiState.value
        assertFalse("enviando debe volver a false", state.enviando)
        assertTrue("exito debe ser true tras guardar local", state.exito)
        assertNull("error debe ser null tras exito", state.error)
    }

    @Test
    fun onAction_enviarDenuncia_persisteEnRoom_offlineFirst() = runTest(testDispatcher) {
        val url = "http://malware.example.com/stolen-creds"
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia(
                url = url,
                idCategoria = 1,
                descripcion = "Robo de credenciales"
            )
        )
        drenarRoomYDispatcher()

        val denuncias = db.denunciaDao().observarTodas().first()
        assertEquals("Debe haber 1 denuncia en Room", 1, denuncias.size)
        assertEquals(url, denuncias[0].url)
        assertEquals(1, denuncias[0].idCategoria)
    }

    @Test
    fun onAction_enviarDenuncia_descripcionBlanca_seEnviaComoNull() = runTest(testDispatcher) {
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia(
                url = "http://suspicious.example.com",
                idCategoria = 1,
                descripcion = "   "
            )
        )
        drenarRoomYDispatcher()

        assertTrue("Debe exitar incluso con descripcion en blanco", viewModel.uiState.value.exito)
    }

    @Test
    fun onAction_enviarDenuncia_urlConEspacios_seTrimea() = runTest(testDispatcher) {
        val urlConEspacios = "  http://evil.example.com  "
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia(
                url = urlConEspacios,
                idCategoria = 1,
                descripcion = "Test"
            )
        )
        drenarRoomYDispatcher()

        val denuncias = db.denunciaDao().observarTodas().first()
        assertEquals(1, denuncias.size)
        assertEquals("La URL debe estar trimeada en Room", urlConEspacios.trim(), denuncias[0].url)
    }

    // ── Idempotencia — doble disparo mientras enviando ──

    @Test
    fun onAction_dobleDisparoMientrasEnvia_noInsertaDoble() = runTest(testDispatcher) {
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia("http://a.example.com", 1, "desc")
        )
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia("http://b.example.com", 1, "desc")
        )
        drenarRoomYDispatcher()

        val denuncias = db.denunciaDao().observarTodas().first()
        assertEquals("Doble disparo mientras enviando no debe insertar 2", 1, denuncias.size)
        assertEquals("http://a.example.com", denuncias[0].url)
    }

    // ── resolverCategoriaPhishing — reactivo desde Room ──

    @Test
    fun resolverCategoriaPhishing_actualizaIdDesdeRoom() = runTest(testDispatcher) {
        subscribirCategorias()
        db.categoriaDao().upsertAll(
            listOf(
                CategoriaDenunciaEntity(id = 5, nombre = "Phishing", descripcion = null, syncedAtMillis = 0L)
            )
        )
        drenarRoomYDispatcher()

        viewModel.resolverCategoriaPhishing("Phishing")
        drenarRoomYDispatcher()

        assertEquals("idCategoriaPhishing debe ser 5 (desde Room)", 5, viewModel.uiState.value.idCategoriaPhishing)
    }

    @Test
    fun resolverCategoriaPhishing_noEncuentra_mantieneDefault() = runTest(testDispatcher) {
        subscribirCategorias()
        viewModel.resolverCategoriaPhishing("Phishing")
        drenarRoomYDispatcher()

        assertEquals("Sin categoria en Room, mantiene default 1", 1, viewModel.uiState.value.idCategoriaPhishing)
    }

    @Test
    fun resolverCategoriaPhishing_caseInsensitive() = runTest(testDispatcher) {
        subscribirCategorias()
        db.categoriaDao().upsertAll(
            listOf(
                CategoriaDenunciaEntity(id = 3, nombre = "phishing", descripcion = null, syncedAtMillis = 0L)
            )
        )
        drenarRoomYDispatcher()

        viewModel.resolverCategoriaPhishing("PHISHING")
        drenarRoomYDispatcher()

        assertEquals(3, viewModel.uiState.value.idCategoriaPhishing)
    }

    // ── consumirEvento — limpia estado para rotacion ──

    @Test
    fun consumirEvento_limpiaErrorYExito() = runTest(testDispatcher) {
        viewModel.onAction(
            DenunciarAction.EnviarDenuncia("http://ok.example.com", 1, "desc")
        )
        drenarRoomYDispatcher()
        assertTrue("exito debe ser true antes de consumir", viewModel.uiState.value.exito)

        viewModel.consumirEvento()

        assertNull("error debe ser null tras consumir", viewModel.uiState.value.error)
        assertFalse("exito debe ser false tras consumir", viewModel.uiState.value.exito)
    }

    // ── categorias Flow — StateFlow con initialValue emptyList ──

    @Test
    fun categorias_initialValue_esEmptyList() {
        assertEquals(
            "Categorias Flow arranca con emptyList como initialValue",
            emptyList<CategoriaDenunciaEntity>(),
            viewModel.categorias.value
        )
    }

    @Test
    fun categorias_emiteDesdeRoom_cuandoSeInserta() = runTest(testDispatcher) {
        subscribirCategorias()
        // Usar insertarTodos (INSERT OR REPLACE) en vez de upsertAll (@Upsert):
        // Room's @Upsert falla con "Cannot execute for last inserted row ID"
        // cuando reemplaza un row existente (id=1 seeded en setUp).
        db.categoriaDao().insertarTodos(
            listOf(
                CategoriaDenunciaEntity(id = 1, nombre = "Phishing", descripcion = "Phishing", syncedAtMillis = 0L),
                CategoriaDenunciaEntity(id = 2, nombre = "Malware", descripcion = "Malware", syncedAtMillis = 0L)
            )
        )
        drenarRoomYDispatcher()

        val categorias = viewModel.categorias.value
        assertEquals(2, categorias.size)
        assertEquals("Phishing", categorias[0].nombre)
        assertEquals("Malware", categorias[1].nombre)
    }
}
