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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.CyberGlassAlto
import com.qrsecurity.detector.ui.theme.CyberTextoPrincipal
import com.qrsecurity.detector.ui.theme.CyberTextoSecundario
import com.qrsecurity.detector.ui.theme.Espaciado
import com.qrsecurity.detector.ui.theme.RadioBorde
import com.qrsecurity.detector.ui.theme.TamanosIcono

/**
 * Fila del historial deduplicado — extraida de [HistorialScreen] para
 * mantener esa pantalla enfocada en layout/estado.
 *
 * El mapeo nivel→(icono, color, etiqueta) usa [NivelAlerta] (single source
 * of truth) en vez de literales crudos — el fallback ante un id desconocido
 * también es consistente (SOSPECHOSO, fail-safe).
 */
@Composable
internal fun FilaEscaneo(
    escaneo: EscaneoEntity,
    bloqueada: Boolean,
    onVerDetalle: (String) -> Unit,
    onMensaje: (TipoMensaje, String) -> Unit
) {
    val nivel = escaneo.nivelAlertaEnum
    val (icono, color) = when (nivel) {
        NivelAlerta.SEGURO -> Icons.Filled.CheckCircle to nivel.color
        NivelAlerta.SOSPECHOSO -> Icons.Filled.Warning to nivel.color
        NivelAlerta.MALICIOSO -> Icons.Filled.Block to nivel.color
    }
    val etiqueta = if (bloqueada) "Bloqueada" else nivel.etiquetaHistorial

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (bloqueada) onMensaje(TipoMensaje.INFO, "Abre el detalle para desbloquear esta URL")
                onVerDetalle(escaneo.id)
            }
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // Status Tile
        Box(
            modifier = Modifier
                .size(TamanosIcono.mediano)
                .background(color.copy(alpha = 0.15f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icono,
                contentDescription = etiqueta,
                tint = color,
                modifier = Modifier.size(TamanosIcono.estandar)
            )
        }
        // Info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = escaneo.urlLimpia,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = CyberTextoPrincipal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = etiqueta,
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario
            )
        }
        // Time + Unlock Pill
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Espaciado.xs)
        ) {
            Text(
                text = fechaRelativa(escaneo.creadoEnMillis),
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario
            )
            if (bloqueada) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Espaciado.xs),
                    modifier = Modifier
                        .background(CyberGlassAlto, RoundedCornerShape(RadioBorde.sm))
                        .padding(horizontal = Espaciado.sm, vertical = Espaciado.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = "Desbloquear",
                        tint = CyberCyan,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
