package com.qrsecurity.detector.sesion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.PendingOpEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.datos.sync.SyncWorker.Companion.KEY_INITIAL_SYNC_COMPLETED
import com.qrsecurity.detector.datos.sync.SyncWorker.Companion.KEY_ULTIMO_SYNC
import com.qrsecurity.detector.datos.sync.SyncWorker.Companion.PREFS_SYNC
import com.qrsecurity.detector.pipeline.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** Fake con flag para verificar que [MediadorSincronizacion.cancelarTodo] fue invocado. */
private class FakeMediadorConFlag(context: Context) : MediadorSincronizacion(context) {
    var cancelarTodoLlamado = false
    override fun cancelarTodo() {
        cancelarTodoLlamado = true
    }
}

/**
 * Fake en memoria de [SesionUsuario]. El real usa
 * [androidx.security.crypto.EncryptedSharedPreferences] (Android Keystore),
 * que Robolectric no implementa (KeyStoreException). Al ser [SesionUsuario]
 * `open` (patron de testabilidad ya usado en MediadorSincronizacion), el
 * test puede verificar que [LogoutCoordinator.logout] invoca
 * [SesionUsuario.cerrarSesion] sin depender del Keystore.
 */
private class FakeSesionUsuario(context: Context) : SesionUsuario(context) {
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

/**
 * m9 (FASE 5 del plan de correccion): cobertura de
 * [LogoutCoordinator.logout] — el flujo completo de cierre de sesion.
 *
 * Bug H7: [SesionUsuario.cerrarSesion] solo borraba token + flag, dejando la
 * Room, el WorkManager encolado y el cache de inferencia del Pipeline
 * intactos (fuga cross-user). [LogoutCoordinator.logout] es el fix que
 * coordina: cancelar work → esperar cancelacion → clearAllTables →
 * resetear prefs de sync → limpiar cache del Pipeline → cerrar sesion.
 *
 * Este test construye el coordinador real con dependencias reales
 * (Room in-memory + Pipeline) y fakes con flag para
 * [MediadorSincronizacion] y [SesionUsuario] (este ultimo usa
 * EncryptedSharedPreferences/Keystore, no soportado por Robolectric —
 * [SesionUsuario] es `open` para permitir el fake, igual que
 * [MediadorSincronizacion]). No usamos MockWebServer: logout() no hace
 * llamadas HTTP.
 *
 * Cubre:
 *   1. `cancelarTodo` es llamado y todas las tablas Room quedan vacias.
 *   2. Las prefs de sync se resetean (initial_sync_completed=false,
 *      ultimo_sync=0) para forzar full pull del siguiente usuario.
 *   3. La sesion se cierra via [SesionUsuario.cerrarSesion] (invocado).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LogoutCoordinatorTest {

    private lateinit var appContext: Context
    private lateinit var db: BaseDatosSeguridad
    private lateinit var mediador: FakeMediadorConFlag
    private lateinit var sesionUsuario: FakeSesionUsuario
    private lateinit var coordinator: LogoutCoordinator
    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()
        // El constructor de MediadorSincronizacion (super) puede tocar
        // WorkManager.getInstance — inicializar el test instance primero
        // (patron de FakeMediadorSincronizacion.kt).
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)

        db = Room.inMemoryDatabaseBuilder(
            appContext,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()

        mediador = FakeMediadorConFlag(appContext)

        val backend = ClienteBackend(
            baseUrl = "http://localhost:1/",
            tokenProvider = { "test-token" }
        )
        val repoEscaneos = RepositorioEscaneos(
            db = db,
            backend = backend,
            json = json,
            ioDispatcher = testDispatcher
        )
        val repoUrlsBloqueadas = RepositorioUrlsBloqueadas(
            db = db,
            backend = backend,
            json = json,
            ioDispatcher = testDispatcher
        )
        // Pipeline constructor (firma real): (context, db, backend, json,
        // repoEscaneos, repoUrlsBloqueadas, mediadorSync, motorInferencia).
        // `motorInferencia` es MotorInferenciaFake (no carga TFLite).
        com.qrsecurity.detector.ml.setupTestVocab()
        val pipeline = Pipeline(
            context = appContext,
            db = db,
            backend = backend,
            json = json,
            repoEscaneos = repoEscaneos,
            repoUrlsBloqueadas = repoUrlsBloqueadas,
            mediadorSync = mediador,
            motorInferencia = com.qrsecurity.detector.ml.MotorInferenciaFake()
        )

        sesionUsuario = FakeSesionUsuario(appContext)
        coordinator = LogoutCoordinator(
            appContext = appContext,
            mediadorSincronizacion = mediador,
            db = db,
            sesionUsuario = sesionUsuario,
            pipeline = pipeline,
            cacheDetalleEscaneos = com.qrsecurity.detector.ui.CacheDetalleEscaneos()
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `logout cancela el work de WorkManager y vacia todas las tablas Room`() =
        runTest(testDispatcher) {
            // ── Estado previo: datos del usuario anterior en todas las tablas ──
            db.escaneoDao().insertar(
                EscaneoEntity(
                    id = "esc-1",
                    urlOriginal = "http://evil.com",
                    urlLimpia = "evil.com",
                    probabilidad = 0.99f,
                    nivelAlerta = "MALICIOSO",
                    delegado = null,
                    esMalicioso = true,
                    creadoEnMillis = 1_000L
                )
            )
            db.urlBloqueadaDao().insertar(
                UrlBloqueadaEntity(
                    id = "bl-1",
                    url = "evil.com",
                    razon = "test",
                    creadoEnMillis = 1_000L
                )
            )
            db.pendingOpDao().insertar(
                PendingOpEntity(
                    tabla = "escaneos",
                    tipoOperacion = "CREATE",
                    idLocal = "esc-1",
                    payloadJson = null,
                    creadoEnMillis = 1_000L
                )
            )

            coordinator.logout()

            assertTrue(
                "logout debe cancelar el work encolado/periodico via MediadorSincronizacion",
                mediador.cancelarTodoLlamado
            )
            assertNull("escaneos debe quedar vacia", db.escaneoDao().obtenerPorId("esc-1"))
            assertNull("urls_bloqueadas debe quedar vacia", db.urlBloqueadaDao().obtenerPorId("bl-1"))
            assertNull("pending_ops debe quedar vacia", db.pendingOpDao().minPendingId())
        }

    @Test
    fun `logout resetea las prefs de sync para forzar full pull del siguiente usuario`() =
        runTest(testDispatcher) {
            // ── Estado previo: sync completado con cursor reciente ──
            appContext.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_INITIAL_SYNC_COMPLETED, true)
                .putLong(KEY_ULTIMO_SYNC, 123_456_789L)
                .apply()

            coordinator.logout()

            val prefs = appContext.getSharedPreferences(PREFS_SYNC, Context.MODE_PRIVATE)
            assertFalse(
                "initial_sync_completed debe resetearse a false",
                prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, true)
            )
            assertEquals(
                "ultimo_sync debe resetearse a 0 para no skippear el primer pull",
                0L,
                prefs.getLong(KEY_ULTIMO_SYNC, -1L)
            )
        }

    @Test
    fun `logout cierra la sesion del usuario en SesionUsuario`() =
        runTest(testDispatcher) {
            // ── Estado previo: usuario logueado ──
            sesionUsuario.guardarSesion(
                token = "tok-1",
                usuario = "tester",
                correo = "tester@test.com"
            )
            assertTrue(sesionUsuario.estaLogueado())

            coordinator.logout()

            assertTrue(
                "logout debe invocar SesionUsuario.cerrarSesion()",
                sesionUsuario.cerrarSesionLlamado
            )
            assertFalse("la sesion debe quedar cerrada", sesionUsuario.estaLogueado())
            assertNull("el token debe quedar borrado", sesionUsuario.obtenerToken())
        }
}
