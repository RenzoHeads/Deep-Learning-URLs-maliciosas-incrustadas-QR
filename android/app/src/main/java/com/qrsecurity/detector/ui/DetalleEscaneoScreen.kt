package com.qrsecurity.detector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberAmbar
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberFondo
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberRojo
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.CyberVerdeAlertaClaro
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pantalla de Detalle de Escaneo — muestra la informacion completa de un
 * escaneo del historial cuando el usuario toca una tarjeta.
 *
 * Bug DETAIL-1 fix: antes [TarjetaHistorial] no tenia `clickable`, asi que
 * tocarla no hacia nada. Ahora la card navega a esta pantalla via la ruta
 * [Rutas.DETALLE_ESCANEO] definida en [NavGuardian].
 *
 * Muestra:
 *  - Icono de veredicto (CheckCircle cyan / Warning rojo / Ambar sospechoso)
 *  - URL limpia + URL original
 *  - Probabilidad de amenaza (barra + porcentaje)
 *  - Nivel de alerta
 *  - Fecha de escaneo
 *  - Delegado de inferencia (motor que produjo el analisis)
 *  - Botones: Abrir enlace, Copiar, Compartir, Volver
 */
@Composable
fun PantallaDetalleEscaneo(
    escaneo: EscaneoEntity,
    urlBloqueada: Boolean = false,
    onVolver: () -> Unit,
    onBloquear: () -> Unit = {},
    onDenunciar: (String) -> Unit = {},
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val estadoScroll = rememberScrollState()
    var procederConfirmado by remember { mutableStateOf(false) }

    val veredicto = remember(escaneo.esMalicioso, escaneo.nivelAlerta) {
        calcularVeredicto(escaneo)
    }
    val requiereConfirmacion = veredicto.esMalicioso && !urlBloqueada

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(estadoScroll)
            .background(CyberFondo)
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .testTag("detalle_escaneo_root"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        BarraSuperiorResultado(
            titulo = "Detalle del escaneo",
            onCerrar = onVolver,
            contentDescriptionBack = "Volver"
        )

        IconoGlowCircular(
            icono = veredicto.icono,
            colorGlow = veredicto.color,
            contentDescription = veredicto.titulo,
            alphaGlow = 0.25f
        )

        Text(
            text = veredicto.titulo,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = veredicto.color
        )

        if (urlBloqueada) {
            BadgeUrlBloqueada()
        }

        TarjetaDatosEscaneo(escaneo = escaneo, veredicto = veredicto)

        if (requiereConfirmacion) {
            SeccionBloquearDenunciar(
                onBloquear = onBloquear,
                onDenunciar = { onDenunciar(escaneo.urlLimpia) }
            )
        }

        if (requiereConfirmacion && !procederConfirmado) {
            CardAdvertenciaMalicioso(onProceder = { procederConfirmado = true })
        }

        val mostrarAcciones = !requiereConfirmacion || procederConfirmado
        if (mostrarAcciones) {
            SeccionAccionesDetalle(
                urlOriginal = escaneo.urlOriginal,
                urlLimpia = escaneo.urlLimpia,
                urlBloqueada = urlBloqueada,
                context = context,
                onMensaje = onMensaje
            )
        }
    }
}

/** Datos consolidados del veredicto para reducir complejidad de PantallaDetalleEscaneo. */
private data class VeredictoDetalle(
    val esMalicioso: Boolean,
    val color: androidx.compose.ui.graphics.Color,
    val icono: androidx.compose.ui.graphics.vector.ImageVector,
    val titulo: String
)

private fun calcularVeredicto(escaneo: EscaneoEntity): VeredictoDetalle {
    val esMalicioso = escaneo.esMalicioso
    val esSospechoso = escaneo.nivelAlerta.uppercase() == "SOSPECHOSO"
    val color = when {
        esMalicioso -> CyberRojo
        esSospechoso -> CyberAmbar
        else -> CyberVerdeAlertaClaro
    }
    val icono = when {
        esMalicioso -> Icons.Filled.Warning
        esSospechoso -> Icons.Filled.Warning
        else -> Icons.Filled.CheckCircle
    }
    val titulo = when {
        esMalicioso -> "Enlace Malicioso"
        esSospechoso -> "Enlace Sospechoso"
        else -> "Enlace Seguro"
    }
    return VeredictoDetalle(esMalicioso, color, icono, titulo)
}

@Composable
private fun BadgeUrlBloqueada() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CyberRojo.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "Bloqueada",
            tint = CyberRojo,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = "URL bloqueada — no se puede abrir, copiar ni compartir",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = CyberRojo
        )
    }
}

