package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla de Detalle de URL — patron NowInAndroid.
 *
 * La pantalla de detalle de URL muestra la entidad [EscaneoEntity] con
 * botones de bloquear/denunciar.
 */
sealed interface DetalleUrlUiState {
    data object Cargando : DetalleUrlUiState
    data class Cargado(
        val escaneo: EscaneoEntity,
        val urlBloqueada: Boolean,
        /**
         * true si este escaneo es la version mas reciente de su
         * `urlLimpia`. La UI solo muestra los botones de accion (Abrir,
         * Copiar, Compartir, Bloquear, Denunciar) en la ultima version;
         * las versiones anteriores (reescaneos) solo muestran detalles.
         */
        val esUltimaVersion: Boolean,
        /**
         * Total de reescaneos (versiones anteriores de la misma URL,
         * excluyendo el escaneo actual). Usado por la UI para mostrar
         * el boton "Ver reescaneos (N)" si N > 0.
         */
        val totalReescaneos: Int
    ) : DetalleUrlUiState
    data object NoEncontrado : DetalleUrlUiState
}

/**
 * Acciones que la UI puede despachar al ViewModel (Unidirectional Data Flow).
 *
 * Las dos acciones cubren el ciclo de bloqueo/desbloqueo de una URL desde
 * la pantalla de detalle.
 */
sealed interface DetalleUrlAction {
    data class BloquearUrl(val url: String, val razon: String) : DetalleUrlAction
    /**
     * Desbloquea (elimina) una URL de la lista de bloqueadas.
     * Toma la URL (no el id del row) para que la UI no necesite conocer
     * el UUID interno — el VM lo resuelve via [RepositorioUrlsBloqueadas.obtenerPorUrl].
     */
    data class DesbloquearUrl(val url: String) : DetalleUrlAction
}

/**
 * ViewModel para la pantalla de Detalle de URL.
 *
 * Muestra la entidad [EscaneoEntity] con botones de bloquear/denunciar.
 *
 * Inyecta [RepositorioEscaneos], [RepositorioUrlsBloqueadas] y
 * [MediadorSincronizacion] via Hilt. Usa [CacheDetalleEscaneos] para
 * evitar flash de "Cargando..." al re-entrar a un detalle ya visitado.
 *
 * Adaptacion vs plan: el plan usaba `estaBloqueada(url)` y
 * `contarReescaneos(url, id)` — los metodos reales son
 * `obtenerPorUrl(url) != null` y `contarReescaneosSnapshot(url, id)`.
 * Tambien, `cache.guardar(estado: Cargado)` toma el estado completo,
 * no `(id, escaneo)`.
 */
@HiltViewModel
class DetalleUrlViewModel @Inject constructor(
    private val repositorioEscaneos: RepositorioEscaneos,
    private val repositorioUrlsBloqueadas: RepositorioUrlsBloqueadas,
    private val mediadorSincronizacion: MediadorSincronizacion,
    private val cacheDetalle: CacheDetalleEscaneos
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetalleUrlUiState>(DetalleUrlUiState.Cargando)
    val uiState: StateFlow<DetalleUrlUiState> = _uiState.asStateFlow()

    // Eventos one-shot via Channel — cada evento se entrega una sola vez.
    private val _mensaje = Channel<MensajeUi>(Channel.BUFFERED)
    val mensaje = _mensaje.receiveAsFlow()

    /**
     * Carga el escaneo por id. Pre-llena _uiState desde el cache (si hay)
     * para evitar flash de "Cargando...", luego refresca desde Room.
     */
    fun cargarEscaneo(id: String) {
        // Pre-llenar desde cache → primer frame muestra el detalle sin flash.
        cacheDetalle.obtener(id)?.let { _uiState.value = it }

        viewModelScope.launch {
            val escaneo = repositorioEscaneos.obtenerPorId(id)
            if (escaneo == null) {
                _uiState.value = DetalleUrlUiState.NoEncontrado
                return@launch
            }
            // Adaptacion: no existe estaBloqueada(url); usar obtenerPorUrl.
            val urlBloqueada = repositorioUrlsBloqueadas.obtenerPorUrl(escaneo.urlLimpia) != null
            val esUltima = repositorioEscaneos.esUltimaVersion(id)
            // Adaptacion: contarReescaneosSnapshot (no contarReescaneos).
            val totalReesc = repositorioEscaneos.contarReescaneosSnapshot(escaneo.urlLimpia, id)

            val cargado = DetalleUrlUiState.Cargado(
                escaneo = escaneo,
                urlBloqueada = urlBloqueada,
                esUltimaVersion = esUltima,
                totalReescaneos = totalReesc
            )
            // Adaptacion: cache.guardar(Cargado), no cache.guardar(id, escaneo).
            cacheDetalle.guardar(cargado)
            _uiState.value = cargado
        }
    }

    /**
     * Despacha una accion desde la UI (UDF).
     */
    fun onAction(action: DetalleUrlAction) {
        when (action) {
            is DetalleUrlAction.BloquearUrl -> bloquearUrl(action.url, action.razon)
            is DetalleUrlAction.DesbloquearUrl -> desbloquearUrl(action.url)
        }
    }

    private fun bloquearUrl(url: String, razon: String) {
        viewModelScope.launch {
            try {
                repositorioUrlsBloqueadas.bloquearLocal(url = url, razon = razon)
                mediadorSincronizacion.dispararSyncUnica()
                // Propagar al cache para que otros detalles de la misma URL
                // reflejen el bloqueo sin esperar al refresh de Room.
                cacheDetalle.actualizarBloqueoPorUrl(url, urlBloqueada = true)
                _uiState.update { estado ->
                    if (estado is DetalleUrlUiState.Cargado && estado.escaneo.urlLimpia == url) {
                        estado.copy(urlBloqueada = true)
                    } else {
                        estado
                    }
                }
                _mensaje.send(MensajeUi(TipoMensaje.EXITO, "URL bloqueada"))
            } catch (e: Exception) {
                _mensaje.send(MensajeUi(TipoMensaje.ERROR, "Error al bloquear URL"))
            }
        }
    }

    private fun desbloquearUrl(url: String) {
        viewModelScope.launch {
            try {
                // Resolver el id del row de URL bloqueada via la URL.
                val entidad = repositorioUrlsBloqueadas.obtenerPorUrl(url)
                if (entidad != null) {
                    repositorioUrlsBloqueadas.desbloquearLocal(entidad.id)
                    mediadorSincronizacion.dispararSyncUnica()
                    cacheDetalle.actualizarBloqueoPorUrl(url, urlBloqueada = false)
                    _uiState.update { estado ->
                        if (estado is DetalleUrlUiState.Cargado && estado.escaneo.urlLimpia == url) {
                            estado.copy(urlBloqueada = false)
                        } else {
                            estado
                        }
                    }
                    _mensaje.send(MensajeUi(TipoMensaje.EXITO, "URL desbloqueada"))
                } else {
                    _mensaje.send(MensajeUi(TipoMensaje.INFO, "La URL no estaba bloqueada"))
                }
            } catch (e: Exception) {
                _mensaje.send(MensajeUi(TipoMensaje.ERROR, "Error al desbloquear URL"))
            }
        }
    }
}
