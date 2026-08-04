package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bug 2 fix: UiState para la pantalla de Reescaneos — versiones
 * anteriores de una misma URL, en su propia pagina (no dentro del
 * Detalle de Escaneo).
 *
 * Refleja el patron de [HistorialScreen]: offline-first + sync pull
 * incremental.
 */
sealed interface ReescaneosUiState {
    data object Cargando : ReescaneosUiState
    data class Cargado(
        val urlLimpia: String,
        /** Id del escaneo "principal" (la version mas reciente). */
        val idActual: String,
        /**
         * Reescaneos cargados hasta el momento (acumulados, paginados
         * de [TAMANO_PAGINA] en [TAMANO_PAGINA]).
         */
        val reescaneos: List<EscaneoEntity>,
        /** Total de reescaneos (excluyendo [idActual]). Reactivo via Flow. */
        val totalReescaneos: Int
    ) : ReescaneosUiState
    data object NoAplica : ReescaneosUiState
}

/**
 * Bug 2 fix: Acciones que la UI despacha al ViewModel (UDF).
 */
sealed interface ReescaneosAction {
    /**
     * Cargar la siguiente pagina de reescaneos desde la base local.
     * El offset se calcula como `reescaneos.size` (cuantos ya tenemos).
     */
    data object CargarMas : ReescaneosAction
}

/**
 * Bug 2 fix: ViewModel para la pantalla de Reescaneos.
 *
 * **Patron incremental sync pull + local pagination** (igual que
 * [HistorialScreen]):
 *  1. Al montar la pantalla, [cargarReescaneos] dispara un sync pull
 *     one-shot via [MediadorSincronizacion.dispararSyncUnica] para
 *     traer del backend los ultimos escaneos de esta URL (incluido
 *     reescaneos que quizas se hicieron en otro dispositivo).
 *  2. A continuacion carga la primer pagina de reescaneos desde la base
 *     local (Room) — respuesta instantanea sin red.
 *  3. Cuando el usuario presiona "Ver mas", [CargarMas] carga la
 *     siguiente pagina desde la base local (Room Flow con
 *     LIMIT/OFFSET). El offset avanza en [TAMANO_PAGINA].
 *  4. El total de reescaneos es reactivo: si el sync trae nuevos
 *     reescaneos, [observarTotalReescaneos] re-emite y la UI actualiza
 *     el contador + refresca la pagina actual.
 *
 * La fuente de verdad es Room (offline-first). El sync solo pobla Room;
 * la UI nunca espera al sync para mostrar datos — muestra lo cacheado
 * y se actualiza reactivamente cuando Room cambia.
 */
