package com.qrsecurity.detector.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
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
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono
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
    /**
     * Bug 2 fix: true si este escaneo es la version mas reciente de su
     * `urlLimpia`. Las acciones (Abrir, Copiar, Compartir, Bloquear,
     * Denunciar) solo se muestran en la ultima version; las versiones
     * anteriores (reescaneos) son "solo detalles".
     */
    esUltimaVersion: Boolean = true,
    /**
     * Bug 2 fix: reescaneos (versiones anteriores de la misma URL),
     * paginados. Se muestran al final de la pantalla; tocar uno navega
     * al detalle de ese reescaneo (donde esUltimaVersion=false).
     */
    reescaneos: List<EscaneoEntity> = emptyList(),
    /**
     * Bug 2 fix: total de reescaneos (excluyendo el escaneo actual). Si
     * [reescaneos].size < [totalReescaneos] se muestra "Ver mas".
     */
    totalReescaneos: Int = 0,
    onVolver: () -> Unit,
    onBloquear: () -> Unit = {},
    onDenunciar: (String) -> Unit = {},
    /**
     * Bug 2 fix: callback para navegar al detalle de un reescaneo.
     * Tocar una tarjeta de reescaneo dispara este callback con el `id`
     * del reescaneo, que se navega via `detalle_escaneo/{id}`.
     */
    onVerDetalle: (String) -> Unit = {},
    /**
     * Bug 2 fix: callback para cargar mas reescaneos (siguiente pagina).
     * Disparado por el boton "Ver mas" en la seccion de reescaneos.
     */
    onCargarMasReescaneos: () -> Unit = {},
    onMensaje: (TipoMensaje, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val estadoScroll = rememberScrollState()
    var procederConfirmado by remember { mutableStateOf(false) }

    val veredicto = remember(escaneo.esMalicioso, escaneo.nivelAlerta) {
        calcularVeredicto(escaneo)
    }
    // Bug 2 fix: las acciones solo aparecen en la ULTIMA version.
    // Las versiones anteriores son "solo detalles".
    val requiereAccion = esUltimaVersion && veredicto.esMalicioso && !urlBloqueada

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(estadoScroll)
            .background(CyberFondo)
            .padding(horizontal = Espaciado.xl, vertical = Espaciado.xxl)
            .testTag("detalle_escaneo_root"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Espaciado.xl)
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

        // Bug 2 fix: si NO es la ultima version, mostrar un badge indicando
        // que es una version anterior (solo lectura, sin acciones).
        if (!esUltimaVersion) {
            BadgeVersionAnterior()
        }

        TarjetaDatosEscaneo(escaneo = escaneo, veredicto = veredicto)

        // Bug 2 fix: solo las acciones aparecen en la ULTIMA version.
        // En las versiones anteriores (reescaneos vistos desde el detalle
        // principal), el usuario pidio "solo detalles, no acciones".
        if (requiereAccion) {
            SeccionBloquearDenunciar(
                onBloquear = onBloquear,
                onDenunciar = { onDenunciar(escaneo.urlLimpia) }
            )
        }

        if (requiereAccion && !procederConfirmado) {
            CardAdvertenciaMalicioso(onProceder = { procederConfirmado = true })
        }

        val mostrarAcciones = esUltimaVersion && (!requiereAccion || procederConfirmado)
        if (mostrarAcciones) {
            SeccionAccionesDetalle(
                urlOriginal = escaneo.urlOriginal,
                urlLimpia = escaneo.urlLimpia,
                urlBloqueada = urlBloqueada,
                context = context,
                onMensaje = onMensaje
            )
        }

        // ── Bug 2 fix: Seccion de reescaneos (versiones anteriores) ──
        //
        // Muestra los reescaneos paginados. Solo aparecen si el escaneo
        // actual ES la ultima version (no mostramos reescaneos de
        // reescaneos — evita recursion visual confusa). Tocar un
        // reescaneo navega a su detalle (esUltimaVersion=false).
        if (esUltimaVersion && reescaneos.isNotEmpty()) {
            SeccionReescaneos(
                reescaneos = reescaneos,
                totalReescaneos = totalReescaneos,
                onVerDetalle = onVerDetalle,
                onCargarMas = onCargarMasReescaneos
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
            .clip(RoundedCornerShape(RadioBorde.lg))
            .background(CyberRojo.copy(alpha = 0.15f))
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "Bloqueada",
            tint = CyberRojo,
            modifier = Modifier.size(Espaciado.xl)
        )
        Text(
            text = "URL bloqueada — no se puede abrir, copiar ni compartir",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = CyberRojo
        )
    }
}

