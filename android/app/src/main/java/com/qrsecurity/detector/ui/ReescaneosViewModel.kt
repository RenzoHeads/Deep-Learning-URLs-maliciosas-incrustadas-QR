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
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Estado reactivo agrupado de la pantalla de Reescaneos, **etiquetado**
 * con la `(url, id)` a la que pertenece la data.
 *
 * La UI correlaciona `estado.url`/`estado.id` contra sus propios params
 * `urlLimpia`/`idActual` para decidir si renderiza la lista — esto
 * elimina el flash de la lista de la URL anterior al cambiar de URL, SIN
 * necesidad de RAM cache ni del hack `onStart { emit(emptyList()) }`.
 *
 * - Si `estado.url == urlLimpia && estado.id == idActual`, los datos
 *   corresponden a la URL actual → renderiza lista / empty-state.
 * - Si no coinciden (el StateFlow retiene la lista de la URL anterior
 *   mientras `flatMapLatest` switchea a la nueva), la UI renderiza solo
 *   el chrome (top bar + URL title) — sin flash de datos ajenos. La nueva
 *   URL emite desde Room en <1ms y `estado.url` pasa a coincidir.
 */
data class EstadoReescaneos(
    val url: String?,
    val id: String?,
    val lista: List<EscaneoEntity>,
    val total: Int
)

/**
 * ViewModel para la pantalla de Reescaneos (versiones anteriores de una URL).
 *
 * **Mismo patron que [DatosTabsViewModel]** (Historial):
 *  - Room es la fuente de verdad. El Flow [estadoReescaneos] se suscribe
 *    via `stateIn(WhileSubscribed(5_000), EstadoReescaneos(null, ...))` —
 *    Room emite la lista cacheada en <1ms, **sin spinner `Cargando`**.
 *  - Al navegar fuera y volver (nav bar o back), el Flow sigue vivo
 *    mientras haya suscriptores o dentro del window de 5s — la UI muestra
 *    los datos cacheados instantaneamente, **no vuelve a consultar Room**.
 *    Si pasan >5s, el Flow re-suscribe y Room re-emite en <1ms (imperceptible).
 *  - **Sin sync cloud en navigation**: el sync cloud (pull) solo ocurre al
 *    reloguear/reinstalar (manejado por ContenedorApp / login), igual que
 *    el Historial. [cargarReescaneos] NO dispara sync — solo setea las
 *    coordenadas (urlLimpia, idActual) para que `flatMapLatest` subscriba
 *    el Flow de Room correcto.
 *  - **Sin paginacion en el ViewModel**: la lista completa de reescaneos se
 *    carga via Flow. La paginacion "Ver mas" se maneja en la UI con un
 *    `visibleCount` (`rememberSaveable`) — `LazyColumn` virtualiza.
 *
 * **Prevenir flash al cambiar de URL (sin RAM cache)**: el Flow emite
 * [EstadoReescaneos] etiquetado con `(url, id)`. Cuando el usuario navega
 * de los reescaneos de URL A a los de URL B, `flatMapLatest` cancela el
 * Flow de A y subscribe el de B. Durante el switch (breve, <1ms), el
 * StateFlow retiene el ultimo valor — A etiquetado como `(A, A_id)`. La
 * UI compara: `estado.url == A != B` → no coincide → renderiza solo el
 * chrome (top bar + URL title), sin mostrar la lista de A. Cuando B
 * emite desde Room, `estado.url == B` → la UI renderiza la lista de B.
 *
 * **Nota**: ya NO usamos `onStart { emit(emptyList()) }` dentro de
 * `flatMapLatest`. Ese hack llenaba la lista de `emptyList()` al
 * re-suscribir (vuelve de la nav bar despues de >5s), causando un
 * flash de empty-state antes de que Room re-emitiera el cache —
 * destructivo para la UX. Con la etiqueta `(url, id)` en el estado
 * agrupado, no lo necesitamos.
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
     * Estado reactivo CON etiqueta `(url, id)`. Patron Historial:
     * `stateIn(WhileSubscribed(5_000), EstadoReescaneos(null, null, emptyList(), 0))`.
     *
     * - **Al cambiar de URL (A→B)**: `flatMapLatest` cancela A y subscribe
     *   B. Mientras B no emite, el StateFlow retiene A etiquetado. La UI
     *   ve `estado.url == A != B` y NO renderiza la lista de A — muestra
     *   solo el chrome sin flash. B emite desde Room en <1ms →
     *   `estado.url == B` → render lista de B.
     * - **Al volver a la misma URL dentro del window de 5s**: el StateFlow
     *   ya tiene la URL actual etiquetada → UI renderiza al instante, sin
     *   re-consultar Room.
     * - **Al volver despues de >5s**: el Flow re-suscribe → Room re-emite
     *   etiquetado → mismo valor, sin flash.
     *
     * Implementacion: `combine(urlLimpiaFlow, idActualFlow)` reacciona a
     * cambios de coordenadas → `flatMapLatest` subscribe el combine de
     * los dos Flows de Room (`observarReescaneosTodos` +
     * `observarTotalReescaneos`) → emite [EstadoReescaneos] etiquetado.
     */
    val estadoReescaneos: StateFlow<EstadoReescaneos> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    combine(
                        repoEscaneos.observarReescaneosTodos(url, id),
                        repoEscaneos.observarTotalReescaneos(url, id)
                    ) { lista, total ->
                        EstadoReescaneos(url = url, id = id, lista = lista, total = total)
                    }
                } else {
                    flowOf(EstadoReescaneos(url = null, id = null, lista = emptyList(), total = 0))
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = EstadoReescaneos(url = null, id = null, lista = emptyList(), total = 0)
            )

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
