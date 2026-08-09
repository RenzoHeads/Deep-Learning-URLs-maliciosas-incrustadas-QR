package com.qrsecurity.detector.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Detalle de URL (Pencil frame ZEXEp).
 *
 * F3.4: implementacion real del layout de Pencil ZEXEp. La firma NO debe cambiar.
 *
 * Fusiona DetalleEscaneo + ResultadoMalicioso. Muestra: URL, probabilidad,
 * nivel de amenaza (gauge + veredicto), "Ver analisis anteriores" y botones
 * de accion (Bloquear/Desbloquear/Compartir/Abrir). Wire a
 * [DetalleUrlViewModel].
 *
 * NOTA: El desbloqueo de URL es manual y permanente (no existe desbloqueo
 * temporal). Tras confirmar el desbloqueo se muestra
 * [ModalDesbloqueoOk].
 *
 * @param id Id del escaneo (nav argument).
 * @param onVerAnalisisAnteriores Callback con (urlLimpia, idActual) para
 *   navegar a ANALISIS_ANTERIORES.
 * @param onMensaje Callback para mostrar snackbars.
 * @param viewModel VM de detalle (Hilt, scoped al NavBackStackEntry).
 * @param onBack Callback para volver atras (default vacio — NavGuardian lo
 *   cablea tras F3).
 */
@Composable
fun PantallaDetalleUrl(
    id: String,
    onVerAnalisisAnteriores: (urlLimpia: String, idActual: String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    viewModel: DetalleUrlViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val contexto = LocalContext.current

    // Carga el escaneo al entrar (y si el id cambia por reutilizacion de VM).
    LaunchedEffect(id) {
        viewModel.cargarEscaneo(id)
    }

    // Eventos one-shot del VM (snackbars) — mismo patron que PantallaLogin.
    LaunchedEffect(viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.mensaje.collect { mensaje ->
                onMensaje(mensaje.tipo, mensaje.texto)
            }
        }
    }

    // Hardware back → onBack.
    BackHandler(onBack = onBack)

    // Modales de desbloqueo (estado local).
    var modalConfirmarVisible by remember { mutableStateOf(false) }
    var modalOkVisible by remember { mutableStateOf(false) }

    if (modalConfirmarVisible) {
        ModalDesbloqueoConfirmar(
            onConfirmar = {
                modalConfirmarVisible = false
                val urlLimpia = (uiState as? DetalleUrlUiState.Cargado)?.escaneo?.urlLimpia
                if (urlLimpia != null) {
                    viewModel.onAction(DetalleUrlAction.DesbloquearUrl(urlLimpia))
                }
                modalOkVisible = true
            },
            onCancelar = { modalConfirmarVisible = false }
        )
    }

    if (modalOkVisible) {
        ModalDesbloqueoOk(onCerrar = { modalOkVisible = false })
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
    ) {
        when (val estado = uiState) {
            is DetalleUrlUiState.Cargando -> ContenidoCargando()
            is DetalleUrlUiState.NoEncontrado -> ContenidoNoEncontrado(onBack = onBack)
            is DetalleUrlUiState.Cargado -> ContenidoDetalle(
                estado = estado,
                contexto = contexto,
                onBack = onBack,
                onVerAnalisisAnteriores = onVerAnalisisAnteriores,
                onSolicitarDesbloqueo = { modalConfirmarVisible = true },
                onMensaje = onMensaje
            )
        }
    }
}

@Composable
private fun ContenidoCargando() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = CyberCyan)
    }
}

@Composable
private fun ContenidoNoEncontrado(onBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            Text(
                text = "Escaneo no encontrado",
                style = MaterialTheme.typography.titleMedium,
                color = CyberTextoSecundario
            )
            OutlinedButton(
                onClick = onBack,
                shape = RoundedCornerShape(RadioBorde.lg)
            ) {
                Text(text = "Volver", color = CyberTextoPrincipal)
            }
        }
    }
}

@Composable
private fun ContenidoDetalle(
    estado: DetalleUrlUiState.Cargado,
    contexto: Context,
    onBack: () -> Unit,
    onVerAnalisisAnteriores: (String, String) -> Unit,
    onSolicitarDesbloqueo: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val escaneo = estado.escaneo
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.xxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
    ) {
        // ─── Back Row ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(vertical = Espaciado.xs),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Volver",
                tint = CyberTextoSecundario,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Text(
                text = "Volver",
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoSecundario
            )
        }

        // ─── Title Block ───
        Text(
            text = "Detalle del análisis",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )

        // URL Row: QR icon + URL + chip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.QrCode,
                contentDescription = null,
                tint = CyberTextoSecundario,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Text(
                text = escaneo.urlOriginal.ifBlank { escaneo.urlLimpia },
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ChipEstadoUrl(estado = estado)
        }

        // ─── Verdict Card ───
        TarjetaVeredicto(estado = estado)

        // ─── Actions ─── (solo en ultima version; los reescaneos son readOnly)
        if (estado.esUltimaVersion) {
            SeccionAcciones(
                estado = estado,
                contexto = contexto,
                onSolicitarDesbloqueo = onSolicitarDesbloqueo,
                onMensaje = onMensaje
            )
        }

        // ─── Versions Link ───
        if (estado.totalReescaneos > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onVerAnalisisAnteriores(escaneo.urlLimpia, escaneo.id)
                    }
                    .padding(vertical = Espaciado.sm),
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ver versiones de este análisis",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberCyan,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
        }
    }
}

