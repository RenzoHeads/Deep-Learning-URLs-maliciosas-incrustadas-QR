package com.qrsecurity.detector.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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

/**
 * Entrada del timeline para la pantalla de Analisis Anteriores — extraido a
 * archivo separado para mantener [PantallaAnalisisAnteriores] bajo 250 LOC.
 *
 * Componente: [EntradaLineaTiempo] — dot + rail + glass card con version
 * badge, % probabilidad, veredicto chip y nota de analisis.
 *
 * Color/etiqueta del chip derivan de [EscaneoEntity.nivelAlertaEnum]
 * (single source of truth en [NivelAlerta]); helpers de fecha delegan a
 * [Fechas.kt] (fechaRelativa, formatoHora).
 */

/**
 * Entrada del timeline — dot de color + rail vertical + glass card
 * con version badge (V1, V2...), % probabilidad, veredicto chip y
 * nota de analisis.
 *
 * El color del dot y del chip, y la etiqueta corta del chip, derivan
 * de [EscaneoEntity.nivelAlertaEnum] — ver [NivelAlerta.etiquetaLineaTiempo]
 * para la decision de mapeo "MALICIOSO" -> "Bloqueada".
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
    val valorPct = probabilidadPct(escaneo.probabilidad)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // ─── Timeline Rail (dot + connector) ───
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            if (!esUltimo) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(48.dp)
                        .background(CyberGlassBorde)
                )
            }
        }

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
                    Box(
                        modifier = Modifier
                            .background(color.copy(alpha = 0.18f), RoundedCornerShape(RadioBorde.sm))
                            .padding(horizontal = Espaciado.sm, vertical = Espaciado.xs)
                    ) {
                        Text(
                            text = etiqueta,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                    }
                }

                // Row 2: % probabilidad + timestamp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$valorPct% probabilidad",
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
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = fechaRelativa(escaneo.creadoEnMillis),
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberTextoSecundario
                        )
                    }
                }

                // Row 3: Hora exacta del analisis
                Text(
                    text = "Análisis refrescado a las ${formatoHora(escaneo.creadoEnMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyberTextoSecundario
                )
            }
        }
    }
}
