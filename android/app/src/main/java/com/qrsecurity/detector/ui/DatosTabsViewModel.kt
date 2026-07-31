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

    val totalEscaneos: StateFlow<Int> =
        repoEscaneos.observarTotal()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val amenazas: StateFlow<Int> =
        repoEscaneos.observarAmenazas()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val ultimos7Dias: StateFlow<Int> =
        repoEscaneos.observarUltimos7Dias()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ── BLOQUEADAS: Flows persistentes ──

    val urlsBloqueadas: StateFlow<List<UrlBloqueadaEntity>> =
        repoUrls.observarTodos()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
