package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassVariant
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilBrandMark
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Registro (Pencil frame fZjhl).
 *
 * F3.1: UI Compose que replica el layout de Pencil fZjhl. Patron UDF:
 * - estado observado desde [RegistroViewModel.uiState] via
 *   `collectAsStateWithLifecycle` (sobrevive a rotacion sin re-emitir).
 * - eventos one-shot del Channel [RegistroViewModel.eventos] recolectados
 *   con `LaunchedEffect` + `repeatOnLifecycle(STARTED)` (mismo fix que
 *   Login: evita re-disparar navegacion/snackbar en rotacion).
 * - acciones despachadas via
 *   `viewModel.onAction(RegistroAction.Registrar(...))`.
 *
 * Los 4 campos viven en `rememberSaveable`; los 2 toggles de visibilidad
 * de contrasena viven en `remember`.
 *
 * @param onExito Callback tras registro exitoso (navega a HOME).
 * @param onVolver Callback para volver a la pantalla de login.
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de registro (Hilt, scoped al NavBackStackEntry).
 */
@Composable
fun PantallaRegistro(
    onExito: () -> Unit,
    onVolver: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: RegistroViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(STARTED) {
            viewModel.eventos.collect { evento ->
                when (evento) {
                    is RegistroEvento.Exito -> onExito()
                    is RegistroEvento.Error -> onMensaje(TipoMensaje.ERROR, evento.mensaje)
                }
            }
        }
    }

    var correo by rememberSaveable { mutableStateOf("") }
    var usuario by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmarPassword by rememberSaveable { mutableStateOf("") }
    var mostrarPassword by remember { mutableStateOf(false) }
    var mostrarConfirmarPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.xxxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.xxl)
    ) {
        // ─── Brand Header ───
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            Box(
                modifier = Modifier
                    .size(Espaciado.gigante)
                    .clip(CircleShape)
                    .background(PencilBrandMark),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
                Text(
                    text = "SeguridadQR",
                    style = MaterialTheme.typography.titleLarge,
                    color = CyberTextoPrincipal
                )
                Text(
                    text = "Proteccion inteligente",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }

        // ─── Registration Intro ───
        Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
            Text(
                text = "Crear cuenta",
                style = MaterialTheme.typography.headlineMedium,
                color = CyberTextoPrincipal
            )
            Text(
                text = "Registrate para analizar enlaces y navegar con confianza.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )
        }

        // ─── Registration Form ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RadioBorde.xxl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Espaciado.xl),
                verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
            ) {
                // Correo electronico
                OutlinedTextField(
                    value = correo,
                    onValueChange = { correo = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("correo@ejemplo.com") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Email,
                            contentDescription = null,
                            modifier = Modifier.size(TamanosIcono.estandar)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(RadioBorde.md),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = fieldColors()
                )

                // Usuario
                OutlinedTextField(
                    value = usuario,
                    onValueChange = { usuario = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("tu_usuario") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(TamanosIcono.estandar)
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(RadioBorde.md),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    colors = fieldColors()
                )

                // Contrasena
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("contrasena") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(TamanosIcono.estandar)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { mostrarPassword = !mostrarPassword }) {
                            Icon(
                                imageVector = if (mostrarPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (mostrarPassword) "Ocultar contrasena" else "Mostrar contrasena",
                                modifier = Modifier.size(TamanosIcono.estandar)
                            )
                        }
                    },
                    visualTransformation = if (mostrarPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(RadioBorde.md),
                    colors = fieldColors()
                )

                // Confirmar contrasena
                OutlinedTextField(
                    value = confirmarPassword,
                    onValueChange = { confirmarPassword = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("confirmar contrasena") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(TamanosIcono.estandar)
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { mostrarConfirmarPassword = !mostrarConfirmarPassword }) {
                            Icon(
                                imageVector = if (mostrarConfirmarPassword) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (mostrarConfirmarPassword) "Ocultar contrasena" else "Mostrar contrasena",
                                modifier = Modifier.size(TamanosIcono.estandar)
                            )
                        }
                    },
                    visualTransformation = if (mostrarConfirmarPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    shape = RoundedCornerShape(RadioBorde.md),
                    colors = fieldColors()
                )
            }
        }

        // ─── Registration Actions ───
        Button(
            onClick = {
                viewModel.onAction(
                    RegistroAction.Registrar(
                        nombreUsuario = usuario,
                        correo = correo,
                        password = password,
                        confirmarPassword = confirmarPassword
                    )
                )
            },
            enabled = !uiState.procesando,
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo
            )
        ) {
            if (uiState.procesando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = CyberFondo,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "Crear cuenta",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        // Login link row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Ya tienes una cuenta?",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )
            TextButton(
                onClick = onVolver,
                contentPadding = PaddingValues(
                    horizontal = Espaciado.xs,
                    vertical = Espaciado.xs
                )
            ) {
                Text(
                    text = "Iniciar sesion",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberCyan
                )
            }
        }
    }
}

/**
 * Colores del [OutlinedTextField] en el formulario de registro.
 * superficie oscura (CyberGlassVariant), borde cyan al enfocar, cursor cyan.
 */
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = CyberGlassVariant,
    unfocusedContainerColor = CyberGlassVariant,
    focusedBorderColor = CyberCyan,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = CyberCyan,
    focusedTextColor = CyberTextoPrincipal,
    unfocusedTextColor = CyberTextoPrincipal
)
