package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class GrupoHistorial(
    val titulo: String,
    val escaneos: List<EscaneoEntity>
)

data class HistorialUiState(
    val grupos: List<GrupoHistorial> = emptyList(),
    // V-1 fix: nullable para distinguir "cargando" (null) de "realmente cero" (0).
    // Antes eran Int = 0, y el valor inicial del StateFlow (HistorialUiState())
    // mostraba "0 escaneos" / "0% seguros" antes de los datos reales.
    val totalTodos: Int? = null,
    val totalSeguras: Int? = null,
    val totalSospechosas: Int? = null,
    val totalBloqueadas: Int? = null,
    val segurosPct: Int? = null
)

internal fun filtrarHistorial(
    historial: List<EscaneoEntity>,
    filtro: String,
    busqueda: String,
    bloqueadasUrls: Set<String>
): List<EscaneoEntity> {
    val porFiltro = when (filtro) {
        "SEGURAS" -> historial.filter { it.nivelAlerta == "SEGURO" }
        "SOSPECHOSAS" -> historial.filter { it.nivelAlerta == "SOSPECHOSO" }
        "BLOQUEADAS" -> historial.filter { it.urlLimpia in bloqueadasUrls }
        else -> historial
    }
    return if (busqueda.isBlank()) {
        porFiltro
    } else {
        porFiltro.filter { it.urlLimpia.contains(busqueda, ignoreCase = true) }
    }
}

internal fun agruparHistorialPorFecha(
    escaneos: List<EscaneoEntity>,
    ahora: Long = System.currentTimeMillis()
): List<GrupoHistorial> {
    val ordenados = escaneos.sortedByDescending { it.creadoEnMillis }
    val hoy = ordenados.filter { diasDeDiferenciaHistorial(it.creadoEnMillis, ahora) == 0L }
    val ayer = ordenados.filter { diasDeDiferenciaHistorial(it.creadoEnMillis, ahora) == 1L }
    val anteriores = ordenados.filter { diasDeDiferenciaHistorial(it.creadoEnMillis, ahora) >= 2L }
    return buildList {
        if (hoy.isNotEmpty()) add(GrupoHistorial("Hoy", hoy))
        if (ayer.isNotEmpty()) add(GrupoHistorial("Ayer", ayer))
        if (anteriores.isNotEmpty()) add(GrupoHistorial("Anteriores", anteriores))
    }
}

internal fun diasDeDiferenciaHistorial(millis: Long, ahora: Long = System.currentTimeMillis()): Long {
    val calAhora = Calendar.getInstance().apply {
        timeInMillis = ahora
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val calEnt = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return TimeUnit.MILLISECONDS.toDays(calAhora.timeInMillis - calEnt.timeInMillis)
}

/**
 * ViewModel compartido entre las tabs ESCANEAR, HISTORIAL y BLOQUEADAS.
 *
 * Hilt: migrado de AndroidViewModel a @HiltViewModel con @Inject constructor
 * — los repositorios se inyectan via Hilt (RepositoryModule) en lugar de
 * construirse manualmente con BaseDatosSeguridad.get() + ClienteBackend().
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class DatosTabsViewModel @Inject constructor(
    private val repoEscaneos: RepositorioEscaneos,
    private val repoUrls: RepositorioUrlsBloqueadas,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    // ── HISTORIAL: Flow persistente con la lista completa ──
    //
    // V-2 fix: Eagerly (activo desde la creacion del VM en NavGuardian)
    // en vez de WhileSubscribed(3_000). Antes, al dejar HistorialScreen
    // por >3s el Flow se detenia; al volver reiniciaba desde emptyList()
    // hasta que Room re-emitia — flash de lista vacia en cada re-entrada.
    // Con Eagerly el Flow colecta continuamente (como totalEscaneos y
    // urlsBloqueadas), y Room emite la lista cacheada antes de que la
    // UI llegue a pintar. La memoria retenida es real pero acotada: una
    // referencia a la List<EscaneoEntity> ya materializada por Room en
    // su cache interno; el Flow solo mantiene la referencia, no duplica
    // los datos.
    //
    // Room controla el dispatcher de sus consultas; el filtrado y la
    // agrupacion se aplican despues en [historialUiState] sobre Default.
    val historialTodos: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarTodos()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    // ── CONTADORES: Eagerly + null initial para eliminar parpadeo de "0" ──
    //
    // Bug 3 fix (parpadeo "0"): antes usaban StateFlow<Int> con initialValue=0.
    // El problema no era solo WhileSubscribed (que ya se arreglo con Eagerly)
    // sino que el initialValue=0 era indistinguible del valor real "0 escaneos".
    // Mostrar "0" mientras se carga elconteo real (e.g. 33) generaba un
    // parpadeo visible de "0" → "33" en la UI.
    //
    // Ahora usamos StateFlow<Int?> con initialValue=null. La UI distingue:
    //  - null  → "cargando" → mostrar placeholder (guion o skeleton)
    //  - 0     → "realmente cero" → mostrar "0"
    //  - N > 0 → mostrar "N"
    //
    // Eagerly sigue activo: el Flow colecta desde que el ViewModel se crea
    // (al montar NavGuardian), asi que Room emite el conteo cacheado en <1ms.
    // El `null` solo dura ese lapso inicial o si Room tarda excepcionalmente.
    //
    // Bug 3 fix (URLs unicas): los DAOs exponen observarTotalUnicos /
    // observarAmenazasUnicas que cuentan DISTINCT urlLimpia (no filas
    // individuales), asi un reescaneo de una URL ya contada no incrementa
    // el contador.

    val totalEscaneos: StateFlow<Int?> =
        repoEscaneos.observarTotal()
            .map { it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val amenazas: StateFlow<Int?> =
        repoEscaneos.observarAmenazas()
            .map { it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
     * Ahora: Eagerly (activo desde la creacion del VM, como totalEscaneos
     * y urlsBloqueadas), initialValue = HistorialUiState() (todos los
     * contadores en null → la UI muestra "—" en vez de "0"). El debounce
     * se aplica solo a busquedaHistorialDebounced para que Room data fluya
     * sin demora.
     */
    val historialUiState: StateFlow<HistorialUiState> = combine(
        historialTodos,
        urlsBloqueadas,
        _filtroHistorial,
        busquedaHistorialDebounced
    ) { historial, urlsBloqueadas, filtro, busqueda ->
        val bloqueadasUrls = urlsBloqueadas.mapTo(hashSetOf()) { it.url }
        val filtradas = filtrarHistorial(historial, filtro, busqueda, bloqueadasUrls)
        val totalTodos = historial.size
        val totalSeguras = historial.count { it.nivelAlerta == "SEGURO" }
        val totalSospechosas = historial.count { it.nivelAlerta == "SOSPECHOSO" }
        val totalBloqueadas = historial.count { it.urlLimpia in bloqueadasUrls }
        HistorialUiState(
            grupos = agruparHistorialPorFecha(filtradas),
            totalTodos = totalTodos,
            totalSeguras = totalSeguras,
            totalSospechosas = totalSospechosas,
            totalBloqueadas = totalBloqueadas,
            segurosPct = if (totalTodos > 0) 100 * totalSeguras / totalTodos else 0
        )
    }
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
