package com.qrsecurity.detector.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.local.BaseDatosSeguridad
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.datos.local.entidades.UrlBloqueadaEntity
import com.qrsecurity.detector.datos.repositorios.RepositorioEscaneos
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json

/**
 * ViewModel compartido entre las tabs ESCANEAR, HISTORIAL y BLOQUEADAS.
 *
 * Problema: con NavHost + popUpTo(ESCANEAR) { saveState = true }, cada
 * vez que el usuario cambia de tab, el composable anterior se destruye
 * y su coleccion de Flows de Room se cancela. Al volver a la tab,
 * collectAsStateWithLifecycle vuelve al initialValue (null) y muestra
 * el spinner de carga mientras el Flow re-emite. Esto causa el lag
 * perceptible ("se recarga la pagina completa") al navegar entre tabs.
 *
 * Solucion: hospedar los Flows de Room en un AndroidViewModel scoped al
 * NavHost (activity scoped). Los Flows se iniciaran una sola vez y
 * permanecen activos mientras la activity este viva. Cuando el usuario
 * vuelve a una tab, el StateFlow ya tiene el ultimo valor emitido — no
 * hay spinner de carga, no hay recomposicion de datos, la UI aparece
 * instantaneamente.
 *
 * El [stateIn] con [SharingStarted.Lazily] inicia la coleccion solo cuando
 * el primer suscriptor se conecta, y nunca la cancela (mientras el
 * ViewModel este vivo) — ideal para tabs que se intercambian frecuentemente.
 */
class DatosTabsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = BaseDatosSeguridad.get(app)
    private val backend = ClienteBackend(ClienteBackend.BASE_POR_DEFECTO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val repoEscaneos = RepositorioEscaneos(db, backend, json)
    val repoUrls = RepositorioUrlsBloqueadas(db, backend, json)

    // ── HISTORIAL: Flows persistentes ──
    // stateIn(Lazily) arranca con el primer suscriptor y mantiene la
    // coleccion viva hasta que el ViewModel se destruye (activity destroy).
    // initialValue = emptyList() — nunca null, nunca spinner.

    val historialTodos: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarTodos()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val historialSeguros: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarSeguros()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val historialMaliciosos: StateFlow<List<EscaneoEntity>> =
        repoEscaneos.observarMaliciosos()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalEscaneos: StateFlow<Int> =
        repoEscaneos.observarTotal()
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val amenazas: StateFlow<Int> =
        repoEscaneos.observarAmenazas()
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val ultimos7Dias: StateFlow<Int> =
        repoEscaneos.observarUltimos7Dias()
            .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    // ── BLOQUEADAS: Flows persistentes ──

    val urlsBloqueadas: StateFlow<List<UrlBloqueadaEntity>> =
        repoUrls.observarTodos()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
                DatosTabsViewModel(app)
            }
        }
    }
}