@HiltViewModel
class ReescaneosViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReescaneosUiState>(ReescaneosUiState.Cargando)
    val uiState: StateFlow<ReescaneosUiState> = _uiState.asStateFlow()

    /**
     * Flow reactivo del total de reescaneos. Se publica como StateFlow
     * Eagerly para que Room emita el conteo cacheado en <1ms y la UI
     * no vea "0" transitorio.
     */
    private var totalReescaneosFlow: StateFlow<Int> =
        kotlinx.coroutines.flow.flowOf(0)
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val totalReescaneos: StateFlow<Int> = totalReescaneosFlow

    /**
     * Flow reactivo del sync one-shot — emite true mientras el
     * SyncWorker esta ENQUEUED o RUNNING. Reutilizado por la UI para
     * mostrar un spinner de "sincronizando" solo la primera vez.
     */
    val syncEnCurso: StateFlow<Boolean> =
        mediadorSync.observarSyncEnCurso()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Tamaño de pagina — igual que el historial (10 items por pagina).
     * El usuario pidio "la misma logica que el historial".
     */
    private val tamanoPagina = 10

    /**
     * Reescaneos acumulados (todas las paginas cargadas hasta ahora).
     * Se mantiene en el ViewModel; la UI solo ve la lista final.
     */
    private var reescaneosCargados: List<EscaneoEntity> = emptyList()

    // ── Coordenadas del escaneo "principal" cuya lista de versiones
    // anteriores estamos mostrando.
    private var urlLimpiaActual: String = ""
    private var idActual: String = ""

    init {
        // Reaccionar al cambio del total de reescaneos (e.g. el sync trajo
        // nuevos reescaneos desde el servidor). Cuando el total cambia,
        // refrescamos la pagina actual para que aparezcan los nuevos
        // reescaneos en orden cronologico sin que el usuario tenga que
        // presionar "Ver mas".
        viewModelScope.launch {
            totalReescaneosFlow.collect { _ ->
                if (_uiState.value is ReescaneosUiState.Cargado) {
                    refrescarPaginaActual()
                }
            }
        }
    }

    /**
     * Carga los reescaneos de [urlLimpia] excluyendo el escaneo [idActual]
     * (la version mas reciente). Dispara un sync pull one-shot para
     * traer del backend cualquier reescaneo nuevo ANTES de cargar la
     * primer pagina local — garantiza que la UI muestre la lista mas
     * completa disponible.
     *
     * Llamado una sola vez al montar la pantalla (LaunchedEffect).
     */
    fun cargarReescaneos(urlLimpia: String, idActual: String) {
        // Evitar re-cargar si ya tenemos el estado para esta URL.
        val estadoActual = _uiState.value
        if (estadoActual is ReescaneosUiState.Cargado &&
            estadoActual.urlLimpia == urlLimpia &&
            estadoActual.idActual == idActual
        ) return

        this.urlLimpiaActual = urlLimpia
        this.idActual = idActual
        this.reescaneosCargados = emptyList()
        _uiState.value = ReescaneosUiState.Cargando

        // Suscribir el Flow del total (reactivo: re-emite cuando el sync
        // inserta nuevos reescaneos en Room).
        totalReescaneosFlow = repoEscaneos
            .observarTotalReescaneos(urlLimpia, idActual)
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

        viewModelScope.launch {
            // Sync pull incremental: pedir al backend los ultimos cambios
            // para que Room tenga los reescaneos mas recientes antes de
            // cargar la primer pagina local. Dispara un WorkManager
            // one-shot que corre en background; no bloquea la UI.
            runCatching { mediadorSync.dispararSyncUnica() }

            // Cargar la primera pagina desde Room (offline-first).
            val primeraPagina = repoEscaneos
                .observarReescaneosSnapshot(urlLimpia, idActual, tamanoPagina, 0)
            reescaneosCargados = primeraPagina

            val totalReesc = repoEscaneos.contarReescaneosSnapshot(urlLimpia, idActual)
            _uiState.value = ReescaneosUiState.Cargado(
                urlLimpia = urlLimpia,
                idActual = idActual,
                reescaneos = reescaneosCargados,
                totalReescaneos = totalReesc
            )
        }
    }

    /**
     * Despacha una acción desde la UI (UDF).
     */
    fun onAction(action: ReescaneosAction) {
        when (action) {
            ReescaneosAction.CargarMas -> cargarMas()
        }
    }

    /**
     * Carga la siguiente pagina de reescaneos desde la base local y los
     * anade a la lista acumulada. El offset es `reescaneosCargados.size`
     * (cuantos ya tenemos). Tras cargar, actualiza el UiState con la
     * nueva lista combinada.
     *
     * Si ya cargamos todos (reescaneosCargados.size >= total), es no-op.
     */
    private fun cargarMas() {
        val estadoActual = _uiState.value as? ReescaneosUiState.Cargado ?: return
        if (reescaneosCargados.size >= estadoActual.totalReescaneos) return

        viewModelScope.launch {
            val offset = reescaneosCargados.size
            val nuevaPagina = repoEscaneos
                .observarReescaneosSnapshot(urlLimpiaActual, idActual, tamanoPagina, offset)
            // Evitar duplicados por si un refresco concurrente inserto
            // los mismos ids. Filtramos por id.
            val idsExistentes = reescaneosCargados.map { it.id }.toSet()
            val sinDuplicar = nuevaPagina.filter { it.id !in idsExistentes }
            reescaneosCargados = reescaneosCargados + sinDuplicar
            refrescarPaginaActual()
        }
    }

    /**
     * Refresca el UiState con la lista acumulada actual y el total
     * reactivo. Se llama tras [cargarMas] y cuando el total Flow emite
     * un nuevo valor.
     */
    private suspend fun refrescarPaginaActual() {
        val total = repoEscaneos.contarReescaneosSnapshot(urlLimpiaActual, idActual)
        _uiState.update { estado ->
            if (estado is ReescaneosUiState.Cargado) {
                estado.copy(reescaneos = reescaneosCargados, totalReescaneos = total)
            } else {
                estado
            }
        }
    }
}
