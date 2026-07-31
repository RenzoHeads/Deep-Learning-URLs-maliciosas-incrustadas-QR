package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel compartido entre las tabs ESCANEAR, HISTORIAL y BLOQUEADAS.
 *
 * Hilt: migrado de AndroidViewModel a @HiltViewModel con @Inject constructor
 * — los repositorios se inyectan via Hilt (RepositoryModule) en lugar de
 * construirse manualmente con BaseDatosSeguridad.get() + ClienteBackend().
 */
@HiltViewModel
class DatosTabsViewModel @Inject constructor(
    val repoEscaneos: RepositorioEscaneos,
    val repoUrls: RepositorioUrlsBloqueadas,
    val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    // ── HISTORIAL: Flows persistentes ──
    // stateIn(WhileSubscribed(5_000)) arranca con el primer suscriptor y
    // mantiene la coleccion viva mientras hay suscriptores activos; cancela
    // el upstream 5s despues del ultimo suscriptor (patron NiA estandar).
    // initialValue = emptyList() — nunca null, nunca spinner.

    val historialTodos: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarTodos()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historialSeguros: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarSeguros()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val historialMaliciosos: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarMaliciosos()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── CONTADORES: Eagerly para eliminar parpadeo de "0" ──
    // Bug fix: antes usaban WhileSubscribed(5_000) con initialValue=0. Cuando
    // el usuario abria Historial/Bloqueadas tras >5s sin suscriptores, el
    // StateFlow re-arrancaba y mostraba "0" por ~1 frame hasta que Room
    // emitia el valor real (e.g. 33). Ahora con Eagerly el Flow colecta
    // desde el momento en que se crea el ViewModel (al montar NavGuardian,
    // antes de que cualquier tab sea visible). Room emite el conteo cacheado
    // desde memoria en <1ms, por lo que cuando HistorialScreen llega a
    // pintar, el StateFlow ya tiene el valor real. Costo: 6 Flows de Int
    // activos permanentemente — insignificante (queries COUNT(*) ligereas).
    val totalEscaneos: StateFlow<Int> =
        repoEscaneos.observarTotal()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val amenazas: StateFlow<Int> =
        repoEscaneos.observarAmenazas()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val ultimos7Dias: StateFlow<Int> =
        repoEscaneos.observarUltimos7Dias()
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ── BLOQUEADAS: Flow eager para eliminar parpadeo de contador ──
    // Bug fix: antes usaba WhileSubscribed(5_000) con emptyList() como
    // initial value. Al reabrir Bloqueadas tras >5s sin suscriptores, la UI
    // mostraba "No hay URLs bloqueadas" por ~1 frame antes de que Room
    // emitira la lista real. Ahora con Eagerly el Flow colecta desde el
    // momento en que se crea el ViewModel, y Room emite la lista cacheada
    // antes de que BloqueadasScreen llegue a pintar.
    val urlsBloqueadas: StateFlow<List<UrlBloqueadaEntity>> =
        repoUrls.observarTodos()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
