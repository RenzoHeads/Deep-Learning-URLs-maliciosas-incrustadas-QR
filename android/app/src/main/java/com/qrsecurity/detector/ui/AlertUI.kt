package com.qrsecurity.detector.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qrsecurity.detector.ml.ControladorAlerta
import com.qrsecurity.detector.ui.theme.AlertaMalicioso
import com.qrsecurity.detector.ui.theme.AlertaMaliciosoFondo
import com.qrsecurity.detector.ui.theme.AlertaSeguro
import com.qrsecurity.detector.ui.theme.AlertaSeguroFondo
import com.qrsecurity.detector.ui.theme.AlertaSospechoso
import com.qrsecurity.detector.ui.theme.AlertaSospechosoFondo
import com.qrsecurity.detector.ui.theme.CyberGlass

/**
 * Componente Compose de tarjeta de alerta con design system Cyber-Sentinel.
 *
 * Muestra el veredicto de seguridad de la URL en uno de tres estados
 * codificados por color:
 *
 *   - **SEGURO** (cyan)      — [ControladorAlerta.NivelAlerta.SEGURO]
 *   - **SOSPECHOSO** (ámbar) — [ControladorAlerta.NivelAlerta.SOSPECHOSO]
 *   - **MALICIOSO** (rojo)   — [ControladorAlerta.NivelAlerta.MALICIOSO]
 *
 * La tarjeta usa glassmorphism (CyberGlass de fondo) + glow radial
 * del color de alerta sobre el icono.
 */
@Composable
fun TarjetaAlerta(
    nivelAlerta: ControladorAlerta.NivelAlerta,
    probabilidad: Float,
    modifier: Modifier = Modifier
) {
    val colores = coloresAlertaCyber(nivelAlerta)
    val titulo = tituloAlerta(nivelAlerta)
    val descripcion = descripcionAlerta(nivelAlerta)
    val icono = iconoAlerta(nivelAlerta)

    val fondoAnimado by animateColorAsState(
        targetValue = colores.fondo,
        animationSpec = tween(durationMillis = 400),
        label = "fondoAlerta"
    )

    val colorIconoAnimado by animateColorAsState(
        targetValue = colores.icono,
        animationSpec = tween(durationMillis = 400),
        label = "iconoAlerta"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = fondoAnimado),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Icono de estado en insignia circular con glow ──
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(colorIconoAnimado.copy(alpha = 0.25f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icono,
                    contentDescription = titulo,
                    modifier = Modifier.size(40.dp),
                    tint = colorIconoAnimado
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Titulo ──
            Text(
                text = titulo,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = colores.icono,
                textAlign = TextAlign.Center
            )

            // ── Barra de probabilidad ──
            BarraProbabilidad(
                probabilidad = probabilidad,
                colorBarra = colores.icono,
                modifier = Modifier.fillMaxWidth()
            )

            // ── Descripcion ──
            Text(
                text = descripcion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Barra de probabilidad horizontal con gradiente cyber-sentinel.
 */
@Composable
private fun BarraProbabilidad(
    probabilidad: Float,
    colorBarra: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(CyberGlass)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(probabilidad.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(colorBarra.copy(alpha = 0.6f), colorBarra)
                        )
                    )
            )
        }
        Text(
            text = "%.1f%%".format(probabilidad * 100f),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = colorBarra
        )
    }
}

// ──────────────────────────────────────────────────────────────────
// Datos + funciones auxiliares — colores Cyber-Sentinel.
// ──────────────────────────────────────────────────────────────────

private data class ConjuntoColoresAlerta(
    val icono: Color,
    val fondo: Color
)

private fun coloresAlertaCyber(nivel: ControladorAlerta.NivelAlerta): ConjuntoColoresAlerta = when (nivel) {
    ControladorAlerta.NivelAlerta.SEGURO -> ConjuntoColoresAlerta(
        icono = AlertaSeguro,
        fondo = AlertaSeguroFondo
    )
    ControladorAlerta.NivelAlerta.SOSPECHOSO -> ConjuntoColoresAlerta(
        icono = AlertaSospechoso,
        fondo = AlertaSospechosoFondo
    )
    ControladorAlerta.NivelAlerta.MALICIOSO -> ConjuntoColoresAlerta(
        icono = AlertaMalicioso,
        fondo = AlertaMaliciosoFondo
    )
}

private fun tituloAlerta(nivel: ControladorAlerta.NivelAlerta): String = when (nivel) {
    ControladorAlerta.NivelAlerta.SEGURO -> "Enlace Seguro"
    ControladorAlerta.NivelAlerta.SOSPECHOSO -> "Enlace Sospechoso"
    ControladorAlerta.NivelAlerta.MALICIOSO -> "Enlace Malicioso"
}

private fun descripcionAlerta(nivel: ControladorAlerta.NivelAlerta): String = when (nivel) {
    ControladorAlerta.NivelAlerta.SEGURO ->
        "El analisis no detecto amenazas significativas."
    ControladorAlerta.NivelAlerta.SOSPECHOSO ->
        "El analisis detecto indicadores de riesgo. Proceda con precaucion."
    ControladorAlerta.NivelAlerta.MALICIOSO ->
        "El analisis detecto que este enlace es malicioso. No lo abra."
}

private fun iconoAlerta(nivel: ControladorAlerta.NivelAlerta) = when (nivel) {
    ControladorAlerta.NivelAlerta.SEGURO -> Icons.Filled.CheckCircle
    ControladorAlerta.NivelAlerta.SOSPECHOSO -> Icons.Filled.Warning
    ControladorAlerta.NivelAlerta.MALICIOSO -> Icons.Filled.Error
}
