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
    private val iniciarSesionTrasAuth: IniciarSesionTrasAuth
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
        // rapido en el cliente, sin gastar red). S2: la política (formato
        // de correo, longitud 15-72, confirmación) vive en
        // [ValidadorCredenciales], compartida con Login.
        val errorValidacion =
            ValidadorCredenciales.validarCamposCompletos(correo, password)
                ?: ValidadorCredenciales.validarCorreo(correo)
                ?: ValidadorCredenciales.validarPassword(password)
                ?: ValidadorCredenciales.validarConfirmacion(password, confirmarPassword)
        if (errorValidacion != null) {
            viewModelScope.launch {
                _eventos.send(RegistroEvento.Error(errorValidacion))
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
                // S2: persistencia de credenciales + sesión + PULL inicial
                // centralizados en el caso de uso compartido con Login.
                val error = iniciarSesionTrasAuth.invocar(
                    credenciales = credenciales,
                    correo = correo,
                    fallbackNombre = nombre
                )
                _uiState.update { it.copy(procesando = false) }
                if (error == null) {
                    _eventos.send(RegistroEvento.Exito)
                } else {
                    _eventos.send(RegistroEvento.Error(error))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: ExcepcionAuth0App) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Error(servicioAuth0.mensajeError(e)))
            } catch (e: Exception) {
                _uiState.update { it.copy(procesando = false) }
                _eventos.send(RegistroEvento.Error("No pudimos conectar. Revisa tu conexión e inténtalo de nuevo."))
            }
        }
    }
}
