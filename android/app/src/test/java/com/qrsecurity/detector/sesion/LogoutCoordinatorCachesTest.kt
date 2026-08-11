package com.qrsecurity.detector.sesion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.ui.CacheDetalleEscaneos
import com.qrsecurity.detector.ui.DetalleUrlUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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
 * Bug 3 (pieza c) — LogoutCoordinator NO limpia CacheDetalleEscaneos
 * (@Singleton Hilt).
 *
 * [LogoutCoordinator.logout] limpia Room, WorkManager y
 * `Pipeline.limpiarCacheInferencia()`, pero NO limpia
 * [CacheDetalleEscaneos] (@Singleton Hilt). Sus entradas
 * [DetalleUrlUiState.Cargado] (con EscaneoEntity completo + flags
 * `urlBloqueada`, `esUltimaVersion`, `totalReescaneos`) sobreviven al
 * logout → al volver a loguear, el detalle aparece "pre-cargado" con
 * datos stale del usuario anterior. Fuga cross-user.
 *
 * Adicionalmente (pieza d), [LogoutCoordinator] no llama
 * [Pipeline.reiniciar] — el estado observable del Pipeline queda como
 * `ResultadoListo` o `UrlDuplicada` del usuario anterior.
 *
 * Contrato de este test:
 *  - Pre-poblar CacheDetalleEscaneos con una entrada Cargado.
 *  - Llamar `coordinator.logout()`.
 *  - El cache de CacheDetalleEscaneos debe quedar vacio.
 *  - Pipeline.estado debe quedar en Escaneando (no stale del usuario anterior).
 *
 * Red: `LogoutCoordinator` no acepta `CacheDetalleEscaneos` en su constructor
 *   → no compila.
 * Green: inyectar `CacheDetalleEscaneos` y llamar `cacheDetalle.limpiar()` +
 *   `pipeline.reiniciar()` en `logout()`.
 *
 * NOTA: PipelineViewModel._resultadoCacheado (SavedStateHandle) es resetado
 * por `pipelineViewModel.reiniciar()` que NavGuardian invoca en el
 * `LaunchedEffect(logueado)` cuando el estado de sesion pasa a false.
 * LogoutCoordinator (Singleton) no puede inyectar PipelineViewModel
 * (@HiltViewModel) — arquitectura correcta: la UI reacciona al estado
 * reactivo de sesion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogoutCoordinatorCachesTest {

    private lateinit var appContext: Context
    private lateinit var db: BaseDatosSeguridad
    private lateinit var mediador: FakeMediadorConFlagCaches
    private lateinit var sesionUsuario: FakeSesionUsuarioCaches
    private lateinit var cacheDetalle: CacheDetalleEscaneos
    private lateinit var pipeline: Pipeline
    private lateinit var coordinator: LogoutCoordinator
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)

        db = Room.inMemoryDatabaseBuilder(
            appContext,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()

        mediador = FakeMediadorConFlagCaches(appContext)

        val backend = ClienteBackend(
            baseUrl = "http://localhost:1/",
            tokenProvider = { "test-token" }
        )
        val repoEscaneos = RepositorioEscaneos(
            db = db, backend = backend, json = json, ioDispatcher = testDispatcher
        )
        val repoUrlsBloqueadas = RepositorioUrlsBloqueadas(
            db = db, backend = backend, json = json, ioDispatcher = testDispatcher
        )
        pipeline = Pipeline(
            context = appContext, db = db, backend = backend, json = json,
            repoEscaneos = repoEscaneos, repoUrlsBloqueadas = repoUrlsBloqueadas,
            mediadorSync = mediador
        )
        cacheDetalle = CacheDetalleEscaneos()
        sesionUsuario = FakeSesionUsuarioCaches(appContext)

        // Red: LogoutCoordinator no acepta cacheDetalleEscaneos.
        coordinator = LogoutCoordinator(
            appContext = appContext,
            mediadorSincronizacion = mediador,
            db = db,
            sesionUsuario = sesionUsuario,
            pipeline = pipeline,
            cacheDetalleEscaneos = cacheDetalle
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `logout limpia CacheDetalleEscaneos`() = runTest(testDispatcher) {
        // ── Pre-poblar el cache con una entrada del usuario anterior ──
        val escaneoViejo = EscaneoEntity(
            id = "esc-stale-1",
            urlOriginal = "https://evil-anterior.com/path",
            urlLimpia = "evil-anterior.com/path",
            probabilidad = 0.97f,
            nivelAlerta = "MALICIOSO",
            delegado = "CPU",
            esMalicioso = true,
            creadoEnMillis = 1_000L
        )
        cacheDetalle.guardar(
            DetalleUrlUiState.Cargado(
                escaneo = escaneoViejo,
                urlBloqueada = true,
                esUltimaVersion = true,
                totalReescaneos = 2
            )
        )
        assertTrue(
            "El cache debe tener 1 entrada antes del logout",
            cacheDetalle.cache.value.size == 1
        )
        assertTrue(
            "obtener debe devolver la entrada stale antes del logout",
            cacheDetalle.obtener("esc-stale-1") != null
        )

        // ── Act: logout ──
        coordinator.logout()

        // ── Assert: el cache debe quedar vacio ──
        assertEquals(
            "CacheDetalleEscaneos debe quedar vacio tras logout " +
                "(fuga cross-user de DetalleUrlUiState.Cargado)",
            emptyMap<String, DetalleUrlUiState.Cargado>(),
            cacheDetalle.cache.value
        )
        assertNull(
            "obtener debe devolver null tras logout para el id stale",
            cacheDetalle.obtener("esc-stale-1")
        )
    }

    @Test
    fun `logout resetea Pipeline estado a Escaneando`() = runTest(testDispatcher) {
        // El Pipeline arranca en Inicializando — forzar a Escaneando primero
        // para simular que el usuario estuvo activo.
        pipeline.reiniciar()
        assertEquals(
            "Pipeline.estado debe ser Escaneando tras reiniciar() manual",
            Pipeline.Estado.Escaneando,
            pipeline.estado.value
        )

        // ── Act: logout ──
        coordinator.logout()

        // ── Assert: Pipeline.estado debe seguir siendo Escaneando ──
        // (no Inicializando, no ResultadoListo stale)
        assertEquals(
            "Pipeline.estado debe ser Escaneando tras logout " +
                "(no debe quedar stale del usuario anterior)",
            Pipeline.Estado.Escaneando,
            pipeline.estado.value
        )
    }
}

/** Fake con flag para verificar cancelarTodo. */
private class FakeMediadorConFlagCaches(context: Context) : MediadorSincronizacion(context) {
    var cancelarTodoLlamado = false
    override fun cancelarTodo() {
        cancelarTodoLlamado = true
    }
}

/** Fake en memoria de SesionUsuario (Keystore no soportado en Robolectric). */
private class FakeSesionUsuarioCaches(context: Context) : SesionUsuario(context) {
    var cerrarSesionLlamado = false
    private var logueado = false
    private var token: String? = null

    override fun estaLogueado(): Boolean = logueado && !token.isNullOrBlank()
    override fun obtenerToken(): String? = token
    override fun guardarSesion(token: String, usuario: String, correo: String) {
        this.token = token
        this.logueado = true
    }

    override fun cerrarSesion() {
        cerrarSesionLlamado = true
        logueado = false
        token = null
    }
}
