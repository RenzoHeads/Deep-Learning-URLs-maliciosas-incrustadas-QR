package com.qrsecurity.detector.datos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Monitor de conectividad de red — offline-first.
 *
 * Expone [estaOnlineAhora] para checks sincronos puntuales. La app decide
 * lanzar sync o mostrar indicador offline consultando este snapshot antes
 * de operaciones de red.
 *
 * API 24+ (minSdk de la app). No usa Deprecated NETWORK_STATE_CHANGED_ACTION.
 *
 * `open` + [estaOnlineAhora] `open` — patron de testabilidad ya usado por
 * [com.qrsecurity.detector.sesion.SesionUsuario] y [MediadorSincronizacion]:
 * los tests del SyncWorker necesitan un fake que reporte red online porque
 * Robolectric no provisiona una red VALIDATED y el preflight del worker
 * abortaria con Result.retry() antes de ejercitar el flujo bajo test.
 */
open class MonitorRed(private val context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Snapshot sincrono del estado de red — util para checks puntuales sin coleccionar el Flow.
     *
     * WAVE 17 fix (S5 MAJOR captive portal): antes solo verificaba
     * `NET_CAPABILITY_INTERNET` — un captive portal (hotel/aeropuerto) reporta
     * "online" aunque el trafico HTTP se redirija al portal. Ahora exigimos
     * tambien `NET_CAPABILITY_VALIDATED`, que el sistema solo otorga tras
     * confirmar que hay conectividad real a internet (no solo duplex link-level).
     * Evita sync storms tras reconnect en captive portal: sin VALIDATED,
     * `estaOnlineAhora()` devuelve false → SyncWorker hace `Result.retry()`
     * (no dispara PUSH a un portal que devolveria 302/HTML-as-JSON).
     */
    open fun estaOnlineAhora(): Boolean {
        val cm = connectivityManager ?: return false
        val redActiva = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(redActiva) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}