@Composable
private fun ChipEstadoUrl(estado: DetalleUrlUiState.Cargado) {
    val (texto, colorFondo, colorTexto) = when {
        estado.urlBloqueada -> Triple("BLOQUEADA", CyberRojo.copy(alpha = 0.18f), CyberRojo)
        estado.escaneo.nivelAlerta == "MALICIOSO" ->
            Triple("MALICIOSO", CyberRojo.copy(alpha = 0.18f), CyberRojo)
        estado.escaneo.nivelAlerta == "SOSPECHOSO" ->
            Triple("SOSPECHOSO", CyberAmbar.copy(alpha = 0.18f), CyberAmbar)
        else -> Triple("SEGURO", CyberVerdeAlerta.copy(alpha = 0.18f), CyberVerdeAlerta)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(RadioBorde.sm))
            .background(colorFondo)
            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
    ) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = colorTexto
        )
    }
}

@Composable
private fun TarjetaVeredicto(estado: DetalleUrlUiState.Cargado) {
    val escaneo = estado.escaneo
    val colorVeredicto = colorPorNivel(escaneo.nivelAlerta)
    val valorPct = (escaneo.probabilidad * 100f).toInt()
    val amenazaLabel = etiquetaAmenazaPorNivel(escaneo.nivelAlerta)
    val amenazaSubtitulo = subtituloPorNivel(escaneo.nivelAlerta)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xl))
            .background(CyberGlass)
            .border(
                width = 1.dp,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.xl)
            )
            .padding(Espaciado.xl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Espaciado.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Gauge
            MedidorGauge(
                progreso = escaneo.probabilidad,
                colorArco = colorVeredicto,
                colorTrack = CyberGlassBorde,
                valorTexto = valorPct.toString(),
                colorTexto = CyberTextoPrincipal,
                modifier = Modifier.size(TamanosIcono.heroContenedor)
            )
            // Verdict Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Text(
                    text = amenazaLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextoPrincipal
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(RadioBorde.sm))
                        .background(colorVeredicto.copy(alpha = 0.16f))
                        .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
                ) {
                    Text(
                        text = amenazaSubtitulo,
                        style = MaterialTheme.typography.labelMedium,
                        color = colorVeredicto
                    )
                }
            }
        }
    }
}

@Composable
private fun SeccionAcciones(
    estado: DetalleUrlUiState.Cargado,
    contexto: Context,
    onSolicitarDesbloqueo: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val escaneo = estado.escaneo
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // ─── Primary Button ───
        if (estado.urlBloqueada) {
            BotonPrimario(
                icono = Icons.Filled.Lock,
                etiqueta = "Enlace bloqueado",
                subEtiqueta = "Desbloquea para continuar",
                habilitado = false
            )
        } else {
            BotonPrimario(
                icono = Icons.Filled.LockOpen,
                etiqueta = "Abrir enlace",
                subEtiqueta = "Se abre en navegador protegido",
                habilitado = true,
                onClick = {
                    val url = urlParaAbrir(escaneo.urlOriginal, escaneo.urlLimpia)
                    if (url == null) {
                        onMensaje(TipoMensaje.ERROR, "Enlace con esquema no permitido")
                    } else {
                        abrirEnNavegador(contexto, url)
                    }
                }
            )
        }

        // ─── Secondary Button: Compartir ───
        OutlinedButton(
            onClick = { compartirUrl(contexto, urlParaAbrir(escaneo.urlOriginal, escaneo.urlLimpia) ?: escaneo.urlLimpia) },
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = CyberGlass,
                contentColor = CyberTextoPrincipal
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyberGlassBorde)
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.size(Espaciado.sm))
            Text(
                text = "Compartir",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        // ─── Unlock Detail Button ─── (solo si esta bloqueada)
        if (estado.urlBloqueada) {
            OutlinedButton(
                onClick = onSolicitarDesbloqueo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TamanosToque.boton),
                shape = RoundedCornerShape(RadioBorde.lg),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = CyberRojo.copy(alpha = 0.12f),
                    contentColor = CyberRojo
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyberRojo.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = Icons.Filled.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Spacer(modifier = Modifier.size(Espaciado.sm))
                Text(
                    text = "Desbloquear esta URL",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun BotonPrimario(
    icono: ImageVector,
    etiqueta: String,
    subEtiqueta: String,
    habilitado: Boolean,
    onClick: () -> Unit = {}
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = Modifier
            .fillMaxWidth()
            .height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.lg),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (habilitado) CyberCyan else CyberGlass,
            contentColor = if (habilitado) CyberFondo else CyberTextoSecundario,
            disabledContainerColor = CyberGlass,
            disabledContentColor = CyberTextoSecundario
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icono,
                    contentDescription = null,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Text(
                    text = etiqueta,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = subEtiqueta,
                style = MaterialTheme.typography.bodySmall,
                color = if (habilitado) CyberFondo.copy(alpha = 0.8f) else CyberTextoSecundario
            )
        }
    }
}

// ─── Helpers ───

private fun colorPorNivel(nivel: String): Color = when (nivel) {
    "MALICIOSO" -> CyberRojo
    "SOSPECHOSO" -> CyberCyan
    else -> CyberVerdeAlerta
}

private fun etiquetaAmenazaPorNivel(nivel: String): String = when (nivel) {
    "MALICIOSO" -> "Amenaza alta"
    "SOSPECHOSO" -> "Amenaza moderada"
    else -> "Sin amenazas"
}

private fun subtituloPorNivel(nivel: String): String = when (nivel) {
    "MALICIOSO" -> "Phishing · smishing activo"
    "SOSPECHOSO" -> "Patrón sospechoso detectado"
    else -> "Análisis completado"
}
