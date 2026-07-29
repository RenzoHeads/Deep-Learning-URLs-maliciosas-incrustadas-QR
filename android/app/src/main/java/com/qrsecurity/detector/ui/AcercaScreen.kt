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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario

// SonarQube S1192 fix: el literal "Cerrar sesion" se duplicaba 3 veces.
private const val TEXTO_CERRAR_SESION = "Cerrar sesion"

/**
 * Pantalla Acerca de / Ayuda — cyber-sentinel design.
 *
 * Secciones:
 *  - Logo QR Guardian + version
 *  - Tarjeta "Que es QR Guardian?"
 *  - Tarjeta "Modelo CANINE-S" (IA on-device)
 *  - Tarjeta "Privacidad" (todo en el dispositivo)
 *  - Tarjeta "Creditos"
 *  - Tarjeta "Reportar bug"
 *  - Boton "Cerrar sesion" (Bug D4-P1 fix Lote H): cablea el
 *    [com.qrsecurity.detector.sesion.LogoutCoordinator] a la UI. Antes
 *    [LogoutCoordinator] estaba definido pero no tenia ningun llamante en la
 *    app, asi que el borrado completo de Room al logout nunca se ejecutaba —
 *    el siguiente usuario heredaba el historial del anterior (bug H7
 *    latent). Ahora el usuario puede disparar el logout desde la pantalla
 *    Acerca de.
 *
 * @param onVolver Callback para volver a la pantalla anterior.
 * @param onCerrarSesion Callback para cerrar sesion del usuario y limpiar
 *   todo el estado persistido (Room, token, etc.). Se disparara despues de
 *   confirmar con un AlertDialog de "estas seguro?". El caller (NavGuardian)
 *   lanza una corutina IO que invoca [LogoutCoordinator.logout] y luego
 *   navega a la pantalla de Login.
 */
@Composable
fun PantallaAcerca(
    onVolver: () -> Unit,
    onCerrarSesion: () -> Unit = {},
) {
    val estadoScroll = rememberScrollState()

    // Bug D4-P1 (fix Lote H): AlertDialog de confirmacion antes de cerrar
    // sesion. Cerrar sesion es irreversible (vacia el historial Room) —
    // pedir confirmacion evita perder datos por un tap accidental.
    var mostrarDialogoLogout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(estadoScroll)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Top bar con boton volver ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onVolver) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = CyberCyan
                )
            }
        }

        // ── Logo con glow cyan ──
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(CyberCyan.copy(alpha = 0.2f), CyberFondo)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(56.dp)
            )
        }

        Text(
            text = "QR GUARDIAN",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyberCyan
        )

        Text(
            text = "Version 1.0.0",
            style = MaterialTheme.typography.labelLarge,
            color = CyberTextoSecundario
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Tarjeta: Que es? ──
        TarjetaAcerca(
            icono = Icons.Filled.Info,
            titulo = "Que es QR Guardian?",
            descripcion = "QR Guardian es una app Android que escanea codigos QR y detecta URLs maliciosas (phishing) usando Deep Learning. Todo el analisis ocurre en tu dispositivo."
        )

        // ── Tarjeta: Modelo CANINE-S ──
        TarjetaAcerca(
            icono = Icons.Filled.Memory,
            titulo = "Modelo CANINE-S",
            descripcion = "La deteccion se realiza con un Transformer CANINE-S (~500M parametros), convertido a TFLite INT8 para inferencia on-device. El delegado de hardware se auto-selecciona (NNAPI → GPU → CPU)."
        )

        // ── Tarjeta: Privacidad ──
        TarjetaAcerca(
            icono = Icons.Filled.Lock,
            titulo = "Privacidad",
            descripcion = "Todo el analisis ocurre en el dispositivo. Nada se envia a la nube. Tu historial de escaneos permanece privado."
        )

        // ── Tarjeta: Creditos ──
        TarjetaAcerca(
            icono = Icons.Filled.Code,
            titulo = "Creditos",
            descripcion = "Modelo CANINE-S: Clark et al. (2022), Google. TFLite: TensorFlow Team. UI: Cyber-Sentinel Design System."
        )

        // ── Tarjeta: Reportar bug ──
        TarjetaAcerca(
            icono = Icons.Filled.BugReport,
            titulo = "Reportar un bug",
            descripcion = "Si encuentras algun problema con la app, puedes reportarlo para ayudarnos a mejorar QR Guardian."
        )

        // Bug D4-P1 (fix Lote H): boton "Cerrar sesion" — antes no habia
        // forma en la UI de invocar [LogoutCoordinator], asi que el
        // logout completo (clearAllTables + token) nunca se ejecutaba.
        // El usuario solo podia "borrar la app" para resetear el estado.
        OutlinedButton(
            onClick = { mostrarDialogoLogout = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("btn_logout_pantalla"),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CyberRojo)
        ) {
            Icon(
                imageVector = Icons.Filled.Logout,
                contentDescription = null,
                tint = CyberRojo,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = TEXTO_CERRAR_SESION,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = CyberRojo
            )
        }
    }

    // Bug D4-P1 (fix Lote H): dialogo de confirmacion antes de cerrar sesion.
    // Cierra sesion = vaciar todo el historial Room + token — irreversible.
    if (mostrarDialogoLogout) {
        AlertDialog(
            onDismissRequest = { mostrarDialogoLogout = false },
            title = { Text(TEXTO_CERRAR_SESION) },
            text = {
                Text(
                    "Se cerrara tu sesion y se borrara todo el historial de " +
                        "escaneos, URLs bloqueadas y denuncias almacenadas " +
                        "localmente en este dispositivo. Esta accion no se " +
                        "puede deshacer.",
                    modifier = Modifier.testTag("dialog_logout_text")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        mostrarDialogoLogout = false
                        onCerrarSesion()
                    },
                    modifier = Modifier.testTag("btn_confirmar_logout")
                ) {
                    Text(TEXTO_CERRAR_SESION, color = CyberCyan, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { mostrarDialogoLogout = false },
                    modifier = Modifier.testTag("btn_cancelar_logout")
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun TarjetaAcerca(
    icono: ImageVector,
    titulo: String,
    descripcion: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberGlass)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CyberCyan.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icono, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = titulo,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextoPrincipal
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = descripcion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }
    }
}
