package com.qrsecurity.detector.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.datos.sync.MediadorSincronizacion
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
     * Registro exitoso. La cuenta se acaba de crear — el consumidor solo
     * necesita saber que el alta fue bien para navegar a HOME.
     */
    data object Exito : RegistroEvento
    data class Error(val mensaje: String) : RegistroEvento
}

/**
 * Acciones que la UI puede despachar (Unidirectional Data Flow).
 */
sealed interface RegistroAction {
    data class Registrar(
        val nombreUsuario: String,
        val correo: String,
        val password: String,
        val confirmarPassword: String
    ) : RegistroAction
}

/**
 * ViewModel para la pantalla de Registro.
 *
 * Maneja exclusivamente el alta de cuentas nuevas via
 * [ClienteBackend.registrarUsuario].
 *
 * Inyecta [ClienteBackend] y [SesionUsuario] via Hilt. Tras un registro
 * exitoso persiste la sesion y dispara sync inmediato para hacer PULL
 * de los datos del usuario desde la nube.
 */
@HiltViewModel
class RegistroViewModel @Inject constructor(
    private val clienteBackend: ClienteBackend,
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
                action.nombreUsuario,
                action.correo,
                action.password,
                action.confirmarPassword
            )
        }
    }

    private fun registrar(
        nombreUsuario: String,
        correo: String,
        password: String,
        confirmarPassword: String
    ) {
        if (_uiState.value.procesando) return

        // Validacion local antes de llamar al backend.
        if (nombreUsuario.isBlank() || password.isBlank()) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("Completa todos los campos"))
            }
            return
        }
        // BUG-M5 fix: validacion de formato de correo en el cliente.
        // Antes, un correo malformado (p.ej. "abc" o "abc@@def") llegaba
        // al backend y devolvia 400 tras ~1-3 s de red. Rechazamos rapido
        // con un patron RFC-lite (algo@algo.algo) — el backend sigue
        // haciendo validacion estricta, este check solo filtra obviously
        // broken inputs.
        val patronEmail = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        if (!patronEmail.matches(correo)) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("El correo no tiene un formato valido"))
            }
            return
        }
        if (password != confirmarPassword) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error("Las contrasenas no coinciden"))
            }
            return
        }

        _uiState.update { it.copy(procesando = true) }
        viewModelScope.launch {
            try {
                val respuesta = clienteBackend.registrarUsuario(
                    nombreUsuario = nombreUsuario,
                    password = password,
                    correo = correo
                )
                if (respuesta.tokenApi.isBlank()) {
                    _uiState.update { it.copy(procesando = false) }
                    _eventos.send(RegistroEvento.Error("El servidor devolvio un token vacio. Intenta de nuevo."))
                    return@launch
                }
                sesionUsuario.guardarSesion(
                    token = respuesta.tokenApi,
                    usuario = respuesta.nombreUsuario ?: nombreUsuario,
                    correo = respuesta.correo ?: correo
                )
                mediadorSincronizacion.dispararSyncUnica()
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Exito)
            } catch (e: ClienteBackend.HttpBackendException) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Error(manejarErrorRegistro(e.codigo, e.cuerpo, e.message)))
            } catch (e: Exception) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Error("No se pudo conectar al backend: ${e.message ?: "error desconocido"}"))
            }
        }
    }
}

private fun manejarErrorRegistro(codigo: Int, cuerpo: String?, message: String?): String = when (codigo) {
    409 -> "El usuario ya existe. Intenta con otro."
    else -> "Error $codigo: ${cuerpo ?: message}"
}
