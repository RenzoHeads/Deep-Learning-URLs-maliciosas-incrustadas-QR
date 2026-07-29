package com.qrsecurity.detector

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.ui.NavGuardian
import com.qrsecurity.detector.ui.theme.TemaDetectorSeguridadQR

/**
 * Subclase de [Application] — referenciada en AndroidManifest como
 * ``android:name=".AppSeguridadQR"``.
 *
 * Inicializa el motor de sincronizacion offline-first en [onCreate]:
 *  - Programa el [com.qrsecurity.detector.datos.sync.SyncWorker] periodico
 *    (cada 15 min, solo con red) como safety-net.
 *  - El sync inmediato tras cada write local lo disparan las pantallas via
 *    [com.qrsecurity.detector.datos.sync.MediadorSincronizacion.dispararSyncUnica].
 *
 * WorkManager se inicializa via su propio provider (androidx.startup) declarado
 * en el manifest; aqui solo pedimos la instancia y encolamos el trabajo.
 *
 * Nota: NO se instancia Room aqui — [com.qrsecurity.detector.datos.local.BaseDatosSeguridad]
 * usa carga lazy via `Instancia.usuario` desde los repositorios, evitando
 * bloquear el arranque de la app con I/O de disco.
 */
class AppSeguridadQR : Application() {

    override fun onCreate() {
        super.onCreate()
        // Safety-net: sync periodico cada 15 min cuando hay red.
        // POLITICA KEEP: si ya estaba programado (arranque previo), no reemplaza.
        // Esto cubre el caso "app cerrada, vuelve la red, hay ops pendientes":
        // WorkManager reativara el worker cuando se cumplan las constraints.
        MediadorSincronizacion(this).programarSyncPeriodica()
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
