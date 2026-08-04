package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bug 2 fix (optimizacion cache): ViewModel para la pantalla de Reescaneos.
 *
 * **Patron reactivo** (igual que [DatosTabsViewModel] para el historial):
 *  - Room es la fuente de verdad. Los Flows se suscriben via
 *    `stateIn(WhileSubscribed(5_000), emptyList())` — Room emite la lista
 *    cacheada en <1ms, **sin spinner `Cargando`**.
 *  - Al navegar fuera y volver (nav bar), el Flow sigue vivo (mientras haya
 *    suscriptores activos o dentro del window de 5s), asi que la UI muestra
 *    los datos cacheados instantaneamente — **no vuelve a consultar**.
 *  - Sync pull incremental es **fire-and-forget**: se lanza en
 *    `viewModelScope` sin bloquear la UI. Cuando el sync inserta nuevos
 *    reescaneos en Room, los Flows re-emiten automaticamente y la UI se
 *    actualiza sin que el usuario haga nada.
 *  - **Sin paginacion en el ViewModel**: la lista completa de reescaneos
 *    se carga via Flow (como el historial). La paginacion "Ver mas" se
 *    maneja en la UI con un `visibleCount` (`rememberSaveable`) — LazyColumn
 *    virtualiza, asi que cargar 1000 items es igual de eficiente que 10.
 *
 * **Como funciona `flatMapLatest`**: las coordenadas `(urlLimpia, idActual)`
 * se guardan en `MutableStateFlow`. Cuando cambian (el usuario navega a
 * reescaneos de otra URL), `flatMapLatest` cancela la suscripcion del Flow
 * anterior y subscribe el nuevo — sin re-carga manual ni spinner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReescaneosViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    // ── Coordenadas del escaneo "principal" ──
    // Seteadas una sola vez por la Screen via [cargarReescaneos].
    // flatMapLatest reacccioa a su cambio cancelando el Flow viejo.

    private val urlLimpiaFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val idActualFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /**
     * Flow reactivo con TODOS los reescaneos de la URL actual (excluyendo
     * [idActualFlow]). Room re-emite automaticamente al cambiar la tabla.
     *
     * Mismo patron que [DatosTabsViewModel.historialTodos]:
     * `stateIn(WhileSubscribed(5_000), emptyList())` — sin spinner, cache
     * instantaneo. Al volver a la pagina (nav bar), el Flow sigue vivo y
     * Room emite la lista cacheada en <1ms.
     */
    val reescaneos: StateFlow<List<EscaneoEntity>> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    repoEscaneos.observarReescaneosTodos(url, id)
                } else {
                    flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Flow reactivo del total de reescaneos. Eagerly para que Room emita
     * el conteo cacheado inmediatamente (sin parpadeo de "0").
     */
    val totalReescaneos: StateFlow<Int> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    repoEscaneos.observarTotalReescaneos(url, id)
                } else {
                    flowOf(0)
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /**
     * Flow reactivo del sync one-shot — emite true mientras el SyncWorker
     * esta ENQUEUED o RUNNING. La UI lo usa para mostrar un indicador de
     * "sincronizando" NO bloqueante (no spinner de carga, solo un banner
     * sutil cuando la lista ya esta visible).
     */
    val syncEnCurso: StateFlow<Boolean> =
        mediadorSync.observarSyncEnCurso()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Establece el escaneo "principal" cuya lista de reescaneos se muestra.
     * Llamado una sola vez al montar la pantalla (LaunchedEffect).
     *
     * Al cambiar `urlLimpia`/`idActual`, `flatMapLatest` cancela el Flow
     * anterior y subscribe el nuevo — no hay re-carga manual ni spinner.
     *
     * Dispara sync pull incremental **fire-and-forget** (no bloquea la UI):
     * cuando el sync inserta nuevos reescaneos en Room, los Flows re-emiten
     * automaticamente.
     */
    fun cargarReescaneos(urlLimpia: String, idActual: String) {
        // Evitar re-disparar si ya estamos suscritos a la misma URL+id.
        if (urlLimpiaFlow.value == urlLimpia && idActualFlow.value == idActual) return

        urlLimpiaFlow.value = urlLimpia
        idActualFlow.value = idActual

        // Sync pull incremental fire-and-forget: trae del backend cualquier
        // reescaneo nuevo. Room re-emite al insertar — la UI se actualiza sola.
        viewModelScope.launch {
            runCatching { mediadorSync.dispararSyncUnica() }
        }
    }
}
