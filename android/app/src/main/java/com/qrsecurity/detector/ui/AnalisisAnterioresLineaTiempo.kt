package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberGlass
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberGlassBorde
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Elevacion
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Entrada del timeline para la pantalla de Analisis Anteriores — extraido a
 * archivo separado para mantener [PantallaAnalisisAnteriores] bajo 250 LOC.
 *
 * Componente: [EntradaLineaTiempo] — dot + rail + glass card con version
 * badge, % seguro, veredicto chip y fecha del análisis.
 *
 * Color/etiqueta del chip derivan de [EscaneoEntity.nivelAlertaEnum]
 * (single source of truth en [NivelAlerta]); helpers de fecha delegan a
 * [Fechas.kt] (fechaRelativa, formatoFechaHoraCorta).
 */

/**
 * Entrada del timeline — dot de color + rail vertical + glass card
 * con version badge (V1, V2...), % probabilidad, veredicto chip y
 * fecha/hora concretas del análisis.
 *
 * El color del dot y del chip, y la etiqueta corta del chip, derivan
 * de [EscaneoEntity.nivelAlertaEnum] — ver [NivelAlerta.etiquetaLineaTiempo]
 * para la decision de mapeo "MALICIOSO" -> "Bloqueada".
 *
 * Auditoría UI 2:
 *  - El conector del rail ya no tiene altura fija (40dp dejaba huecos con
 *    cards altas): se dibuja con `drawBehind` desde el borde inferior del
 *    dot hasta el fondo de la fila, cubriendo todo el tramo hasta el dot
 *    de la versión siguiente sin pasada de medición intrínseca (F4.4).
 *  - La fecha/hora completa ("Analizado el dd/MM/yyyy · HH:mm") acompaña a
 *    la relativa: para versiones viejas, "hace N días" no ubica en el
 *    calendario.
 *
 * Nota: esta lista contiene SOLO versiones anteriores — el DAO excluye el
 * escaneo vigente (idActual), que se ve en DetalleUrl. Por eso no existe
 * badge "Actual" aquí: la versión vigente no está en la lista.
 */
@Composable
internal fun EntradaLineaTiempo(
    escaneo: EscaneoEntity,
    version: Int,
    esUltimo: Boolean,
    onClick: () -> Unit = {}
) {
    val nivel = escaneo.nivelAlertaEnum
    val color = nivel.color
    val etiqueta = nivel.etiquetaLineaTiempo
    // Métrica canónica de la app: % SEGURO (idéntica al gauge del detalle de
    // esta versión). Antes mostraba el complemento crudo ("18% probabilidad"
    // junto a "Segura" y un gauge de "82%") — dos números para el mismo dato.
    val valorPct = pctSeguro(escaneo.probabilidad)

    // M3 audit fix — igual que FilaEscaneo: los strings de fecha son puros
    // respecto de `creadoEnMillis`. `remember(creadoEnMillis)` cachea ambos
    // strings entre recomposiciones (cada fila recompona al entrar/salir del
    // viewport durante el scroll) y evita re-ejecutar fechaRelativa
    // (epoch-days) + formatoFechaHoraCorta (2 DateTimeFormatter) por pasada.
    val fechaRelativaTexto = remember(escaneo.creadoEnMillis) {
        fechaRelativa(escaneo.creadoEnMillis)
    }
    val fechaAnalizadoTexto = remember(escaneo.creadoEnMillis) {
        "Analizado el ${formatoFechaHoraCorta(escaneo.creadoEnMillis)}"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // F4.4 audit fix — el conector del rail se dibuja con drawBehind
            // en vez de Box(weight 1f) + IntrinsicSize.Min, que forzaba una
            // pasada extra de medición intrínseca por entrada visible de la
            // lista. Aquí la línea se pinta en draw time (el tamaño del Row
            // ya está resuelto), desde el borde inferior del dot hasta el
            // fondo de la fila — mismo visual, color y grosor que antes.
            .drawBehind {
                if (esUltimo) return@drawBehind
                val dotPx = TamanosIcono.chico.toPx()
                val anchoLinea = 2.dp.toPx()
                // El rail ocupa el ancho del dot (su único hijo); la línea
                // queda centrada bajo el dot. RTL-aware.
                val xCentro = if (layoutDirection == LayoutDirection.Ltr) {
                    dotPx / 2f
                } else {
                    size.width - dotPx / 2f
                }
                val yInicio = dotPx + Espaciado.xs.toPx()
                drawRect(
                    color = CyberGlassBorde,
                    topLeft = Offset(xCentro - anchoLinea / 2f, yInicio),
                    size = Size(anchoLinea, size.height - yInicio)
                )
            },
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // ─── Timeline Rail (dot; el conector lo dibuja drawBehind) ───
        Box(
            modifier = Modifier
                .size(TamanosIcono.chico)
                .clip(RadioBorde.full)
                .background(color)
        )

        // ─── Analysis Card ───
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(RadioBorde.xl),
            colors = CardDefaults.cardColors(containerColor = CyberGlass),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevacion.ninguna)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Espaciado.lg),
                verticalArrangement = Arrangement.spacedBy(Espaciado.sm)
            ) {
                // Row 1: Version badge + verdict chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(RadioBorde.sm))
                            .background(CyberGlassAlto)
                            .padding(horizontal = Espaciado.md, vertical = Espaciado.xs)
                    ) {
                        Text(
                            text = "V$version",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = CyberTextoPrincipal
                        )
                    }
                    ChipNivel(texto = etiqueta, color = color)
                }

                // Row 2: % seguro + timestamp relativo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$valorPct% seguro",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberTextoPrincipal
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Espaciado.xs)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Schedule,
                            contentDescription = null,
                            tint = CyberTextoSecundario,
                            modifier = Modifier.size(TamanosIcono.chico)
                        )
                        Text(
                            text = fechaRelativaTexto,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTextoSecundario
                        )
                    }
                }

                // Row 3: Fecha y hora concretas del análisis
                Text(
                    text = fechaAnalizadoTexto,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTextoSecundario
                )
            }
        }
    }
}
