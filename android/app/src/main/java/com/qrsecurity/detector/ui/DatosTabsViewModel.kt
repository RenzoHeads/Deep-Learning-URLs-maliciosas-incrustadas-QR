package com.qrsecurity.detector.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/**
 * M5 audit fix — página inicial del historial y paso incremental de
 * "Cargar más". Con ≤ LIMITE URLs únicas el comportamiento es idéntico al
 * de antes (lista completa); solo usuarios con historial masivo pagan el
 * paso extra, acotando memoria retenida y recomputo por emisión.
 */
private const val LIMITE_HISTORIAL_INICIAL = 500
private const val PASO_CARGAR_MAS_HISTORIAL = 500

// M6 audit fix — @Immutable: estas data classes se emiten una vez y nunca
// se mutan tras la emisión (las listas son read-only para la UI) y
// EscaneoEntity es estable (todos los campos val de tipos primitivos/String,
// mismo módulo). Sin la anotación, el parámetro `List` es tratado como
// inestable por el compiler de Compose (sin Strong Skipping en Kotlin 1.9)
// y bloquea el skip de PantallaHistorial en cada emisión del StateFlow.
@Immutable
data class GrupoHistorial(
    val titulo: String,
    val escaneos: List<EscaneoEntity>
)

@Immutable
data class HistorialUiState(
    val grupos: List<GrupoHistorial> = emptyList(),
    // V-1 fix: nullable para distinguir "cargando" (null) de "realmente cero" (0).
    // Antes eran Int = 0, y el valor inicial del StateFlow (HistorialUiState())
    // mostraba "0 escaneos" / "0% seguros" antes de los datos reales.
    val totalTodos: Int? = null,
    val totalSeguras: Int? = null,
    val totalSospechosas: Int? = null,
    val totalBloqueadas: Int? = null,
    val segurosPct: Int? = null,
    // F4.3 audit fix — set de URLs bloqueadas derivado UNA vez en el combine.
    // Antes la pantalla lo recomputaba con `remember(urlsBloqueadas)
    // { map { it.url }.toSet() }` (hashing de la lista completa por emisión)
    // y colectaba `urlsBloqueadas` aparte solo para `.size`. Read-only tras
    // la emisión → seguro bajo @Immutable.
    val urlsBloqueadasSet: Set<String> = emptySet(),
    // F4.3: total de filas de la tabla urls_bloqueadas — el mismo valor que
    // la pantalla leía de `urlsBloqueadas.size` para el chip "N bloqueados".
    val totalUrlsBloqueadas: Int = 0
)

internal fun filtrarHistorial(
    historial: List<EscaneoEntity>,
    filtro: String,
    busqueda: String,
    bloqueadasUrls: Set<String>
): List<EscaneoEntity> {
    // Audit fix D2: comparar contra NivelAlerta (single source of truth)
    // en vez de literales "SEGURO"/"SOSPECHOSO" sueltos.
    val porFiltro = when (filtro) {
        "SEGURAS" -> historial.filter { it.nivelAlerta == NivelAlerta.SEGURO.id }
        "SOSPECHOSAS" -> historial.filter { it.nivelAlerta == NivelAlerta.SOSPECHOSO.id }
        "BLOQUEADAS" -> historial.filter { it.urlLimpia in bloqueadasUrls }
        else -> historial
    }
    return if (busqueda.isBlank()) {
        porFiltro
    } else {
        // Audit fix P9: la busqueda matchea tambien la URL original (la que
        // el usuario vio en el QR), no solo la limpia.
        porFiltro.filter {
            it.urlLimpia.contains(busqueda, ignoreCase = true) ||
                it.urlOriginal.contains(busqueda, ignoreCase = true)
        }
    }
}

