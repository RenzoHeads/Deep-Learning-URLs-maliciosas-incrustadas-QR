package com.qrsecurity.detector.ui

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.pipeline.PipelineViewModel
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque
import kotlinx.coroutines.launch

/**
 * Pantalla de Analisis / Escaner QR (Pencil frame dQeDx).
 *
 * F3.2: UI Compose que replica el layout de Pencil dQeDx. Observa el
 * estado del [PipelineViewModel] (compartido a nivel NavGuardian) y reacciona
 * a los estados del pipeline:
 *
 *  - [Pipeline.Estado.Escaneando] / `pipelineViewModel.analizando == true`:
 *    muestra el layout de progreso (URL detectada, spinner de activity,
 *    tarjeta de 3 checks con Reputacion activo y Redirecciones/Contenido
 *    pendientes, barra de progreso al 33% y boton Cancelar).
 *  - [Pipeline.Estado.ResultadoListo]: busca el id del escaneo en
 *    [DatosTabsViewModel.historialTodos] (match por urlLimpia + urlOriginal,
 *    fallback al mas reciente) y dispara [onResultadoMalicioso] /
 *    [onResultadoSeguro] segun el nivel de alerta. Si no encuentra el id,
 *    emite un error y reinicia.
 *  - [Pipeline.Estado.UrlDuplicada]: muestra un [AlertDialog] para que el
 *    usuario decida reescanear (forzar) o cancelar.
 *  - [Pipeline.Estado.Error]: emite el mensaje por [onMensaje] y reinicia.
 *  - [Pipeline.Estado.NoUrl]: emite "El QR no contiene una URL" y reinicia.
 *
 * @param onResultadoMalicioso Callback con el id del escaneo cuando el
 *   resultado es malicioso (navega a DETALLE_URL/{id}).
 * @param onResultadoSeguro Callback con el id del escaneo cuando el
 *   resultado es seguro (navega a URL_SEGURA/{id}).
 * @param onMensaje Callback para mostrar snackbars.
 * @param pipelineViewModel VM del pipeline (compartido a nivel NavGuardian).
 */
