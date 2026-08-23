package com.qrsecurity.detector.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Tamano de pagina de la lista de versiones (Paging 3 local sobre Room). */
private const val TAMANO_PAGINA = 50

/**
 * Estado de la CABECERA de Analisis Anteriores, **etiquetado** con la
 * `(url, id)` a la que pertenece (S1).
 *
 * v10 — Paging 3: la lista de versiones YA NO vive en este estado (antes
 * `Cargado.lista` traia TODAS las filas sin LIMIT — con miles de versiones
 * de una URL, toda la lista en memoria y re-materializada en cada
 * invalidacion de la tabla). Ahora la lista es un [PagingData] paginado
 * ([AnalisisAnterioresViewModel.versiones]) y este estado solo lleva el
 * total del COUNT indexado — correcto siempre, independiente de cuantas
 * paginas haya cargado el usuario.
 *
 * La UI correlaciona `Cargado.url`/`Cargado.id` contra sus params para
 * decidir si renderiza — elimina el flash de la URL anterior al cambiar de
 * URL (S1), sin RAM cache.
 */
sealed interface EstadoAnalisisAnteriores {
    /** Sin coordenadas todavia (o re-suscribiendo tras >3s sin suscriptores). */
    data object Cargando : EstadoAnalisisAnteriores

    // M6 audit fix — @Immutable: emitida una vez y nunca mutada tras la
    // emision (ver comentario en GrupoHistorial). Permite el skip de la
    // pantalla cuando el estado no cambia.
    @Immutable
    data class Cargado(
        val url: String,
        val id: String,
        val total: Int
    ) : EstadoAnalisisAnteriores
}

