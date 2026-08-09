package com.qrsecurity.detector.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Bug A11 fix: nombre del archivo SharedPreferences y clave para guardar
// el flag "onboarding completado". Se lee en NavGuardian para decidir si
// mostrar Onboarding tras login. Antes, el flag no se persistia y el
// onboarding reaparecia cada vez que el usuario cerraba y reabria la app
// o volvia a hacer login.
//
// F2.1: las constantes se migraron a [ConstantesApp] para que [NavGuardian]
// deje de depender de esta pantalla (que se eliminara en F3). Los usos
// internos referencian `ConstantesApp.*` directamente.

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

    fun completarOnboarding() {
        scope.launch {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences(ConstantesApp.PREFS_QR_GUARDIAN, android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(ConstantesApp.CLAVE_ONBOARDING_COMPLETADO, true)
                    .commit()
            }
            onComenzar()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CabeceraOnboarding(onSaltara = { completarOnboarding() })

        Spacer(modifier = Modifier.height(Espaciado.lg))

        HorizontalPager(
            state = estadoPagina,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { pagina ->
            PaginaOnboarding(indice = pagina)
        }

        IndicadoresPagina(paginaActual = estadoPagina.currentPage)

        BotonAccionOnboarding(
            esUltimaPagina = estadoPagina.currentPage >= 2,
            onSiguiente = {
                scope.launch { estadoPagina.animateScrollToPage(estadoPagina.currentPage + 1) }
            },
            onComenzar = { completarOnboarding() }
        )
    }
}

/**
 * Cabecera del onboarding — logo "QR GUARDIAN" a la izquierda, boton
 * "Saltar" a la derecha. Extraida para reducir complejidad cognitiva (S3776).
 */
@Composable
private fun CabeceraOnboarding(onSaltara: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
        ) {
            Icon(
                imageVector = Icons.Filled.Security,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Text(
                text = "QR GUARDIAN",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CyberCyan
            )
        }
        androidx.compose.material3.TextButton(onClick = onSaltara) {
            Text(
                text = "Saltar",
                style = MaterialTheme.typography.labelLarge,
                color = CyberTextoSecundario
            )
        }
    }
}

/**
 * Indicadores de pagina (puntos animados). Extraido para reducir
 * complejidad cognitiva (S3776).
 */
@Composable
private fun IndicadoresPagina(paginaActual: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Espaciado.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { indice ->
            val activo = indice == paginaActual
            val anchoIndicador by animateFloatAsState(
                targetValue = if (activo) 32f else 8f,
                animationSpec = tween(300),
                label = "indicador_$indice"
            )
            Box(
                modifier = Modifier
                    .height(8.dp)
                    .width(anchoIndicador.dp)
                    .clip(RoundedCornerShape(RadioBorde.sm))
                    .background(
                        if (activo) CyberCyan
                        else CyberTextoSecundario.copy(alpha = 0.3f)
                    )
            )
            if (indice < 2) {
                Spacer(modifier = Modifier.width(Espaciado.sm))
            }
        }
    }
}

/**
 * Boton de accion del onboarding — "Siguiente" o "Comenzar" segun la
 * pagina actual. Extraido para reducir complejidad cognitiva (S3776).
 */
@Composable
private fun BotonAccionOnboarding(
    esUltimaPagina: Boolean,
    onSiguiente: () -> Unit,
    onComenzar: () -> Unit
) {
    Button(
        onClick = if (esUltimaPagina) onComenzar else onSiguiente,
        modifier = Modifier
            .fillMaxWidth()
            .height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.xl),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyberCyan,
            contentColor = CyberFondo
        )
    ) {
        Text(
            text = if (esUltimaPagina) "Comenzar" else "Siguiente",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Pagina individual del onboarding — card glassmorphism con icono grande,
 * titulo y descripcion. El icono tiene un glow cyan radial y animacion de
 * entrada escalado.
 */
@Composable
private fun PaginaOnboarding(indice: Int) {
    val datos = datosPagina(indice)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Espaciado.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // ── Icono en circulo glow ──
        // Circulo grande (120dp) con gradiente radial para presencia visual.
        // El icono interior mide 56dp — 46% del contenedor, centrado.
        Box(
            modifier = Modifier
                .size(TamanosIcono.heroContenedor) // 120dp
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            datos.colorAcento.copy(alpha = 0.25f),
                            CyberFondo.copy(alpha = 0.0f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Anillo de borde
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.Transparent,
                                datos.colorAcento.copy(alpha = 0.08f)
                            ),
                            radius = 120f
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = datos.colorAcento.copy(alpha = 0.3f),
                        shape = CircleShape
                    )
            )
            Icon(
                imageVector = datos.icono,
                contentDescription = null,
                tint = datos.colorAcento,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(Espaciado.xxxl))

        // ── Titulo ──
        Text(
            text = datos.titulo,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(Espaciado.md))

        // ── Descripcion ──
        Text(
            text = datos.descripcion,
            style = MaterialTheme.typography.bodyLarge,
            color = CyberTextoSecundario,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Espaciado.lg)
        )

        Spacer(modifier = Modifier.height(Espaciado.xxxl))

        // ── Step indicator ──
        Text(
            text = "${indice + 1} / 3",
            style = MaterialTheme.typography.labelMedium,
            color = datos.colorAcento.copy(alpha = 0.7f),
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Devuelve los datos (icono, titulo, descripcion, color de acento) de la
 * pagina del onboarding en el indice dado. Funcion pura extraida del
 * Composable para reducir complejidad cognitiva (S3776).
 */
private fun datosPagina(indice: Int): PaginaOnboarding = when (indice) {
    0 -> PaginaOnboarding(
        icono = Icons.Filled.Security,
        titulo = "Proteccion en tu bolsillo",
        descripcion = "Escanea cualquier codigo QR y detecta URLs maliciosas antes de abrir el enlace.",
        colorAcento = CyberCyan
    )
    1 -> PaginaOnboarding(
        icono = Icons.Filled.QrCodeScanner,
        titulo = "Analisis on-device con IA",
        descripcion = "El modelo CANINE-S Transformer corre en tu telefono. Sin nube, sin latencia.",
        colorAcento = CyberCyan
    )
    2 -> PaginaOnboarding(
        icono = Icons.Filled.VerifiedUser,
        titulo = "Tu privacidad primero",
        descripcion = "Todo el analisis ocurre en el dispositivo. Tus datos nunca salen de tu telefono.",
        colorAcento = CyberVerdeAlerta
    )
    else -> PaginaOnboarding(
        icono = Icons.Filled.Security,
        titulo = "",
        descripcion = "",
        colorAcento = CyberCyan
    )
}

/**
 * Datos de una pagina del onboarding — data class para tipado seguro.
 */
private data class PaginaOnboarding(
    val icono: ImageVector,
    val titulo: String,
    val descripcion: String,
    val colorAcento: Color
)
