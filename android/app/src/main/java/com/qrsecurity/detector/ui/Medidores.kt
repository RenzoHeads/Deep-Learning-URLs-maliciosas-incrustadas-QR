package com.qrsecurity.detector.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight

/**
 * Gauge circular unificado — reemplaza [DetalleUrlScreen]`MedidorAmenaza` y
 * [UrlSeguraScreen]`MedidorSeguro` (duplicados casi idénticos de ~45 líneas).
 *
 * Dibuja un track completo de fondo y un arco de progreso desde las 12 en
 * sentido horario, con el valor numérico centrado.
 *
 * @param progreso  Fracción del arco (0f..1f); se coacciona al rango por
 *                  seguridad. Para amenazas usar `probabilidad`; para URLs
 *                  seguras usar `puntuacionSeguridad / 100f`.
 * @param colorArco Color del arco de progreso (ej. veredicto de amenaza o
 *                  verde seguro).
 * @param colorTrack Color del anillo de fondo.
 * @param valorTexto Texto centrado (ej. porcentaje de amenaza o puntuación).
 * @param colorTexto Color del texto centrado.
 * @param modifier   Modifier externo.
 */
@Composable
fun MedidorGauge(
    progreso: Float,
    colorArco: Color,
    colorTrack: Color,
    valorTexto: String,
    colorTexto: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val progresoSeguro = progreso.coerceIn(0f, 1f)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val diametro = size.minDimension
            val grosor = diametro * 0.08f
            val ladoArco = diametro - grosor
            val offsetArco = Offset(grosor / 2f, grosor / 2f)
            val tamanoArco = Size(ladoArco, ladoArco)
            // Track completo
            drawArc(
                color = colorTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = grosor),
                size = tamanoArco,
                topLeft = offsetArco
            )
            // Arco de progreso (desde las 12, en sentido horario)
            if (progresoSeguro > 0f) {
                drawArc(
                    color = colorArco,
                    startAngle = -90f,
                    sweepAngle = 360f * progresoSeguro,
                    useCenter = false,
                    style = Stroke(width = grosor, cap = StrokeCap.Round),
                    size = tamanoArco,
                    topLeft = offsetArco
                )
            }
        }
        Text(
            text = valorTexto,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = colorTexto
        )
    }
}