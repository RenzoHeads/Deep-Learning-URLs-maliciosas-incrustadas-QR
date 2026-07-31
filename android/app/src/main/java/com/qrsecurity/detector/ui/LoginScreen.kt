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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.R
import com.qrsecurity.detector.ui.TipoMensaje
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

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
    onExito: (esNuevoRegistro: Boolean) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: LoginViewModel = hiltViewModel()
) {
    var modoRegistro by remember { mutableStateOf(false) }
    var nombreUsuario by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var correo by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Bug L1 fix: consumir eventos one-shot via Channel (receiveAsFlow)
    // en lugar de LaunchedEffect(uiState.exito, uiState.error) que
    // re-disparaba en rotacion. El Channel entrega cada evento una sola
    // vez — no hay re-fire al recomponer tras config change.
    LaunchedEffect(Unit) {
        viewModel.eventos.collect { evento ->
            when (evento) {
                is LoginEvento.Exito -> {
                    onMensaje(TipoMensaje.EXITO, "Sesion iniciada")
                    onExito(evento.esNuevoRegistro)
                }
                is LoginEvento.Error -> {
                    onMensaje(TipoMensaje.ERROR, evento.mensaje)
                }
            }
        }
    }

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
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.hero),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.xxxl, Alignment.Top)
    ) {
        LogoQRGuardian()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(RadioBorde.xl))
                .background(CyberGlass.copy(alpha = 0.85f))
                .padding(Espaciado.xl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
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
                    procesando = uiState.procesando,
                    nombreUsuario = nombreUsuario,
                    password = password,
                    correo = correo,
                    onMensaje = onMensaje,
                    onAction = { vmAction ->
                        viewModel.onAction(vmAction)
                    }
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
        verticalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(TamanosIcono.heroContenedor)
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
                modifier = Modifier.size(TamanosIcono.grande)
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
                .clip(RoundedCornerShape(RadioBorde.sm))
                .background(CyberCyan.copy(alpha = 0.1f))
                .padding(horizontal = Espaciado.sm, vertical = Espaciado.xs)
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

/** Datos agrupados para BotonAuth — evita S107 (>7 params). */
private data class ParametrosBotonAuth(
    val modoRegistro: Boolean,
    val procesando: Boolean,
    val nombreUsuario: String,
    val password: String,
    val correo: String,
    val onMensaje: (TipoMensaje, String) -> Unit,
    val onAction: (LoginAction) -> Unit
)

/**
 * Boton de auth con validacion + indicador de carga.
 * Extraido de PantallaLogin para reducir complejidad cognitiva (S3776).
 *
 * Hilt: la logica de auth se delega al [LoginViewModel] via onAction
 * (UDF). Ya no construye `ClienteBackend()` ni lanza corutinas — el
 * VM lo hace via viewModelScope.
 */
@Composable
private fun BotonAuth(params: ParametrosBotonAuth) {
    val (modoRegistro, procesando, nombreUsuario, password, correo, onMensaje, onAction) = params
    Button(
        onClick = {
            if (procesando) return@Button
            val error = validarCredenciales(modoRegistro, nombreUsuario, password)
            if (error != null) {
                onMensaje(TipoMensaje.ERROR, error)
                return@Button
            }
            onAction(
                LoginAction.Autenticar(
                    modoRegistro = modoRegistro,
                    nombreUsuario = nombreUsuario,
                    password = password,
                    correo = correo
                )
            )
        },
        enabled = !procesando,
        modifier = Modifier
            .fillMaxWidth()
            .height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyberCyan,
            contentColor = CyberFondo,
            disabledContainerColor = CyberCyan.copy(alpha = 0.38f),
            disabledContentColor = CyberFondo
        )
    ) {
        if (procesando) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Espaciado.xl),
                    strokeWidth = Elevacion.flotante,
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
