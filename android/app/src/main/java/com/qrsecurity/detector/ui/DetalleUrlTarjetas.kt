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
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.Borde
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
 *  - [TarjetaUrl]: glass card con QR icon + URL (hasta 4 líneas) + chip de
 *    estado + fecha "Analizado:" opcional.
 *  - [ChipEstadoUrl]: chip de nivel-alerta (o "BLOQUEADA" si aplica) sobre
 *    la receta única [ChipNivel].
 *  - [TarjetaVeredicto]: gauge hero (120dp) + amenaza label + subtitulo pill.
 *  - [TarjetaVersiones]: glass card clickable "Versiones anteriores".
 */

/**
 * Tarjeta URL — glass card con QR icon en circulo + URL + chip de estado.
 *
 * Auditoría UI 2: el chip ya no compite por ancho con la URL. La URL vive en
 * su propia columna (weight 1f) con hasta 4 líneas antes del ellipsis — el
 * contenedor se adapta al contenido en vez de mutilar la URL para caber en
 * una card chica. [fechaAnalisis] añade la línea "Analizado: dd/MM/yyyy ·
 * HH:mm" (solo DetalleUrl la pasa; DetalleVersionAntigua conserva su tarjeta
 * de fecha dedicada).
 */
@Composable
internal fun TarjetaUrl(
    escaneo: EscaneoEntity,
    urlBloqueada: Boolean,
    fechaAnalisis: Long? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xxl))
            .background(CyberGlass)
            .border(
                width = Borde.fino,
                color = CyberGlassBorde,
                shape = RoundedCornerShape(RadioBorde.xxl)
            )
            .padding(Espaciado.lg),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        Box(
            modifier = Modifier
                .size(TamanosIcono.mediano)
                .clip(RadioBorde.full)
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
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
        ) {
            Text(
                text = escaneo.urlOriginal.ifBlank { escaneo.urlLimpia },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = CyberTextoPrincipal,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            ChipEstadoUrl(nivelAlerta = escaneo.nivelAlertaEnum, urlBloqueada = urlBloqueada)
            if (fechaAnalisis != null) {
                Text(
                    text = "Analizado: ${formatoFechaHoraCorta(fechaAnalisis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyberTextoSecundario
                )
            }
        }
    }
}

/**
 * Chip de estado de la URL. Muestra "Bloqueada" si [urlBloqueada] es true
 * (predomina sobre el nivelAlerta); si no, muestra la etiqueta sentence case
 * del [NivelAlerta] ("Maliciosa" | "Sospechosa" | "Segura") con el color del
 * nivel. "Bloqueada" reusa el color de MALICIOSO (rojo) por consistencia
 * visual. Unificado con FilaEscaneo y el timeline vía [NivelAlerta.etiquetaHistorial].
 *
 * Auditoría UI 2: la geometría (radio, padding, alphas, tipografía) delega a
 * [ChipNivel] — receta única del design system compartida con el chip del
 * timeline y la pill del veredicto.
 */
@Composable
internal fun ChipEstadoUrl(nivelAlerta: NivelAlerta, urlBloqueada: Boolean = false) {
    // BLOQUEADA predomina; reusa el color de MALICIOSO. Si no, muestra la
    // etiqueta sentence case del nivelAlerta (coincide con el historial).
    val (texto, nivel) = if (urlBloqueada) {
        "Bloqueada" to NivelAlerta.MALICIOSO
    } else {
        nivelAlerta.etiquetaHistorial to nivelAlerta
    }
    ChipNivel(texto = texto, color = nivel.color)
}

/**
 * Tarjeta de veredicto — gauge 120dp centrado + label + subtitulo pill,
 * con border glow del color del veredicto. Toma [EscaneoEntity] para
 * ser reutilizada por DetalleUrl y DetalleVersionAntigua sin un wrapper de
 * UiState. Color/etiqueta/subitulo derivan de [EscaneoEntity.nivelAlertaEnum].
 *
 * Convencion del gauge — SIEMPRE muestra % de seguridad (100 - amenaza):
 *  - SEGURO:    97% seguro  (arco verde casi lleno)  → "Sin amenazas"
 *  - SOSPECHOSO: ~50% seguro (arco ambar medio)     → "Amenaza moderada"
 *  - MALICIOSO: 3% seguro  (arco rojo casi vacio)   → "Amenaza alta"
 *
 * El numero SIEMPRE significa "% seguro" — mas alto = mas seguro.
 * El color del arco indica el veredicto. Sin ambiguedad.
 */
@Composable
internal fun TarjetaVeredicto(escaneo: EscaneoEntity) {
    val nivel = escaneo.nivelAlertaEnum
    val pctSeguridad = pctSeguro(escaneo.probabilidad)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadioBorde.xxl))
            .background(CyberGlass)
            .border(
                width = Borde.fino,
                color = nivel.color.copy(Alphas.notorio),
                shape = RoundedCornerShape(RadioBorde.xxl)
            )
            .padding(Espaciado.xxl),
        verticalArrangement = Arrangement.spacedBy(Espaciado.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MedidorGauge(
            progreso = 1f - escaneo.probabilidad.coerceIn(0f, 1f),
            colorArco = nivel.color,
            colorTrack = CyberGlassBorde,
            valorTexto = "$pctSeguridad%",
            colorTexto = CyberTextoPrincipal,
            modifier = Modifier.size(TamanosIcono.heroContenedor)
        )
        Text(
            text = nivel.etiquetaAmenaza,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = CyberTextoPrincipal
        )
        ChipNivel(texto = nivel.subtituloAmenaza, color = nivel.color)
    }
}

/**
 * Tarjeta "Versiones anteriores" — glass card con History icon, contador de
 * versiones y arrow. Entrada al historial de versiones de la URL.
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
                width = Borde.fino,
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
                .clip(RadioBorde.full)
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
                text = "Versiones anteriores del análisis",
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
