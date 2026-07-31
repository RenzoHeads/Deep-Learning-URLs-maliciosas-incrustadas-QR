package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla de Detalle de Escaneo — patrón NowInAndroid.
 *
 * Sellado para que la Screen pueda hacer `when` exhaustivo sin `else`.
 */
sealed interface DetalleEscaneoUiState {
    data object Cargando : DetalleEscaneoUiState
    data class Cargado(
        val escaneo: EscaneoEntity,
        val urlBloqueada: Boolean
    ) : DetalleEscaneoUiState
    data object NoEncontrado : DetalleEscaneoUiState
}

/**
 * Acción que la UI puede despachar al ViewModel (Unidirectional Data Flow).
 */
sealed interface DetalleEscaneoAction {
    data class BloquearUrl(val url: String, val razon: String) : DetalleEscaneoAction
}

/**
 * ViewModel para la pantalla de Detalle de Escaneo.
 *
 * Inyecta los repositorios y el mediador de sync via Hilt — elimina la
 * construccion manual de `BaseDatosSeguridad.get(context)`,
 * `ClienteBackend(...)`, `RepositorioUrlsBloqueadas(...)`,
 * `MediadorSincronizacion(context)` que aparecia en DetalleEscaneoContainer.
 *
 * Expone un [DetalleEscaneoUiState] reactivo y un metodo [onAction] para
 * despachar acciones de la UI (bloquear URL).
 */
@HiltViewModel
class DetalleEscaneoViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val repoUrls: RepositorioUrlsBloqueadas,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetalleEscaneoUiState>(DetalleEscaneoUiState.Cargando)
    val uiState: StateFlow<DetalleEscaneoUiState> = _uiState.asStateFlow()

    // Bug D4 fix: migrado de StateFlow<MensajeUi?> a Channel<MensajeUi>
    // (BUFFERED). StateFlow hace conflation: si dos tap rapidos en
    // "Bloquear URL" emiten EXITO dos veces antes de que la UI recomponga,
    // el segundo sobrescribe el primero y collect solo emite el ultimo →
    // el primer snackbar se pierde. Channel(BUFFERED) preserva ambos
    // eventos y los entrega uno por uno. Adios consumirMensaje().
    private val _mensaje = Channel<MensajeUi>(Channel.BUFFERED)
    val mensaje = _mensaje.receiveAsFlow()

    fun cargarEscaneo(id: String) {
        viewModelScope.launch {
            val escaneo = repoEscaneos.obtenerPorId(id)
            if (escaneo == null) {
                _uiState.value = DetalleEscaneoUiState.NoEncontrado
                return@launch
            }
            val urlBloqueada = repoUrls.obtenerPorUrl(escaneo.urlLimpia) != null
            _uiState.value = DetalleEscaneoUiState.Cargado(
                escaneo = escaneo,
                urlBloqueada = urlBloqueada
            )
        }
    }

    /**
     * Despacha una acción desde la UI (UDF).
     * Devuelve `true` si la acción fue procesada (para callbacks de UI).
     */
    fun onAction(action: DetalleEscaneoAction): Boolean = when (action) {
        is DetalleEscaneoAction.BloquearUrl -> {
            bloquearUrl(action.url, action.razon)
            true
        }
    }

    private fun bloquearUrl(url: String, razon: String) {
        viewModelScope.launch {
            try {
                repoUrls.bloquearLocal(url = url, razon = razon)
                mediadorSync.dispararSyncUnica()
                _uiState.update { estado ->
                    if (estado is DetalleEscaneoUiState.Cargado && estado.escaneo.urlLimpia == url) {
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
}

/**
 * Wrapper para mensajes de UI (snackbar). Tipo + texto.
 */
data class MensajeUi(
    val tipo: TipoMensaje,
    val texto: String
)
