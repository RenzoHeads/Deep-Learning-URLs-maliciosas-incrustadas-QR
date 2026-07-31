package com.qrsecurity.detector.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.R
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Componentes Composables compartidos entre las pantallas de resultado
 * (ResultadoMalicioso, ResultadoSeguro, DetalleEscaneo) para eliminar
 * duplicacion de codigo (SonarQube S4144 / code duplication).
 *
 * Cada Composable extrae un patron visual que se repetia verbatim en
 * multiples pantallas: barra superior, icono con glow radial, tarjeta
 * glass con URL, botones copiar/compartir y boton escanear otro.
 */

// ── Composables compartidos ──

/**
 * Barra superior con titulo y boton de volver/cerrar.
 * Comun a ResultadoMalicioso, ResultadoSeguro y DetalleEscaneo.
 *
 * @param titulo Texto a mostrar como titulo.
 * @param onCerrar Callback al pulsar el boton de cerrar/volver.
 * @param contentDescriptionBack Descripcion del boton para accesibilidad.
 */
@Composable
fun BarraSuperiorResultado(
    titulo: String,
    onCerrar: () -> Unit,
    contentDescriptionBack: String = "Cerrar"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = titulo,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )
        IconButton(onClick = onCerrar) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescriptionBack,
                tint = CyberTextoSecundario
            )
        }
    }
}

/**
 * Icono circular con glow radial — patron visual comun a todas las
 * pantallas de resultado.
 *
 * @param icono ImageVector a mostrar (Warning, CheckCircle, etc.).
 * @param colorGlow Color del glow radial (alpha se aplica internamente).
 * @param alphaGlow Alpha del color de glow (default 0.3f, algunas pantallas usan 0.25f).
 * @param contentDescription Descripcion para accesibilidad.
 * @param tamanoIcono Tamano del icono interno (dp).
 */
@Composable
fun IconoGlowCircular(
    icono: ImageVector,
    colorGlow: Color,
    contentDescription: String?,
    alphaGlow: Float = 0.3f,
    tamanoIcono: androidx.compose.ui.unit.Dp = TamanosIcono.grande
) {
    Box(
        modifier = Modifier
            .size(TamanosIcono.heroContenedor)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(colorGlow.copy(alpha = alphaGlow), CyberFondo)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icono,
            contentDescription = contentDescription,
            tint = colorGlow,
            modifier = Modifier.size(tamanoIcono)
        )
    }
}

/**
 * Tarjeta glass con borde que muestra los detalles de una URL detectada.
 * Compartida entre ResultadoMalicioso, ResultadoSeguro y DetalleEscaneo.
 *
 * @param urlTexto Texto de la URL a mostrar.
 * @param colorUrl Color del texto de la URL.
 * @param delegado Nombre del delegado de inferencia (opcional, se oculta si es "cache" o vacio).
 * @param mostrarBorde Si true, anade un borde glass (ResultadoSeguro/DetalleEscaneo lo usan).
 * @param modifier Modifier opcional para testTag, etc.
 * @param contenidoExtra Bloque Composable opcional dentro de la Column de la tarjeta.
 */
