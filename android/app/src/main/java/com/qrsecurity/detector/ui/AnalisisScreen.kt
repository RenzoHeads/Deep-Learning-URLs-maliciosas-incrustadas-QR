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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qrsecurity.detector.pipeline.Estado
import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Pantalla de análisis EN VIVO (restaurada tras el bug reportado en
 * dispositivo): se llega desde Home cuando el pipeline entra en
 * [Estado.Analizando] (inferencia real en vuelo). El modal de Home solo
 * cubre la fase de dedup + preview de la URL; en cuanto hay inferencia
 * real, el usuario aterriza aquí.
 *
 * Manejo de estados terminales — mismo contrato que los handlers de
 * HomeScreen (los caminos rápidos NoUrlListo/Error resuelven antes de salir
 * de Home y los maneja esa pantalla; aquí se manejan los que llegan mientras
 * esta pantalla está en primer plano):
 *  - [Estado.ResultadoListo] (gate `escaneoActivo`, la señal de intención
 *    sin carrera): consume la señal, `reiniciar()` (deja el pipeline en
 *    Escaneando para que Home resetee el modal/cámara al volver) y navega a
 *    DETALLE_URL con el idLocal del sealed — sin match heurístico.
 *  - [Estado.NoUrlListo] / [Estado.Error]: mensaje + reiniciar + volver.
 *  - [Estado.UrlDuplicada]: volver a Home (el modal del dedup vive allí).
 *
 * @param onResultado Navega a DETALLE_URL con el id del escaneo.
 * @param onVolverHome Vuelve a Home (popBackStack).
 */
@Composable
fun PantallaAnalisis(
    onResultado: (idEscaneo: String) -> Unit,
    onVolverHome: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> },
    pipelineViewModel: PipelineViewModel
) {
    val estado by pipelineViewModel.estado.collectAsStateWithLifecycle()
    val analizando by pipelineViewModel.analizando.collectAsStateWithLifecycle()
    val escaneoActivo by pipelineViewModel.escaneoActivo.collectAsStateWithLifecycle()

    // ── Reacción a estados terminales ──
    LaunchedEffect(estado) {
        val e = estado
        when {
            escaneoActivo && e is Estado.ResultadoListo -> {
                pipelineViewModel.consumirEscaneoActivo()
                // reiniciar() ANTES de navegar: deja estado en Escaneando
                // para que el efecto "Reanudar camara" de Home limpie el
                // modal y reanude la detección al volver del detalle (bug:
                // el modal quedaba congelado mostrando el QR detectado).
                pipelineViewModel.reiniciar()
                onResultado(e.idLocal)
            }
            e is Estado.NoUrlListo -> {
                pipelineViewModel.reiniciar()
                onMensaje(TipoMensaje.INFO, mensajeNoUrl(e.resultado.tipoContenido))
                onVolverHome()
            }
            e is Estado.Error && !analizando -> {
                pipelineViewModel.reiniciar()
                onMensaje(TipoMensaje.ERROR, e.mensaje)
                onVolverHome()
            }
            e is Estado.UrlDuplicada -> onVolverHome()
            else -> Unit
        }
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
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.lg),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
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
                onVolverHome()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Volver",
                    tint = CyberTextoSecundario
                )
            }
        }

        // ─── Tarjeta URL detectada ───
        TarjetaCyber {
            Column(
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Text(
                    text = "URL detectada",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
                Text(
                    text = urlEnAnalisis(estado),
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
                    text = "Analizando actividad…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoSecundario
                )
            }
        }

        // ─── Tarjeta de 3 checks ───
        TarjetaCyber {
            Column(
                verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
            ) {
                FilaCheck(
                    icono = Icons.Filled.Shield,
                    titulo = "Reputación",
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
            trackColor = CyberGlassAlto
        )

        // ─── Botón Cancelar (U8: el dedup/reescaneo puede tardar con red
        //     colgada — sin él el usuario quedaba atrapado) ───
        BotonCyber(
            texto = "Cancelar",
            onClick = {
                pipelineViewModel.reiniciar()
                onVolverHome()
            },
            habilitado = analizando,
            icono = Icons.Filled.Close,
            contenedor = CyberGlassAlto,
            contenido = CyberTextoPrincipal,
        )
    }
}

// Audit fix M5: se elimino COMPLETADO — nunca se construia (FilaCheck solo
// recibe ACTIVO/PENDIENTE); su rama era inalcanzable.
private enum class EstadoCheck { ACTIVO, PENDIENTE }

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
                .clip(RadioBorde.full)
                .background(colorIcono.copy(Alphas.bajo)),
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
                text = "Analizando…",
                style = MaterialTheme.typography.labelMedium,
                color = CyberCyan
            )
            EstadoCheck.PENDIENTE -> Text(
                text = "Pendiente",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
        }
    }
}

/**
 * URL a mostrar en la tarjeta mientras la pantalla está visible: durante la
 * inferencia ([Estado.Analizando]) el sealed no lleva URL — placeholder; si
 * el estado ya trae resultado (UrlDuplicada/ResultadoUrl, p.ej. un frame
 * antes de navegar) se muestra su URL original.
 */
private fun urlEnAnalisis(estado: Estado): String = when (estado) {
    is Estado.UrlDuplicada -> estado.resultado.urlOriginal
    is Estado.ResultadoListo -> estado.resultado.urlOriginal
    else -> "Analizando contenido…"
}
