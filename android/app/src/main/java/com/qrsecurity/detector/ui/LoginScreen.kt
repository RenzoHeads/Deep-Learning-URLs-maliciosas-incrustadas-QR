package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
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
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilBrandMark
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Login (Pencil frame Jitpw).
 *
 * F3.1: UI Compose que replica el layout de Pencil Jitpw. Patron UDF:
 * - estado observado desde [LoginViewModel.uiState] via
 *   `collectAsStateWithLifecycle` (sobrevive a rotacion sin re-emitir).
 * - eventos one-shot del Channel [LoginViewModel.eventos] recolectados
 *   con `LaunchedEffect` + `repeatOnLifecycle(STARTED)` (Bug L1 fix:
 *   evita re-disparar navegacion/snackbar en rotacion).
 * - acciones despachadas via `viewModel.onAction(LoginAction.Autenticar(...))`.
 *
 * El estado de los campos vive en `rememberSaveable` (sobrevive a
 * rotacion); la visibilidad de la contrasena vive en `remember` (es
 * efimera y no merece sobrevivir al config change).
 *
 * @param onExito Callback tras login exitoso (navega a HOME).
 * @param onNavegarRegistro Callback para ir a la pantalla de registro.
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de login (Hilt, scoped al NavBackStackEntry).
 */
@Composable
fun PantallaLogin(
    onExito: () -> Unit,
    onNavegarRegistro: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: LoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(STARTED) {
            viewModel.eventos.collect { evento ->
                when (evento) {
                    is LoginEvento.Exito -> onExito()
                    is LoginEvento.Error -> onMensaje(TipoMensaje.ERROR, evento.mensaje)
                }
            }
        }
    }

    var usuario by rememberSaveable { mutableStateOf("") }
    // Audit fix S3: la contraseña con `remember` (NO rememberSaveable) — no
    // viaja al Bundle de instancia ni sobrevive process death en claro.
    var password by remember { mutableStateOf("") }
    var mostrarPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Brand Intro ───
        BrandHeader(
            subtitulo = "Tu centro de control para navegar con confianza.",
            etiquetaSuperior = "ACCESO SEGURO",
        )

        // ─── Login Form Card ───
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
                Text(
                    text = "Bienvenido de nuevo",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CyberTextoPrincipal
                )
                Text(
                    text = "Inicia sesion para gestionar tus alertas y proteger tus accesos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )

                // Usuario field group
                Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
                    Text(
                        text = "Usuario",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTextoSecundario
                    )
                    OutlinedTextField(
                        value = usuario,
                        onValueChange = { usuario = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Escribe tu usuario") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                modifier = Modifier.size(TamanosIcono.estandar)
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(RadioBorde.md),
                        colors = coloresCampoTexto()
                    )
                }

                // Contrasena field group
                CampoPassword(
                    value = password,
                    onValueChange = { password = it },
                    mostrarPassword = mostrarPassword,
                    onTogglePassword = { mostrarPassword = !mostrarPassword },
                    placeholder = "Introduce tu contrasena",
                    label = "Contrasena",
                )

                // WAVE 22 fix: eliminado el TextButton "Olvidaste tu contrasena?"
                // — era un boton muerto (onClick vacio) sin ruta de recuperacion
                // definida. Re-añadir solo cuando se implemente el flujo de
                // recuperacion (evitaUI prometedora sin funcion).

                // Primary Login button
                BotonSubmit(
                    texto = "Iniciar sesion",
                    procesando = uiState.procesando,
                    onClick = {
                        viewModel.onAction(
                            LoginAction.Autenticar(
                                nombreUsuario = usuario,
                                password = password
                            )
                        )
                    },
                    mostrarIcono = true,
                )

                // Trust note
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = CyberTextoSecundario,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Tus datos se mantienen protegidos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextoSecundario
                    )
                }
            }
        }

        // ─── Create Account Footer ───
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = "Aun no tienes cuenta?",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )
            TextButton(onClick = onNavegarRegistro) {
                Text(
                    text = "Crear cuenta",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberCyan
                )
            }
        }
    }
}
