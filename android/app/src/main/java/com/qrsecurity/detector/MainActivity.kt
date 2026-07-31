package com.qrsecurity.detector

import android.app.Application
import android.os.Bundle
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
import com.qrsecurity.detector.ui.NavGuardian
import com.qrsecurity.detector.ui.theme.TemaDetectorSeguridadQR
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
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

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Safety-net: sync periodico cada 15 min cuando hay red.
        mediadorSincronizacion.programarSyncPeriodica()

        // Procesar pending_ops que quedaron de sesiones anteriores.
        // dispararSyncUnica() solo se llama tras un write local nuevo,
        // pero los pending_ops antiguos (escaneos hechos offline) nunca
        // se procesarian si el usuario no hace un nuevo write.
        mediadorSincronizacion.dispararSyncUnica()
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