/**
 * Bug 2 fix: badge que aparece cuando el usuario esta viendo una
 * **version anterior** (reescaneo) de una URL. Indica que es solo lectura
 * — sin botones de accion (Abrir/Copiar/Compartir/Bloquear/Denunciar).
 * Las acciones solo figuran en la cartilla de la ultima version.
 */
@Composable
private fun BadgeVersionAnterior() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.lg))
            .background(CyberAmbar.copy(alpha = 0.15f))
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
    ) {
        Icon(
            imageVector = Icons.Filled.History,
            contentDescription = "Version anterior",
            tint = CyberAmbar,
            modifier = Modifier.size(Espaciado.xl)
        )
        Text(
            text = "Version anterior — solo detalles. Las acciones se " +
                "muestran en la version mas reciente de esta URL.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = CyberAmbar
        )
    }
}

/**
 * Bug 2 fix: seccion de reescaneos (versiones anteriores de la misma URL).
 *
 * Muestra los reescaneos como tarjetas compactas, paginadas. Tocar una
 * tarjeta navega al detalle de ese reescaneo (donde
 * [DetalleEscaneoUiState.Cargado.esUltimaVersion] = false).
 *
 * Si hay mas reescaneos por cargar ([reescaneos].size < [totalReescaneos]),
 * muestra un boton "Ver mas" que dispara [onCargarMas].
 *
 * @param reescaneos Lista de reescaneos ya cargados (acumulada, paginada).
 * @param totalReescaneos Total de reescaneos (excluyendo el escaneo actual).
 * @param onVerDetalle Llamado al tocar una tarjeta → navega al detalle.
 * @param onCargarMas Llamado al pulsar "Ver mas" → carga siguiente pagina.
 */
