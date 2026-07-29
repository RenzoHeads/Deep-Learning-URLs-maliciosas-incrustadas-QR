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
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag("resultado_seguro_root"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
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
                Spacer(modifier = Modifier.height(4.dp))
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
            onClick = {
                // Bug A8 fix: permitir solo schemes http/https. Antes se lanzaba
                // ACTION_VIEW con cualquier scheme que llegara en el QR
                // (intent:, content:, file:, market:, mailto:, javascript:,
                // etc.), lo que convierte a la app en un puente para ejecutar
                // intents arbitrarios. Un QR malicioso podria abrir apps
                // sensibles o escapar el sandbox del navegador. Ahora se
                // rechaza explicitamente cualquier scheme que no sea http/https.
                val uri = Uri.parse(resultado.urlOriginal)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    onMensaje(TipoMensaje.ERROR, "Solo se pueden abrir URLs HTTP/HTTPS en el navegador")
                    return@OutlinedButton
                }
                // F8 (CWE-601): rechazar URLs con userinfo (``https://
                // apple.com@evil.com``). El navegador mostraria ``apple.com``
                // como autoridad antes del ``@``, engañando al usuario para
                // que crea que visita apple.com cuando en realidad va a
                // evil.com. Aunque el modelo ya evaluo la URL como segura,
                // abrir una URL con userinfo en el navegador introduce un
                // vector de phishing visual que bypassa la deteccion.
                if (uri.userInfo != null) {
                    onMensaje(TipoMensaje.ERROR, "La URL contiene credenciales embebidas y no se puede abrir")
                    return@OutlinedButton
                }
                // Bug 10 fix: envolver startActivity en try/catch para evitar
                // crash por ActivityNotFoundException cuando no hay navegador
                // instalado (MIUI/Kindle hardened) o la URL usa un esquema no
                // http(s) (intent:, mailto:, etc.) sin app que lo resuelva.
                val intent = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(
                        Intent.createChooser(intent, "Abrir con...")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    onMensaje(TipoMensaje.EXITO, "Abriendo enlace…")
                } catch (e: android.content.ActivityNotFoundException) {
                    onMensaje(TipoMensaje.ERROR, "No hay app para abrir este enlace")
                } catch (e: Exception) {
                    onMensaje(TipoMensaje.ERROR, "No se pudo abrir el enlace")
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("btn_abrir_enlace"),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Abrir enlace", tint = CyberCyan)
            Spacer(modifier = Modifier.width(8.dp))
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
