package com.qrsecurity.detector.ui

import androidx.lifecycle.SavedStateHandle
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
 * F2.4: typealias a [DetalleUrlUiState] — la fusion de
 * [DetalleEscaneoViewModel] + [ResultadoMaliciosoViewModel] en
 * [DetalleUrlViewModel] unifica el UiState. Este alias mantiene la
 * compatibilidad con [CacheDetalleEscaneos] y el codigo heredado sin
 * tocarlos; F3 eliminara este archivo.
 */
typealias DetalleEscaneoUiState = DetalleUrlUiState

/**
 * Acción que la UI puede despachar al ViewModel (Unidirectional Data Flow).
 *
 * F2.4: typealias a [DetalleUrlAction] — incluye [DetalleUrlAction.DesbloquearUrl]
 * que antes no existia. El `when` en [DetalleEscaneoViewModel.onAction]
 * se actualizo para cubrir el nuevo caso.
 */
typealias DetalleEscaneoAction = DetalleUrlAction

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
 *
 * Bug flash-Detalle fix: el VM se crea por cada NavBackStackEntry. Sin
 * cache, re-entrar a un detalle ya visitado arrancaba en `Cargando` y
 * mostraba un flash de "Cargando..." hasta que Room respondia. Con el
 * [CacheDetalleEscaneos] Singleton, el VM pre-llena `_uiState` desde el
 * cache (via [SavedStateHandle]) y refresca desde Room en background — el
 * primer frame muestra el detalle cacheado sin flash.
 *
 * Bug 2 fix: la lista de reescaneos ya NO se carga aqui — se mudo a su
 * propia pagina ([PantallaReescaneos]) con [ReescaneosViewModel]. Este
 * ViewModel solo carga el [totalReescaneos] (conteo puntual) para que la
 * pantalla de detalle decida si mostrar el boton "Ver reescaneos (N)".
 */
@HiltViewModel
class DetalleEscaneoViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val repoUrls: RepositorioUrlsBloqueadas,
    private val mediadorSync: MediadorSincronizacion,
    // Bug flash-Detalle fix: cache Singleton de estados Cargado por id.
    // Permite que el VM inicialice _uiState con el estado cacheado al
    // re-entrar a un detalle ya visitado → sin flash de "Cargando...".
    private val cacheDetalle: CacheDetalleEscaneos,
    // SavedStateHandle: Hilt lo inyecta con los nav args de la ruta
    // `detalle_escaneo/{id}`. Lo usamos para leer el id SIN necesidad de
    // esperar a cargarEscaneo() — asi podemos pre-llenar _uiState en el
    // constructor y evitar el flash de "Cargando..." en el primer frame.
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Bug flash-Detalle fix: pre-llenar _uiState desde el cache.
    //
    // Si el usuario ya visito este detalle antes (hay un Cargado cacheado
    // para este id), inicializamos _uiState directamente a Cargado — el
    // primer frame de la UI muestra el detalle renderizado, SIN pasar por
    // "Cargando...". El refresco de Room (~ms) corre en background en
    // [cargarEscaneo] y silenciosamente valida/actualiza el estado cacheado.
    //
    // Si NO esta en cache (primera visita a este id), _uiState arranca en
    // [DetalleEscaneoUiState.Cargando] y [cargarEscaneo] lo rellena.
    private val idInicial: String = savedStateHandle.get<String>("id") ?: ""

    private val _uiState = MutableStateFlow<DetalleEscaneoUiState>(
        cacheDetalle.obtener(idInicial) ?: DetalleEscaneoUiState.Cargando
    )
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
        // Bug flash-Detalle fix: si hay cache, mostrarlo inmediatamente.
        // Cubre el caso donde el id era "" en savedStateHandle (path no
        // tenia arg al primer constructor) o donde _uiState inicio en
        // Cargando. Esto evita cualquier flash de "Cargando..." entre el
        // primer frame y esta linea.
        cacheDetalle.obtener(id)?.let { _uiState.value = it }

        // Refresco en background desde Room (~ms). Valida el cache y
        // actualiza _uiState con la version mas reciente (e.g., si el
        // escaneo cambio en Room desde la ultima visita, o si la cuenta
        // de reescaneos cambio).
        viewModelScope.launch {
            val escaneo = repoEscaneos.obtenerPorId(id)
            if (escaneo == null) {
                _uiState.value = DetalleEscaneoUiState.NoEncontrado
                return@launch
            }
            val urlBloqueada = repoUrls.obtenerPorUrl(escaneo.urlLimpia) != null
            // Bug 2 fix: determinar si este escaneo es la ultima version.
            val esUltima = repoEscaneos.esUltimaVersion(id)

            // Bug 2 fix: cargar solo el TOTAL de reescaneos (no la lista).
            // La lista vive en su propia pagina (PantallaReescaneos) con
            // sync pull incremental + paginacion local.
            val totalReesc = repoEscaneos.contarReescaneosSnapshot(escaneo.urlLimpia, id)

            val cargado = DetalleEscaneoUiState.Cargado(
                escaneo = escaneo,
                urlBloqueada = urlBloqueada,
                esUltimaVersion = esUltima,
                totalReescaneos = totalReesc
            )
            // Guardar en cache Singleton → la proxima visita a este id
            // mostrara este estado instantaneamente sin flash de Cargando.
            cacheDetalle.guardar(cargado)
            _uiState.value = cargado
        }
    }

    /**
     * Despacha una acción desde la UI (UDF).
     * Devuelve `true` si la acción fue procesada (para callbacks de UI).
     *
     * F2.4: [DetalleEscaneoAction] es ahora typealias a [DetalleUrlAction]
     * que incluye [DetalleUrlAction.DesbloquearUrl]. Este VM heredado no
     * implementa desbloqueo (devuelve false); [DetalleUrlViewModel] si.
     */
    fun onAction(action: DetalleEscaneoAction): Boolean = when (action) {
        is DetalleEscaneoAction.BloquearUrl -> {
            bloquearUrl(action.url, action.razon)
            true
        }
        is DetalleEscaneoAction.DesbloquearUrl -> false
    }

    private fun bloquearUrl(url: String, razon: String) {
        viewModelScope.launch {
            try {
                repoUrls.bloquearLocal(url = url, razon = razon)
                mediadorSync.dispararSyncUnica()
                _uiState.update { estado ->
                    if (estado is DetalleEscaneoUiState.Cargado && estado.escaneo.urlLimpia == url) {
                        // Bug flash-Detalle fix: propagar el bloqueo al
                        // cache Singleton para que OTROS detalles
                        // cacheados de reescaneos de la misma URL tambien
                        // reflejen el cambio sin esperar al refresh de
                        // Room (que solo ocurrira cuando el usuario los
                        // re-abra).
                        cacheDetalle.actualizarBloqueoPorUrl(url, urlBloqueada = true)
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
