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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ui.theme.Alphas
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
    onVerDetalle: (String) -> Unit
) {
    val nivel = escaneo.nivelAlertaEnum
    val (icono, color) = when (nivel) {
        NivelAlerta.SEGURO -> Icons.Filled.CheckCircle to nivel.color
        NivelAlerta.SOSPECHOSO -> Icons.Filled.Warning to nivel.color
        NivelAlerta.MALICIOSO -> Icons.Filled.Block to nivel.color
    }
    val etiqueta = if (bloqueada) "Bloqueada" else nivel.etiquetaHistorial

    // M3 audit fix — `fechaRelativa` es pura respecto de `creadoEnMillis`
    // (su dependencia en "ahora" no cambia dentro de una misma sesión de
    // visualización). `remember(creadoEnMillis)` cachea el string entre
    // recomposiciones sin re-ejecutar el DateTimeFormatter cada vez que
    // la columna se recompone sin que haya cambiado el escaneo. La fecha
    // grupo ("Hoy" / "Ayer") sí sigue viva porque la computa
    // `agruparHistorialPorFecha` dentro del StateFlow (no aquí).
    val fechaRelativaTexto = remember(escaneo.creadoEnMillis) {
        fechaRelativa(escaneo.creadoEnMillis)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Sin snackbar previo: este tap YA navega al detalle, donde vive
            // el botón Desbloquear — el mensaje "abre el detalle" aparecía
            // encima de la pantalla a la que acabábamos de entrar.
            .clickable { onVerDetalle(escaneo.id) }
            .padding(horizontal = Espaciado.lg, vertical = Espaciado.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espaciado.md)
    ) {
        // Status Tile
        Box(
            modifier = Modifier
                .size(TamanosIcono.mediano)
                .background(color.copy(Alphas.bajo), RadioBorde.full),
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
                // Auditoría UI 2: la URL es la información principal de la
                // fila — 2 líneas antes del ellipsis (antes 1) para que la
                // mayoría de URLs se lean casi completas.
                maxLines = 2,
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
                text = fechaRelativaTexto,
                style = MaterialTheme.typography.labelSmall,
                color = CyberTextoSecundario,
                // Acotado a 1 línea para no robarle ancho a la URL.
                maxLines = 1
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
                        modifier = Modifier.size(TamanosIcono.chico)
                    )
                }
            }
        }
    }
}
