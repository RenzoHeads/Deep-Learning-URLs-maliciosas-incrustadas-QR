package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.sesion.LogoutCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla de Ajustes.
 *
 * Audit fix M4: se eliminaron `nombreUsuario`/`correo` — la UI nunca los
 * leia (AjustesScreen solo consume `syncEnCurso`) y forzaban inyectar
 * [com.qrsecurity.detector.sesion.SesionUsuario] solo para alimentarlos.
 */
data class AjustesUiState(
    val syncEnCurso: Boolean = false
)

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 *
 * Audit fix M3: se elimino `DispararSync` — nunca se despacho desde la UI.
 */
sealed interface AjustesAction {
    data object CerrarSesion : AjustesAction
}

/**
 * Eventos one-shot emitidos por el VM hacia la UI (patron Channel + receiveAsFlow).
 *
 * - [LogoutCompletado]: el logout (limpieza completa via [LogoutCoordinator])
 *   termino; la UI puede navegar al login con seguridad de que DB, cursores,
 *   prefs y SyncWorker quedaron en estado consistente.
 */
sealed interface AjustesEvento {
    data object LogoutCompletado : AjustesEvento
}

/**
 * ViewModel para la pantalla de Ajustes.
 *
 * Muestra el estado de sync (desde [MediadorSincronizacion]) y permite
 * cerrar sesion (limpieza completa via [LogoutCoordinator]).
 */
@HiltViewModel
class AjustesViewModel @Inject constructor(
    private val mediadorSincronizacion: MediadorSincronizacion,
    private val logoutCoordinator: LogoutCoordinator
) : ViewModel() {

    private val _eventos = Channel<AjustesEvento>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

    /**
     * Estado de UI: indicador reactivo de sync en curso (solo durante el
     * sync inicial — ver [MediadorSincronizacion.observarSyncEnCurso]).
     *
     * Audit fix: WhileSubscribed en vez de Eagerly — la coleccion solo
     * corre mientras Ajustes esta en primer plano (la pantalla que la
     * consume), no durante toda la vida del VM.
     */
    val uiState: StateFlow<AjustesUiState> =
        mediadorSincronizacion.observarSyncEnCurso()
            .map { syncEnCurso -> AjustesUiState(syncEnCurso = syncEnCurso) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = AjustesUiState()
            )

    fun onAction(action: AjustesAction) {
        when (action) {
            AjustesAction.CerrarSesion -> cerrarSesion()
        }
    }

    private var cerrandoSesion = false

    private fun cerrarSesion() {
        // U7: logout() tarda hasta ~3s (espera de WorkManager + clearAllTables);
        // sin guard, un segundo tap lanzaba un logout() CONCURRENTE y dos
        // eventos LogoutCompletado navegaban a LOGIN dos veces (pila
        // [LOGIN, LOGIN] — el boton back "no hacia nada").
        if (cerrandoSesion) return
        cerrandoSesion = true
        viewModelScope.launch {
            // LogoutCoordinator.logout() hace la limpieza COMPLETA:
            // - sesionUsuario.cerrarSesion() (borra token)
            // - clearAllTables() (vacia Room, incluida sync_state con cursores)
            // - reset PREFS_SYNC.KEY_INITIAL_SYNC_COMPLETED = false
            // - reset PREFS_SYNC.KEY_ULTIMO_SYNC = 0L
            // - cancela SyncWorker encolado
            // - limpia cache del Pipeline
            // Esto garantiza que el siguiente login dispare sync incremental
            // completo (DB vacia + cursor reseteado -> backend envia todo el
            // historial). Antes solo se borraba el token, dejando cursores
            // avanzados que hacian que el backend respondiera "no hay cambios".
            try {
                logoutCoordinator.logout()
            } catch (e: Exception) {
                // Si el logout falla (p.ej. SQLiteException por dos
                // clearAllTables racing), rearmar el guard para permitir
                // reintentar; sin evento de navegacion — la sesion pudo no
                // limpiarse y navegar a LOGIN dejaria estado inconsistente.
                cerrandoSesion = false
                android.util.Log.e("AjustesViewModel", "logout fallo", e)
                return@launch
            }
            _eventos.send(AjustesEvento.LogoutCompletado)
        }
    }
}
