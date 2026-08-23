package com.qrsecurity.detector.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.qrsecurity.detector.datos.local.dao.ConteosHistorial
import com.qrsecurity.detector.datos.local.dao.EscaneoConBloqueo
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * v10 — tamaño de página del historial (Paging 3 local sobre Room). La
 * memoria retenida es proporcional a la ventana (pageSize + prefetch
 * alrededor del scroll), no al total de URLs únicas ni a lo scrolleado.
 */
private const val TAMANO_PAGINA_HISTORIAL = 50

// M6 audit fix — @Immutable: emitidas una vez y nunca mutadas tras la
// emision (EscaneoEntity es estable — todos los campos val de tipos
// primitivos/String, mismo módulo). Sin la anotación, el parámetro es
// tratado como inestable por el compiler de Compose (sin Strong Skipping
// en Kotlin 1.9) y bloquea el skip de las pantallas en cada emisión.
@Immutable
sealed interface FilaHistorial {

    /**
     * Clave del grupo temporal al que pertenece la fila — compartida por
     * ambos tipos para que `insertSeparators` compare adyacentes sin
     * conocer el subtipo (null en cargas en curso).
     */
    val claveGrupo: String?

    /** Fila de escaneo, anotada con su clave de grupo de fecha (v10) y el
     *  flag de bloqueo calculado en SQL (F4.3-b — el badge de candado viaja
     *  con la fila; ya no se deriva un Set de URLs bloqueadas en el VM). */
    @Immutable
    data class Entrada(
        val escaneo: EscaneoEntity,
        val bloqueada: Boolean,
        override val claveGrupo: String
    ) : FilaHistorial

    /** Cabecera de grupo temporal ("Hoy", "Ayer", "dd/MM/yyyy"). */
    @Immutable
    data class Cabecera(val titulo: String) : FilaHistorial {
        override val claveGrupo: String get() = titulo
    }
}

/**
 * Clave (= título) del grupo temporal de una fila del historial.
 *
 * v10 — reemplaza a `agruparHistorialPorFecha` (que materializaba todos los
 * grupos en memoria): con Paging los headers se insertan via
 * `insertSeparators` comparando esta clave entre filas adyacentes, y se
 * computan contra [ahora] capturado al crear el Pager (el `diaActual`
 * alimentado por el ticker de medianoche lo recrea al cambiar de día —
 * fix P6 preservado).
 *
 * Jerarquía temporal (idéntica a la de la agrupación anterior): "Hoy"
 * (incluye fechas futuras por reloj atrasado — fix fechas-futuras), "Ayer",
 * "Anteayer" y a partir de ahí un grupo por día calendario con fecha
 * concreta "dd/MM/yyyy".
 */
internal fun claveGrupoHistorial(
    creadoEnMillis: Long,
    ahora: Long = System.currentTimeMillis()
): String = when (val dias = diasDeDiferencia(creadoEnMillis, ahora)) {
    in Long.MIN_VALUE..0L -> "Hoy"
    1L -> "Ayer"
    2L -> "Anteayer"
    else -> formatoFechaCorta(creadoEnMillis)
}

@Immutable
data class HistorialUiState(
    // V-1 fix: nullable para distinguir "cargando" (null) de "realmente cero" (0).
    // Antes eran Int = 0, y el valor inicial del StateFlow (HistorialUiState())
    // mostraba "0 escaneos" / "0% seguros" antes de los datos reales.
    val totalTodos: Int? = null,
    val totalSeguras: Int? = null,
    val totalSospechosas: Int? = null,
    val totalBloqueadas: Int? = null,
    val segurosPct: Int? = null
    // F4.3-b: urlsBloqueadasSet y totalUrlsBloqueadas ELIMINADOS — el badge
    // de candado ahora viaja en cada FilaHistorial.Entrada (EXISTS en SQL)
    // y el chip "N bloqueados" usa totalBloqueadas (mismo COUNT del chip de
    // filtro, sin el Flow de la tabla completa de urls_bloqueadas).
)

/**
 * Filtro del historial (M2 — auditoría frontend): antes un `String` tecleado
 * a mano en dos capas (chips de HistorialScreen + `filtrarHistorial`); un
 * typo en cualquiera producía un filtro silenciosamente muerto sin error de
 * compilación. El enum hace el contrato verificable en ambos lados.
 *
 * v10: el filtro se aplica en SQL ([RepositorioEscaneos.paginarHistorial])
 * — solo se materializan las filas que matchean.
 */
enum class FiltroHistorial {
    TODAS,
    SEGURAS,
    SOSPECHOSAS,
    BLOQUEADAS
}

