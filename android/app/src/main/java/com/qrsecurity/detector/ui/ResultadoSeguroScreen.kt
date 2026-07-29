package com.qrsecurity.detector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
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
        // ── Barra superior ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Resultado del analisis",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = CyberTextoPrincipal
            )
            IconButton(onClick = onEscanearOtro) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cerrar",
                    tint = CyberTextoSecundario
                )
            }
        }

        // ── Icono CheckCircle con glow verde esmeralda ──
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CyberVerdeAlerta.copy(alpha = 0.25f), CyberFondo)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Seguro",
                tint = CyberVerdeAlerta,
                modifier = Modifier.size(64.dp)
            )
        }

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

        // ── Tarjeta glass con detalles de URL ──
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(1.dp, CyberGlassBorde), RoundedCornerShape(20.dp)),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CyberGlass)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "URL DETECTADA",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
                Text(
                    text = resultado.urlOriginal,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = CyberTextoPrincipal,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("url_original_detectada")
                )

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

                Spacer(modifier = Modifier.height(4.dp))
                // Bug 16 fix (mirror): ocultar "Inferencia: cache" cuando el
                // resultado viene del cache. Solo mostrar delegados reales
                // (NNAPI/GPU/CPU) para consistencia con AcercaDe.
                if (resultado.delegado != "cache") {
                    Text(
                        text = "Inferencia: ${resultado.delegado}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberTextoSecundario
                    )
                }
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

        // ── Botones secundarios: Copiar + Compartir ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(
                onClick = {
                    val portapapeles = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    portapapeles.setPrimaryClip(
                        android.content.ClipData.newPlainText("URL", resultado.urlOriginal)
                    )
                    onMensaje(TipoMensaje.EXITO, "URL copiada al portapapeles")
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = CyberTextoSecundario)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_copy), color = CyberTextoSecundario)
            }
            TextButton(
                onClick = {
                    val texto = construirTextoCompartirSeguro(resultado)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, texto)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    // H2 fix: envolver startActivity(createChooser(...)) en
                    // try/catch para evitar ActivityNotFoundException crashear
                    // la app en dispositivos sin handler de share (Android TV,
                    // dispositivos hardened sin apps de mensajeria/firestore).
                    // Patron identico al bloque "Abrir enlace" de arriba.
                    try {
                        context.startActivity(
                            Intent.createChooser(intent, "Compartir")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onMensaje(TipoMensaje.EXITO, "Compartiendo enlace…")
                    } catch (e: android.content.ActivityNotFoundException) {
                        onMensaje(TipoMensaje.ERROR, "No hay app para compartir")
                    } catch (e: Exception) {
                        onMensaje(TipoMensaje.ERROR, "No se pudo compartir")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, tint = CyberTextoSecundario)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_share), color = CyberTextoSecundario)
            }
        }

        // ── Boton Escanear otro ──
        Button(
            onClick = onEscanearOtro,
            modifier = Modifier.fillMaxWidth().height(56.dp).testTag("btn_escanear_otro"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyberCyan,
                contentColor = CyberFondo
            )
        ) {
            Text(stringResource(R.string.action_scan_again), fontWeight = FontWeight.Bold)
        }
    }
}

private fun construirTextoCompartirSeguro(resultado: Pipeline.ResultadoAnalisis.ResultadoUrl): String {
    return buildString {
        appendLine("Resultado del analisis QR Guardian:")
        appendLine("  URL: ${resultado.urlOriginal}")
        appendLine("  Probabilidad de amenaza: ${(resultado.probabilidad * 100).toInt()}%")
        appendLine("  Veredicto: SEGURO")
    }
}
