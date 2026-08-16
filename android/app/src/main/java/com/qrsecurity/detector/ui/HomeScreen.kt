package com.qrsecurity.detector.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.camera.DeteccionQr
import com.qrsecurity.detector.camera.ModuloCamara
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qrsecurity.detector.pipeline.Estado
import com.qrsecurity.detector.pipeline.ResultadoAnalisis
import com.qrsecurity.detector.ui.escaner.OverlayResaltadoQr
import com.qrsecurity.detector.ui.escaner.ScanReticle
import com.qrsecurity.detector.ui.escaner.qrDentroDeReticulo

/**
 * Pantalla de Inicio — camara full-bleed con overlay minimal.
 *
 * Camera-first design: el viewfinder llena toda la pantalla. Sobre el
 * feed se superponen:
 *  - Brand header (top-left): "SeguridadQR" texto + icono scanner
 *  - Scan reticle (center): marco cuadrado con esquinas cyan y linea
 *    de escaneo animada (solo en idle)
 *  - QR highlight (Google Lens style): cuando se detecta un QR, se
 *    oscurece toda la pantalla excepto el area del bounding box del QR,
 *    con un borde cyan. El bounding box se mapea de coordenadas de
 *    imagen (ML Kit) a coordenadas de pantalla usando el scale type
 *    FILL_CENTER del PreviewView.
 *  - Bottom modal (unificado): card en la parte inferior que muestra
 *    la URL detectada. Si es duplicada, muestra el mensaje "ya
 *    escaneada" + botones Reescanear/Cancelar. Si no, muestra un
 *    spinner de "Procesando..." durante los 700ms de feedback visual.
 *  - Floating pill (bottom-center): "Apunta y escanea" (solo en idle)
 *
 * Logica:
 *  1. Camera viewfinder (AndroidView + PreviewView, FILL_CENTER).
 *  2. [ModuloCamara] detecta QR codes via ML Kit y llama a
 *     `onQrDetectado` con [DeteccionQr] (payload + boundingBox +
 *     dimensiones de imagen post-rotacion).
 *  3. `onQrDetectado` pausa la deteccion y setea `deteccionQr`.
 *  4. Un [LaunchedEffect] espera 700ms (feedback visual) y despacha
 *     `pipelineViewModel.analizar(payload)`.
 *  5. Cuando `analizando` transiciona a `true`, un [LaunchedEffect]
 *     llama a [onEscanear] para navegar a AnalisisScreen.
 *  6. Al volver de AnalisisScreen (estado -> Escaneando), se limpia
 *     `deteccionQr` y se reanuda la deteccion de la camara.
 *  7. El modal inferior unificado reemplaza al overlay "QR detectado"
 *     y al AlertDialog "URL ya escaneada" por separado.
 *
 * @param onEscanear Callback para navegar a la pantalla de analisis.
 * @param pipelineViewModel VM del pipeline (compartido a nivel NavGuardian).
 */
