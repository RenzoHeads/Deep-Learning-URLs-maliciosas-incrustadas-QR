package com.qrsecurity.detector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle.State.STARTED
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado

/**
 * Pantalla de Registro (Pencil frame fZjhl) — alta embebida en Auth0.
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
 * El alta es 100% nativa (sin navegador): correo+password van por TLS
 * directo a Auth0, que crea la cuenta y deja la sesion iniciada. La
 * password jamas se persiste en el dispositivo.
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

    // M13: RecolectorEventos encapsula el boilerplate repeatOnLifecycle.
    RecolectorEventos(viewModel.eventos) { evento ->
        when (evento) {
            is RegistroEvento.Exito -> onExito()
            is RegistroEvento.Error -> onMensaje(TipoMensaje.ERROR, evento.mensaje)
        }
    }

    var correo by rememberSaveable { mutableStateOf("") }
    var nombre by rememberSaveable { mutableStateOf("") }
    // Audit fix S3: las contraseñas con `remember` (NO rememberSaveable) —
    // no viajan al Bundle de instancia ni sobreviven process death en claro.
    var password by remember { mutableStateOf("") }
    var confirmarPassword by remember { mutableStateOf("") }
    var mostrarPassword by remember { mutableStateOf(false) }
    var mostrarConfirmarPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Brand Header ───
        BrandHeader(
            subtitulo = "Protección inteligente",
        )

        // ─── Registration Intro ───
        Column(verticalArrangement = Arrangement.spacedBy(Espaciado.xs)) {
            Text(
                text = "Crear cuenta",
                style = MaterialTheme.typography.headlineMedium,
                color = CyberTextoPrincipal
            )
            Text(
                    text = "Regístrate para analizar enlaces y navegar con confianza.",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )
        }

        // ─── Registration Form ───
        // M13: receta Card glass absorbida por TarjetaCyber.
        TarjetaCyber {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
            ) {
                // Correo electronico
                CampoTexto(
                    value = correo,
                    onValueChange = { correo = it },
                    label = "Correo",
                    placeholder = "correo@ejemplo.com",
                    icono = Icons.Filled.Email,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )

                // Nombre (opcional — display name)
                CampoTexto(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = "Nombre (opcional)",
                    placeholder = "Tu nombre",
                    icono = Icons.Filled.Person,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )

                // Contrasena
                CampoPassword(
                    value = password,
                    onValueChange = { password = it },
                    mostrarPassword = mostrarPassword,
                    onTogglePassword = { mostrarPassword = !mostrarPassword },
                    placeholder = "Crea una contraseña (mín. 15)",
                    label = "Contraseña",
                )

                // Confirmar contrasena
                CampoPassword(
                    value = confirmarPassword,
                    onValueChange = { confirmarPassword = it },
                    mostrarPassword = mostrarConfirmarPassword,
                    onTogglePassword = { mostrarConfirmarPassword = !mostrarConfirmarPassword },
                    placeholder = "Confirmar contraseña",
                    label = "Confirmar contraseña",
                )

                BotonCyber(
                    texto = "Crear cuenta",
                    procesando = uiState.procesando,
                    onClick = {
                        viewModel.onAction(
                            RegistroAction.Registrar(
                                correo = correo,
                                nombre = nombre,
                                password = password,
                                confirmarPassword = confirmarPassword
                            )
                        )
                    },
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
                text = "¿Ya tienes una cuenta?",
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
                    text = "Iniciar sesión",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberCyan
                )
            }
        }
    }
}