internal fun agruparHistorialPorFecha(
    escaneos: List<EscaneoEntity>,
    ahora: Long = System.currentTimeMillis()
): List<GrupoHistorial> {
    val ordenados = escaneos.sortedByDescending { it.creadoEnMillis }
    // M2 audit fix — single-pass. Antes hacía 4 pasadas (`filter` x3 +
    // `filter`+`groupBy`) con 4 llamadas a `diasDeDiferencia` por elemento.
    // Ahora una sola pasada con 1 `diasDeDiferencia` por elemento → O(n)
    // post-sort en vez de O(4n). Comportamiento idéntico:
    //   - `hoy`    = dias <= 0 (incluye fechas futuras por reloj atrasado)
    //   - `ayer`   = dias == 1
    //   - `anteayer` = dias == 2
    //   - `anteriores` = dias >= 3, agrupados por `formatoFechaCorta`.
    // LinkedHashMap preserva orden de inserción = DESC (ordenados está DESC
    // por creadoEnMillis), así los grupos más recientes van primero —
    // equivalente al `groupBy` sobre lista DESC original.
    val hoy = mutableListOf<EscaneoEntity>()
    val ayer = mutableListOf<EscaneoEntity>()
    val anteayer = mutableListOf<EscaneoEntity>()
    val anteriores = LinkedHashMap<String, MutableList<EscaneoEntity>>()
    for (escaneo in ordenados) {
        val dias = diasDeDiferencia(escaneo.creadoEnMillis, ahora)
        when {
            dias <= 0L -> hoy.add(escaneo)
            dias == 1L -> ayer.add(escaneo)
            dias == 2L -> anteayer.add(escaneo)
            else -> anteriores.getOrPut(formatoFechaCorta(escaneo.creadoEnMillis)) { mutableListOf() }
                .add(escaneo)
        }
    }
    return buildList {
        if (hoy.isNotEmpty()) add(GrupoHistorial("Hoy", hoy))
        if (ayer.isNotEmpty()) add(GrupoHistorial("Ayer", ayer))
        if (anteayer.isNotEmpty()) add(GrupoHistorial("Anteayer", anteayer))
        anteriores.forEach { (fecha, escaneosDelDia) ->
            add(GrupoHistorial(fecha, escaneosDelDia))
        }
    }
}

