package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.datos.repositorios.RepositorioUrlsBloqueadas
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla Resultado Malicioso — patrón NowInAndroid.
 */
data class ResultadoMaliciosoUiState(
    val bloqueando: Boolean = false,
    val bloqueadaOk: Boolean? = null,
    val error: String? = null
)

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface ResultadoMaliciosoAction {
    data class BloquearUrl(
        val urlLimpia: String,
        val probabilidad: Float
    ) : ResultadoMaliciosoAction
}

/**
 * ViewModel para la pantalla Resultado Malicioso.
 *
 * Inyecta [RepositorioUrlsBloqueadas] y [MediadorSincronizacion] via Hilt —
 * elimina la construccion manual de `BaseDatosSeguridad.get(context)`,
 * `ClienteBackend(...)`, `RepositorioUrlsBloqueadas(...)`,
 * `MediadorSincronizacion(context)` que aparecia en `PantallaResultadoMalicioso`.
 *
 * Bug A5/A6 fix preservado: el bloqueo es offline-first (Room + pending_ops),
 * no espera al backend.
 */
@HiltViewModel
class ResultadoMaliciosoViewModel @Inject constructor(
    private val repoUrls: RepositorioUrlsBloqueadas,
    private val mediadorSync: MediadorSincronizacion
) : ViewModel() {

    private val _uiState = MutableStateFlow(ResultadoMaliciosoUiState())
    val uiState: StateFlow<ResultadoMaliciosoUiState> = _uiState.asStateFlow()

    /**
     * Despacha una acción desde la UI (UDF).
     */
    fun onAction(action: ResultadoMaliciosoAction) {
        when (action) {
            is ResultadoMaliciosoAction.BloquearUrl -> bloquearUrl(
                urlLimpia = action.urlLimpia,
                probabilidad = action.probabilidad
            )
        }
    }

    private fun bloquearUrl(urlLimpia: String, probabilidad: Float) {
        if (_uiState.value.bloqueando) return
        _uiState.update { it.copy(bloqueando = true, bloqueadaOk = null, error = null) }
        viewModelScope.launch {
            try {
                repoUrls.bloquearLocal(
                    url = urlLimpia,
                    razon = "Malicioso (probabilidad ${(probabilidad * 100).toInt()}%)"
                )
                mediadorSync.dispararSyncUnica()
                _uiState.update { it.copy(bloqueando = false, bloqueadaOk = true) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        bloqueando = false,
                        bloqueadaOk = false,
                        error = "No se pudo bloquear la URL: ${e.message ?: "error desconocido"}"
                    )
                }
            }
        }
    }

    /**
     * Llamado por la UI tras consumir el error (snackbar) para evitar
     * que reaparezca en rotación.
     */
    fun consumirError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Bug S6 fix: llamado por la UI tras mostrar el snackbar de "URL
     * bloqueada" para que LaunchedEffect(uiState.bloqueadaOk) no re-dispare
     * el snackbar en rotacion. Antes bloqueadaOk se seteaba a true pero
     * nunca se consumia — en rotacion el LaunchedEffect re-disparaba el
     * snackbar "URL bloqueada" aunque el bloqueo ocurrio hace rato.
     */
    fun consumirBloqueoOk() {
        _uiState.update { it.copy(bloqueadaOk = null) }
    }
}
