package com.qrsecurity.detector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.R
import com.qrsecurity.detector.pipeline.Pipeline
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberVerdeAlerta
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosToque

/**
 * Pantalla de Resultado Seguro — cyber-sentinel design.
 *
 * Muestra:
 *  - Icono CheckCircle cyan con glow radial
 *  - Titulo "Enlace Seguro"
 *  - Probabilidad de amenaza (barra + porcentaje)
 *  - Tarjeta glass con URL detectada + delegado de inferencia
 *  - Botones: Abrir enlace, Copiar, Compartir, Escanear otro
 */
@Composable
fun PantallaResultadoSeguro(
    resultado: Pipeline.ResultadoAnalisis.ResultadoUrl,
    onEscanearOtro: () -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val estadoScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(estadoScroll)
            .background(CyberFondo)
            .padding(horizontal = Espaciado.xl, vertical = Espaciado.xxl)
            .testTag("resultado_seguro_root"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.xl)
    ) {
        // ── Barra superior (shared component) ──
        BarraSuperiorResultado(
            titulo = "Resultado del analisis",
            onCerrar = onEscanearOtro
        )

        // ── Icono CheckCircle con glow verde esmeralda (shared component) ──
        IconoGlowCircular(
            icono = Icons.Filled.CheckCircle,
            colorGlow = CyberVerdeAlerta,
            contentDescription = "Seguro",
            alphaGlow = 0.25f
        )

        // ── Titulo + probabilidad ──
            Text(
                text = "Enlace Seguro",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = CyberVerdeAlerta,
                modifier = Modifier.testTag("titulo_enlace_seguro")
            )

        Text(
            text = "Probabilidad de amenaza: %.1f%%".format(resultado.probabilidad * 100f),
            style = MaterialTheme.typography.bodyLarge,
            color = CyberTextoSecundario
        )

        // ── Tarjeta glass con detalles de URL (shared component) ──
        TarjetaUrlDetectada(
            urlTexto = resultado.urlOriginal,
            colorUrl = CyberTextoPrincipal,
            delegado = resultado.delegado,
            mostrarBorde = true,
            modifier = Modifier.testTag("url_original_detectada")
        ) {
            // Contenido extra: URL normalizada si difiere
            if (resultado.urlLimpia != resultado.urlOriginal.removePrefix("https://")
                    .removePrefix("http://").removePrefix("www.")) {
                Spacer(modifier = Modifier.height(Espaciado.xs))
                Text(
                    text = "URL NORMALIZADA",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
                Text(
                    text = resultado.urlLimpia,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CyberTextoPrincipal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // ── Boton Abrir enlace ──
        OutlinedButton(
            onClick = { abrirEnlaceSeguro(context, resultado.urlOriginal, onMensaje) },
            modifier = Modifier.fillMaxWidth().height(TamanosToque.boton).testTag("btn_abrir_enlace"),
            shape = RoundedCornerShape(RadioBorde.lg)
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Abrir enlace", tint = CyberCyan)
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text(stringResource(R.string.action_open_url), color = CyberCyan)
        }

        // ── Botones secundarios: Copiar + Compartir (shared component) ──
        FilaCopiarCompartir(
            urlTexto = resultado.urlOriginal,
            onMensaje = onMensaje
        )

        // ── Boton Escanear otro (shared component) ──
        BotonEscanearOtro(
            onEscanearOtro = onEscanearOtro,
            modifier = Modifier.testTag("btn_escanear_otro")
        )
    }
}

/**
 * Valida scheme (http/https) y rechaza userinfo antes de lanzar ACTION_VIEW.
 * Bug A8/F8 fix: prevenir schemes arbitrarios y phishing con userinfo.
 */
private fun abrirEnlaceSeguro(
    context: android.content.Context,
    url: String,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        onMensaje(TipoMensaje.ERROR, "Solo se pueden abrir URLs HTTP/HTTPS en el navegador")
        return
    }
    if (uri.userInfo != null) {
        onMensaje(TipoMensaje.ERROR, "La URL contiene credenciales embebidas y no se puede abrir")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(
            Intent.createChooser(intent, "Abrir con...").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        onMensaje(TipoMensaje.EXITO, "Abriendo enlace…")
    } catch (e: android.content.ActivityNotFoundException) {
        onMensaje(TipoMensaje.ERROR, "No hay app para abrir este enlace")
    } catch (e: Exception) {
        onMensaje(TipoMensaje.ERROR, "No se pudo abrir el enlace")
    }
}
