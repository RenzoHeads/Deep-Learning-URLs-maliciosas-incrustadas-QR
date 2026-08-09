package com.qrsecurity.detector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.camera.ModuloCamara
import com.qrsecurity.detector.pipeline.PipelineViewModel
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
import kotlinx.coroutines.launch

/**
 * Pantalla de Inicio / Dashboard (Pencil frame VgSxr).
 *
 * F3.2: UI Compose que replica el layout de Pencil VgSxr. Hospeda la camara
 * con [ModuloCamara] para deteccion automatica de QR codes. El flujo es:
 *
 * 1. Camera viewfinder (AndroidView + PreviewView) muestra la camara en vivo.
 * 2. [ModuloCamara] detecta QR codes via ML Kit y llama a `onQrDetectado`.
 * 3. `onQrDetectado` despacha `pipelineViewModel.analizar(payload)` en una
 *    corutina (guardado por `analizando` para evitar double-disparo).
 * 4. Cuando `analizando` transiciona a `true`, un [LaunchedEffect] llama a
 *    [onEscanear] para navegar a AnalisisScreen (que muestra el progreso).
 * 5. AnalisisScreen maneja los estados terminales (ResultadoListo,
 *    UrlDuplicada, Error, NoUrl).
 *
 * El ciclo de vida de la camara se vincula al [LocalLifecycleOwner] via
 * [DisposableEffect]: ON_RESUME → iniciar, ON_PAUSE → detener, dispose →
 * liberar scanner. El callback `onQrDetectado` se refresca en cada
 * recomposition via [ModuloCamara.setOnQrDetectado] (H1 fix — evita
 * stale callbacks que referencian estado obsoleto).
 *
 * @param onEscanear Callback para navegar a la pantalla de analisis.
 * @param onVerHistorial Callback para navegar al historial.
 * @param datosViewModel VM compartido con los contadores de escaneos.
 * @param sessionViewModel VM de sesion para mostrar info del usuario.
 * @param pipelineViewModel VM del pipeline (compartido a nivel NavGuardian).
 */
@Composable
fun PantallaHome(
    onEscanear: () -> Unit,
    onVerHistorial: () -> Unit,
    datosViewModel: DatosTabsViewModel,
    pipelineViewModel: PipelineViewModel
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analizando by pipelineViewModel.analizando.collectAsStateWithLifecycle()
    val totalEscaneos by datosViewModel.totalEscaneos.collectAsStateWithLifecycle()
    val amenazas by datosViewModel.amenazas.collectAsStateWithLifecycle()

    var moduloCamara by remember { mutableStateOf<ModuloCamara?>(null) }
    var yaNavegoAnalisis by rememberSaveable { mutableStateOf(false) }

    // ── Navegacion a AnalisisScreen cuando inicia el analisis ──
    // Rising-edge guard: solo navega en la transicion false→true de
    // `analizando`. Sin este guard, al volver a HOME con un analisis
    // todavia en vuelo, `analizando=true` re-dispararia onEscanear()
    // y crearia un loop de navegacion HOME→ANALISIS→HOME→...
    LaunchedEffect(analizando) {
        if (analizando && !yaNavegoAnalisis) {
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

    // ── Refresh del callback QR (H1 fix — evita stale callbacks) ──
    LaunchedEffect(analizando) {
        moduloCamara?.setOnQrDetectado { payload ->
            if (!analizando) {
                scope.launch { pipelineViewModel.analizar(payload) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.xxxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.xxl)
    ) {
        // ─── Brand header ───
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
            Column {
                Text(
                    text = "SeguridadQR",
                    style = MaterialTheme.typography.titleLarge,
                    color = CyberTextoPrincipal
                )
                Text(
                    text = "ESCANEANDO",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberCyan
                )
            }
        }

        // ─── Camera viewfinder ───
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(RadioBorde.xxl))
                .background(CyberGlass)
        ) {
            // Camera preview
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

            // QR glyph overlay (semi-transparent)
            Icon(
                imageVector = Icons.Filled.QrCode2,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.12f),
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.Center)
            )

            // Finder corners (L-shaped accents)
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Espaciado.lg)
            ) {
                val cornerLen = 28.dp.toPx()
                val strokeW = 3.dp.toPx()
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
        }

        // ─── "Apunta y escanea" prompt ───
        Text(
            text = "Apunta y escanea",
            style = MaterialTheme.typography.bodyLarge,
            color = CyberTextoSecundario,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        // ─── Scan button ───
        Button(
            onClick = onEscanear,
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo
            )
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(
                text = "Escanear",
                style = MaterialTheme.typography.labelLarge
            )
        }

        // ─── Stats row ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            TarjetaEstadistica(
                etiqueta = "Escaneos",
                valor = totalEscaneos,
                modifier = Modifier.weight(1f)
            )
            TarjetaEstadistica(
                etiqueta = "Amenazas",
                valor = amenazas,
                modifier = Modifier.weight(1f)
            )
        }

        // ─── Ver historial ───
        TextButton(
            onClick = onVerHistorial,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Ver historial",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberCyan
            )
        }
    }
}

/**
 * Tarjeta de estadistica mini — muestra un contador con etiqueta.
 * Usada en el dashboard de Home para total escaneos y amenazas.
 */
@Composable
private fun TarjetaEstadistica(
    etiqueta: String,
    valor: Int?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(RadioBorde.xl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
    ) {
        Column(
            modifier = Modifier.padding(Espaciado.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = valor?.toString() ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
            Text(
                text = etiqueta,
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
        }
    }
}
