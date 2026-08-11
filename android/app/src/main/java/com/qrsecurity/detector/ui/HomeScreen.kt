package com.qrsecurity.detector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.camera.ModuloCamara
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.PencilBrandMark
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import kotlinx.coroutines.launch

/**
 * Pantalla de Inicio — camara full-bleed con overlay minimal.
 *
 * Camera-first design: el viewfinder llena toda la pantalla. Sobre el
 * feed se superponen:
 *  - Brand header (top-left): shield icon + "SeguridadQR"
 *  - Finder corners (L-shaped accents): marco visual para guiar al QR
 *  - Floating pill (bottom-center): "Apunta y escanea"
 *
 * La nav bar inferior ya expone Historial y Ajustes, por lo que los
 * botones redundantes ("Ver historial", stats cards) fueron eliminados.
 *
 * Logica preservada intacta:
 *  1. Camera viewfinder (AndroidView + PreviewView) muestra la camara en vivo.
 *  2. [ModuloCamara] detecta QR codes via ML Kit y llama a `onQrDetectado`.
 *  3. `onQrDetectado` despacha `pipelineViewModel.analizar(payload)` en una
 *    corutina (guardado por `analizando` para evitar double-disparo).
 *  4. Cuando `analizando` transiciona a `true`, un [LaunchedEffect] llama a
 *    [onEscanear] para navegar a AnalisisScreen (que muestra el progreso).
 *  5. El dialogo "URL ya escaneada" aparece sobre el viewfinder vivo.
 *
 * @param onEscanear Callback para navegar a la pantalla de analisis.
 * @param onVerHistorial Callback (no usado en UI pero preservado por contrato).
 * @param datosViewModel VM compartido con los contadores de escaneos.
 * @param pipelineViewModel VM del pipeline (compartido a nivel NavGuardian).
 */
@Composable
fun PantallaHome(
    onEscanear: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onVerHistorial: () -> Unit,
    @Suppress("UNUSED_PARAMETER") datosViewModel: DatosTabsViewModel,
    pipelineViewModel: PipelineViewModel
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analizando by pipelineViewModel.analizando.collectAsStateWithLifecycle()
    val estado by pipelineViewModel.estado.collectAsStateWithLifecycle()

    var moduloCamara by remember { mutableStateOf<ModuloCamara?>(null) }
    var yaNavegoAnalisis by rememberSaveable { mutableStateOf(false) }

    // ── Navegacion a AnalisisScreen cuando inicia el analisis ──
    LaunchedEffect(analizando, estado) {
        if (analizando && !yaNavegoAnalisis &&
            estado !is Pipeline.Estado.Escaneando &&
            estado !is Pipeline.Estado.Inicializando &&
            estado !is Pipeline.Estado.UrlDuplicada
        ) {
            yaNavegoAnalisis = true
            onEscanear()
        } else if (!analizando) {
            yaNavegoAnalisis = false
        }
    }

    // ── Ciclo de vida de la camara ──
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> moduloCamara?.iniciar()
                Lifecycle.Event.ON_PAUSE -> moduloCamara?.detener()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            moduloCamara?.detener()
            moduloCamara?.liberarEscaner()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ── Refresh del callback QR (H1 fix) ──
    LaunchedEffect(analizando, estado) {
        moduloCamara?.setOnQrDetectado { payload ->
            if (!analizando && estado !is Pipeline.Estado.UrlDuplicada) {
                scope.launch { pipelineViewModel.analizar(payload) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
    ) {
        // ─── Camera viewfinder (full-bleed) ───
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    val modulo = ModuloCamara(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onQrDetectado = { payload ->
                            if (!analizando) {
                                scope.launch { pipelineViewModel.analizar(payload) }
                            }
                        }
                    )
                    moduloCamara = modulo
                    modulo.iniciar()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ─── QR glyph overlay (very subtle) ───
        Icon(
            imageVector = Icons.Filled.QrCode2,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.06f),
            modifier = Modifier
                .size(TamanosIcono.heroContenedor)
                .align(Alignment.Center)
        )

        // ─── Finder corners (L-shaped accents, more prominent) ───
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(Espaciado.xxl)
        ) {
            val cornerLen = 36.dp.toPx()
            val strokeW = 4.dp.toPx()
            val color = CyberCyan
            val w = size.width
            val h = size.height

            // Top-left
            drawLine(color, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW)
            drawLine(color, Offset(0f, 0f), Offset(0f, cornerLen), strokeW)
            // Top-right
            drawLine(color, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeW)
            drawLine(color, Offset(w, 0f), Offset(w, cornerLen), strokeW)
            // Bottom-left
            drawLine(color, Offset(0f, h), Offset(cornerLen, h), strokeW)
            drawLine(color, Offset(0f, h), Offset(0f, h - cornerLen), strokeW)
            // Bottom-right
            drawLine(color, Offset(w, h), Offset(w - cornerLen, h), strokeW)
            drawLine(color, Offset(w, h), Offset(w, h - cornerLen), strokeW)
        }

        // ─── Brand header (overlay, top-left) ───
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
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
            Text(
                text = "SeguridadQR",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
        }

        // ─── "Apunta y escanea" floating pill (overlay, bottom-center) ───
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Espaciado.xxl)
                .clip(RoundedCornerShape(50))
                .background(CyberGlass.copy(alpha = 0.85f))
                .padding(horizontal = Espaciado.xl, vertical = Espaciado.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCodeScanner,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Text(
                    text = "Apunta y escanea",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = CyberTextoPrincipal
                )
            }
        }
    }

    // ── Dialogo "URL ya escaneada" sobre el viewfinder de la camara ──
    val duplicada = estado as? Pipeline.Estado.UrlDuplicada
    if (duplicada != null) {
        AlertDialog(
            onDismissRequest = { pipelineViewModel.cancelarReescaneo() },
            containerColor = CyberGlassAlto,
            titleContentColor = CyberTextoPrincipal,
            textContentColor = CyberTextoSecundario,
            shape = RoundedCornerShape(RadioBorde.lg),
            title = { Text("URL ya escaneada") },
            text = {
                Text(
                    text = "Esta URL ya fue escaneada " +
                        "${duplicada.vecesEscaneadaMaxima} vez(es). " +
                        "\u00bfDeseas reescanearla de todas formas?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { pipelineViewModel.confirmarReescaneo() }
                }) {
                    Text("Reescanear", color = CyberCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pipelineViewModel.cancelarReescaneo()
                }) {
                    Text("Cancelar", color = CyberTextoSecundario)
                }
            }
        )
    }
}