@Composable
fun PantallaAnalisis(
    onResultadoMalicioso: (String) -> Unit,
    onResultadoSeguro: (String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    pipelineViewModel: PipelineViewModel
) {
    val estado by pipelineViewModel.estado.collectAsStateWithLifecycle()
    val analizando by pipelineViewModel.analizando.collectAsStateWithLifecycle()
    val datosViewModel: DatosTabsViewModel = hiltViewModel()
    val historial by datosViewModel.historialTodos.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // ── Reaccion a estados terminales del pipeline ──
    LaunchedEffect(estado) {
        when (val e = estado) {
            is Pipeline.Estado.ResultadoListo -> {
                when (val resultado = e.resultado) {
                    is Pipeline.ResultadoAnalisis.ResultadoUrl -> {
                        val match = historial.firstOrNull {
                            it.urlLimpia == resultado.urlLimpia &&
                                it.urlOriginal == resultado.urlOriginal
                        } ?: historial.maxByOrNull { it.creadoEnMillis }

                        if (match != null) {
                            if (resultado.nivelAlerta ==
                                com.qrsecurity.detector.ml.ControladorAlerta.NivelAlerta.MALICIOSO
                            ) {
                                onResultadoMalicioso(match.id)
                            } else {
                                onResultadoSeguro(match.id)
                            }
                            pipelineViewModel.reiniciar()
                        } else {
                            onMensaje(
                                TipoMensaje.ERROR,
                                "No se pudo guardar el analisis"
                            )
                            pipelineViewModel.reiniciar()
                        }
                    }
                    is Pipeline.ResultadoAnalisis.NoUrl -> {
                        onMensaje(TipoMensaje.INFO, "El QR no contiene una URL")
                        pipelineViewModel.reiniciar()
                    }
                }
            }
            is Pipeline.Estado.Error -> {
                onMensaje(TipoMensaje.ERROR, e.mensaje)
                pipelineViewModel.reiniciar()
            }
            else -> Unit
        }
    }

    // ── Dialogo UrlDuplicada ──
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
                TextButton(onClick = { pipelineViewModel.cancelarReescaneo() }) {
                    Text("Cancelar", color = CyberTextoSecundario)
                }
            }
        )
    }

    // URL del resultado si ya esta listo (null durante Escaneando).
    val urlMostrada: String? = if (analizando) {
        null
    } else {
        (estado as? Pipeline.Estado.ResultadoListo)?.resultado
            ?.let { it as? Pipeline.ResultadoAnalisis.ResultadoUrl }
            ?.urlOriginal
    }

    val progreso by animateFloatAsState(
        targetValue = if (analizando) 0.33f else 0f,
        label = "progresoAnalisis"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberFondo)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Espaciado.xxl, vertical = Espaciado.xxxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.xxl)
    ) {
        // ─── Barra superior ───
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Analizando",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
            IconButton(onClick = {
                pipelineViewModel.reiniciar()
                onMensaje(TipoMensaje.INFO, "Analisis cancelado")
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = CyberTextoSecundario
                )
            }
        }

        // ─── Tarjeta URL detectada ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RadioBorde.xxl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier.padding(Espaciado.xl),
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Text(
                    text = "URL DETECTADA",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
                Text(
                    text = urlMostrada ?: "Analizando contenido...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = CyberTextoPrincipal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ─── Spinner de activity ───
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(TamanosIcono.estandar),
                    color = CyberCyan,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "Analizando actividad...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }

        // ─── Tarjeta de 3 checks ───
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(RadioBorde.xxl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier.padding(Espaciado.xl),
                verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
            ) {
                FilaCheck(
                    icono = Icons.Filled.Shield,
                    titulo = "Reputacion",
                    estado = if (analizando) EstadoCheck.ACTIVO else EstadoCheck.PENDIENTE,
                    colorIcono = CyberCyan
                )
                FilaCheck(
                    icono = Icons.Filled.Public,
                    titulo = "Redirecciones",
                    estado = EstadoCheck.PENDIENTE,
                    colorIcono = CyberTextoSecundario
                )
                FilaCheck(
                    icono = Icons.Filled.HideSource,
                    titulo = "Contenido",
                    estado = EstadoCheck.PENDIENTE,
                    colorIcono = CyberTextoSecundario
                )
            }
        }

        // ─── Barra de progreso ───
        LinearProgressIndicator(
            progress = { progreso },
            modifier = Modifier.fillMaxWidth(),
            color = CyberCyan,
            trackColor = CyberGlassBorde
        )

        // ─── Boton Cancelar ───
        Button(
            onClick = {
                pipelineViewModel.reiniciar()
                onMensaje(TipoMensaje.INFO, "Analisis cancelado")
            },
            enabled = analizando,
            modifier = Modifier
                .fillMaxWidth()
                .height(TamanosToque.boton),
            shape = RoundedCornerShape(RadioBorde.lg),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberGlassAlto,
                contentColor = CyberTextoPrincipal
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = null,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(
                text = "Cancelar",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private enum class EstadoCheck { ACTIVO, PENDIENTE, COMPLETADO }

@Composable
private fun FilaCheck(
    icono: ImageVector,
    titulo: String,
    estado: EstadoCheck,
    colorIcono: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(Espaciado.gigante)
                .clip(CircleShape)
                .background(colorIcono.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            if (estado == EstadoCheck.ACTIVO) {
                CircularProgressIndicator(
                    modifier = Modifier.size(TamanosIcono.estandar),
                    color = colorIcono,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = icono,
                    contentDescription = null,
                    tint = colorIcono,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
            }
        }
        Text(
            text = titulo,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = if (estado == EstadoCheck.ACTIVO) CyberTextoPrincipal
                    else CyberTextoSecundario
        )
        Spacer(modifier = Modifier.weight(1f))
        when (estado) {
            EstadoCheck.ACTIVO -> Text(
                text = "Analizando...",
                style = MaterialTheme.typography.labelMedium,
                color = CyberCyan
            )
            EstadoCheck.PENDIENTE -> Text(
                text = "Pendiente",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
            EstadoCheck.COMPLETADO -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = CyberVerdeAlerta,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        }
    }
}
