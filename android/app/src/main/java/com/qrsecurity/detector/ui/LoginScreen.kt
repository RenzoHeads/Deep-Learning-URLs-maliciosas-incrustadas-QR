package com.qrsecurity.detector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
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
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Pantalla de Login (Pencil frame Jitpw) — auth embebida Auth0.
 *
 * F3.1: UI Compose que replica el layout de Pencil Jitpw. Patron UDF:
 * - estado observado desde [LoginViewModel.uiState] via
 *   `collectAsStateWithLifecycle` (sobrevive a rotacion sin re-emitir).
 * - eventos one-shot del Channel [LoginViewModel.eventos] recolectados
 *   con `LaunchedEffect` + `repeatOnLifecycle(STARTED)` (Bug L1 fix:
 *   evita re-disparar navegacion/snackbar en rotacion).
 * - acciones despachadas via `viewModel.onAction(LoginAction.Autenticar(...))`.
 *
 * El login es 100% nativo (sin navegador): correo+password van por TLS
 * directo a Auth0 y la password jamas se persiste.
 *
 * El estado del correo vive en `rememberSaveable` (sobrevive a
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

    // M13: RecolectorEventos encapsula el boilerplate repeatOnLifecycle.
    RecolectorEventos(viewModel.eventos) { evento ->
        when (evento) {
            is LoginEvento.Exito -> onExito()
            is LoginEvento.Error -> onMensaje(TipoMensaje.ERROR, evento.mensaje)
        }
    }

    var correo by rememberSaveable { mutableStateOf("") }
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
        // M13: receta Card glass absorbida por TarjetaCyber.
        TarjetaCyber {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
            ) {
                Text(
                    text = "Bienvenido de nuevo",
                    style = MaterialTheme.typography.headlineMedium,
                    color = CyberTextoPrincipal
                )
                Text(
                    text = "Inicia sesión para gestionar tus alertas y proteger tus accesos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )

                // Correo field group
                CampoTexto(
                    value = correo,
                    onValueChange = { correo = it },
                    label = "Correo",
                    placeholder = "correo@ejemplo.com",
                    icono = Icons.Filled.Email,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )

                // Contrasena field group
                CampoPassword(
                    value = password,
                    onValueChange = { password = it },
                    mostrarPassword = mostrarPassword,
                    onTogglePassword = { mostrarPassword = !mostrarPassword },
                    placeholder = "Introduce tu contraseña",
                    label = "Contraseña",
                )

                // WAVE 22 fix: sin boton "Olvidaste tu contrasena?" — seria un
                // boton muerto (no hay ruta de recuperacion en la app). El
                // reset se hace desde la pagina web de Auth0 si se activa.

                // Primary Login button (S5: BotonCyber absorbió a BotonSubmit)
                BotonCyber(
                    texto = "Iniciar sesión",
                    procesando = uiState.procesando,
                    onClick = {
                        viewModel.onAction(
                            LoginAction.Autenticar(
                                correo = correo,
                                password = password
                            )
                        )
                    },
                    icono = Icons.AutoMirrored.Filled.ArrowForward,
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
                        modifier = Modifier.size(TamanosIcono.chico)
                    )
                    Text(
                        text = "Tus credenciales se autentican de forma segura con Auth0.",
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
                text = "¿Aún no tienes una cuenta?",
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
