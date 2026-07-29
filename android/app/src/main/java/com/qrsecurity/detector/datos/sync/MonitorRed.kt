package com.qrsecurity.detector.datos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Monitor reactivo de conectividad de red — offline-first.
 *
 * Expone un [Flow]<Boolean> que emite `true` cuando hay conexion a internet
 * valida (WiFi, Cellular, Ethernet) y `false` cuando no.
 *
 * Usa [ConnectivityManager.registerNetworkCallback] envuelto en [callbackFlow]
 * para que el collector se desregistre automaticamente al cancelar el Flow.
 *
 * El [SyncWorker] y los ViewModel observan este Flow para:
 *  - Disparar sync cuando la red pasa false -> true.
 *  - Mostrar indicador "offline" en la UI cuando es false.
 *
 * API 24+ (minSdk de la app). No usa Deprecated NETWORK_STATE_CHANGED_ACTION.
 */
class MonitorRed(private val context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Flow que emite el estado de conectividad actual y cualquier cambio futuro.
     *
     * Emisiones:
     *  - `true`: hay al menos un transporte activo con internet (WiFi/Cellular/Ethernet).
     *  - `false`: sin red o red sin capabilities de internet.
     *
     * [distinctUntilChanged] evita emisiones redundantes (mismo estado repetido).
     */
    fun observarConectividad(): Flow<Boolean> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            // Sin ConnectivityManager (raro en device real) — emitir false y terminar.
            trySend(false)
            awaitClose()
            return@callbackFlow
        }

        fun estaOnline(): Boolean {
            val redActiva = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(redActiva) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // Al perder una red, re-verificar si otra sigue activa.
                trySend(estaOnline())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val tieneInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                trySend(tieneInternet && estaOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // Emite el estado actual inmediatamente, luego registra callback para cambios.
        trySend(estaOnline())
        cm.registerNetworkCallback(request, callback)

        awaitClose {
            cm.unregisterNetworkCallback(callback)
        }
    }.distinctUntilChanged()

    /**
     * Snapshot sincrono del estado de red — util para checks puntuales sin coleccionar el Flow.
     */
    fun estaOnlineAhora(): Boolean {
        val cm = connectivityManager ?: return false
        val redActiva = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(redActiva) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }
}
