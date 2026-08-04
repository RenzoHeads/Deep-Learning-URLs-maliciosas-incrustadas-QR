package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel para la pantalla de Reescaneos (versiones anteriores de una URL).
 *
 * **Mismo patron que [DatosTabsViewModel]** (Historial):
 *  - Room es la fuente de verdad. Los Flows se suscriben via
 *    `stateIn(WhileSubscribed(5_000), emptyList())` — Room emite la lista
 *    cacheada en <1ms, **sin spinner `Cargando`**.
 *  - Al navegar fuera y volver (nav bar o back), el Flow sigue vivo mientras
 *    haya suscriptores o dentro del window de 5s — la UI muestra los datos
 *    cacheados instantaneamente, **no vuelve a consultar Room**. Si pasan
 *    >5s, el Flow se re-suscribe y Room emite en <1ms (imperceptible).
 *  - **Sin sync cloud en navigation**: el sync cloud (pull) solo ocurre al
 *    reloguear/reinstalar (manejado por ContenedorApp / login), igual que
 *    el Historial. [cargarReescaneos] NO dispara sync — solo setea las
 *    coordenadas (urlLimpia, idActual) para que `flatMapLatest` subscriba
 *    el Flow de Room correcto.
 *  - **Sin paginacion en el ViewModel**: la lista completa de reescaneos se
 *    carga via Flow. La paginacion "Ver mas" se maneja en la UI con un
 *    `visibleCount` (`rememberSaveable`) — `LazyColumn` virtualiza.
 *
 * **Prevenir flash al cambiar de URL**: cuando el usuario navega de los
 * reescaneos de URL A a los de URL B, `flatMapLatest` cancela el Flow de A
 * y subscribe el de B. Sin `onStart`, el `StateFlow` retiene la lista de A
 * hasta que B emite — el usuario ve la lista de A brevemente (flash). Con
 * `onStart { emit(emptyList()) }`, el Flow de B emite `emptyList()` antes
 * de que Room emita, limpiando la lista de A inmediatamente. Room emite en
 * <1ms, asi que el estado vacio es practicamente invisible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReescaneosViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    // ── Coordenadas del escaneo "principal" ──
    // Seteadas una sola vez por la Screen via [cargarReescaneos].
    // flatMapLatest reacciona a su cambio cancelando el Flow viejo.

    private val urlLimpiaFlow = MutableStateFlow<String?>(null)
    private val idActualFlow = MutableStateFlow<String?>(null)

    /**
     * Flow reactivo con TODOS los reescaneos de la URL actual (excluyendo
     * [idActualFlow]). Room re-emite automaticamente al cambiar la tabla.
     *
     * `WhileSubscribed(5_000)`: mismo patron que
     * [DatosTabsViewModel.historialTodos]. El Flow se cancela 5s despues
     * del ultimo suscriptor, y se re-suscribe al volver — Room emite en
     * <1ms. Mientras hay suscriptores o dentro del window, el StateFlow
     * retiene la lista cacheada (no re-consulta Room).
     *
     * `onStart { emit(emptyList()) }`: limpia la lista de la URL anterior
     * al cambiar de URL, evitando el flash de la lista equivocada. Room
     * emite en <1ms, asi que el estado vacio es imperceptible.
     */
    val reescaneos: StateFlow<List<EscaneoEntity>> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    repoEscaneos.observarReescaneosTodos(url, id)
                        .onStart { emit(emptyList()) }
                } else {
                    flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Flow reactivo del total de reescaneos. `WhileSubscribed(5_000)` igual
     * que la lista. `onStart { emit(0) }` limpia el total de la URL anterior
     * al cambiar de URL.
     */
    val totalReescaneos: StateFlow<Int> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    repoEscaneos.observarTotalReescaneos(url, id)
                        .onStart { emit(0) }
                } else {
                    flowOf(0)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Flow reactivo del sync one-shot — emite true mientras el SyncWorker
     * esta ENQUEUED o RUNNING. La UI lo usa para mostrar un indicador de
     * "sincronizando" NO bloqueante. `Eagerly` igual que
     * [DatosTabsViewModel.syncEnCurso].
     */
    val syncEnCurso: StateFlow<Boolean> =
        mediadorSync.observarSyncEnCurso()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Establece el escaneo "principal" cuya lista de reescaneos se muestra.
     * Llamado una sola vez al montar la pantalla (LaunchedEffect).
     *
     * **No dispara sync cloud** — el sync cloud (pull) solo ocurre al
     * reloguear/reinstalar (manejado por ContenedorApp / login), igual que
     * el Historial. Aqui solo setea las coordenadas para que
     * `flatMapLatest` subscribe el Flow de Room correcto.
     *
     * Guard: si las coordenadas no cambiaron, no hace nada (evita re-setear
     * los StateFlows y disparar `flatMapLatest` innecesariamente).
     */
    fun cargarReescaneos(urlLimpia: String, idActual: String) {
        if (urlLimpiaFlow.value == urlLimpia && idActualFlow.value == idActual) return
        urlLimpiaFlow.value = urlLimpia
        idActualFlow.value = idActual
    }
}
