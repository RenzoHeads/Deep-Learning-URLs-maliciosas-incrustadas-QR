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
 * UiState para la pantalla de Detalle de Escaneo — patrón NowInAndroid.
 *
 * Sellado para que la Screen pueda hacer `when` exhaustivo sin `else`.
 */
sealed interface DetalleEscaneoUiState {
    data object Cargando : DetalleEscaneoUiState
    data class Cargado(
        val escaneo: EscaneoEntity,
        val urlBloqueada: Boolean,
        /**
         * Bug 2 fix: true si este escaneo es la version mas reciente de su
         * `urlLimpia`. La UI solo muestra los botones de accion (Abrir,
         * Copiar, Compartir, Bloquear, Denunciar) en la ultima version;
         * las versiones anteriores (reescaneos) solo muestran detalles.
         */
        val esUltimaVersion: Boolean,
        /**
         * Bug 2 fix: reescaneos (versiones anteriores de la misma URL),
         * paginados de [TAMANO_PAGINA_REESCANEOS] en [TAMANO_PAGINA_REESCANEOS].
         * Vacio si no hay reescaneos (escaneo unico) o si son la version
         * mas reciente (no se muestran a si mismos).
         */
        val reescaneos: List<EscaneoEntity>,
        /**
         * Total de reescaneos (excluyendo el escaneo actual). Usado por la
         * UI para mostrar "Ver mas" si [reescaneos].size < [totalReescaneos].
         */
        val totalReescaneos: Int
    ) : DetalleEscaneoUiState
    data object NoEncontrado : DetalleEscaneoUiState
}

/**
 * Acción que la UI puede despachar al ViewModel (Unidirectional Data Flow).
 */
sealed interface DetalleEscaneoAction {
    data class BloquearUrl(val url: String, val razon: String) : DetalleEscaneoAction
    /**
     * Bug 2 fix: cargar mas reescaneos (incrementar pagina).
     * Llamado por el boton "Ver mas" en la seccion de reescaneos.
     */
    data object CargarMasReescaneos : DetalleEscaneoAction
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
 * despachar acciones de la UI (bloquear URL, cargar mas reescaneos).
 *
 * Bug 2 fix: ademas del escaneo principal, expone los reescaneos
 * (versiones anteriores de la misma URL) paginados de 5 en 5, y un flag
 * [DetalleEscaneoUiState.Cargado.esUltimaVersion] que indica si este
 * escaneo es la version mas reciente (las acciones solo se muestran en
 * la ultima version).
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

    /**
     * Bug 2 fix: tamaño de pagina para reescaneos. El usuario pidio mostrar
     * los 5 primeros reescaneos y cargar incrementalmente los demas.
     */
    private val tamanoPaginaReescaneos = 5

    // ── Estado de paginacion de reescaneos ──
    //
    // Se mantienen en el ViewModel (no en el UiState) porque son detalle
    // de implementacion: la UI solo ve [reescaneos] (la lista acumulada)
    // y [totalReescaneos] (el total para saber si hay mas). El offset
    // se calcula como `reescaneos.size` —tras cargar mas, sumar
    // [tamanoPaginaReescaneos] al offset actual (que ya esta en
    // `reescaneos.size`).
    private var reescaneosCargados: List<EscaneoEntity> = emptyList()
    private var urlLimpiaActual: String = ""
    private var idActual: String = ""

    fun cargarEscaneo(id: String) {
        viewModelScope.launch {
            val escaneo = repoEscaneos.obtenerPorId(id)
            if (escaneo == null) {
                _uiState.value = DetalleEscaneoUiState.NoEncontrado
                return@launch
            }
            val urlBloqueada = repoUrls.obtenerPorUrl(escaneo.urlLimpia) != null
            // Bug 2 fix: determinar si este escaneo es la ultima version.
            val esUltima = repoEscaneos.esUltimaVersion(id)

            urlLimpiaActual = escaneo.urlLimpia
            idActual = id
            reescaneosCargados = emptyList()

            // Bug 2 fix: cargar la primera pagina de reescaneos (5).
            val primeraPagina = repoEscaneos
                .observarReescaneosSnapshot(escaneo.urlLimpia, id, tamanoPaginaReescaneos, 0)
            reescaneosCargados = primeraPagina

            val totalReesc = repoEscaneos.contarReescaneosSnapshot(escaneo.urlLimpia, id)

            _uiState.value = DetalleEscaneoUiState.Cargado(
                escaneo = escaneo,
                urlBloqueada = urlBloqueada,
                esUltimaVersion = esUltima,
                reescaneos = reescaneosCargados,
                totalReescaneos = totalReesc
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
        is DetalleEscaneoAction.CargarMasReescaneos -> {
            cargarMasReescaneos()
            true
        }
    }

    /**
     * Bug 2 fix: carga la siguiente pagina de reescaneos y los anade a la
     * lista acumulada. El offset es `reescaneosCargados.size` (cuantos ya
     * tenemos).Tras cargar, actualiza [DetalleEscaneoUiState.Cargado] con
     * la nueva lista combinada.
     */
    private fun cargarMasReescaneos() {
        val estadoActual = _uiState.value as? DetalleEscaneoUiState.Cargado ?: return
        // Si ya cargamos todos, no-op.
        if (reescaneosCargados.size >= estadoActual.totalReescaneos) return

        viewModelScope.launch {
            val offset = reescaneosCargados.size
            val nuevaPagina = repoEscaneos
                .observarReescaneosSnapshot(urlLimpiaActual, idActual, tamanoPaginaReescaneos, offset)
            reescaneosCargados = reescaneosCargados + nuevaPagina
            _uiState.update { estado ->
                if (estado is DetalleEscaneoUiState.Cargado) {
                    estado.copy(reescaneos = reescaneosCargados)
                } else {
                    estado
                }
            }
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