/**
 * ViewModel compartido entre las tabs ESCANEAR, HISTORIAL y BLOQUEADAS.
 *
 * v10 — Paging 3 en el Historial: la lista es [historialPaging]
 * (`Flow<PagingData<FilaHistorial>>`, scroll infinito, filtros/búsqueda en
 * SQL, headers de fecha via insertSeparators). El botón "Cargar más" y la
 * ventana LIMIT creciente (M5) desaparecen — la memoria queda acotada a la
 * ventana de Paging aunque el usuario tenga 10.000 URLs únicas. Los
 * contadores siguen viniendo del COUNT indexado del DAO (M3), separados de
 * la lista paginada.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DatosTabsViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    private val _busquedaHistorial = MutableStateFlow("")
    val busquedaHistorial: StateFlow<String> = _busquedaHistorial.asStateFlow()

    private val _filtroHistorial = MutableStateFlow(FiltroHistorial.TODAS)
    val filtroHistorial: StateFlow<FiltroHistorial> = _filtroHistorial.asStateFlow()

    // V-2 fix: debounce SOLO sobre el texto de busqueda, no sobre todo el
    // combine (antes retardaba TODAS las emisiones 300ms). Agrupa keystrokes
    // del usuario; el resto de Flows fluyen sin demora.
    private val busquedaHistorialDebounced: StateFlow<String> =
        _busquedaHistorial
            .debounce(300)
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ── CONTADORES (audit fix M1): eliminados totalEscaneos/amenazas ──
    //
    // Eran dos StateFlow Eagerly corriendo para siempre desde el arranque
    // sin NINGUN consumidor en la UI (solo tests). Los contadores que la
    // UI pinta vienen de [historialUiState] (COUNT del DAO, M3).
    //
    // F4.3-b: el Flow eager de la TABLA COMPLETA de urls_bloqueadas
    // (`repoUrls.observarTodos()`) también fue eliminado — su único consumo
    // era derivar el Set de badges y el `.size` del chip; ambos salen ahora
    // del SQL del paging (EXISTS por fila) y del COUNT totalBloqueadas.

    /** Actualiza la búsqueda; el filtrado se aplica tras 300 ms sin tecleo. */
    fun actualizarBusquedaHistorial(busqueda: String) {
        _busquedaHistorial.value = busqueda
    }

    /** Actualiza el filtro activo del historial. */
    fun actualizarFiltroHistorial(filtro: FiltroHistorial) {
        _filtroHistorial.value = filtro
    }

    /**
     * Día calendario actual (epoch-days) — P6/v10: al cruzar medianoche
     * cambia el valor y `flatMapLatest` recrea el Pager del historial, de
     * modo que las claves "Hoy/Ayer" de las filas y los headers insertados
     * se recomputan contra el nuevo día.
     *
     * v10 fix (hang de tests): el refresco de medianoche vive en un
     * `Handler.postDelayed` al main looper y NO en un Flow infinito de
     * `delay()`s. La versión con ticker-corutina coleccionada Eagerly
     * colgaba `runTest` para siempre: su drain final (`advanceUntilIdle`)
     * avanzaba el reloj virtual a través de la cadena INFINITA de delays
     * del ticker (confirmado por thread dump). Con Handler, el production
     * behavior es idéntico (un callback por medianoche en el main looper)
     * y el scheduler de tests nunca lo ve.
     */
    private val handlerCambioDeDia = android.os.Handler(android.os.Looper.getMainLooper())
    private val _diaActual = MutableStateFlow(System.currentTimeMillis() / 86_400_000L)

    /** Programa el refresco del día para la próxima medianoche local (P6). */
    private fun programarCambioDeDia() {
        val ahora = ZonedDateTime.now(ZoneId.systemDefault())
        val proximaMedianoche = ahora.toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
        handlerCambioDeDia.postDelayed(
            {
                _diaActual.value = System.currentTimeMillis() / 86_400_000L
                programarCambioDeDia()
            },
            Duration.between(ahora, proximaMedianoche).toMillis()
        )
    }

    override fun onCleared() {
        handlerCambioDeDia.removeCallbacksAndMessages(null)
        super.onCleared()
    }

    init {
        programarCambioDeDia()
    }

    private val diaActual: StateFlow<Long> get() = _diaActual

    /**
     * v10 — Lista del Historial paginada (Paging 3 sobre Room, sin
     * RemoteMediator: la red nunca está atada a la navegación).
     *
     * - Cambios de filtro/búsqueda (debounced) o de día recrean el Pager
     *   (`flatMapLatest`) con los nuevos parámetros SQL.
     * - `cachedIn(viewModelScope)`: la ventana cargada sobrevive a
     *   re-entradas a la pantalla — al volver pinta al instante.
     * - Headers de fecha via [androidx.paging.PagingData.insertSeparators]:
     *   se inserta una [FilaHistorial.Cabecera] cuando cambia la clave de
     *   grupo entre filas adyacentes (incluida la primera fila).
     * - `enablePlaceholders = false`: los headers se deciden entre filas
     *   cargadas; no hay numeración dependiente del índice absoluto (a
     *   diferencia de Análisis anteriores).
     */
    val historialPaging: Flow<PagingData<FilaHistorial>> =
        combine(
            _filtroHistorial,
            busquedaHistorialDebounced,
            diaActual
        ) { filtro, busqueda, dia -> Triple(filtro, busqueda, dia) }
            .distinctUntilChanged()
            .flatMapLatest { (filtro, busqueda, dia) ->
                // `dia` (epoch-days) es solo el TRIGGER de recreación del
                // Pager al cruzar medianoche (P6); las claves de grupo se
                // computan contra millis REALES — un epoch-day (~20.714)
                // leído como millis dejaría todas las fechas del siglo
                // XXI en el grupo "Hoy" (bug de la primera iteración v10).
                val ahoraMillis = System.currentTimeMillis()
                Pager(
                    config = PagingConfig(
                        pageSize = TAMANO_PAGINA_HISTORIAL,
                        initialLoadSize = TAMANO_PAGINA_HISTORIAL,
                        prefetchDistance = TAMANO_PAGINA_HISTORIAL,
                        enablePlaceholders = false
                    ),
                    pagingSourceFactory = {
                        repoEscaneos.paginarHistorial(
                            nivelAlerta = when (filtro) {
                                FiltroHistorial.SEGURAS -> NivelAlerta.SEGURO.id
                                FiltroHistorial.SOSPECHOSAS -> NivelAlerta.SOSPECHOSO.id
                                FiltroHistorial.TODAS, FiltroHistorial.BLOQUEADAS -> null
                            },
                            soloBloqueadas = filtro == FiltroHistorial.BLOQUEADAS,
                            busqueda = busqueda
                        )
                    }
                ).flow.map { datos: PagingData<EscaneoConBloqueo> ->
                    // map + insertSeparators encadenados sobre PagingData
                    // (en Paging 3 la extension vive en PagingData, no en el
                    // Flow): cada pagina se transforma al vuelo.
                    datos
                        .map { fila ->
                            FilaHistorial.Entrada(
                                escaneo = fila.escaneo,
                                bloqueada = fila.bloqueada,
                                claveGrupo = claveGrupoHistorial(fila.escaneo.creadoEnMillis, ahoraMillis)
                            )
                        }
                        .insertSeparators { antes, despues ->
                            if (despues != null && antes?.claveGrupo != despues.claveGrupo) {
                                FilaHistorial.Cabecera(despues.claveGrupo ?: "")
                            } else {
                                null
                            }
                        }
                }
            }
            .cachedIn(viewModelScope)

    /**
     * Estado derivado del historial SIN la lista (v10): contadores del
     * COUNT indexado (M3 — independientes de la paginación).
     *
     * V-1 fix: initialValue con contadores null — la UI muestra "—" hasta
     * que Room emite, sin flash de "0 escaneos".
     *
     * F4.3-b: sin combine — la única fuente es [conteosTotales] (el Set de
     * URLs bloqueadas y su total salieron del estado: badge por fila en SQL,
     * chip "N bloqueados" = [HistorialUiState.totalBloqueadas]).
     */
    private val conteosTotales: StateFlow<ConteosHistorial?> =
        repoEscaneos.observarConteosHistorial()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                // RC2 fix: null = Room todavía no emitió. El valor inicial
                // anterior (ConteosHistorial(0,0,0,0)) hacía que el combine
                // de abajo emitiera totalTodos=0 ANTES de la primera emisión
                // real, pisando los null del fix V-1 y destellando
                // "0 escaneos / 0% seguros" en el arranque frío.
                null
            )

    val historialUiState: StateFlow<HistorialUiState> =
        conteosTotales
            .map { conteos ->
                if (conteos == null) {
                    // RC2 fix: contadores en null → la UI pinta "—" (V-1).
                    HistorialUiState()
                } else {
                    HistorialUiState(
                        totalTodos = conteos.totalTodos,
                        totalSeguras = conteos.totalSeguras,
                        totalSospechosas = conteos.totalSospechosas,
                        totalBloqueadas = conteos.totalBloqueadas,
                        segurosPct = if (conteos.totalTodos > 0) {
                            100 * conteos.totalSeguras / conteos.totalTodos
                        } else {
                            0
                        }
                    )
                }
            }
            // A3-b audit fix — `distinctUntilChanged` filtra re-emisiones
            // idénticas; se aplica ANTES de `flowOn(Default)` para
            // que la comparación corra en Default, no en el hilo del colector.
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, HistorialUiState())

    // ── Fix #3: Estado de sincronizacion ──
    // syncEnCurso emite true mientras el SyncWorker esta ENQUEUED o RUNNING.
    // La UI lo usa para mostrar skeleton/loading en el Historial en lugar de
    // "Aun no hay escaneos" cuando Room esta vacio y el PULL inicial esta corriendo.
    val syncEnCurso: StateFlow<Boolean> =
        mediadorSync.observarSyncEnCurso()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
