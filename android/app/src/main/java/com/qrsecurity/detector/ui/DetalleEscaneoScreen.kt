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

    val esMalicioso = escaneo.esMalicioso
    val esSospechoso = escaneo.nivelAlerta.uppercase() == "SOSPECHOSO"

    // Para enlaces maliciosos NO bloqueados, el usuario debe confirmar
    // "Recomendamos bloquearlo, ¿seguro que deseas proceder?" antes de
    // ver los botones Abrir/Copiar/Compartir.
    val requiereConfirmacion = esMalicioso && !urlBloqueada
    var procederConfirmado by remember { mutableStateOf(false) }

    val colorIcono = when {
        esMalicioso -> CyberRojo
        esSospechoso -> CyberAmbar
        else -> CyberVerdeAlertaClaro
    }
    val icono = when {
        esMalicioso -> Icons.Filled.Warning
        esSospechoso -> Icons.Filled.Warning
        else -> Icons.Filled.CheckCircle
    }
    val tituloVeredicto = when {
        esMalicioso -> "Enlace Malicioso"
        esSospechoso -> "Enlace Sospechoso"
        else -> "Enlace Seguro"
    }

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
        // ── Barra superior (shared component) ──
        BarraSuperiorResultado(
            titulo = "Detalle del escaneo",
            onCerrar = onVolver,
            contentDescriptionBack = "Volver"
        )

        // ── Icono de veredicto con glow (shared component) ──
        IconoGlowCircular(
            icono = icono,
            colorGlow = colorIcono,
            contentDescription = tituloVeredicto,
            alphaGlow = 0.25f
        )

        // ── Titulo de veredicto ──
        Text(
            text = tituloVeredicto,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colorIcono
        )

        // ── Badge URL bloqueada (Bug BLOQUEO-1) ──
        if (urlBloqueada) {
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

        // ── Tarjeta glass con datos del escaneo ──
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
                // URL limpia
                Column {
                    Text(
                        text = "URL analizada",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTextoSecundario
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = escaneo.urlLimpia,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberTextoPrincipal,
                        fontWeight = FontWeight.Medium
                    )
                }

                // URL original (si difiere de la limpia)
                if (escaneo.urlOriginal != escaneo.urlLimpia) {
                    Column {
                        Text(
                            text = "URL original",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberTextoSecundario
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = escaneo.urlOriginal,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextoSecundario,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Dominio
                Column {
                    Text(
                        text = "Dominio",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTextoSecundario
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = dominio,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberTextoPrincipal
                    )
                }

                // Nivel de alerta
                Column {
                    Text(
                        text = "Nivel de alerta",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTextoSecundario
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = escaneo.nivelAlerta.uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colorIcono
                    )
                }

                // Probabilidad
                Column {
                    Text(
                        text = "Probabilidad de amenaza: ${(escaneo.probabilidad * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTextoSecundario
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = escaneo.probabilidad,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = colorIcono,
                        trackColor = CyberGlass
                    )
                }

                // Fecha
                Column {
                    Text(
                        text = "Fecha de escaneo",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberTextoSecundario
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = fechaStr,
                        style = MaterialTheme.typography.bodyLarge,
                        color = CyberTextoPrincipal
                    )
                }

                // Delegado
                if (!escaneo.delegado.isNullOrEmpty()) {
                    Column {
                        Text(
                            text = "Motor de analisis",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberTextoSecundario
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = escaneo.delegado,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CyberTextoSecundario
                        )
                    }
                }
            }
        }

        // ── Acciones para maliciosos NO bloqueados: Bloquear + Denunciar ──
        if (requiereConfirmacion) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onBloquear,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_bloquear_malicioso"),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberRojo)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Block,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bloquear", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onDenunciar(escaneo.urlLimpia) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("btn_denunciar_malicioso")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Report,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Denunciar")
                }
            }
        }

        // ── Advertencia para maliciosos NO bloqueados ──
        if (requiereConfirmacion && !procederConfirmado) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("card_advertencia_malicioso"),
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
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = "Advertencia",
                            tint = CyberRojo,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = "Recomendamos bloquearlo, ¿seguro que deseas proceder?",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberRojo
                        )
                    }

                    Button(
                        onClick = { procederConfirmado = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("btn_proceder_malicioso"),
                        colors = ButtonDefaults.buttonColors(containerColor = CyberAmbar)
                    ) {
                        Text("Proceder bajo mi responsabilidad", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Botones Abrir / Copiar / Compartir ──
        // Para maliciosos NO bloqueados, se muestran solo despues de confirmar.
        // Para maliciosos bloqueados, estan deshabilitados (badge BLOQUEO-1).
        // Para seguros/sospechosos, se muestran normalmente.
        val mostrarAcciones = !requiereConfirmacion || procederConfirmado

        if (mostrarAcciones) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        // Bug A8/F8 fix — simetrico con ResultadoSeguroScreen:
                        // validar scheme (solo http/https) y rechazar userinfo
                        // antes de lanzar ACTION_VIEW. Sin esto, un QR escaneado
                        // con scheme intent:/market:/javascript: o con userinfo
                        // (https://apple.com@evil.com) se abre desde el detalle
                        // del historial sin ninguna proteccion.
                        val uri = Uri.parse(escaneo.urlOriginal)
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
                            android.content.ClipData.newPlainText("URL", escaneo.urlLimpia)
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
                            putExtra(Intent.EXTRA_TEXT, escaneo.urlLimpia)
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
    }
}