/**
 * ViewModel compartido entre las tabs ESCANEAR, HISTORIAL y BLOQUEADAS.
 *
 * Hilt: migrado de AndroidViewModel a @HiltViewModel con @Inject constructor
 * — los repositorios se inyectan via Hilt (RepositoryModule) en lugar de
 * construirse manualmente con BaseDatosSeguridad.get() + ClienteBackend().
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class DatosTabsViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val repoUrls: RepositorioUrlsBloqueadas,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    // ── HISTORIAL: Flow persistente con la ventana actual de la lista ──
    //
    // V-2 fix: Eagerly (activo desde la creacion del VM en NavGuardian)
    // en vez de WhileSubscribed(3_000). Antes, al dejar HistorialScreen
    // por >3s el Flow se detenia; al volver reiniciaba desde emptyList()
    // hasta que Room re-emitia — flash de lista vacia en cada re-entrada.
    // Con Eagerly el Flow colecta continuamente (como urlsBloqueadas), y
    // Room emite la lista cacheada antes de que la UI llegue a pintar. La
    // memoria retenida es real pero acotada: una referencia a la
    // List<EscaneoEntity> ya materializada por Room en su cache interno;
    // el Flow solo mantiene la referencia, no duplica los datos.
    //
    // Room controla el dispatcher de sus consultas; el filtrado y la
    // agrupacion se aplican despues en [historialUiState] sobre Default.
    //
    // M5 audit fix: la consulta se acota con `LIMIT _limiteHistorial`
    // (flatMapLatest re-suscribe el Flow del DAO cuando el límite cambia).
    // Con ≤ LIMITE_HISTORIAL_INICIAL URLs únicas el resultado y los
    // contadores derivados son idénticos a la lista completa; más allá,
    // `cargarMasHistorial()` amplía la ventana y [hayMasHistorial] expone
    // si puede haber más filas (size == limite). La lista es DESC — la
    // fila recién escaneada siempre está en la ventana.
    private val _limiteHistorial = MutableStateFlow(LIMITE_HISTORIAL_INICIAL)

    val historialTodos: StateFlow<List<EscaneoEntity>> =
        _limiteHistorial
            .flatMapLatest { limite -> repoEscaneos.observarTodos(limite) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * true cuando la ventana cargada alcanzó el límite — puede haber más
     * URLs más allá (M5). La UI muestra el botón "Cargar más" con esto.
     * Si el tamaño coincide por casualidad con el total exacto, el botón
     * aparece una vez y desaparece tras el siguiente "cargar más".
     */
    val hayMasHistorial: StateFlow<Boolean> =
        combine(historialTodos, _limiteHistorial) { lista, limite ->
            lista.size >= limite
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Amplía la ventana del historial en [PASO_CARGAR_MAS_HISTORIAL] (M5). */
    fun cargarMasHistorial() {
        _limiteHistorial.value += PASO_CARGAR_MAS_HISTORIAL
    }

    private val _busquedaHistorial = MutableStateFlow("")
    val busquedaHistorial: StateFlow<String> = _busquedaHistorial.asStateFlow()

    private val _filtroHistorial = MutableStateFlow("TODAS")
    val filtroHistorial: StateFlow<String> = _filtroHistorial.asStateFlow()

    // V-2 fix: debounce SOLO sobre el texto de busqueda, no sobre todo el
    // combine. Antes, debounce(300) encadenado al final de historialUiState
    // retardaba TODAS las emisiones — incluyendo datos de Room al re-entrar
    // y updates de sync — en 300ms. Ahora el debounce aislado agrupa
    // keystrokes del usuario; los Flows de datos (historialTodos,
    // urlsBloqueadas) fluyen sin demora a traves del combine.
    private val busquedaHistorialDebounced: StateFlow<String> =
        _busquedaHistorial
            .debounce(300)
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ── CONTADORES (audit fix M1): eliminados totalEscaneos/amenazas ──
    //
    // Eran dos StateFlow Eagerly corriendo para siempre desde el arranque
    // sin NINGUN consumidor en la UI (solo tests). Los contadores que la
    // UI pinta se derivan en [historialUiState] desde la lista ya cargada.

    // ── BLOQUEADAS: Flow eager para eliminar parpadeo de contador ──
    // Bug fix: antes usaba WhileSubscribed(5_000) con emptyList() como
    // initial value. Al reabrir Bloqueadas tras >5s sin suscriptores, la UI
    // mostraba "No hay URLs bloqueadas" por ~1 frame antes de que Room
    // emitira la lista real. Ahora con Eagerly el Flow colecta desde el
    // momento en que se crea el ViewModel, y Room emite la lista cacheada
    // antes de que BloqueadasScreen llegue a pintar.
    //
    val urlsBloqueadas: StateFlow<List<UrlBloqueadaEntity>> =
        repoUrls.observarTodos()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Actualiza la búsqueda; el filtrado se aplica tras 300 ms sin tecleo. */
    fun actualizarBusquedaHistorial(busqueda: String) {
        _busquedaHistorial.value = busqueda
    }

    /** Actualiza el filtro activo del historial. */
    fun actualizarFiltroHistorial(filtro: String) {
        _filtroHistorial.value = filtro
    }

    /**
     * Ticker que emite al pasar cada medianoche local (audit fix P6): los
     * grupos "Hoy/Ayer" se computan contra `System.currentTimeMillis()`,
     * pero el combine solo re-emite cuando cambian Room/filtro/busqueda.
     * Sin este ticker, al pasar medianoche con la app abia los headers
     * quedaban stale hasta la proxima mutacion. El valor emitido es
     * irrelevante — solo fuerza el recomputo de [historialUiState].
     */
    private val medianocheTicker: kotlinx.coroutines.flow.Flow<Long> = flow {
        // P6 fix: combine no emite hasta que TODAS las fuentes emiten al menos
        // una vez; sin esta emision inicial inmediata, el primer valor del
        // ticker llegaba recien en la proxima medianoche y [historialUiState]
        // quedaba congelado en su valor inicial (historial "vacio" en la UI).
        emit(System.currentTimeMillis())
        while (true) {
            val ahora = ZonedDateTime.now(ZoneId.systemDefault())
            val proximaMedianoche = ahora.toLocalDate().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
            delay(Duration.between(ahora, proximaMedianoche).toMillis())
            emit(System.currentTimeMillis())
        }
    }

    /**
     * Estado derivado del historial. La busqueda, el filtro y la agrupacion
     * se ejecutan fuera del hilo principal para que la recomposicion solo
     * reciba el resultado listo para pintar.
     *
     * V-1+V-2 fix: antes usaba WhileSubscribed(3_000) con HistorialUiState()
     * (todos los contadores en 0) como valor inicial + debounce(300) sobre
     * TODO el combine. Eso causaba:
     *  1. Flash de "0 escaneos", "0 bloqueados", "0% seguros" antes de los
     *     datos reales (HistorialUiState() = zeros indistinguibles de
     *     "realmente cero").
     *  2. Al re-entrar al historial tras >3s, el Flow se reiniciaba desde
     *     el valor inicial (zeros) + 300ms de debounce adicional.
     *
     * Ahora: Eagerly (activo desde la creacion del VM, como urlsBloqueadas),
     * initialValue = HistorialUiState() (todos los contadores en null → la
     * UI muestra "—" en vez de "0"). El debounce se aplica solo a
     * busquedaHistorialDebounced para que Room data fluya sin demora.
     */
    val historialUiState: StateFlow<HistorialUiState> = combine(
        historialTodos,
        urlsBloqueadas,
        _filtroHistorial,
        busquedaHistorialDebounced,
        medianocheTicker
    ) { historial, urlsBloqueadas, filtro, busqueda, _ ->
        val bloqueadasUrls = urlsBloqueadas.mapTo(hashSetOf()) { it.url }
        val filtradas = filtrarHistorial(historial, filtro, busqueda, bloqueadasUrls)
        val totalTodos = historial.size
        val totalSeguras = historial.count { it.nivelAlerta == NivelAlerta.SEGURO.id }
        val totalSospechosas = historial.count { it.nivelAlerta == NivelAlerta.SOSPECHOSO.id }
        val totalBloqueadas = historial.count { it.urlLimpia in bloqueadasUrls }
        HistorialUiState(
            grupos = agruparHistorialPorFecha(filtradas),
            totalTodos = totalTodos,
            totalSeguras = totalSeguras,
            totalSospechosas = totalSospechosas,
            totalBloqueadas = totalBloqueadas,
            segurosPct = if (totalTodos > 0) 100 * totalSeguras / totalTodos else 0,
            // F4.3: derivados de urlsBloqueadas una sola vez (antes la
            // pantalla duplicaba el toSet y colectaba el flow aparte).
            urlsBloqueadasSet = bloqueadasUrls,
            totalUrlsBloqueadas = urlsBloqueadas.size
        )
    }
        // A3-b audit fix — `distinctUntilChanged` filtra re-emisiones
        // idénticas del combine. Room puede re-emitir `historialTodos`
        // con el mismo contenido (referencia distinta) cuando una tabla
        // ajena cambia o el observador sale y re-entra. Sin este filtro
        // la UI recomponía sin que cambiara ningún dato visible.
        // historialUiState final es un data class → equals compara todos
        // los campos (grupos, contadores, pct). El coste del equals es
        // O(grupos) que es diminuto frente a una recomposición.
        // Se aplica ANTES de `flowOn(Default)` para que la comparación
        // corra en Default, no en el hilo del colector.
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistorialUiState())

    // ── Fix #3: Estado de sincronizacion ──
    // syncEnCurso emite true mientras el SyncWorker esta ENQUEUED o RUNNING.
    // La UI lo usa para mostrar skeleton/loading en el Historial en lugar de
    // "Aun no hay escaneos" cuando Room esta vacio y el PULL inicial esta corriendo.
    // Eagerly: el flujo de WorkInfo debe estar activo desde el arranque para
    // que syncEnCurso refleje inmediatamente el estado real del worker sin
    // depender de que un suscriptor este presente.
    val syncEnCurso: StateFlow<Boolean> =
        mediadorSync.observarSyncEnCurso()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
