package com.qrsecurity.detector.ui.escaner

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.qrsecurity.detector.camera.DeteccionQr
import com.qrsecurity.detector.ui.theme.Alphas
import com.qrsecurity.detector.ui.theme.CyberCyan
import com.qrsecurity.detector.ui.theme.PencilOverlay

/**
 * Componentes visuales y geometricos del viewfinder de escaneo.
 *
 * Extraidos de HomeScreen (628 LOC) — la geometria FILL_CENTER estaba
 * duplicada entre el overlay y la validacion del reticulo; ahora vive en
 * [mapeoFillCenter] como unico punto de verdad (testeable sin Compose).
 */

/** Lado del reticulo como fraccion del lado menor del viewfinder. */
internal const val FACTOR_RETICULO = 0.6f

/**
 * Rectangulo del reticulo de escaneo centrado — unico punto de verdad
 * (Audit M4). Compartido por el dibujo de [ScanReticle] y la validacion
 * [qrDentroDeReticulo], que antes duplicaban la formula del reticulo.
 */
internal data class RectanguloReticulo(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Calcula el rectangulo del reticulo centrado para unas dimensiones de
 * viewfinder dadas: lado = `minOf(ancho, alto) * FACTOR_RETICULO`, centrado
 * en el viewfinder. Puro (solo Floats), testeable sin Compose.
 */
internal fun rectanguloReticulo(ancho: Float, alto: Float): RectanguloReticulo {
    val reticleSize = minOf(ancho, alto) * FACTOR_RETICULO
    val left = (ancho - reticleSize) / 2f
    val top = (alto - reticleSize) / 2f
    return RectanguloReticulo(left, top, left + reticleSize, top + reticleSize)
}

/**
 * Mapeo FILL_CENTER imagen → pantalla, igual que el scale type del
 * [androidx.camera.view.PreviewView]: `scale = max(viewW/imgW, viewH/imgH)`,
 * centrado. Unico punto de verdad compartido por el overlay y la
 * validacion del reticulo.
 */
internal data class MapeoFillCenter(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    fun x(imgX: Float): Float = offsetX + imgX * scale
    fun y(imgY: Float): Float = offsetY + imgY * scale
}

/**
 * Calcula el [MapeoFillCenter]; `null` si alguna dimension es invalida
 * (0 o negativa) — los callers degradan elegantemente en ese caso.
 */
internal fun mapeoFillCenter(
    vistaW: Float,
    vistaH: Float,
    imgW: Float,
    imgH: Float
): MapeoFillCenter? {
    if (vistaW <= 0f || vistaH <= 0f || imgW <= 0f || imgH <= 0f) return null
    val scale = maxOf(vistaW / imgW, vistaH / imgH)
    return MapeoFillCenter(
        scale = scale,
        offsetX = (vistaW - imgW * scale) / 2f,
        offsetY = (vistaH - imgH * scale) / 2f
    )
}

/**
 * Reticulo de escaneo centrado — marco cuadrado con esquinas cyan y linea
 * de escaneo animada (top→bottom, loop 2.5s). Solo visible en idle.
 * Sin dependencias de estado externo — puro Canvas drawing.
 */
@Composable
fun ScanReticle() {
    val scanLineTransition = rememberInfiniteTransition(label = "scanLine")
    val scanLineOffset by scanLineTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLineY"
    )

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        // Reticulo centrado — unico punto de verdad (Audit M4).
        val r = rectanguloReticulo(size.width, size.height)
        val left = r.left
        val top = r.top
        val right = r.right
        val bottom = r.bottom
        val cornerLen = (right - left) * 0.08f
        val strokeW = 3.dp.toPx()
        val color = CyberCyan

        // Corner brackets
        drawLine(color, Offset(left, top), Offset(left + cornerLen, top), strokeW)
        drawLine(color, Offset(left, top), Offset(left, top + cornerLen), strokeW)
        drawLine(color, Offset(right, top), Offset(right - cornerLen, top), strokeW)
        drawLine(color, Offset(right, top), Offset(right, top + cornerLen), strokeW)
        drawLine(color, Offset(left, bottom), Offset(left + cornerLen, bottom), strokeW)
        drawLine(color, Offset(left, bottom), Offset(left, bottom - cornerLen), strokeW)
        drawLine(color, Offset(right, bottom), Offset(right - cornerLen, bottom), strokeW)
        drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLen), strokeW)

        // Animated scan line (horizontal line moving top->bottom inside reticle)
        val scanY = top + (bottom - top) * scanLineOffset
        val scanAlpha = when {
            scanLineOffset < 0.05f || scanLineOffset > 0.95f -> Alphas.notorio
            else -> Alphas.alto
        }
        drawLine(
            color = color.copy(alpha = scanAlpha),
            start = Offset(left + cornerLen, scanY),
            end = Offset(right - cornerLen, scanY),
            strokeWidth = 2.dp.toPx()
        )
    }
}