@Composable
private fun TarjetaDatosEscaneo(
    escaneo: EscaneoEntity,
    veredicto: VeredictoDetalle
) {
    val fechaStr = remember(escaneo.creadoEnMillis) {
        Instant.ofEpochMilli(escaneo.creadoEnMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }
    val dominio = remember(escaneo.urlLimpia) {
        val sinProtocolo = escaneo.urlLimpia.substringAfter("://", escaneo.urlLimpia)
        sinProtocolo.substringBefore("/")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, CyberGlassBorde), RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CyberGlass)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CampoTexto("URL analizada", escaneo.urlLimpia, MaterialTheme.typography.bodyLarge, CyberTextoPrincipal)
            if (escaneo.urlOriginal != escaneo.urlLimpia) {
                CampoTexto("URL original", escaneo.urlOriginal, MaterialTheme.typography.bodyMedium, CyberTextoSecundario, maxLines = 3)
            }
            CampoTexto("Dominio", dominio, MaterialTheme.typography.bodyLarge, CyberTextoPrincipal)
            CampoTexto("Nivel de alerta", escaneo.nivelAlerta.uppercase(), MaterialTheme.typography.titleMedium, veredicto.color, bold = true)
            Column {
                Text(
                    text = "Probabilidad de amenaza: ${(escaneo.probabilidad * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = escaneo.probabilidad,
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = veredicto.color,
                    trackColor = CyberGlass
                )
            }
            CampoTexto("Fecha de escaneo", fechaStr, MaterialTheme.typography.bodyLarge, CyberTextoPrincipal)
            if (!escaneo.delegado.isNullOrEmpty()) {
                CampoTexto("Motor de analisis", escaneo.delegado, MaterialTheme.typography.bodyMedium, CyberTextoSecundario)
            }
        }
    }
}

@Composable
private fun CampoTexto(
    etiqueta: String,
    valor: String,
    estilo: androidx.compose.material3.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    Column {
        Text(text = etiqueta, style = MaterialTheme.typography.labelMedium, color = CyberTextoSecundario)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = valor,
            style = estilo,
            color = color,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            maxLines = maxLines,
            overflow = if (maxLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip
        )
    }
}

@Composable
private fun SeccionBloquearDenunciar(
    onBloquear: () -> Unit,
    onDenunciar: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onBloquear,
            modifier = Modifier.weight(1f).testTag("btn_bloquear_malicioso"),
            colors = ButtonDefaults.buttonColors(containerColor = CyberRojo)
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Bloquear", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onDenunciar,
            modifier = Modifier.weight(1f).testTag("btn_denunciar_malicioso")
        ) {
            Icon(Icons.Filled.Report, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Denunciar")
        }
    }
}

@Composable
private fun CardAdvertenciaMalicioso(onProceder: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("card_advertencia_malicioso"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CyberRojo.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = "Advertencia", tint = CyberRojo, modifier = Modifier.size(28.dp))
                Text(
                    text = "Recomendamos bloquearlo, \u00bfseguro que deseas proceder?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberRojo
                )
            }
            Button(
                onClick = onProceder,
                modifier = Modifier.fillMaxWidth().testTag("btn_proceder_malicioso"),
                colors = ButtonDefaults.buttonColors(containerColor = CyberAmbar)
            ) {
                Text("Proceder bajo mi responsabilidad", fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Seccion de acciones Abrir / Copiar / Compartir del detalle.
 * Bug A8/F8 fix: validar scheme (solo http/https) y rechazar userinfo.
 */
@Composable
private fun SeccionAccionesDetalle(
    urlOriginal: String,
    urlLimpia: String,
    urlBloqueada: Boolean,
    context: android.content.Context,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                val uri = Uri.parse(urlOriginal)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    onMensaje(TipoMensaje.ERROR, "Solo se pueden abrir URLs HTTP/HTTPS en el navegador")
                    return@Button
                }
                if (uri.userInfo != null) {
                    onMensaje(TipoMensaje.ERROR, "La URL contiene credenciales embebidas y no se puede abrir")
                    return@Button
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    context.startActivity(intent)
                    onMensaje(TipoMensaje.EXITO, "Abriendo enlace…")
                } catch (e: Exception) {
                    onMensaje(TipoMensaje.ERROR, "No hay app para abrir este enlace")
                }
            },
            modifier = Modifier.weight(1f),
            enabled = !urlBloqueada,
            colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Abrir", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                clip?.setPrimaryClip(
                    android.content.ClipData.newPlainText("URL", urlLimpia)
                )
                onMensaje(TipoMensaje.EXITO, "URL copiada al portapapeles")
            },
            modifier = Modifier.weight(1f),
            enabled = !urlBloqueada
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Copiar")
        }
    }

    OutlinedButton(
        onClick = {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, urlLimpia)
                }
                context.startActivity(Intent.createChooser(intent, "Compartir"))
                onMensaje(TipoMensaje.EXITO, "Compartiendo enlace…")
            } catch (e: Exception) {
                onMensaje(TipoMensaje.ERROR, "No hay app para compartir")
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !urlBloqueada
    ) {
        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Compartir URL")
    }
}
