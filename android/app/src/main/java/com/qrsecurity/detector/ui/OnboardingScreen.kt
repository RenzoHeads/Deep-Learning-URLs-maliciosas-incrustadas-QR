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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Bug A11 fix: nombre del archivo SharedPreferences y clave para guardar
// el flag "onboarding completado". Se lee en NavGuardian para decidir si
// mostrar Onboarding tras login. Antes, el flag no se persistia y el
// onboarding reaparecia cada vez que el usuario cerraba y reabría la app
// o volvia a hacer login.
internal const val PREFS_QR_GUARDIAN = "qr_guardian_prefs"
internal const val CLAVE_ONBOARDING_COMPLETADO = "onboarding_completado"

/**
 * Pantalla de Onboarding — 3 paginas con walkthrough.
 *
 * Pagina 1: Escudo QR Guardian + "Proteccion en tu bolsillo"
 * Pagina 2: Escaneo QR + "Analisis on-device con IA"
 * Pagina 3: Verificado + "Tu privacidad primero"
 *
 * Al final, boton "Comenzar" navega a [Rutas.ESCANEAR].
 *
 * Bug A11 fix: persiste "onboarding_completado=true" en SharedPreferences
 * cuando el usuario pulsa "Comenzar" (o "Saltar"). Antes el onboarding se
 * volvia a mostrar tras cada login porque no se guardaba la finalizacion.
 */
@Composable
fun PantallaOnboarding(
    onComenzar: () -> Unit
) {
    val context = LocalContext.current
    val estadoPagina = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // Bug A11 fix: marca el onboarding como completado en SharedPreferences
    // antes de invocar onComenzar(). Asi, la proxima vez que el usuario abra
    // la app (o vuelva a loguearse), NavGuardian saltara directo a Escanear
    // sin volver a mostrar este onboarding.
    // M3 fix: commit() sincrono en vez de apply() async. Si la Activity se
    // recrea (rotacion) o el proceso muere entre apply() y el siguiente
    // arranque, el flag podia no haberse persistido al disco y el onboarding
    // reaparecia. commit() bloquea hasta el fsync — el flag queda escrito
    // antes de navegar a Escanear.
    //
    // Bug D3-P2 (Lote H): commit() hace un fsync sobre el disco, lo cual
    // puede tardar varios milisegundos (estimacion ~5-50 ms en dispositivos
    // gama baja con almacenamiento cifrado — prefs esta en
    // `/data/data/<pkg>/shared_prefs/`). Si la llamada era sincrona en el
    // main thread (como en el fix M3 original), una sola llamada podia
    // provocar un frame drop; en casos raros (dispositivo lento + UI
    // heavy), rozaba el umbral ANR de 5 s. Ahora envolvemos el `commit()`
    // en una corutina con `Dispatchers.IO`, y solo disparamos `onComenzar()`
    // cuando la escritura se completa. Benefit: no bloqueamos el main
    // thread + garantizamos durabilidad (igual que commit() sincrono, pero
    // fuera del main). Trade-off: el usuario puede percibir un delay de
    // ~50 ms entre el tap y la navegacion — tolerable para un flag onboarding.
    fun completarOnboarding() {
        scope.launch {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(PREFS_QR_GUARDIAN, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(CLAVE_ONBOARDING_COMPLETADO, true)
                    .commit()
            }
            // Solo navega tras confirmar que el flag fue persistido.
            onComenzar()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Logo superior + Saltar (Bug 20 fix) ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "QR GUARDIAN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CyberCyan
                )
            }
            // Bug 20 fix: boton "Saltar" para usuarios recurrentes que no
            // quieren paginar las 3 pantallas; llama onComenzar() directo.
            // Bug A11 fix: llama completarOnboarding() para persistir el flag.
            androidx.compose.material3.TextButton(onClick = { completarOnboarding() }) {
                Text(
                    text = "Saltar",
                    style = MaterialTheme.typography.labelLarge,
                    color = CyberTextoSecundario
                )
            }
        }

        // ── Pager de 3 paginas ──
        HorizontalPager(
            state = estadoPagina,
            modifier = Modifier.fillMaxWidth()
        ) { pagina ->
            PaginaOnboarding(indice = pagina)
        }

        // ── Indicadores de pagina ──
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(3) { indice ->
                // Bug 9 fix: antes `Modifier.size(24/8, 8)` aplastaba el dot
                // activo a 24×8 (barra horizontal 3:1) mientras los inactivos
                // eran 8×8 (cuadrados). Ahora ambos miden 8dp de alto y solo
                // el ancho cambia → pill 24×8 vs cuadrado 8×8, aspect ratio
                // consistente con clip(RoundedCornerShape(4.dp)).
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .then(
                            if (indice == estadoPagina.currentPage) Modifier.width(24.dp)
                            else Modifier.width(8.dp)
                        )
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (indice == estadoPagina.currentPage) CyberVerdeAlerta
                            else CyberTextoSecundario.copy(alpha = 0.3f)
                        )
                )
            }
        }

        // ── Boton Comenzar / Siguiente ──
        Button(
            onClick = {
                if (estadoPagina.currentPage < 2) {
                    scope.launch { estadoPagina.animateScrollToPage(estadoPagina.currentPage + 1) }
                } else {
                    // Bug A11 fix: persistir onboarding completado antes de
                    // navegar a Escanear. Reemplaza `onComenzar()` directo.
                    completarOnboarding()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo
            )
        ) {
            Text(
                text = if (estadoPagina.currentPage < 2) "Siguiente" else "Comenzar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PaginaOnboarding(indice: Int) {
    val (icono, titulo, descripcion) = when (indice) {
        0 -> Triple(
            Icons.Filled.Security,
            "Proteccion en tu bolsillo",
            "Escanea cualquier codigo QR y detecta URLs maliciosas antes de abrir el enlace."
        )
        1 -> Triple(
            Icons.Filled.QrCodeScanner,
            "Analisis on-device con IA",
            "El modelo CANINE-S Transformer (~500M) corre en tu telefono. Sin nube, sin latencia."
        )
        2 -> Triple(
            Icons.Filled.VerifiedUser,
            "Tu privacidad primero",
            "Todo el analisis ocurre en el dispositivo. Tus datos nunca salen de tu telefono."
        )
        else -> Triple(Icons.Filled.Security, "", "")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── Icono en circulo con glow cyan ──
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CyberCyan.copy(alpha = 0.2f), CyberFondo)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icono,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(64.dp)
            )
        }

        // ── Titulo ──
        Text(
            text = titulo,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal,
            textAlign = TextAlign.Center
        )

        // ── Descripcion ──
        Text(
            text = descripcion,
            style = MaterialTheme.typography.bodyLarge,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center
        )
    }
}
