package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UiState para la pantalla Login — patrón NowInAndroid.
 *
 * Bug L1 fix: `exito` y `error` se removieron del UiState. Antes eran
 * Boolean/String en el StateFlow, y `LaunchedEffect(uiState.exito,
 * uiState.error)` re-disparaba el snackbar (y la navegacion) en rotacion
 * porque el StateFlow sobrevive al config change y los valores no se
 * habian reseteado a tiempo. Ahora los eventos one-shot viven en un
 * Channel (receiveAsFlow) — cada evento se entrega una sola vez.
 */
data class LoginUiState(
    val procesando: Boolean = false
)

/**
 * Eventos one-shot del Login — entregados via Channel, no StateFlow.
 * Bug L1 fix: reemplaza los campos `exito: Boolean` y `error: String?`
 * del UiState que re-disparaban en rotacion.
 */
sealed interface LoginEvento {
    data object Exito : LoginEvento
    data class Error(val mensaje: String) : LoginEvento
}

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface LoginAction {
    data class Autenticar(
        val modoRegistro: Boolean,
        val nombreUsuario: String,
        val password: String,
        val correo: String
    ) : LoginAction
}

/**
 * ViewModel para la pantalla Login.
 *
 * Inyecta [ClienteBackend] via Hilt — elimina la construccion manual
 * `ClienteBackend()` que aparecia en `ejecutarAuth`. [SesionUsuario] se
 * inyecta tambien (companion bridge ya hecho, pero la instancia Hilt
 * es la misma registrada desde AppSeguridadQR).
 *
 * El dogma offline-first no aplica aqui: el login es la excepcion —
 * requiere conectividad (sin token, sin datos cacheados que servir).
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val clienteBackend: ClienteBackend,
    private val sesionUsuario: SesionUsuario
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // Bug L1 fix: eventos one-shot via Channel (receiveAsFlow). Antes
    // exito/error vivian en el StateFlow y LaunchedEffect re-disparaba
    // en rotacion. Channel entrega cada evento una sola vez.
    private val _eventos = Channel<LoginEvento>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

    /**
     * Despacha una acción desde la UI (UDF).
     */
    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.Autenticar -> autenticar(
                modoRegistro = action.modoRegistro,
                nombreUsuario = action.nombreUsuario,
                password = action.password,
                correo = action.correo
            )
        }
    }

    private fun autenticar(
        modoRegistro: Boolean,
        nombreUsuario: String,
        password: String,
        correo: String
    ) {
        if (_uiState.value.procesando) return
        _uiState.update { it.copy(procesando = true) }
        viewModelScope.launch {
            try {
                val respuesta = if (modoRegistro) {
                    clienteBackend.registrarUsuario(nombreUsuario, password, correo)
                } else {
                    clienteBackend.login(nombreUsuario, password)
                }
                if (respuesta.tokenApi.isBlank()) {
                    _uiState.update { it.copy(procesando = false) }
                    _eventos.send(LoginEvento.Error("El servidor devolvio un token vacio. Intenta de nuevo."))
                    return@launch
                }
                sesionUsuario.guardarSesion(
                    token = respuesta.tokenApi,
                    usuario = respuesta.nombreUsuario ?: nombreUsuario,
                    correo = respuesta.correo ?: correo
                )
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(LoginEvento.Exito)
            } catch (e: ClienteBackend.HttpBackendException) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(LoginEvento.Error(manejarErrorBackend(e.codigo, e.cuerpo, e.message)))
            } catch (e: Exception) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(LoginEvento.Error("No se pudo conectar al backend: ${e.message ?: "error desconocido"}"))
            }
        }
    }
}

private fun manejarErrorBackend(codigo: Int, cuerpo: String?, message: String?): String = when (codigo) {
    409 -> "El usuario ya existe. Intenta con otro."
    401 -> "Usuario o contrasena incorrectos."
    else -> "Error $codigo: ${cuerpo ?: message}"
}