@Composable
private fun SeccionReescaneos(
    reescaneos: List<EscaneoEntity>,
    totalReescaneos: Int,
    onVerDetalle: (String) -> Unit,
    onCargarMas: () -> Unit
) {
    val hayMas = reescaneos.size < totalReescaneos

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("seccion_reescaneos"),
        shape = RoundedCornerShape(RadioBorde.xxl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass),
        border = BorderStroke(Elevacion.sutil, CyberGlassBorde)
    ) {
        Column(
            modifier = Modifier.padding(Espaciado.xl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = CyberTextoSecundario,
                    modifier = Modifier.size(TamanosIcono.estandar)
                )
                Text(
                    text = "Otras versiones de escaneo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = CyberTextoPrincipal
                )
                Spacer(modifier = Modifier.width(Espaciado.sm))
                Text(
                    text = "($totalReescaneos)",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberTextoSecundario
                )
            }

            reescaneos.forEach { reescaneo ->
                TarjetaReescaneo(reescaneo = reescaneo, onVerDetalle = onVerDetalle)
            }

            if (hayMas) {
                OutlinedButton(
                    onClick = onCargarMas,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_ver_mas_reescaneos")
                ) {
                    Text(
                        text = "Ver mas (${totalReescaneos - reescaneos.size} restantes)",
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Bug 2 fix: tarjeta compacta de un reescaneo (version anterior).
 * Muestra URL, fecha y nivel de alerta. Tocar navega al detalle.
 */
@Composable
private fun TarjetaReescaneo(
    reescaneo: EscaneoEntity,
    onVerDetalle: (String) -> Unit
) {
    val esMalicioso = reescaneo.esMalicioso
    val colorIcono = if (esMalicioso) CyberRojo else CyberVerdeAlertaClaro
    val icono = if (esMalicioso) Icons.Filled.Warning else Icons.Filled.CheckCircle

    val fechaStr = remember(reescaneo.creadoEnMillis) {
        Instant.ofEpochMilli(reescaneo.creadoEnMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.lg))
            .background(CyberGlass.copy(alpha = 0.5f))
            .clickable { onVerDetalle(reescaneo.id) }
            .padding(Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Icon(
            imageVector = icono,
            contentDescription = null,
            tint = colorIcono,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reescaneo.urlLimpia,
                style = MaterialTheme.typography.bodyMedium,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$fechaStr • ${reescaneo.nivelAlerta.uppercase()} • " +
                    "${(reescaneo.probabilidad * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = CyberTextoSecundario,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = "Ver detalle",
            tint = CyberTextoSecundario,
            modifier = Modifier.size(TamanosIcono.estandar)
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
            .border(BorderStroke(Elevacion.sutil, CyberGlassBorde), RoundedCornerShape(RadioBorde.xxl)),
        shape = RoundedCornerShape(RadioBorde.xxl),
        colors = CardDefaults.cardColors(containerColor = CyberGlass)
    ) {
        Column(
            modifier = Modifier.padding(Espaciado.xl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
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
                Spacer(modifier = Modifier.height(Espaciado.sm))
                LinearProgressIndicator(
                    progress = escaneo.probabilidad,
                    modifier = Modifier.fillMaxWidth().height(Espaciado.sm).clip(RoundedCornerShape(RadioBorde.sm)),
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
    estilo: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    bold: Boolean = false,
    maxLines: Int = Int.MAX_VALUE
) {
    Column {
        Text(text = etiqueta, style = MaterialTheme.typography.labelMedium, color = CyberTextoSecundario)
        Spacer(modifier = Modifier.height(Espaciado.xs))
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
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Button(
            onClick = onBloquear,
            modifier = Modifier.weight(1f).testTag("btn_bloquear_malicioso"),
            colors = ButtonDefaults.buttonColors(containerColor = CyberRojo)
        ) {
            Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(TamanosIcono.estandar))
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text("Bloquear", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onDenunciar,
            modifier = Modifier.weight(1f).testTag("btn_denunciar_malicioso")
        ) {
            Icon(Icons.Filled.Report, contentDescription = null, modifier = Modifier.size(TamanosIcono.estandar))
            Spacer(modifier = Modifier.width(Espaciado.sm))
            Text("Denunciar")
        }
    }
}

@Composable
private fun CardAdvertenciaMalicioso(onProceder: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("card_advertencia_malicioso"),
        shape = RoundedCornerShape(RadioBorde.xl),
        colors = CardDefaults.cardColors(containerColor = CyberRojo.copy(alpha = 0.12f))
    ) {
        Column(
            modifier = Modifier.padding(Espaciado.xl),
            verticalArrangement = Arrangement.spacedBy(Espaciado.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
            ) {
                Icon(Icons.Filled.Warning, contentDescription = "Advertencia", tint = CyberRojo, modifier = Modifier.size(Espaciado.xxxs))
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
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
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
                    val chooser = Intent.createChooser(intent, "Abrir con...")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
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
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
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
                modifier = Modifier.size(TamanosIcono.estandar)
            )
            Spacer(modifier = Modifier.width(Espaciado.sm))
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
            modifier = Modifier.size(TamanosIcono.estandar)
        )
        Spacer(modifier = Modifier.width(Espaciado.sm))
        Text("Compartir URL")
    }
}
