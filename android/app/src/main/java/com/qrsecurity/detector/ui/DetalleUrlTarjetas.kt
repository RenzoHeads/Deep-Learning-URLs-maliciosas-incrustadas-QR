package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Tarjetas de la pantalla de Detalle — compartidas entre DetalleUrl y
 * DetalleVersionAntigua. Tomar [EscaneoEntity] (no el UiState) las hace
 * reutilizables en ambas pantallas: DetalleUrl envuelve el EscaneoEntity
 * con `urlBloqueada` y `esUltimaVersion` como concerns separados (no
 * bakeados en la tarjeta), mientras DetalleVersionAntigua solo tiene el
 * EscaneoEntity puro.
 *
 * Componentes:
 *  - [TarjetaUrl]: glass card con QR icon + URL + chip de estado.
 *  - [ChipEstadoUrl]: chip de nivel-alerta (o "BLOQUEADA" si aplica).
 *  - [TarjetaVeredicto]: gauge 140dp + amenaza label + subtitulo pill.
 *  - [TarjetaVersiones]: glass card clickable "Ver versiones de este analisis".
 */

/** Tarjeta URL — glass card con QR icon en circulo + URL + chip de estado. */
@Composable
internal fun TarjetaUrl(escaneo: EscaneoEntity, urlBloqueada: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xxl))
            .background(CyberGlass)
            .border(
                width = 1.dp,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.xxl)
            )
            .padding(Espaciado.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(TamanosIcono.mediano)
                .clip(CircleShape)
                .background(CyberGlassAlto),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.QrCode,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        }
        Text(
            text = escaneo.urlOriginal.ifBlank { escaneo.urlLimpia },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = CyberTextoPrincipal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        ChipEstadoUrl(nivelAlerta = escaneo.nivelAlertaEnum, urlBloqueada = urlBloqueada)
    }
}

/**
 * Chip de estado de la URL. Muestra "BLOQUEADA" si [urlBloqueada] es true
 * (predomina sobre el nivelAlerta); si no, muestra el [NivelAlerta.id] con
 * el color del nivel. BLOQUEADA reusa el color de MALICIOSO (rojo) por
 * consistencia visual.
 */
@Composable
internal fun ChipEstadoUrl(nivelAlerta: NivelAlerta, urlBloqueada: Boolean = false) {
    // BLOQUEADA predomina; reusa el color de MALICIOSO. Si no, muestra el id
    // del nivelAlerta tal cual ("MALICIOSO" | "SOSPECHOSO" | "SEGURO").
    val (texto, nivel) = if (urlBloqueada) {
        "BLOQUEADA" to NivelAlerta.MALICIOSO
    } else {
        nivelAlerta.id to nivelAlerta
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(RadioBorde.sm))
            .background(nivel.color.copy(alpha = 0.18f))
            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
    ) {
        Text(
            text = texto,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = nivel.color
        )
    }
}

/**
 * Tarjeta de veredicto — gauge 140dp centrado + label de amenaza + subtitulo
 * pill, con border glow del color del veredicto. Toma [EscaneoEntity] para
 * ser reutilizada por DetalleUrl y DetalleVersionAntigua sin un wrapper de
 * UiState. Color/etiqueta/subtitulo derivan de [EscaneoEntity.nivelAlertaEnum].
 */
@Composable
internal fun TarjetaVeredicto(escaneo: EscaneoEntity) {
    val nivel = escaneo.nivelAlertaEnum
    val valorPct = probabilidadPct(escaneo.probabilidad)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xxl))
            .background(CyberGlass)
            .border(
                width = 1.dp,
                color = nivel.color.copy(alpha = 0.25f),
                shape = RoundedCornerShape(RadioBorde.xxl)
            )
            .padding(Espaciado.xxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MedidorGauge(
            progreso = escaneo.probabilidad,
            colorArco = nivel.color,
            colorTrack = CyberGlassBorde,
            valorTexto = valorPct.toString(),
            colorTexto = CyberTextoPrincipal,
            modifier = Modifier.size(140.dp)
        )
        Text(
            text = nivel.etiquetaAmenaza,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(RadioBorde.sm))
                .background(nivel.color.copy(alpha = 0.16f))
                .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
        ) {
            Text(
                text = nivel.subtituloAmenaza,
                style = MaterialTheme.typography.labelMedium,
                color = nivel.color
            )
        }
    }
}

/**
 * Tarjeta "Ver versiones" — glass card con History icon, contador de
 * versiones y arrow. Reemplaza el text link del diseno anterior.
 */
@Composable
internal fun TarjetaVersiones(
    totalReescaneos: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xxl))
            .background(CyberGlass)
            .border(
                width = 1.dp,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.xxl)
            )
            .clickable(onClick = onClick)
            .padding(Espaciado.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(TamanosIcono.mediano)
                .clip(CircleShape)
                .background(CyberGlassAlto),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = "Ver versiones de este análisis",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CyberTextoPrincipal
            )
            Text(
                text = "$totalReescaneos ${if (totalReescaneos == 1) "versión anterior" else "versiones anteriores"}",
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = CyberCyan,
            modifier = Modifier.size(TamanosIcono.estandar)
        )
    }
}