/**
 * ViewModel para la pantalla de Analisis Anteriores (versiones anteriores
 * de una URL).
 *
 * Mismo patron que [DatosTabsViewModel] (Historial):
 *  - Room es la fuente de verdad. El Flow [estadoAnalisisAnteriores] se
 *    suscribe via `stateIn(WhileSubscribed(3_000),
 *    EstadoAnalisisAnteriores(null, ...))` — Room emite la lista cacheada
 *    en <1ms, **sin spinner `Cargando`**.
 *  - Al navegar fuera y volver (nav bar o back), el Flow sigue vivo
 *    mientras haya suscriptores o dentro del window de 3s — la UI muestra
 *    los datos cacheados instantaneamente, **no vuelve a consultar Room**.
 *    Si pasan >3s, el Flow re-suscribe y Room re-emite en <1ms (imperceptible).
 *  - **Sin sync cloud en navigation**: el sync cloud (pull) solo ocurre al
 *    reloguear/reinstalar (manejado por ContenedorApp / login), igual que
 *    el Historial. [cargarAnalisisAnteriores] NO dispara sync — solo setea
 *    las coordenadas (urlLimpia, idActual) para que `flatMapLatest`
 *    subscriba el Pager/Flow de Room correcto.
 *  - **v10 — paginacion real**: la lista es [versiones] (Paging 3 sobre
 *    Room, scroll infinito, memoria acotada a la ventana); el total de la
 *    cabecera viene del COUNT indexado via [estadoAnalisisAnteriores].
 *
 * **Prevenir flash al cambiar de URL (sin RAM cache)**: el Flow emite
 * [EstadoAnalisisAnteriores] etiquetado con `(url, id)`. Cuando el usuario
 * navega de los analisis anteriores de URL A a los de URL B,
 * `flatMapLatest` cancela el Flow de A y subscribe el de B. Durante el
 * switch (breve, <1ms), el StateFlow retiene el ultimo valor — A etiquetado
 * como `(A, A_id)`. La UI compara: `estado.url == A != B` -> no coincide
 * -> renderiza solo el chrome (top bar + URL title), sin mostrar la lista
 * de A. Cuando B emite desde Room, `estado.url == B` -> la UI renderiza la
 * lista de B.
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
class AnalisisAnterioresViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos
) : ViewModel() {

    // ── Coordenadas del escaneo "principal" ──
    // Seteadas una sola vez por la Screen via [cargarAnalisisAnteriores].
    // flatMapLatest reacciona a su cambio cancelando el Flow/Pager viejo.

    private val urlLimpiaFlow = MutableStateFlow<String?>(null)
    private val idActualFlow = MutableStateFlow<String?>(null)

    /**
     * v10 — Lista de versiones paginada (Paging 3 sobre Room, sin
     * RemoteMediator: la red nunca esta atada a la navegacion).
     *
     * - `flatMapLatest` recrea el Pager al cambiar de URL (cancela el viejo).
     * - `cachedIn(viewModelScope)`: el stream sobrevive a rotaciones de
     *   pantalla y re-entradas — al volver, la ventana ya cargada pinta al
     *   instante.
     * - `enablePlaceholders = true`: los indices del LazyColumn son
     *   absolutos, de modo que el badge `version = total - index` es correcto
     *   incluso para filas cargadas tarde; las no cargadas se pintan como
     *   placeholders ligeros.
     * - `initialLoadSize = TAMANO_PAGINA`: Room local es rapido; una pagina
     *   inicial de 50 basta y hace el comportamiento predecible.
     * - Memoria acotada a la ventana (pageSize + prefetchDistance alrededor
     *   del scroll): con 10.000 versiones de una URL, la huella es la misma
     *   que con 50.
     */
    val versiones: Flow<PagingData<EscaneoEntity>> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    Pager(
                        config = PagingConfig(
                            pageSize = TAMANO_PAGINA,
                            initialLoadSize = TAMANO_PAGINA,
                            prefetchDistance = TAMANO_PAGINA,
                            enablePlaceholders = true
                        ),
                        pagingSourceFactory = { repoEscaneos.paginarReescaneos(url, id) }
                    ).flow
                } else {
                    emptyFlow()
                }
            }
            .cachedIn(viewModelScope)

    /**
     * Estado de la CABECERA (url, id, total) — el total viene del COUNT
     * indexado ([RepositorioEscaneos.observarTotalReescaneos]), separado de
     * la lista paginada: el "N análisis" y los numeros de version son
     * correctos siempre, independiente del avance de la paginacion.
     *
     * Patron S1 (etiquetado) preservado: al cambiar de URL A→B, el StateFlow
     * retiene el Cargado de A mientras el Pager/Flow de B emite — la UI
     * correlaciona la etiqueta y no pinta totales ajenos.
     */
    val estadoAnalisisAnteriores: StateFlow<EstadoAnalisisAnteriores> =
        combine(urlLimpiaFlow, idActualFlow) { url, id -> url to id }
            .flatMapLatest { (url, id) ->
                if (url != null && id != null) {
                    repoEscaneos.observarTotalReescaneos(url, id)
                        .map { total -> EstadoAnalisisAnteriores.Cargado(url, id, total) }
                } else {
                    flowOf(EstadoAnalisisAnteriores.Cargando)
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(3_000),
                initialValue = EstadoAnalisisAnteriores.Cargando
            )

    /**
     * F4.6 audit fix — eliminado `syncEnCurso` (StateFlow Eagerly propio):
     * duplicaba la observación perpetua del WorkInfo Flow que
     * [DatosTabsViewModel.syncEnCurso] ya mantiene activa desde el arranque
     * (dos suscripciones al mismo Flow de WorkManager por proceso). La
     * pantalla ahora consume `datosViewModel.syncEnCurso` directamente.
     */

    /**
     * Establece el escaneo "principal" cuya lista de analisis anteriores
     * se muestra. Llamado una sola vez al montar la pantalla (LaunchedEffect).
     *
     * **No dispara sync cloud** — el sync cloud (pull) solo ocurre al
     * reloguear/reinstalar (manejado por ContenedorApp / login), igual que
     * el Historial. Aqui solo setea las coordenadas para que
     * `flatMapLatest` subscribe el Flow de Room correcto.
     *
     * **Invariante**: el [idActual] recibido de DetalleUrlScreen ya es el
     * serverUUID real (no el clientUUID stale) porque
     * [DetalleUrlViewModel.cargarEscaneo] resuelve y re-subscribe el Flow
     * con el id correcto tras un reKey. No se necesita resolving aqui.
     *
     * Guard: si las coordenadas no cambiaron, no hace nada (evita re-setear
     * los StateFlows y disparar `flatMapLatest` innecesariamente).
     */
    fun cargarAnalisisAnteriores(urlLimpia: String, idActual: String) {
        if (urlLimpiaFlow.value == urlLimpia && idActualFlow.value == idActual) return
        urlLimpiaFlow.value = urlLimpia
        idActualFlow.value = idActual
    }
}
