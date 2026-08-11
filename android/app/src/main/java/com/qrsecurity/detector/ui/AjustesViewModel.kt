package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.sesion.LogoutCoordinator
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla de Ajustes — patron NowInAndroid.
 *
 * F2.6: nueva pantalla que reemplaza a la antigua "Acerca de" para el
 * cierre de sesion, y anade informacion del usuario + estado de sync.
 */
data class AjustesUiState(
    val nombreUsuario: String? = null,
    val correo: String? = null,
    val syncEnCurso: Boolean = false
)

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface AjustesAction {
    data object CerrarSesion : AjustesAction
    data object DispararSync : AjustesAction
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
 * F2.6: nueva. Muesta la informacion del usuario (nombre + correo desde
 * [SesionUsuario]) y el estado de sync (desde [MediadorSincronizacion]).
 * Permite cerrar sesion y disparar sync manual.
 *
 * Adaptacion vs plan: el plan usaba `mediadorSincronizacion.syncEnCurso`
 * (propiedad) — el metodo real es `observarSyncEnCurso()` (funcion que
 * retorna un Flow). Tambien, el plan asumia que [SesionUsuario] tenia
 * `obtenerNombreUsuario()` y `obtenerCorreo()` — se anadieron en F2.6.
 */
@HiltViewModel
class AjustesViewModel @Inject constructor(
    private val sesionUsuario: SesionUsuario,
    private val mediadorSincronizacion: MediadorSincronizacion,
    private val logoutCoordinator: LogoutCoordinator
) : ViewModel() {

    private val _infoUsuario = MutableStateFlow(AjustesUiState())

    private val _eventos = Channel<AjustesEvento>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

    /**
     * Estado de UI combinado: info del usuario (snapshot) + sync (reactivo).
     *
     * La info del usuario se lee una sola vez en init (no es reactiva —
     * no cambia durante la vida del VM). El estado de sync si es reactivo
     * via `observarSyncEnCurso()`.
     */
    val uiState: StateFlow<AjustesUiState> = combineInfo()

    init {
        cargarInfoUsuario()
    }

    private fun combineInfo(): StateFlow<AjustesUiState> {
        // Info del usuario es un snapshot (no cambia). Sync es reactivo.
        // Combinamos ambos en un solo StateFlow para la UI.
        val syncFlow = mediadorSincronizacion.observarSyncEnCurso()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        return kotlinx.coroutines.flow.combine(
            _infoUsuario,
            syncFlow
        ) { info, syncEnCurso ->
            info.copy(syncEnCurso = syncEnCurso)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AjustesUiState()
        )
    }

    private fun cargarInfoUsuario() {
        _infoUsuario.value = _infoUsuario.value.copy(
            nombreUsuario = sesionUsuario.obtenerNombreUsuario(),
            correo = sesionUsuario.obtenerCorreo()
        )
    }

    fun onAction(action: AjustesAction) {
        when (action) {
            AjustesAction.CerrarSesion -> cerrarSesion()
            AjustesAction.DispararSync -> dispararSync()
        }
    }

    private fun cerrarSesion() {
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
            logoutCoordinator.logout()
            _eventos.send(AjustesEvento.LogoutCompletado)
        }
    }

    private fun dispararSync() {
        viewModelScope.launch {
            mediadorSincronizacion.dispararSyncUnica()
        }
    }
}
