package com.qrsecurity.detector.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.cache.CacheDetalleEscaneos
import com.qrsecurity.detector.cache.DetalleEscaneoCacheado
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.repositorios.bloquearLocal
import com.qrsecurity.detector.datos.repositorios.desbloquearLocal
import com.qrsecurity.detector.datos.repositorios.eliminarLocal
import com.qrsecurity.detector.datos.repositorios.eliminarLocalPorUrlLimpia
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformLatest
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

// Mapeo al contrato neutro del cache ([DetalleEscaneoCacheado]) — el
// cache vive en la capa `cache` y no conoce los estados de pantalla.
private fun DetalleUrlUiState.Cargado.aCacheado() = DetalleEscaneoCacheado(
    escaneo, urlBloqueada, esUltimaVersion, totalReescaneos
)

private fun DetalleEscaneoCacheado.aCargado() = DetalleUrlUiState.Cargado(
    escaneo, urlBloqueada, esUltimaVersion, totalReescaneos
)

/**
 * Acciones que la UI puede despachar al ViewModel (Unidirectional Data Flow).
 *
 * Las dos acciones cubren el ciclo de bloqueo/desbloqueo de una URL desde
 * la pantalla de detalle.
 */
sealed interface DetalleUrlAction {
    /**
     * Bloquea la URL. M1 (auditoría frontend): sin `razon` — la política de
     * NEGOCIO (qué razón se registra al bloquear desde el detalle) vive en
     * el VM, no en el payload de la acción armado por la UI (antes la
     * pantalla importaba `RepositorioUrlsBloqueadas.RAZON_MALICIOSA`,
     * constante de la capa datos).
     */
    data class BloquearUrl(val url: String) : DetalleUrlAction
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
    /**
     * Elimina UNA versión histórica específica por id (NO la cascada por
     * [urlLimpia] de [EliminarUrl]). Delega en
     * [RepositorioEscaneos.eliminarLocal], que maneja branch dirty/synced
     * y la sincronización de `urls_catalogo` en una transacción atómica.
     *
     * Reemplaza a `DetalleVersionAntiguaViewModel.eliminarVersion` tras
     * unificar ambas pantallas (auditoría frontend F1.1).
     */
    data class EliminarVersion(val id: String) : DetalleUrlAction
}

/**
 * Mensajes one-shot del detalle, tipados (S6 — auditoría frontend): la capa
 * de lógica emite QUÉ pasó; la copy (texto + severidad) se resuelve en la
 * UI vía [aMensajeUi] — antes los strings de usuario vivían en el ViewModel,
 * acoplando lógica y presentación.
 */
sealed interface MensajeDetalleUrl {
    data object UrlBloqueada : MensajeDetalleUrl
    data object UrlDesbloqueada : MensajeDetalleUrl
    data object UrlNoEstabaBloqueada : MensajeDetalleUrl
    data object UrlEliminada : MensajeDetalleUrl
    data object VersionEliminada : MensajeDetalleUrl
    data object FalloBloqueo : MensajeDetalleUrl
    data object FalloDesbloqueo : MensajeDetalleUrl
    data object FalloEliminarUrl : MensajeDetalleUrl
    data object FalloEliminarVersion : MensajeDetalleUrl
}

