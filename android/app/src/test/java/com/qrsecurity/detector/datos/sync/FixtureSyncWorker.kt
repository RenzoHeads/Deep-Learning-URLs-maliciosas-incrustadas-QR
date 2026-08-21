package com.qrsecurity.detector.datos.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.ml.MotorInferenciaFake
import com.qrsecurity.detector.ml.setupTestVocab
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.sesion.LogoutCoordinator
import com.qrsecurity.detector.sesion.SesionUsuario
import com.qrsecurity.detector.ui.CacheDetalleEscaneos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockWebServer

/** Fake con flag para verificar que [MediadorSincronizacion.cancelarTodo] fue invocado. */
class FakeMediadorConFlag(context: Context) : MediadorSincronizacion(context) {
    var cancelarTodoLlamado = false
    override fun cancelarTodo() {
        cancelarTodoLlamado = true
    }
}

/**
 * Fake en memoria de [SesionUsuario]. El real usa
 * [androidx.security.crypto.EncryptedSharedPreferences] (Android Keystore),
 * que Robolectric no implementa. Al ser [SesionUsuario] `open` (patron de
 * testabilidad ya usado en [LogoutCoordinatorTest]), el test puede verificar
 * que se cerro la sesion sin depender del Keystore.
 */
class FakeSesionUsuario(context: Context) : SesionUsuario(context) {
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

/** Fake de [MonitorRed] que siempre reporta red online. */
class FakeMonitorRed(context: Context) : MonitorRed(context) {
    override fun estaOnlineAhora(): Boolean = true
}

/**
 * Fixture compartido por los tests de [SyncWorker] que necesitan ejercitar
 * `doWork()` completo (S1 error permanente, S3/S7 logout completo en 401).
 *
 * Construye el grafo real del worker con fakes solo donde Robolectric no
 * puede provisionar la dependencia real (Keystore de [SesionUsuario], red
 * validada de [MonitorRed]) o donde el test necesita observabilidad (flag de
 * [FakeMediadorConFlag]). El [LogoutCoordinator] es el real — es justo lo que
 * estos tests verifican (logout completo: clearAllTables + reset de prefs).
 *
 * Patron copiado de [com.qrsecurity.detector.sesion.LogoutCoordinatorTest]
 * (Room in-memory + Pipeline real con MotorInferenciaFake + MockWebServer).
 *
 * Uso:
 * ```
 * private val fixture = FixtureSyncWorker()
 *
 * @Before fun setUp() = fixture.iniciar()
 * @After fun tearDown() = fixture.cerrar()
 * ```
 */
class FixtureSyncWorker {
    val testDispatcher = StandardTestDispatcher()
    lateinit var appContext: Context
    lateinit var server: MockWebServer
    lateinit var db: BaseDatosSeguridad
    lateinit var sesionUsuario: FakeSesionUsuario
    lateinit var mediador: FakeMediadorConFlag
    lateinit var logoutCoordinator: LogoutCoordinator
    lateinit var repoEscaneos: RepositorioEscaneos
    lateinit var repoUrls: RepositorioUrlsBloqueadas
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun iniciar() {
        Dispatchers.setMain(testDispatcher)
        appContext = ApplicationProvider.getApplicationContext()
        // El constructor de MediadorSincronizacion (super) puede tocar
        // WorkManager.getInstance — inicializar el test instance primero
        // (patron de FakeMediadorSincronizacion.kt / LogoutCoordinatorTest).
        WorkManagerTestInitHelper.initializeTestWorkManager(appContext)

        server = MockWebServer()
        server.start()

        db = Room.inMemoryDatabaseBuilder(
            appContext,
            BaseDatosSeguridad::class.java
        ).allowMainThreadQueries().build()

        val backend = ClienteBackend(
            baseUrl = server.url("/").toString(),
            tokenProvider = { "test-token" }
        )
        repoEscaneos = RepositorioEscaneos(
            db = db,
            backend = backend,
            json = json,
            ioDispatcher = testDispatcher
        )
        repoUrls = RepositorioUrlsBloqueadas(
            db = db,
            backend = backend,
            json = json,
            ioDispatcher = testDispatcher
        )

        mediador = FakeMediadorConFlag(appContext)

        // Pipeline constructor (firma real): (context, db, backend, json,
        // repoEscaneos, repoUrlsBloqueadas, mediadorSync, motorInferencia).
        // motorInferencia es MotorInferenciaFake (no carga TFLite).
        setupTestVocab()
        val pipeline = Pipeline(
            context = appContext,
            db = db,
            backend = backend,
            json = json,
            repoEscaneos = repoEscaneos,
            repoUrlsBloqueadas = repoUrls,
            mediadorSync = mediador,
            motorInferencia = MotorInferenciaFake()
        )

        sesionUsuario = FakeSesionUsuario(appContext)
        sesionUsuario.guardarSesion(
            token = "test-token",
            usuario = "tester",
            correo = "tester@test.com"
        )

        logoutCoordinator = LogoutCoordinator(
            appContext = appContext,
            mediadorSincronizacion = mediador,
            db = db,
            sesionUsuario = sesionUsuario,
            pipeline = pipeline,
            cacheDetalleEscaneos = CacheDetalleEscaneos(),
            credentialsManager = com.auth0.android.authentication.storage.SecureCredentialsManager(
                appContext,
                com.auth0.android.Auth0.getInstance(appContext),
                com.auth0.android.authentication.storage.SharedPreferencesStorage(appContext)
            )
        )
    }

    fun cerrar() {
        db.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    /**
     * Construye un [SyncWorker] real via [TestListenableWorkerBuilder] con
     * una [WorkerFactory] que inyecta el grafo del fixture. `doWork()` se
     * invoca directamente (suspend) desde el test — sin pasar por WorkManager.
     */
    fun construirWorker(): SyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters
            ): ListenableWorker = SyncWorker(
                appContext,
                workerParameters,
                sesionUsuario,
                FakeMonitorRed(appContext),
                db,
                ClienteBackend(
                    baseUrl = server.url("/").toString(),
                    tokenProvider = { "test-token" }
                ),
                repoEscaneos,
                repoUrls,
                logoutCoordinator
            )
        }
        return TestListenableWorkerBuilder<SyncWorker>(appContext)
            .setWorkerFactory(factory)
            .build()
    }

    /** Escribe las prefs de sync que [SyncWorker.doWorkInternal] lee. */
    fun escribirPrefsSync(initialSyncCompleted: Boolean, ultimoSyncMs: Long) {
        appContext
            .getSharedPreferences(SyncWorker.PREFS_SYNC, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(SyncWorker.KEY_INITIAL_SYNC_COMPLETED, initialSyncCompleted)
            .putLong(SyncWorker.KEY_ULTIMO_SYNC, ultimoSyncMs)
            .apply()
    }
}
