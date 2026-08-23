package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.sesion.ExcepcionAuth0App
import com.qrsecurity.detector.sesion.ServicioAuth0
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UiState para la pantalla Login — patron NowInAndroid.
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
    /** Login exitoso — la pantalla navega a HOME. */
    data object Exito : LoginEvento
    data class Error(val mensaje: String) : LoginEvento
}

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface LoginAction {
    data class Autenticar(
        val correo: String,
        val password: String
    ) : LoginAction
}

/**
 * ViewModel para la pantalla Login — auth embebida contra Auth0.
 *
 * [ServicioAuth0.iniciarSesion] manda correo+password por TLS directo a
 * Auth0 (la password nunca se persiste) y devuelve los [Credentials] de
 * siempre: access token JWT (audience del backend) + refresh token,
 * que se guardan cifrados en [SecureCredentialsManager].
 *
 * [SesionUsuario] sigue siendo la fuente unica del estado de sesion
 * (token + perfil + flag reactivo para NavGuardian).
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val servicioAuth0: ServicioAuth0,
    private val iniciarSesionTrasAuth: IniciarSesionTrasAuth
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    // Bug L1 fix: eventos one-shot via Channel (receiveAsFlow). Antes
    // exito/error vivian en el StateFlow y LaunchedEffect re-disparaba
    // en rotacion. Channel entrega cada evento una sola vez.
    private val _eventos = Channel<LoginEvento>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

    /**
     * Despacha una accion desde la UI (UDF).
     */
    fun onAction(action: LoginAction) {
        when (action) {
            is LoginAction.Autenticar -> autenticar(
                correo = action.correo,
                password = action.password
            )
        }
    }

    private fun autenticar(correo: String, password: String) {
        if (_uiState.value.procesando) return

        // BUG-M5 fix: validacion local antes de gastar red/timeout —
        // falla rapido en el cliente con mensaje claro. S2: la política
        // vive en [ValidadorCredenciales], compartida con Registro.
        val errorValidacion =
            ValidadorCredenciales.validarCamposCompletos(correo, password)
                ?: ValidadorCredenciales.validarCorreo(correo)
        if (errorValidacion != null) {
            viewModelScope.launch {
                _eventos.send(LoginEvento.Error(errorValidacion))
            }
            return
        }

        _uiState.update { it.copy(procesando = true) }
        viewModelScope.launch {
            try {
                val credenciales = withContext(Dispatchers.IO) {
                    servicioAuth0.iniciarSesion(correo, password)
                }
                // S2: persistencia de credenciales + sesión + PULL inicial
                // centralizados en el caso de uso compartido con Registro.
                val error = iniciarSesionTrasAuth.invocar(credenciales, correo)
                _uiState.update { it.copy(procesando = false) }
                if (error == null) {
                    _eventos.send(LoginEvento.Exito)
                } else {
                    _eventos.send(LoginEvento.Error(error))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: ExcepcionAuth0App) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(LoginEvento.Error(servicioAuth0.mensajeError(e)))
            } catch (e: Exception) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(LoginEvento.Error("No pudimos conectar. Revisa tu conexión e inténtalo de nuevo."))
            }
        }
    }
}
