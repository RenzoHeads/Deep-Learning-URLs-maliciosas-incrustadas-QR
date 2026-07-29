package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.R
import com.qrsecurity.detector.api.ClienteBackend
import com.qrsecurity.detector.sesion.SesionUsuario
import com.qrsecurity.detector.ui.TipoMensaje
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Pantalla de Login / Registro con usuario y password.
 *
 * Flujo:
 *  - Modo REGISTRO: el usuario ingresa nombre_usuario + password (+ correo opcional),
 *    se llama a [ClienteBackend.registrarUsuario] (`POST /auth/registrar`).
 *  - Modo LOGIN: el usuario ingresa nombre_usuario + password,
 *    se llama a [ClienteBackend.login] (`POST /auth/login`).
 *
 * Tras exito, [SesionUsuario.guardarSesion] persiste token + usuario y se
 * invoca [onExito], que navega a Onboarding.
 *
 * El token devuelto por el backend es interno: la app solo lo persiste
 * (no se muestra al usuario). El usuario se identifica con su nombre_usuario.
 */
@Composable
fun PantallaLogin(
    onExito: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var modoRegistro by remember { mutableStateOf(false) }
    var nombreUsuario by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var correo by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var procesando by remember { mutableStateOf(false) }

    val estadoScroll = rememberScrollState()
    val onToggleModo = {
        modoRegistro = !modoRegistro
        password = ""
        correo = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(estadoScroll)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.Top)
    ) {
        LogoQRGuardian()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CyberGlass.copy(alpha = 0.85f))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (modoRegistro) "Crear cuenta" else "Iniciar sesion",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )

            FormularioAuth(
                modoRegistro = modoRegistro,
                nombreUsuario = nombreUsuario,
                password = password,
                correo = correo,
                passwordVisible = passwordVisible,
                handlers = HandlersFormulario(
                    onNombreUsuario = { nuevo ->
                        val filtrado = nuevo.trim().filter { c ->
                            c.isLetterOrDigit() || c == '_' || c == '.'
                        }
                        nombreUsuario = filtrado
                    },
                    onPassword = { password = it },
                    onCorreo = { correo = it.trim() },
                    onTogglePassword = { passwordVisible = !passwordVisible }
                )
            )

            BotonAuth(
                ParametrosBotonAuth(
                    modoRegistro = modoRegistro,
                    procesando = procesando,
                    nombreUsuario = nombreUsuario,
                    password = password,
                    correo = correo,
                    context = context,
                    scope = scope,
                    onMensaje = onMensaje,
                    onExito = onExito,
                    onProcesando = { procesando = it }
                )
            )

            ToggleLoginRegistro(modoRegistro = modoRegistro, onToggle = onToggleModo)
        }

        Text(
            text = "El backend debe estar activo para usar la app",
            style = MaterialTheme.typography.bodySmall,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LogoQRGuardian() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CyberVerdeAlerta.copy(alpha = 0.2f), CyberFondo)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(64.dp)
            )
        }
        Text(
            text = "QR GUARDIAN",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyberCyan
        )
        Text(
            text = "Detecta URLs maliciosas\nincrustadas en codigos QR",
            style = MaterialTheme.typography.bodyMedium,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FormularioAuth(
    modoRegistro: Boolean,
    nombreUsuario: String,
    password: String,
    correo: String,
    passwordVisible: Boolean,
    handlers: HandlersFormulario
) {
    OutlinedTextField(
        value = nombreUsuario,
        onValueChange = handlers.onNombreUsuario,
        label = { Text(stringResource(R.string.label_username)) },
        placeholder = { Text(stringResource(R.string.placeholder_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth(),
        colors = colorsCyber()
    )

    OutlinedTextField(
        value = password,
        onValueChange = handlers.onPassword,
        label = { Text(stringResource(R.string.label_password)) },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = handlers.onTogglePassword) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                    contentDescription = if (passwordVisible) "Ocultar" else "Mostrar",
                    tint = CyberTextoSecundario
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
        colors = colorsCyber()
    )

    if (modoRegistro) {
        OutlinedTextField(
            value = correo,
            onValueChange = handlers.onCorreo,
            label = { Text(stringResource(R.string.label_email_optional)) },
            placeholder = { Text(stringResource(R.string.placeholder_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
            colors = colorsCyber()
        )
    }
}

private class HandlersFormulario(
    val onNombreUsuario: (String) -> Unit,
    val onPassword: (String) -> Unit,
    val onCorreo: (String) -> Unit,
    val onTogglePassword: () -> Unit
)

@Composable
private fun ToggleLoginRegistro(
    modoRegistro: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (modoRegistro) "Ya tienes cuenta? " else "No tienes cuenta? ",
            style = MaterialTheme.typography.bodySmall,
            color = CyberTextoSecundario
        )
        Text(
            text = if (modoRegistro) "Inicia sesion" else "Crea una",
            style = MaterialTheme.typography.bodySmall,
            color = CyberCyan,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(CyberCyan.copy(alpha = 0.1f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .clickable(onClick = onToggle)
        )
    }
}

@Composable
private fun colorsCyber() = TextFieldDefaults.colors(
    focusedContainerColor = CyberFondo,
    unfocusedContainerColor = CyberFondo,
    focusedTextColor = CyberTextoPrincipal,
    unfocusedTextColor = CyberTextoPrincipal,
    cursorColor = CyberCyan,
    focusedIndicatorColor = CyberCyan,
    unfocusedIndicatorColor = CyberTextoSecundario,
    focusedLabelColor = CyberCyan,
    unfocusedLabelColor = CyberTextoSecundario
)

private fun validarCredenciales(
    modoRegistro: Boolean,
    nombreUsuario: String,
    password: String
): String? = when {
    nombreUsuario.length < 3 -> "Usuario muy corto (minimo 3 caracteres)"
    modoRegistro && password.length < 6 -> "Contrasena muy corta (minimo 6 caracteres)"
    !modoRegistro && password.isEmpty() -> "Ingresa tu contrasena"
    else -> null
}

private fun manejarErrorBackend(codigo: Int, cuerpo: String?, message: String?): String = when (codigo) {
    409 -> "El usuario ya existe. Intenta con otro."
    401 -> "Usuario o contrasena incorrectos."
    else -> "Error $codigo: ${cuerpo ?: message}"
}

/** Datos Agrupados para ejecutarAuth — evita S107 (>7 params). */
private data class ParametrosAuth(
    val scope: kotlinx.coroutines.CoroutineScope,
    val modoRegistro: Boolean,
    val nombreUsuario: String,
    val password: String,
    val correo: String,
    val context: android.content.Context,
    val onMensaje: (TipoMensaje, String) -> Unit,
    val onExito: () -> Unit,
    val onProcesando: (Boolean) -> Unit
)

private fun ejecutarAuth(params: ParametrosAuth) {
    val (scope, modoRegistro, nombreUsuario, password, correo, context, onMensaje, onExito, onProcesando) = params
    onProcesando(true)
    scope.launch {
        try {
            val cliente = ClienteBackend()
            val respuesta = if (modoRegistro) {
                cliente.registrarUsuario(nombreUsuario, password, correo)
            } else {
                cliente.login(nombreUsuario, password)
            }
            if (respuesta.tokenApi.isBlank()) {
                onMensaje(TipoMensaje.ERROR, "El servidor devolvio un token vacio. Intenta de nuevo.")
                onProcesando(false)
                return@launch
            }
            SesionUsuario.guardarSesion(
                context = context,
                token = respuesta.tokenApi,
                usuario = respuesta.nombreUsuario ?: nombreUsuario,
                correo = respuesta.correo ?: correo
            )
            onMensaje(TipoMensaje.EXITO, "Sesion iniciada")
            onExito()
        } catch (e: ClienteBackend.HttpBackendException) {
            onMensaje(TipoMensaje.ERROR, manejarErrorBackend(e.codigo, e.cuerpo, e.message))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onMensaje(TipoMensaje.ERROR, "No se pudo conectar al backend: ${e.message ?: "error desconocido"}")
        } finally {
            onProcesando(false)
        }
    }
}

/** Datos agrupados para BotonAuth — evita S107 (>7 params). */
private data class ParametrosBotonAuth(
    val modoRegistro: Boolean,
    val procesando: Boolean,
    val nombreUsuario: String,
    val password: String,
    val correo: String,
    val context: android.content.Context,
    val scope: kotlinx.coroutines.CoroutineScope,
    val onMensaje: (TipoMensaje, String) -> Unit,
    val onExito: () -> Unit,
    val onProcesando: (Boolean) -> Unit
)

/**
 * Boton de auth con validacion + indicador de carga.
 * Extraido de PantallaLogin para reducir complejidad cognitiva (S3776).
 */
@Composable
private fun BotonAuth(params: ParametrosBotonAuth) {
    val (modoRegistro, procesando, nombreUsuario, password, correo, context, scope, onMensaje, onExito, onProcesando) = params
    Button(
        onClick = {
            if (procesando) return@Button
            val error = validarCredenciales(modoRegistro, nombreUsuario, password)
            if (error != null) {
                onMensaje(TipoMensaje.ERROR, error)
                return@Button
            }
            ejecutarAuth(
                ParametrosAuth(
                    scope = scope,
                    modoRegistro = modoRegistro,
                    nombreUsuario = nombreUsuario,
                    password = password,
                    correo = correo,
                    context = context,
                    onMensaje = onMensaje,
                    onExito = onExito,
                    onProcesando = onProcesando
                )
            )
        },
        enabled = !procesando,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyberCyan,
            contentColor = CyberFondo,
            disabledContainerColor = CyberCyan.copy(alpha = 0.4f),
            disabledContentColor = CyberFondo
        )
    ) {
        if (procesando) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = CyberFondo
                )
                Text(stringResource(R.string.action_connecting), fontWeight = FontWeight.Bold)
            }
        } else {
            Text(
                if (modoRegistro) "Registrarse" else stringResource(R.string.action_login),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