/** Severidad + copy de cada mensaje — única fuente, viviendo en la capa UI. */
internal fun MensajeDetalleUrl.aMensajeUi(): MensajeUi = when (this) {
    MensajeDetalleUrl.UrlBloqueada -> MensajeUi(TipoMensaje.EXITO, "URL bloqueada.")
    MensajeDetalleUrl.UrlDesbloqueada -> MensajeUi(TipoMensaje.EXITO, "URL desbloqueada.")
    MensajeDetalleUrl.UrlNoEstabaBloqueada ->
        MensajeUi(TipoMensaje.INFO, "La URL no estaba bloqueada.")
    MensajeDetalleUrl.UrlEliminada ->
        MensajeUi(TipoMensaje.EXITO, "URL eliminada del historial.")
    MensajeDetalleUrl.VersionEliminada ->
        MensajeUi(TipoMensaje.EXITO, "Versión eliminada del historial.")
    MensajeDetalleUrl.FalloBloqueo ->
        MensajeUi(TipoMensaje.ERROR, "No pudimos bloquear la URL. Inténtalo de nuevo.")
    MensajeDetalleUrl.FalloDesbloqueo ->
        MensajeUi(TipoMensaje.ERROR, "No pudimos desbloquear la URL. Inténtalo de nuevo.")
    MensajeDetalleUrl.FalloEliminarUrl ->
        MensajeUi(TipoMensaje.ERROR, "No pudimos eliminar la URL. Inténtalo de nuevo.")
    MensajeDetalleUrl.FalloEliminarVersion ->
        MensajeUi(TipoMensaje.ERROR, "No pudimos eliminar la versión. Inténtalo de nuevo.")
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
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetalleUrlViewModel @Inject constructor(
    private val repositorioEscaneos: RepositorioEscaneos,
    private val repositorioUrlsBloqueadas: RepositorioUrlsBloqueadas,
    private val mediadorSincronizacion: MediadorSincronizacion,
    private val cacheDetalle: CacheDetalleEscaneos,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow<DetalleUrlUiState>(DetalleUrlUiState.Cargando)
    val uiState: StateFlow<DetalleUrlUiState> = _uiState.asStateFlow()

    // S3 (auditoría frontend): id del escaneo observado. Cambiarlo
    // re-subscribe la cadena reactiva completa (flatMapLatest cancela la
    // observación anterior) — reemplaza los Jobs manuales `cargarJob` /
    // `innerJob` y la recursión pública de `cargarEscaneo(nuevoId,
    // yaTeniaCargado = true)`, que cancelaba el Job que estaba ejecutando
    // la propia llamada.
    private val idObservado = MutableStateFlow<String?>(null)

    init {
        // RC1 fix (parpadeo de "Cargando..."): este VM se instancia por
        // NavBackStackEntry y su estado inicial era Cargando hasta que la
        // cadena reactiva — disparada por el LaunchedEffect de la pantalla,
        // DESPUÉS del primer frame — hiciera el prefill del cache. Eso
        // dejaba 1-3 frames de spinner en cada navegación, incluso con
        // cache hit. El nav arg "id" de la ruta detalle_url/{id} ya vive en
        // el SavedStateHandle del entry: leerlo aquí permite pintar el
        // detalle cacheado en la PRIMERA composición (obtener() es una
        // lectura síncrona de un map en memoria — ver CacheDetalleEscaneos,
        // que documenta este diseño desde su creación). La cadena reactiva
        // de abajo re-valida contra Room en background (~ms, invisible).
        val idNav = savedStateHandle.get<String>("id")
        if (!idNav.isNullOrEmpty()) {
            cacheDetalle.obtener(idNav)?.let { _uiState.value = it.aCargado() }
            idObservado.value = idNav
        }
        idObservado
            .filterNotNull()
            .flatMapLatest { id -> observarEscaneo(id) }
            .distinctUntilChanged()
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    // Eventos one-shot via Channel — cada evento se entrega una sola vez.
    private val _mensaje = Channel<MensajeDetalleUrl>(Channel.BUFFERED)
    val mensaje = _mensaje.receiveAsFlow()

    // Evento one-shot: se emite cuando la URL fue eliminada del historial.
    // La UI lo recolecta para navegar atras (onBack) tras un eliminado exitoso.
    private val _eliminarCompletado = Channel<Unit>(Channel.BUFFERED)
    val eliminarCompletado = _eliminarCompletado.receiveAsFlow()

    // Evento one-shot: se emite cuando el desbloqueo confirmo exito real en
    // el repositorio. La UI lo recolecta para abrir el modal de exito
    // (OkDesbloqueo) — reemplaza al flag `desbloqueoPendiente` que
    // correlacionaba el boolean con el *tipo* de mensaje del canal `mensaje`
    // (fragil ante cualquier EXITO/ERROR concurrente).
    private val _desbloqueoCompletado = Channel<Unit>(Channel.BUFFERED)
    val desbloqueoCompletado = _desbloqueoCompletado.receiveAsFlow()

    // Guarda de reentrada (B4): doble-tap en Bloquear/Desbloquear/Eliminar
    // lanzaba cascadas duplicadas (doble DELETE al backend + doble
    // `eliminarCompletado` → navegacion atras doble + snackbar de error tras
    // un borrado exitoso). Check sincrono en el entry point — mismo patron
    // que LoginViewModel.procesando. Solo se toca desde el hilo Main
    // (viewModelScope + llamadas UI), sin necesidad de atomica.
    private var operandoUrl = false

    /**
     * Carga el escaneo por id: alimenta [idObservado] y deja que la cadena
     * reactiva del `init` (flatMapLatest) haga el resto — prefill de cache
     * incluido dentro de [observarEscaneo] para evitar flash de "Cargando...".
     *
     * V-6: reactiva via [RepositorioEscaneos.observarPorId] (Room re-emite
     * cuando la fila cambia — sin datos stale). Idempotente: re-llamar con
     * el mismo id (recomposición del LaunchedEffect) no re-subscribe nada.
     */
    fun cargarEscaneo(id: String) {
        if (idObservado.value == id) return
        idObservado.value = id
    }

    /**
     * Cadena reactiva de un escaneo — S3 (auditoría frontend): antes eran
     * 125 líneas con 2 Jobs manuales (`cargarJob`/`innerJob`) y una
     * recursión pública que cancelaba el Job que la ejecutaba.
     *
     *  1. Prefill del cache ([CacheDetalleEscaneos]) — evita flash de
     *     "Cargando..." al re-entrar a un detalle ya visitado.
     *  2. `observarPorId(id)` + [transformLatest]: cada escaneo NO-NULO
     *     monta el combine (bloqueo reactivo + snapshot paralelo
     *     esUltimaVersion/contarReescaneos) y CANCELA el anterior — antes
     *     era `innerJob?.cancel()` a mano.
     *  3. Emisión null con fila viva previa (reKey del SyncWorker: client
     *     UUID → server UUID): resuelve el id real via
     *     [RepositorioEscaneos.ultimoIdVivoPorUrlLimpia] y muta
     *     [idObservado] — el flatMapLatest del init cancela esta cadena
     *     (incluidos los nulls stale del id viejo) y re-subscribe con el
     *     serverUUID real, preservando el Cargado vigente (sin flash).
     *  4. Null sin fila viva de la misma URL (U6): la fila fue eliminada
     *     de verdad — invalida caches y emite NoEncontrado (no dejar
     *     actuar sobre un escaneo fantasma).
     */
    private fun observarEscaneo(id: String): Flow<DetalleUrlUiState> = flow {
        val preFill = cacheDetalle.obtener(id)
        if (preFill != null) emit(preFill.aCargado())
        var urlLimpiaViva: String? = preFill?.escaneo?.urlLimpia

        emitAll(
            repositorioEscaneos.observarPorId(id).transformLatest { escaneo ->
                if (escaneo == null) {
                    val urlLimpia = urlLimpiaViva
                    if (urlLimpia == null) {
                        // Caso legit: id inexistente (navegacion a detalle
                        // borrado o id invalido). Cache miss + primer emit
                        // null -> NoEncontrado.
                        emit(DetalleUrlUiState.NoEncontrado)
                    } else {
                        val nuevoId = repositorioEscaneos
                            .ultimoIdVivoPorUrlLimpia(urlLimpia)
                        if (nuevoId != null && nuevoId != id) {
                            idObservado.value = nuevoId
                        } else {
                            cacheDetalle.invalidar(id)
                            cacheDetalle.invalidarPorUrlLimpia(urlLimpia)
                            emit(DetalleUrlUiState.NoEncontrado)
                        }
                    }
                } else {
                    urlLimpiaViva = escaneo.urlLimpia
                    val urlBloqueadaFlow = repositorioUrlsBloqueadas
                        .observarPorUrl(escaneo.urlLimpia)
                        .map { it != null }
                        .distinctUntilChanged()

                    // Snapshot paralelo (A2.b): esUltimaVersion y
                    // contarReescaneosSnapshot corren concurrentes; ambas
                    // delegan en `ioDispatcher` via el repos.
                    val snapshotFlow = flow<Pair<Boolean, Int>> {
                        coroutineScope {
                            val deferredUltima = async {
                                repositorioEscaneos.esUltimaVersion(
                                    escaneo.urlLimpia,
                                    escaneo.creadoEnMillis,
                                    escaneo.id
                                )
                            }
                            val deferredReesc = async {
                                repositorioEscaneos.contarReescaneosSnapshot(
                                    escaneo.urlLimpia,
                                    escaneo.id
                                )
                            }
                            emit(deferredUltima.await() to deferredReesc.await())
                        }
                    }

                    emitAll(
                        combine(urlBloqueadaFlow, snapshotFlow) { urlBloqueada, snapshot ->
                            val (esUltima, totalReesc) = snapshot
                            DetalleUrlUiState.Cargado(
                                escaneo = escaneo,
                                urlBloqueada = urlBloqueada,
                                esUltimaVersion = esUltima,
                                totalReescaneos = totalReesc
                            )
                        }
                            .onEach { cargado -> cacheDetalle.guardar(cargado.aCacheado()) }
                    )
                }
            }
        )
    }

    /**
     * Despacha una accion desde la UI (UDF).
     */
    fun onAction(action: DetalleUrlAction) {
        // B4: rechazo en el dispatch, no dentro del launch — dos taps
        // consecutivos sobre el mismo boton nunca encolan dos cascadas.
        if (operandoUrl) return
        operandoUrl = true
        when (action) {
            is DetalleUrlAction.BloquearUrl -> bloquearUrl(action.url)
            is DetalleUrlAction.DesbloquearUrl -> desbloquearUrl(action.url)
            is DetalleUrlAction.EliminarUrl -> eliminarUrl(action.urlLimpia)
            is DetalleUrlAction.EliminarVersion -> eliminarVersion(action.id)
        }
    }

    private fun bloquearUrl(url: String) {
        viewModelScope.launch {
            try {
                repositorioUrlsBloqueadas.bloquearLocal(
                    url = url,
                    razon = RepositorioUrlsBloqueadas.RAZON_MALICIOSA
                )
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
                _mensaje.send(MensajeDetalleUrl.UrlBloqueada)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _mensaje.send(MensajeDetalleUrl.FalloBloqueo)
            } finally {
                operandoUrl = false
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
                    _mensaje.send(MensajeDetalleUrl.UrlDesbloqueada)
                    // Evento tipado en vez de sniffear el tipo del mensaje:
                    // la UI abre OkDesbloqueo solo con esta señal.
                    _desbloqueoCompletado.send(Unit)
                } else {
                    _mensaje.send(MensajeDetalleUrl.UrlNoEstabaBloqueada)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _mensaje.send(MensajeDetalleUrl.FalloDesbloqueo)
            } finally {
                operandoUrl = false
            }
        }
    }

    /**
     * Elimina TODOS los escaneos de la URL (ultima version + reescaneos)
     * del historial local y encola DELETEs al backend via SyncWorker.
     *
     * Tras un eliminado exitoso:
     *  1. Emite [_eliminarCompletado] → la UI navega atras.
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
                _mensaje.send(MensajeDetalleUrl.UrlEliminada)
                _eliminarCompletado.send(Unit)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _mensaje.send(MensajeDetalleUrl.FalloEliminarUrl)
            } finally {
                operandoUrl = false
            }
        }
    }

    /**
     * Elimina UNA versión histórica por id (NO cascada por URL). Delega en
     * [RepositorioEscaneos.eliminarLocal], que maneja:
     *  - Branch dirty (CREATE pending): cancela el CREATE op + borra el row
     *    local. No encola DELETE (fila nunca llegó al backend).
     *  - Branch synced: encola DELETE op + borra el row local.
     *  - Sincroniza `urls_catalogo` atómicamente (borra el hash si era la
     *    última versión de la URL, o actualiza el row con la nueva última).
     *
     * Tras un eliminado exitoso invalida el cache del id y emite
     * [_eliminarCompletado] → la UI navega atrás.
     */
    private fun eliminarVersion(id: String) {
        viewModelScope.launch {
            try {
                repositorioEscaneos.eliminarLocal(id)
                mediadorSincronizacion.dispararSyncUnica()
                cacheDetalle.invalidar(id)
                _mensaje.send(MensajeDetalleUrl.VersionEliminada)
                _eliminarCompletado.send(Unit)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _mensaje.send(MensajeDetalleUrl.FalloEliminarVersion)
            } finally {
                operandoUrl = false
            }
        }
    }
}
