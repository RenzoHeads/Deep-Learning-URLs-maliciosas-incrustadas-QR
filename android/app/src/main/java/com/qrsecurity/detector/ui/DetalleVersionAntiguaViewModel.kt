package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.eliminarLocal
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para [PantallaDetalleVersionAntigua] - pantalla dedicada a una
 * version historica especifica de una URL (NO la ultima version).
 *
 * A diferencia de [DetalleUrlUiState], esta pantalla NO expone flags de
 * `urlBloqueada`, `esUltimaVersion` ni `totalReescaneos` porque NO renderiza
 * botones dependientes de esos flags (ver documentacion de
 * [DetalleVersionAntiguaViewModel]).
 */
sealed interface DetalleVersionAntiguaUiState {
    data object Cargando : DetalleVersionAntiguaUiState
    data class Cargado(val escaneo: EscaneoEntity) : DetalleVersionAntiguaUiState
    data object NoEncontrado : DetalleVersionAntiguaUiState
}

/**
 * ViewModel para [PantallaDetalleVersionAntigua] - pantalla dedicada para
 * visualizar una version historica especifica de una URL.
 *
 * Diferencia vs [DetalleUrlViewModel]:
 *  - NO expone `BloquearUrl` / `DesbloquearUrl`: el bloqueo aplica a la URL
 *    como entidad, no a una version individual. Esa accion vive en
 *    [PantallaDetalleUrl] (ultima version), donde tiene sentido semantico.
 *  - NO expone `EliminarUrl(urlLimpia)` (cascada por URL): borraria todas
 *    las versiones de la URL, incluyendo las que el usuario NO pidio
 *    eliminar. En el contexto de "version historica", el usuario quiere
 *    borrar SOLO esa version.
 *  - Expone `eliminarVersion(id)`: llama a
 *    [RepositorioEscaneos.eliminarLocal] (borrado individual por id). El
 *    repositorio maneja:
 *      * Branch dirty (CREATE pending): cancela el CREATE op + borra el
 *        row local. No encola DELETE (fila nunca llego al backend).
 *      * Branch synced: encola DELETE op + borra el row local.
 *      * Sincroniza `urls_catalogo` atomically: si era la ultima version
 *        de la URL, borra el row del cache maestro; si quedan reescaneos
 *        vivos, actualiza el row con la nueva "ultima version" (prob,
 *        nivel, fecha).
 *      * Todo dentro de una transaccion Room (atomico).
 *
 * Loop-prevention: esta pantalla NO muestra el boton "Ver analisis
 * anteriores" (que es lo que iniciaba el loop DetalleUrl →
 * AnalisisAnteriores → DetalleUrl → ...). Por eso, el usuario llega aqui
 * DESDE AnalisisAnteriores (callback `onVerDetalle(id)`) y solo puede
 * volver atras (onBack) o eliminar esta version.
 */
@HiltViewModel
class DetalleVersionAntiguaViewModel @Inject constructor(
    private val repositorioEscaneos: RepositorioEscaneos,
    private val mediadorSincronizacion: MediadorSincronizacion,
    private val cacheDetalle: CacheDetalleEscaneos
) : ViewModel() {

    private val _uiState =
        MutableStateFlow<DetalleVersionAntiguaUiState>(DetalleVersionAntiguaUiState.Cargando)
    val uiState: StateFlow<DetalleVersionAntiguaUiState> = _uiState.asStateFlow()

    // Job de la coleccion reactiva - se cancela al cargar un nuevo id
    // (navegacion a otro detalle) para evitar coleccionadores duplicados.
    private var cargarJob: Job? = null

    // Eventos one-shot via Channel - cada evento se entrega una sola vez.
    private val _mensaje = Channel<MensajeUi>(Channel.BUFFERED)
    val mensaje = _mensaje.receiveAsFlow()

    // Evento one-shot: se emite (true) cuando la version fue eliminada del
    // historial. La UI lo recolecta para navegar atras (onBack) tras un
    // eliminado exitoso.
    private val _eliminarCompletado = Channel<Boolean>(Channel.BUFFERED)
    val eliminarCompletado = _eliminarCompletado.receiveAsFlow()

    /**
     * Carga el escaneo por id. Pre-llena [_uiState] desde el cache visual
     * de [CacheDetalleEscaneos] (si hay entrada cacheada para este id
     * desde una visita previa al detalle de URL - misma entidad,
     * diferente vista) para evitar flash de "Cargando...", luego observa
     * Room reactivamente via [RepositorioEscaneos.observarPorId].
     *
     * Si la fila se borra en vuelo (escalado: el usuario elimina esta
     * version desde otra pantalla), el Flow emite `null` → emitimos
     * [DetalleVersionAntiguaUiState.NoEncontrado].
     *
     * @param id UUID del escaneo a cargar.
     */
    fun cargarEscaneo(id: String) {
        cargarJob?.cancel()

        // Pre-fill desde cache: si la visita previa a DetalleUrl cachéo
        // esta misma entidad, la mostramos al instante sin esperar al
        // Flow de Room (<1ms).
        val preFill = cacheDetalle.obtener(id)
        if (preFill != null) {
            _uiState.value = DetalleVersionAntiguaUiState.Cargado(preFill.escaneo)
        }

        cargarJob = viewModelScope.launch {
            repositorioEscaneos.observarPorId(id).collect { escaneo ->
                if (escaneo == null) {
                    _uiState.value = DetalleVersionAntiguaUiState.NoEncontrado
                } else {
                    _uiState.value = DetalleVersionAntiguaUiState.Cargado(escaneo)
                }
            }
        }
    }

    /**
     * Elimina UN solo escaneo por id (NO cascada por URL). Reusa
     * [RepositorioEscaneos.eliminarLocal] que ya maneja:
     *  - Branch dirty vs synced (CREATE-cancel vs DELETE-encolar)
     *  - Sincronizacion de `urls_catalogo` (eliminar hash si era ultima
     *    version, o actualizar el cache con la nueva ultima version si
     *    quedan reescaneos vivos) - ver [RepositorioEscaneos.eliminarLocal]
     *  - Atomicidad en una transaccion Room
     *
     * Tras un eliminado exitoso:
     *  1. Invalida el cache de detalle para el id (sin esto, reabrir
     *     el mismo detalle tras eliminar mostraria el cache stale
     *     (Cargado) en lugar de NoEncontrado).
     *  2. Dispara sync unica al backend via [MediadorSincronizacion].
     *  3. Emite [_eliminarCompletado] `true` → la UI navega atras
     *    (onBack) a AnalisisAnteriores, que re-consulta Room y refleja
     *     la version eliminada (desaparece de la lista).
     *
     * @param id UUID del escaneo a eliminar.
     */
    fun eliminarVersion(id: String) {
        viewModelScope.launch {
            try {
                repositorioEscaneos.eliminarLocal(id)
                mediadorSincronizacion.dispararSyncUnica()
                cacheDetalle.invalidar(id)
                _mensaje.send(MensajeUi(TipoMensaje.EXITO, "Versión eliminada del historial"))
                _eliminarCompletado.send(true)
            } catch (e: Exception) {
                _mensaje.send(MensajeUi(TipoMensaje.ERROR, "Error al eliminar la versión"))
            }
        }
    }
}
