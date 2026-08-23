package com.qrsecurity.detector.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.cache.CacheDetalleEscaneos
import com.qrsecurity.detector.cache.DetalleEscaneoCacheado
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.FakeMediadorSincronizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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
 * Tests de [DetalleUrlViewModel] para los fixes de Fase 0 de la auditoria
 * de frontend:
 *
 *  - B2: `desbloqueoCompletado` es un evento one-shot tipado que se emite
 *    una sola vez por desbloqueo exitoso (reemplaza al flag
 *    `desbloqueoPendiente` que la UI correlacionaba con el tipo de
 *    mensaje).
 *  - B4: guarda de reentrada en `onAction` — dos dispatches consecutivos
 *    (doble-tap) de una accion destructiva solo ejecutan UNA cascada
 *    (un solo `eliminarCompletado`), y la guarda se libera al terminar
 *    para permitir la siguiente accion.
 *
 * Estrategia identica a [DatosTabsViewModelTest]: Room in-memory +
 * Dispatchers.Unconfined en los repos + FakeMediadorSincronizacion +
 `drenarRoomYDispatcher()` para drenar los hops Room→Main.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DetalleUrlViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var db: BaseDatosSeguridad
    private lateinit var repoEscaneos: RepositorioEscaneos
    private lateinit var repoUrls: RepositorioUrlsBloqueadas
    private lateinit var cacheDetalle: CacheDetalleEscaneos
    private lateinit var viewModel: DetalleUrlViewModel
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
        cacheDetalle = CacheDetalleEscaneos()
        viewModel = DetalleUrlViewModel(
            repoEscaneos, repoUrls, FakeMediadorSincronizacion(context), cacheDetalle,
            SavedStateHandle()
        )
    }

    @After
    fun tearDown() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
        Dispatchers.resetMain()
        db.close()
    }

    private suspend fun TestScope.drenarRoomYDispatcher() {
        repeat(5) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        advanceUntilIdle()
    }

    private fun escaneoSemilla(
        id: String = "id-1",
        urlLimpia: String = "https://ejemplo.com/path",
        creadoEnMillis: Long = 1_000L
    ) = EscaneoEntity(
        id = id,
        urlOriginal = urlLimpia,
        urlLimpia = urlLimpia,
        probabilidad = 0.9f,
        nivelAlerta = "MALICIOSO",
        delegado = null,
        esMalicioso = true,
        creadoEnMillis = creadoEnMillis
    )

    // ──────────────────────────────────────────────────────────────
    // B2 — desbloqueo exitoso emite desbloqueoCompletado + EXITO
    // ──────────────────────────────────────────────────────────────

    @Test
    fun desbloquearUrl_exitoso_emiteDesbloqueoCompletadoYMensajeExito() = runTest(testDispatcher) {
        db.urlBloqueadaDao().insertar(
            UrlBloqueadaEntity(
                id = "b-1",
                url = "https://ejemplo.com/path",
                razon = "maliciosa",
                creadoEnMillis = 1L
            )
        )

        val desbloqueos = mutableListOf<Unit>()
        val mensajes = mutableListOf<MensajeDetalleUrl>()
        collectorJobs += viewModel.viewModelScope.launch { viewModel.desbloqueoCompletado.collect { desbloqueos += it } }
        collectorJobs += viewModel.viewModelScope.launch { viewModel.mensaje.collect { mensajes += it } }

        viewModel.onAction(DetalleUrlAction.DesbloquearUrl("https://ejemplo.com/path"))
        drenarRoomYDispatcher()

        assertEquals(
            "Un desbloqueo exitoso debe emitir exactamente UN desbloqueoCompletado",
            1,
            desbloqueos.size
        )
        assertTrue(
            "El mensaje acompañante debe ser el EXITO tipado. Fue: $mensajes",
            MensajeDetalleUrl.UrlDesbloqueada in mensajes
        )
    }

    @Test
    fun desbloquearUrl_urlNoBloqueada_noEmiteDesbloqueoCompletado() = runTest(testDispatcher) {
        val desbloqueos = mutableListOf<Unit>()
        collectorJobs += viewModel.viewModelScope.launch { viewModel.desbloqueoCompletado.collect { desbloqueos += it } }

        viewModel.onAction(DetalleUrlAction.DesbloquearUrl("https://no-bloqueada.com"))
        drenarRoomYDispatcher()

        assertEquals(
            "Sin row bloqueada no hay exito real: el evento tipado no debe emitirse",
            0,
            desbloqueos.size
        )
    }

    // ──────────────────────────────────────────────────────────────
    // B4 — guarda de reentrada en acciones destructivas
    // ──────────────────────────────────────────────────────────────

    @Test
    fun eliminarUrl_dobleDispatch_consecutivo_emiteUnSoloEvento() = runTest(testDispatcher) {
        db.escaneoDao().insertar(escaneoSemilla())

        val eliminados = mutableListOf<Unit>()
        collectorJobs += viewModel.viewModelScope.launch { viewModel.eliminarCompletado.collect { eliminados += it } }

        // Doble-tap: dos dispatches SIN avanzar el dispatcher entre ellos —
        // el segundo debe rechazarse por la guarda antes de lanzar la
        // segunda cascada DELETE.
        viewModel.onAction(DetalleUrlAction.EliminarUrl("https://ejemplo.com/path"))
        viewModel.onAction(DetalleUrlAction.EliminarUrl("https://ejemplo.com/path"))
        drenarRoomYDispatcher()

        assertEquals(
            "El doble dispatch solo debe ejecutar UNA cascada de eliminado (un solo evento de navegacion atras)",
            1,
            eliminados.size
        )
    }

    @Test
    fun eliminarUrl_guardaSeLiberaTrasCompletar_permiteSiguienteAccion() = runTest(testDispatcher) {
        db.escaneoDao().insertar(escaneoSemilla(id = "id-a", urlLimpia = "https://a.com"))
        db.escaneoDao().insertar(escaneoSemilla(id = "id-b", urlLimpia = "https://b.com", creadoEnMillis = 2_000L))

        val eliminados = mutableListOf<Unit>()
        collectorJobs += viewModel.viewModelScope.launch { viewModel.eliminarCompletado.collect { eliminados += it } }

        viewModel.onAction(DetalleUrlAction.EliminarUrl("https://a.com"))
        drenarRoomYDispatcher()

        // La guarda es de reentrada, no un one-shot: tras completar la
        // primera accion, una segunda accion sobre OTRA URL debe proceder.
        viewModel.onAction(DetalleUrlAction.EliminarUrl("https://b.com"))
        drenarRoomYDispatcher()

        assertEquals(
            "Tras completar la primera eliminacion, la segunda debe ejecutarse (guarda liberada en finally)",
            2,
            eliminados.size
        )
    }

    // ──────────────────────────────────────────────────────────────
    // F1.1 — EliminarVersion: borrado individual, NO cascada
    // ──────────────────────────────────────────────────────────────

    @Test
    fun eliminarVersion_borraSoloElIdIndicado_yEmiteEvento() = runTest(testDispatcher) {
        // Dos versiones de la misma URL: id-a (antigua, creada antes) e
        // id-b (vigente).
        db.escaneoDao().insertar(escaneoSemilla(id = "id-a", urlLimpia = "https://misma.com", creadoEnMillis = 1_000L))
        db.escaneoDao().insertar(escaneoSemilla(id = "id-b", urlLimpia = "https://misma.com", creadoEnMillis = 2_000L))

        val eliminados = mutableListOf<Unit>()
        collectorJobs += viewModel.viewModelScope.launch { viewModel.eliminarCompletado.collect { eliminados += it } }

        viewModel.onAction(DetalleUrlAction.EliminarVersion("id-a"))
        drenarRoomYDispatcher()

        assertEquals(
            "La versión eliminada debe emitir un solo evento de navegación atrás",
            1,
            eliminados.size
        )
        val viva = db.escaneoDao().obtenerPorId("id-b")
        assertNotNull(
            "La OTRA versión de la misma URL debe seguir viva (borrado individual, no cascada)",
            viva
        )
        assertEquals(
            "La versión eliminada debe desaparecer de Room",
            null,
            db.escaneoDao().obtenerPorId("id-a")
        )
    }

    // ──────────────────────────────────────────────────────────────
    // S3 — cadena reactiva: carga feliz, reKey del SyncWorker, NoEncontrado
    // ──────────────────────────────────────────────────────────────

    @Test
    fun cargarEscaneo_idExistente_llegaACargado() = runTest(testDispatcher) {
        db.escaneoDao().insertar(escaneoSemilla(id = "vivo-1", urlLimpia = "https://vivo.com"))

        viewModel.cargarEscaneo("vivo-1")
        drenarRoomYDispatcher()

        val estado = viewModel.uiState.value
        assertTrue(
            "Un id existente debe llegar a Cargado. Fue: $estado",
            estado is DetalleUrlUiState.Cargado
        )
        assertEquals(
            "El Cargado debe exponer el escaneo pedido",
            "vivo-1",
            (estado as DetalleUrlUiState.Cargado).escaneo.id
        )
    }

    @Test
    fun cargarEscaneo_idInexistente_llegaANoEncontrado() = runTest(testDispatcher) {
        viewModel.cargarEscaneo("no-existe")
        drenarRoomYDispatcher()

        assertTrue(
            "Un id inexistente (sin fila viva de la misma URL) debe llegar a NoEncontrado. Fue: ${viewModel.uiState.value}",
            viewModel.uiState.value is DetalleUrlUiState.NoEncontrado
        )
    }

    @Test
    fun cargarEscaneo_reKey_rescribeLaObservacionAlIdNuevo() = runTest(testDispatcher) {
        // Escaneo bajo client UUID; el SyncWorker hará reKey a server UUID.
        db.escaneoDao().insertar(
            escaneoSemilla(id = "client-uuid", urlLimpia = "https://rekey.com", creadoEnMillis = 1_000L)
        )
        viewModel.cargarEscaneo("client-uuid")
        drenarRoomYDispatcher()
        assertTrue(
            "Pre-condicion: Cargado bajo el client UUID",
            viewModel.uiState.value is DetalleUrlUiState.Cargado
        )

        // Simular el reKey: la fila cambia su PK (borrar la vieja, insertar
        // la misma fila con el id del server, mismo urlLimpia/creadoEnMillis).
        db.escaneoDao().eliminarPorId("client-uuid")
        db.escaneoDao().insertar(
            escaneoSemilla(id = "server-uuid", urlLimpia = "https://rekey.com", creadoEnMillis = 1_000L)
        )
        // Drain reforzado: la migracion reKey encadena varios hops reales
        // (invalidacion de Room → null → resolucion del id vivo →
        // re-subscripcion via flatMapLatest → nueva emision), cada uno en
        // un thread distinto (tracker de Room / Main virtual).
        repeat(15) {
            Thread.sleep(100)
            advanceUntilIdle()
        }
        advanceUntilIdle()

        val estado = viewModel.uiState.value
        assertTrue(
            "Tras el reKey el estado debe seguir siendo Cargado (no NoEncontrado ni flash). Fue: $estado",
            estado is DetalleUrlUiState.Cargado
        )
        assertEquals(
            "La observacion debe haber migrado al server UUID real",
            "server-uuid",
            (estado as DetalleUrlUiState.Cargado).escaneo.id
        )
    }

    @Test
    fun cargarEscaneo_borradoRealSinFilaViva_llegaANoEncontrado() = runTest(testDispatcher) {
        db.escaneoDao().insertar(
            escaneoSemilla(id = "efimero", urlLimpia = "https://efimera.com", creadoEnMillis = 1_000L)
        )
        viewModel.cargarEscaneo("efimero")
        drenarRoomYDispatcher()
        assertTrue(viewModel.uiState.value is DetalleUrlUiState.Cargado)

        // DELETE real: la fila desaparece y NO queda ninguna otra version
        // viva de la misma URL (U6) — no es un reKey.
        db.escaneoDao().eliminarPorId("efimero")
        drenarRoomYDispatcher()

        assertTrue(
            "Sin fila viva de la misma URL, el estado debe ser NoEncontrado (no un Cargado fantasma). Fue: ${viewModel.uiState.value}",
            viewModel.uiState.value is DetalleUrlUiState.NoEncontrado
        )
    }

    // ──────────────────────────────────────────────────────────────
    // RC1 — prefill síncrono del cache en el constructor (SavedStateHandle)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun init_idEnSavedStateHandleYCacheHit_arrancaCargadoSinAvanzarElDispatcher() = runTest(testDispatcher) {
        db.escaneoDao().insertar(
            escaneoSemilla(id = "cacheado-1", urlLimpia = "https://cacheada.com")
        )
        cacheDetalle.guardar(
            DetalleEscaneoCacheado(
                escaneo = escaneoSemilla(id = "cacheado-1", urlLimpia = "https://cacheada.com"),
                urlBloqueada = false,
                esUltimaVersion = true,
                totalReescaneos = 0
            )
        )

        val vm = DetalleUrlViewModel(
            repoEscaneos,
            repoUrls,
            FakeMediadorSincronizacion(ApplicationProvider.getApplicationContext()),
            cacheDetalle,
            SavedStateHandle(mapOf("id" to "cacheado-1"))
        )

        // SIN advanceUntilIdle: el estado inicial debe salir del prefill
        // síncrono del constructor (lectura de map en memoria), no de la
        // cadena reactiva — así la PRIMERA composición ya pinta el detalle,
        // sin frames de spinner.
        val estadoInicial = vm.uiState.value
        assertTrue(
            "Con cache hit + id en SavedStateHandle, el VM debe nacer Cargado. Fue: $estadoInicial",
            estadoInicial is DetalleUrlUiState.Cargado
        )
        assertEquals(
            "El prefill debe exponer el escaneo del id navegado",
            "cacheado-1",
            (estadoInicial as DetalleUrlUiState.Cargado).escaneo.id
        )

        // La cadena reactiva re-valida contra Room en background y el
        // estado se mantiene (sin flash de Cargando intermedio).
        repeat(5) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        advanceUntilIdle()
        val estadoFinal = vm.uiState.value
        assertTrue(
            "Tras la re-validación de Room el estado debe seguir Cargado. Fue: $estadoFinal",
            estadoFinal is DetalleUrlUiState.Cargado
        )
        assertEquals("cacheado-1", (estadoFinal as DetalleUrlUiState.Cargado).escaneo.id)
    }

    @Test
    fun init_idEnSavedStateHandleSinCache_arrancaCargandoHastaRoom() = runTest(testDispatcher) {
        db.escaneoDao().insertar(
            escaneoSemilla(id = "frio-1", urlLimpia = "https://fria.com")
        )

        val vm = DetalleUrlViewModel(
            repoEscaneos,
            repoUrls,
            FakeMediadorSincronizacion(ApplicationProvider.getApplicationContext()),
            cacheDetalle,
            SavedStateHandle(mapOf("id" to "frio-1"))
        )

        // Cache miss: el constructor NO tiene nada que prefillar — el
        // estado inicial sigue siendo Cargando hasta que Room emita.
        assertTrue(
            "Sin cache hit el VM arranca en Cargando (spinner breve honesto). Fue: ${vm.uiState.value}",
            vm.uiState.value is DetalleUrlUiState.Cargando
        )

        repeat(5) {
            Thread.sleep(50)
            advanceUntilIdle()
        }
        advanceUntilIdle()
        assertTrue(
            "Tras el drain, Room debe haber llevado el estado a Cargado. Fue: ${vm.uiState.value}",
            vm.uiState.value is DetalleUrlUiState.Cargado
        )
    }
}