/**
 * Overlay estilo Google Lens — oscurece toda la pantalla excepto el area
 * del bounding box del QR, con un borde cyan. El boundingBox de ML Kit
 * esta en coordenadas de imagen post-rotacion; se mapea a coordenadas de
 * pantalla con [mapeoFillCenter] (FILL_CENTER del PreviewView).
 *
 * @param deteccion la deteccion QR con boundingBox + dimensiones de imagen.
 */
@Composable
fun OverlayResaltadoQr(deteccion: DeteccionQr) {
    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        val mapeo = mapeoFillCenter(size.width, size.height, deteccion.anchoImagen.toFloat(), deteccion.altoImagen.toFloat())
            ?: return@Canvas

        val bbox = deteccion.boundingBox
        val rectLeft = mapeo.x(bbox.left.toFloat())
        val rectTop = mapeo.y(bbox.top.toFloat())
        val rectRight = mapeo.x(bbox.right.toFloat())
        val rectBottom = mapeo.y(bbox.bottom.toFloat())

        val dimColor = PencilOverlay

        // Dim four strips around the QR area (leaves QR visible)
        // Top strip
        if (rectTop > 0f) {
            drawRect(
                color = dimColor,
                topLeft = Offset(0f, 0f),
                size = Size(size.width, rectTop)
            )
        }
        // Bottom strip
        if (rectBottom < size.height) {
            drawRect(
                color = dimColor,
                topLeft = Offset(0f, rectBottom),
                size = Size(size.width, size.height - rectBottom)
            )
        }
        // Left strip
        if (rectLeft > 0f) {
            drawRect(
                color = dimColor,
                topLeft = Offset(0f, rectTop),
                size = Size(rectLeft, rectBottom - rectTop)
            )
        }
        // Right strip
        if (rectRight < size.width) {
            drawRect(
                color = dimColor,
                topLeft = Offset(rectRight, rectTop),
                size = Size(size.width - rectRight, rectBottom - rectTop)
            )
        }

        // Cyan border around the QR highlight area
        drawRect(
            color = CyberCyan.copy(Alphas.alto),
            topLeft = Offset(rectLeft, rectTop),
            size = Size(rectRight - rectLeft, rectBottom - rectTop),
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

/**
 * Valida que el QR detectado cae COMPLETAMENTE dentro del reticulo de
 * escaneo centrado (el mismo que dibuja [ScanReticle]).
 *
 * Exige que las 4 esquinas del boundingBox del QR caigan dentro del
 * reticulo — el QR debe estar COMPLETAMENTE encuadrado para que el escaneo
 * se acepte. Usa el mismo [mapeoFillCenter] del overlay.
 *
 * @return true si el QR completo cae dentro del reticulo, false si asoma
 *     fuera. Retorna true (acepta) si las dimensiones del Box o de la
 *     imagen son invalidas (0 o negativas) — degradacion elegante: si no
 *     podemos medir, no bloqueamos el escaneo.
 */
internal fun qrDentroDeReticulo(
    deteccion: DeteccionQr,
    boxW: Int,
    boxH: Int
): Boolean {
    // Degradacion elegante: si el Box todavia no se measured (size Zero),
    // aceptar la deteccion para no bloquear el escaneo antes del primer layout.
    if (boxW <= 0 || boxH <= 0) return true

    val mapeo = mapeoFillCenter(
        boxW.toFloat(), boxH.toFloat(),
        deteccion.anchoImagen.toFloat(), deteccion.altoImagen.toFloat()
    ) ?: return true

    // Mapear las 4 esquinas del bbox QR → coords de pantalla
    val screenLeft = mapeo.x(deteccion.boundingBox.left.toFloat())
    val screenRight = mapeo.x(deteccion.boundingBox.right.toFloat())
    val screenTop = mapeo.y(deteccion.boundingBox.top.toFloat())
    val screenBottom = mapeo.y(deteccion.boundingBox.bottom.toFloat())

    // Limites del reticulo — unico punto de verdad compartido con ScanReticle
    // (Audit M4: antes ambas duplicaban la formula del reticulo centrado).
    val r = rectanguloReticulo(boxW.toFloat(), boxH.toFloat())
    val reticleLeft = r.left
    val reticleTop = r.top
    val reticleRight = r.right
    val reticleBottom = r.bottom

    // Las 4 esquinas del QR deben caer dentro del reticulo
    return screenLeft >= reticleLeft && screenRight <= reticleRight &&
           screenTop >= reticleTop && screenBottom <= reticleBottom
}
