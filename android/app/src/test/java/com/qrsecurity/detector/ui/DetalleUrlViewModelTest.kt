package com.qrsecurity.detector.ui

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.cache.CacheDetalleEscaneos
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
        val mediadorSync = FakeMediadorSincronizacion(context)
        viewModel = DetalleUrlViewModel(repoEscaneos, repoUrls, mediadorSync, CacheDetalleEscaneos())
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
        val mensajes = mutableListOf<MensajeUi>()
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
            "El mensaje acompanante debe ser EXITO. Fue: ${mensajes.map { it.tipo }}",
            mensajes.any { it.tipo == TipoMensaje.EXITO }
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
}
