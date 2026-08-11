package com.qrsecurity.detector

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.sesion.SesionUsuario
import com.qrsecurity.detector.ui.NavGuardian
import com.qrsecurity.detector.ui.theme.TemaDetectorSeguridadQR
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Subclase de [Application] — referenciada en AndroidManifest como
 * ``android:name=".AppSeguridadQR"``.
 *
 * Hilt: anotada con [@HiltAndroidApp] para generar el componente de
 * aplicacion. Implementa [Configuration.Provider] para que WorkManager
 * use [HiltWorkerFactory] y pueda inyectar dependencias en el
 * [com.qrsecurity.detector.datos.sync.SyncWorker] (@HiltWorker).
 *
 * Inicializa el motor de sincronizacion offline-first en [onCreate]:
 *  - Programa el [com.qrsecurity.detector.datos.sync.SyncWorker] periodico
 *    (cada 15 min, solo con red) como safety-net.
 */
@HiltAndroidApp
class AppSeguridadQR : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var mediadorSincronizacion: MediadorSincronizacion

    @Inject
    lateinit var sesionUsuario: SesionUsuario

    // L-2 fix: scope de aplicacion para la precarga de EncryptedSharedPreferences.
    // Supervisado para que un fallo aislado no cancele trabajos hermanos. Vive
    // lo de todo el proceso (AppSeguridadQR es singleton), igual que prefsCache.
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Log.d("AppSeguridadQR", "onCreate() — iniciando. workerFactory injected=${::workerFactory.isInitialized}")

        // L-2 fix: precargar EncryptedSharedPreferences en background ANTES de
        // que MainActivity compose NavGuardian. La cadena
        // NavGuardian -> remember{ estaLogueado() } -> prefs() hace la primera
        // carga de EncryptedSharedPreferences (MasterKey.Builder del Keystore +
        // read + desencriptar el archivo completo) de forma sincrona; al
        // ejecutarla aqui en Dispatchers.IO, el cache process-global
        // (SesionUsuario.prefsCache @Volatile) suele estar caliente cuando
        // NavGuardian compone, evitando el bloqueo del main thread.
        // Thread-safe: precargar() -> prefs() usa double-checked locking.
        appScope.launch { sesionUsuario.precargar() }

        // Safety-net: sync periodico cada 15 min cuando hay red.
        try {
            mediadorSincronizacion.programarSyncPeriodica()
            Log.d("AppSeguridadQR", "programarSyncPeriodica() OK")
        } catch (e: Exception) {
            Log.e("AppSeguridadQR", "programarSyncPeriodica() fallo", e)
        }

        // Procesar pending_ops que quedaron de sesiones anteriores.
        // dispararSyncUnica() solo se llama tras un write local nuevo,
        // pero los pending_ops antiguos (escaneos hechos offline) nunca
        // se procesarian si el usuario no hace un nuevo write.
        try {
            mediadorSincronizacion.dispararSyncUnica()
            Log.d("AppSeguridadQR", "dispararSyncUnica() OK")
        } catch (e: Exception) {
            Log.e("AppSeguridadQR", "dispararSyncUnica() fallo", e)
        }
    }
}

/**
 * Activity principal, punto de entrada.
 *
 * Hospeda el NavHost con las 8 pantallas de QR Guardian:
 * Onboarding, Escanear, Resultado Seguro/Malicioso, Historial, URLs Bloqueadas,
 * Denunciar URL y Acerca de.
 *
 * La navegacion usa Navigation Compose (NavHost). Toda la logica de inferencia
 * se delega al [com.qrsecurity.detector.pipeline.Pipeline] y se observa su flow
 * desde [NavGuardian].
 */
@dagger.hilt.android.AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TemaDetectorSeguridadQR {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGuardian()
                }
            }
        }
    }
}