@Composable
fun TarjetaUrlDetectada(
    urlTexto: String,
    colorUrl: Color = CyberTextoPrincipal,
    delegado: String? = null,
    mostrarBorde: Boolean = false,
    modifier: Modifier = Modifier,
    contenidoExtra: @Composable ColumnScope.() -> Unit = {}
) {
    val modificador = if (mostrarBorde) {
        modifier
            .fillMaxWidth()
            .border(BorderStroke(Elevacion.sutil, CyberGlassBorde), RoundedCornerShape(RadioBorde.xxl))
    } else {
        modifier.fillMaxWidth()
    }
    Card(
        modifier = modificador,
        shape = RoundedCornerShape(if (mostrarBorde) RadioBorde.xxl else RadioBorde.xl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass)
    ) {
        Column(modifier = Modifier.padding(Espaciado.xl), verticalArrangement = Arrangement.spacedBy(Espaciado.sm)) {
            Text(
                text = "URL DETECTADA",
                style = MaterialTheme.typography.labelMedium,
                color = CyberTextoSecundario
            )
            Text(
                text = urlTexto,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = colorUrl,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (delegado != null && delegado != "cache" && delegado.isNotEmpty()) {
                Spacer(modifier = Modifier.height(Espaciado.xs))
                Text(
                    text = "Inferencia: $delegado",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTextoSecundario
                )
            }

            contenidoExtra()
        }
    }
}

/**
 * Fila de botones Copiar + Compartir.
 * Compartida entre ResultadoMalicioso, ResultadoSeguro y DetalleEscaneo.
 *
 * @param urlTexto Texto a copiar/compartir.
 * @param onMensaje Callback para mostrar mensajes (exito/error).
 * @param modifier Modifier opcional.
 */
@Composable
fun FilaCopiarCompartir(
    urlTexto: String,
    onMensaje: (TipoMensaje, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        TextButton(
            onClick = {
                copiarAlPortapapeles(context, urlTexto)
                onMensaje(TipoMensaje.EXITO, "URL copiada al portapapeles")
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CyberTextoSecundario)
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(stringResource(R.string.action_copy), color = CyberTextoSecundario)
        }
        TextButton(
            onClick = {
                val (tipo, msg) = compartirTexto(context, urlTexto)
                onMensaje(tipo, msg)
            },
            modifier = Modifier.weight(1f)
        ) {
            Icon(Icons.Filled.Share, contentDescription = null, tint = CyberTextoSecundario)
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(stringResource(R.string.action_share), color = CyberTextoSecundario)
        }
    }
}

/**
 * Boton "Escanear otro" con estilo cyber-sentinel.
 * Comun a ResultadoMalicioso y ResultadoSeguro.
 *
 * @param onEscanearOtro Callback al pulsar el boton.
 * @param modifier Modifier opcional para testTag, etc.
 */
@Composable
fun BotonEscanearOtro(
    onEscanearOtro: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onEscanearOtro,
        modifier = modifier.fillMaxWidth().height(TamanosToque.boton),
        shape = RoundedCornerShape(RadioBorde.xl),
        colors = ButtonDefaults.buttonColors(
            containerColor = CyberCyan,
            contentColor = CyberFondo
        )
    ) {
        Text(stringResource(R.string.action_scan_again), fontWeight = FontWeight.Bold)
    }
}

// ── Funciones helper (no Composable) ──

/**
 * Copia texto al portapapeles del sistema.
 */
private fun copiarAlPortapapeles(context: Context, texto: String) {
    val portapapeles = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    portapapeles.setPrimaryClip(ClipData.newPlainText("URL", texto))
}

/**
 * Lanza el intent de compartir texto. Devuelve el tipo de mensaje y texto
 * para el callback [onMensaje].
 */
private fun compartirTexto(context: Context, texto: String): Pair<TipoMensaje, String> {
    return try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, texto)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Compartir")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        TipoMensaje.EXITO to "Compartiendo enlace…"
    } catch (e: android.content.ActivityNotFoundException) {
        TipoMensaje.ERROR to "No hay app para compartir"
    } catch (e: Exception) {
        TipoMensaje.ERROR to "No se pudo compartir"
    }
}

/**
 * Construye texto de compartir para un resultado de analisis.
 *
 * @param urlOriginal URL original detectada.
 * @param probabilidad Probabilidad de amenaza (0..1).
 * @param veredicto Texto del veredicto ("MALICIOSO" o "SEGURO").
 * @return Texto formateado para compartir.
 */
fun construirTextoCompartir(
    urlOriginal: String,
    probabilidad: Float,
    veredicto: String
): String = buildString {
    appendLine("Resultado del analisis QR Guardian:")
    appendLine("  URL: $urlOriginal")
    appendLine("  Probabilidad de amenaza: ${(probabilidad * 100).toInt()}%")
    appendLine("  Veredicto: $veredicto")
}
