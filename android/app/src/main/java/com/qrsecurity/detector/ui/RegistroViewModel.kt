package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auth0.android.authentication.storage.SecureCredentialsManager
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
import com.qrsecurity.detector.sesion.ExcepcionAuth0App
import com.qrsecurity.detector.sesion.PerfilIdToken
import com.qrsecurity.detector.sesion.ServicioAuth0
import com.qrsecurity.detector.sesion.SesionUsuario
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * UiState para la pantalla Registro — patron NowInAndroid.
 */
data class RegistroUiState(
    val procesando: Boolean = false
)

/**
 * Eventos one-shot del Registro — entregados via Channel, no StateFlow.
 * Mismo patron que [LoginEvento] para evitar re-disparos en rotacion.
 */
sealed interface RegistroEvento {
    /**
     * Registro exitoso — la cuenta queda creada en Auth0 con la sesion
     * iniciada; el consumidor navega a HOME.
     */
    data object Exito : RegistroEvento
    data class Error(val mensaje: String) : RegistroEvento
}

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface RegistroAction {
    data class Registrar(
        val correo: String,
        val nombre: String,
        val password: String,
        val confirmarPassword: String
    ) : RegistroAction
}

/**
 * ViewModel para la pantalla de Registro — alta embebida en Auth0.
 *
 * [ServicioAuth0.registrarCuenta] crea el usuario en la database
 * connection de Auth0 y deja la sesion iniciada (signup + login). La
 * password transita por TLS directo a Auth0 y nunca se persiste — en
 * disco solo viven tokens cifrados.
 *
 * Tras un registro exitoso persiste la sesion y dispara sync inmediato
 * para hacer PULL de los datos del usuario desde la nube.
 */
@HiltViewModel
class RegistroViewModel @Inject constructor(
    private val servicioAuth0: ServicioAuth0,
    private val credentialsManager: SecureCredentialsManager,
    private val sesionUsuario: SesionUsuario,
    private val mediadorSincronizacion: MediadorSincronizacion
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegistroUiState())
    val uiState: StateFlow<RegistroUiState> = _uiState.asStateFlow()

    private val _eventos = Channel<RegistroEvento>(Channel.BUFFERED)
    val eventos = _eventos.receiveAsFlow()

    fun onAction(action: RegistroAction) {
        when (action) {
            is RegistroAction.Registrar -> registrar(
                action.correo,
                action.nombre,
                action.password,
                action.confirmarPassword
            )
        }
    }

    private fun registrar(
        correo: String,
        nombre: String,
        password: String,
        confirmarPassword: String
    ) {
        if (_uiState.value.procesando) return

        // Validacion local antes de llamar a Auth0 (BUG-M5 fix: fallar
        // rapido en el cliente con mensaje claro, sin gastar red).
        if (correo.isBlank() || password.isBlank()) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("Completa todos los campos."))
            }
            return
        }
        if (!PATRON_CORREO.matches(correo)) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("El correo no tiene un formato válido."))
            }
            return
        }
        // Politica real de la database connection de Auth0 (verificada
        // contra el tenant): longitud 15-72, sin requisitos de clases.
        if (password.length < 15) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("La contraseña debe tener al menos 15 caracteres."))
            }
            return
        }
        if (password.length > 72) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("La contraseña no puede superar los 72 caracteres."))
            }
            return
        }
        if (password != confirmarPassword) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("Las contraseñas no coinciden."))
            }
            return
        }

        _uiState.update { it.copy(procesando = true) }
        viewModelScope.launch {
            try {
                val credenciales = withContext(Dispatchers.IO) {
                    servicioAuth0.registrarCuenta(
                        correo = correo,
                        password = password,
                        nombre = nombre
                    )
                }
                withContext(Dispatchers.IO) {
                    credentialsManager.saveCredentials(credenciales)
                }
                val perfil = PerfilIdToken.desdeIdToken(credenciales.idToken)
                sesionUsuario.guardarSesion(
                    token = credenciales.accessToken,
                    usuario = perfil?.nombreMostrable()
                        ?: nombre.ifBlank { correo.substringBefore("@") },
                    correo = perfil?.correo ?: correo
                )
                mediadorSincronizacion.dispararSyncUnica()
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Exito)
            } catch (e: ExcepcionAuth0App) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Error(servicioAuth0.mensajeError(e)))
            } catch (e: Exception) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Error("No pudimos conectar. Revisa tu conexión e inténtalo de nuevo."))
            }
        }
    }

    private companion object {
        /** RFC-lite: algo@algo.algo. */
        val PATRON_CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
