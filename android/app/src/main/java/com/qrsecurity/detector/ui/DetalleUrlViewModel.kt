package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.bloquearLocal
import com.qrsecurity.detector.datos.repositorios.desbloquearLocal
import com.qrsecurity.detector.datos.repositorios.eliminarLocalPorUrlLimpia
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    /**
     * Elimina TODOS los escaneos (ultima version + reescaneos) de una URL
     * del historial local + encola DELETEs al backend via SyncWorker.
     * Toma la `urlLimpia` (no el id) porque el borrado es en cascada por URL
     * (ver [RepositorioEscaneos.eliminarLocalPorUrlLimpia]).
     */
    data class EliminarUrl(val urlLimpia: String) : DetalleUrlAction
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

    // V-6 fix: job de la coleccion reactiva de cargarEscaneo. Se cancela
    // al cargar un nuevo id (navegacion a otro detalle) para evitar
    // coleccionadores duplicados.
    private var cargarJob: Job? = null

    // Eventos one-shot via Channel — cada evento se entrega una sola vez.
    private val _mensaje = Channel<MensajeUi>(Channel.BUFFERED)
    val mensaje = _mensaje.receiveAsFlow()

    // Evento one-shot: se emite (true) cuando la URL fue eliminada del historial.
    // La UI lo recolecta para navegar atras (onBack) tras un eliminado exitoso.
    private val _eliminarCompletado = Channel<Boolean>(Channel.BUFFERED)
    val eliminarCompletado = _eliminarCompletado.receiveAsFlow()

    /**
     * Carga el escaneo por id. Pre-llena _uiState desde el cache (si hay)
     * para evitar flash de "Cargando...", luego observa Room reactivamente.
     *
     * V-6 fix: antes usaba [RepositorioEscaneos.obtenerPorId] (suspend
     * one-shot) — si un sync actualizaba el escaneo mientras el usuario
     * estaba en el detalle, la UI mostraba datos stale hasta salir y
     * re-entrar. Ahora usa [RepositorioEscaneos.observarPorId] (Flow) —
     * Room re-emite automaticamente cuando la fila cambia, refrescando
     * el detalle en vivo. El cache se actualiza en cada emision, asi la
     * re-entrada a un detalle ya visitado trae datos frescos.
     *
     * **Bug A + Bug D fix (reKey resilience)**: el SyncWorker hace reKey
     * del id del escaneo (client UUID -> server UUID via
     * `UPDATE escaneos SET id=...`) cuando el POST al backend devuelve un
     * id propio. Tras el reKey, `observarPorId(clientUUID)` re-emite null
     * porque la fila con ese id ya no existe — solo existe bajo el
     * server UUID.
     *
     * Solucion estandarizada: en lugar de solo preservar el ultimo Cargado
     * con el id obsoleto (Bug A fix original), resolvemos el id real de la
     * fila viva mas reciente via
     * [RepositorioEscaneos.ultimoIdVivoPorUrlLimpia] y **re-subscribimos** el
     * Flow con ese nuevo id. Esto asegura que:
     *   - `Cargado.escaneo.id` always tenga el serverUUID real (no el
     *     clientUUID stale)
     *   - El cache se guarda bajo el key correcto (serverUUID)
     *   - Los downstream consumers (DetalleUrlScreen -> AnalisisAnteriores)
     *     reciben el id correcto sin necesidad de workarounds
     *   - `esUltimaVersion` y `contarReescaneosSnapshot` usan el id real
     *
     * @param id el id del escaneo a observar (client UUID o server UUID).
     * @param yaTeniaCargado true si ya hay un Cargado preservado (evita
     *     flash de NoEncontrado si el re-subscribed Flow tarda 1 frame).
     */
    fun cargarEscaneo(id: String, yaTeniaCargado: Boolean = false) {
        // Cancelar coleccion anterior (p.ej. navegacion a otro detalle).
        cargarJob?.cancel()

        val preFill = cacheDetalle.obtener(id)
        var tuvoEmisionNoNula = preFill != null || yaTeniaCargado
        if (preFill != null) _uiState.value = preFill

        cargarJob = viewModelScope.launch {
            repositorioEscaneos.observarPorId(id).collect { escaneo ->
                if (escaneo == null) {
                    if (!tuvoEmisionNoNula) {
                        // Caso legit: id inexistente (navegacion a detalle
                        // borrado o id invalido). Cache miss + Flow first
                        // emit null -> NoEncontrado.
                        _uiState.value = DetalleUrlUiState.NoEncontrado
                    } else {
                        // reKey detectado: la fila cambio su PK de clientUUID
                        // a serverUUID. Resolver el id real y re-subscribir.
                        val cargado = _uiState.value as? DetalleUrlUiState.Cargado
                        if (cargado != null) {
                            val nuevoId = repositorioEscaneos
                                .ultimoIdVivoPorUrlLimpia(cargado.escaneo.urlLimpia)
                            if (nuevoId != null && nuevoId != id) {
                                // Re-subscribir con el serverUUID real. El
                                // Cargado actual se preserva (no hay cache
                                // hit para nuevoId, _uiState no se resetea),
                                // y el nuevo Flow emitira el escaneo con el
                                // id correcto en <1ms.
                                cargarEscaneo(nuevoId, yaTeniaCargado = true)
                            }
                            // Si nuevoId == null (fila borrada en vuelo) o
                            // nuevoId == id (no hubo reKey, caso raro),
                            // preservar el ultimo Cargado.
                        }
                    }
                    return@collect
                }
                tuvoEmisionNoNula = true
                val urlBloqueada = repositorioUrlsBloqueadas.obtenerPorUrl(escaneo.urlLimpia) != null
                // Usar escaneo.id (siempre el PK real en la BD) en lugar de
                // `id` (param que puede ser clientUUID stale tras reKey).
                val esUltima = repositorioEscaneos.esUltimaVersion(escaneo.id)
                val totalReesc = repositorioEscaneos.contarReescaneosSnapshot(
                    escaneo.urlLimpia, escaneo.id
                )

                val cargado = DetalleUrlUiState.Cargado(
                    escaneo = escaneo,
                    urlBloqueada = urlBloqueada,
                    esUltimaVersion = esUltima,
                    totalReescaneos = totalReesc
                )
                cacheDetalle.guardar(cargado)
                _uiState.value = cargado
            }
        }
    }

    /**
     * Despacha una accion desde la UI (UDF).
     */
    fun onAction(action: DetalleUrlAction) {
        when (action) {
            is DetalleUrlAction.BloquearUrl -> bloquearUrl(action.url, action.razon)
            is DetalleUrlAction.DesbloquearUrl -> desbloquearUrl(action.url)
            is DetalleUrlAction.EliminarUrl -> eliminarUrl(action.urlLimpia)
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

    /**
     * Elimina TODOS los escaneos de la URL (ultima version + reescaneos)
     * del historial local y encola DELETEs al backend via SyncWorker.
     *
     * Tras un eliminado exitoso:
     *  1. Emite [_eliminarCompletado] `true` → la UI navega atras.
     *  2. Invalida el cache de detalle para que el id ya no aparezca.
     *
     * @param urlLimpia la URL limpia cuyos escaneos se eliminaran.
     */
    private fun eliminarUrl(urlLimpia: String) {
        viewModelScope.launch {
            try {
                repositorioEscaneos.eliminarLocalPorUrlLimpia(urlLimpia)
                mediadorSincronizacion.dispararSyncUnica()
                // Invalidar cache — el borrado es EN CASCADA (elimina la
                // ultima version + todos los reescaneos de la URL), asi que
                // hay que invalidar TODOS los ids de la misma urlLimpia, no
                // solo el navegado (audit fix B7: los demas ids quedaban
                // como Cargado stale y pintaban un detalle fantasma al
                // re-entrar desde AnalisisAnteriores).
                cacheDetalle.invalidarPorUrlLimpia(urlLimpia)
                _mensaje.send(MensajeUi(TipoMensaje.EXITO, "URL eliminada del historial"))
                _eliminarCompletado.send(true)
            } catch (e: Exception) {
                _mensaje.send(MensajeUi(TipoMensaje.ERROR, "Error al eliminar URL"))
            }
        }
    }
}