@Composable
fun PantallaHome(
    onEscanear: () -> Unit,
    pipelineViewModel: PipelineViewModel,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analizando by pipelineViewModel.analizando.collectAsStateWithLifecycle()
    val estado by pipelineViewModel.estado.collectAsStateWithLifecycle()

    var moduloCamara by remember { mutableStateOf<ModuloCamara?>(null) }
    var yaNavegoAnalisis by rememberSaveable { mutableStateOf(false) }
    var deteccionQr by remember { mutableStateOf<DeteccionQr?>(null) }
    var tamanoBox by remember { mutableStateOf(IntSize.Zero) }

    // ── Navegacion a AnalisisScreen cuando inicia el analisis ──
    LaunchedEffect(analizando, estado) {
        if (analizando && !yaNavegoAnalisis &&
            estado !is Estado.Escaneando &&
            estado !is Estado.Inicializando &&
            estado !is Estado.UrlDuplicada
        ) {
            yaNavegoAnalisis = true
            onEscanear()
        } else if (!analizando) {
            yaNavegoAnalisis = false
        }
    }

    // ── Reanudar camara cuando volvemos a Escaneando ──
    LaunchedEffect(estado) {
        if (estado is Estado.Escaneando && deteccionQr != null) {
            deteccionQr = null
            moduloCamara?.reanudarDeteccion()
        }
    }

    // ── QR no-URL: el pipeline resuelve NoUrl tan rapido que el gate
    //    de navegacion nunca lo pilla (analizando ya es false). Manejar
    //    aqui: limpiar modal, reanudar camara, mostrar mensaje. ──
    LaunchedEffect(estado) {
        val e = estado
        if (e is Estado.ResultadoListo &&
            e.resultado is ResultadoAnalisis.NoUrl
        ) {
            deteccionQr = null
            moduloCamara?.reanudarDeteccion()
            pipelineViewModel.reiniciar()
            onMensaje(TipoMensaje.INFO, "El QR no contiene una URL")
        }
    }

    // ── Procesar QR detectado tras mostrar el highlight 700ms ──
    LaunchedEffect(deteccionQr) {
        val deteccion = deteccionQr
        if (deteccion != null && !analizando) {
            delay(700)
            scope.launch { pipelineViewModel.analizar(deteccion.payload) }
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

    // ── Refresh del callback QR (H1 fix: evitar stale callback) ──
    LaunchedEffect(analizando, estado, deteccionQr, tamanoBox) {
        moduloCamara?.setOnQrDetectado { deteccion ->
            if (!analizando &&
                estado !is Estado.UrlDuplicada &&
                deteccionQr == null &&
                qrDentroDeReticulo(deteccion, tamanoBox.width, tamanoBox.height)
            ) {
                // Pausar la deteccion inmediatamente — antes de que
                // ningun otro frame pueda disparar otro escaneo.
                moduloCamara?.pausarDeteccion()
                deteccionQr = deteccion
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { tamanoBox = it }
            .background(CyberFondo)
    ) {
        // ─── Camera viewfinder (full-bleed, FILL_CENTER) ───
        // El callback QR se setea en el LaunchedEffect de arriba (H1 fix
        // para evitar stale closure sobre `analizando`/`deteccionQr`).
        // Aqui dejamos un callback vacio — el LaunchedEffect lo sobrescribe
        // en el mismo frame antes de que cualquier frame de la camara pueda
        // disparar un escaneo.
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                    val modulo = ModuloCamara(
                        context = ctx,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        onQrDetectado = { /* set by LaunchedEffect above */ }
                    )
                    moduloCamara = modulo
                    modulo.iniciar()
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ─── Frozen-frame snapshot (covers live preview while QR holds) ───
        // El [DeteccionQr.instantanea] es el Bitmap del frame EXACTO en el que
        // ML Kit detecto el QR (capturado sincrono dentro del success listener
        // del BarcodeScanner, antes de imageProxy.close()). Al renderizarlo con
        // ContentScale.Crop ≈ FILL_CENTER del PreviewView, el overlay de dim
        // strips + cyan border se alinea 1:1 con el QR visible en el snapshot
        // congelado — sin importar cuanto se mueva el telefono despues.
        //
        // Sin este snapshot, el overlay (computado del boundingBox del frame N)
        // se queda quieto en coords de pantalla, pero el preview en vivo pasa
        // a frame N+K donde el QR ya se movio → el overlay ya no apunta al QR.
        deteccionQr?.instantanea?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // ─── Scan reticle (centered square with corner brackets + scan line) ───
        // Solo visible en idle (sin deteccion activa ni analizando)
        if (deteccionQr == null && !analizando) {
            ScanReticle()
        }

        // ─── QR highlight overlay (Google Lens style) ───
        // Oscurece toda la pantalla excepto el area del bounding box del QR.
        // El boundingBox de ML Kit esta en coordenadas de imagen post-rotacion;
        // se mapea a coordenadas de pantalla usando el scale type FILL_CENTER
        // del PreviewView (scale = max(viewW/imgW, viewH/imgH), centrado).
        deteccionQr?.let { OverlayResaltadoQr(it) }

        // ─── Brand header (overlay, top-left) ───
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
        ) {
            Icon(
                imageVector = Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "SeguridadQR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
        }

        // ─── Bottom modal (unificado: QR detectado + URL duplicada) ───
        // Reemplaza al overlay "QR detectado" y al AlertDialog "URL ya
        // escaneada" por separado. Ahora todo esta en un solo modal que
        // NO tapa el QR (esta en la parte inferior, el QR highlight queda
        // visible arriba).
        val duplicada = estado as? Estado.UrlDuplicada
        AnimatedVisibility(
            visible = deteccionQr != null,
            enter = fadeIn(tween(200)) + slideInVertically(
                animationSpec = tween(200),
                initialOffsetY = { it }
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(
                animationSpec = tween(200),
                targetOffsetY = { it }
            ),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val deteccion = deteccionQr
            val urlMostrada = deteccion?.payload
                ?.takeIf { it.startsWith("http") }
                ?: deteccion?.payload
                ?: ""
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg)
                    .clip(RoundedCornerShape(RadioBorde.xl))
                    .background(CyberGlass.copy(alpha = 0.97f))
                    .padding(Espaciado.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Espaciado.md)
            ) {
                if (duplicada != null) {
                    // ── Estado: URL ya escaneada (duplicada) ──
                    Text(
                        text = "URL ya escaneada",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan
                    )
                    Text(
                        text = urlMostrada,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextoSecundario,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Esta URL ya fue escaneada " +
                            "${duplicada.vecesEscaneadaMaxima} vez(es). " +
                            "\u00bfDeseas reescanearla de todas formas?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CyberTextoSecundario
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            pipelineViewModel.cancelarReescaneo()
                        }) {
                            Text("Cancelar", color = CyberTextoSecundario)
                        }
                        TextButton(onClick = {
                            scope.launch { pipelineViewModel.confirmarReescaneo() }
                        }) {
                            Text("Reescanear", color = CyberCyan)
                        }
                    }
                } else {
                    // ── Estado: QR recien detectado (pre-analisis o analizando) ──
                    Text(
                        text = "QR detectado",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CyberTextoPrincipal
                    )
                    Text(
                        text = urlMostrada,
                        style = MaterialTheme.typography.bodySmall,
                        color = CyberTextoSecundario,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CyberCyan
                        )
                        Text(
                            text = if (analizando) "Analizando..." else "Procesando...",
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberTextoSecundario
                        )
                    }
                }
            }
        }

        // ─── "Apunta y escanea" floating pill (only in idle) ───
        AnimatedVisibility(
            visible = deteccionQr == null && !analizando,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
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
                        modifier = Modifier.size(20.dp)
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
    }
}
