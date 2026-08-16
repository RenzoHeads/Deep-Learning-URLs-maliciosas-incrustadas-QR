package com.qrsecurity.detector.camera

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Resultado de una deteccion QR exitosa.
 *
 * Contiene el [payload] (URL cruda del QR) y el [boundingBox] del codigo QR
 * detectado por ML Kit, expresado en coordenadas de la imagen POST-ROTACION
 * (es decir, la imagen tal como ML Kit la ve tras aplicar `rotationDegrees`).
 *
 * Tambien incluye [anchoImagen] y [altoImagen] — las dimensiones de la imagen
 * post-rotacion. Estos valores permiten a la UI mapear el bounding box de
 * espacio de imagen a coordenadas de pantalla, para dibujar un overlay estilo
 * Google Lens que resalta el QR detectado.
 *
 * [instantanea] es el Bitmap del frame EXACTO en el que ML Kit detecto el QR,
 * ya rotado a [anchoImagen] x [altoImagen]. La UI lo renderiza sobre el preview
 * en vivo (ContentScale.Crop ≈ FILL_CENTER) cuando se dispara una deteccion,
 * congelando el viewfinder: el overlay de bounding box se queda perfectamente
 * alineado con el QR que el usuario vio, sin importar cuanto se mueva el
 * telefono despues. Es nulo si la captura del frame fallo (degradacion
 * elegante — en ese caso se mantiene el comportamiento previo: overlay sobre el
 * preview en vivo, potencialmente desalineado si el usuario mueve el telefono).
 *
 * @param payload URL cruda del codigo QR.
 * @param boundingBox Rectangulo del QR en coordenadas de imagen post-rotacion.
 * @param anchoImagen Ancho de la imagen post-rotacion (pixels).
 * @param altoImagen Alto de la imagen post-rotacion (pixels).
 * @param instantanea Bitmap del frame exacto de la deteccion (post-rotacion),
 *     o null si la captura fallo. La UI lo renderiza para congelar el viewfinder.
 */
data class DeteccionQr(
    val payload: String,
    val boundingBox: Rect,
    val anchoImagen: Int,
    val altoImagen: Int,
    val instantanea: Bitmap? = null
)
