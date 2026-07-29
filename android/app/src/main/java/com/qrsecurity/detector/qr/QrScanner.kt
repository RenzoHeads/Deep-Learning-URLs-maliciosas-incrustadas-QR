package com.qrsecurity.detector.qr

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Interfaz abstracta de escaner que define un unico contrato [escanear].
 *
 * Se proveen dos implementaciones:
 *  - [EscannerMlKit] (principal): usa escaneo de codigos de barras ML Kit de Google via GMS.
 *  - [EscannerZxing] (respuesto): usa la libreria ZXing para dispositivos sin GMS.
 */
interface EscanerQr {

    /**
     * Escanea asincronicamente un [ImageProxy] de CameraX buscando codigos QR.
     * @return la cadena de payload cruda o `null` si no se encontro un codigo QR en el frame.
     */
    suspend fun escanear(proxy: ImageProxy): String?

    /**
     * Escanea asincronicamente un [Bitmap] buscando codigos QR.
     * Usado principalmente por la ruta de respuesto ZXing.
     * @return la cadena de payload cruda o `null`.
     */
    suspend fun escanearBitmap(bitmap: Bitmap): String?

    companion object {
        /**
         * Fabrica: devuelve [EscannerMlKit] si GMS esta disponible, si no [EscannerZxing].
         * La verificacion se posterga a la primera invocacion para que el llamador siempre
         * pueda llamarla sin preocuparse por la disponibilidad del dispositivo.
         */
        fun crear(context: Context): EscanerQr {
            return if (gmsDisponible(context)) {
                EscannerMlKit()
            } else {
                EscannerZxing()
            }
        }

        private fun gmsDisponible(context: Context): Boolean {
            return try {
                context.packageManager.getPackageInfo("com.google.android.gms", 0) != null
            } catch (e: Exception) {
                false
            }
        }
    }
}

/**
 * Escaner principal respaldado por ML Kit Barcode Scanning.
 * Requiere Google Play Services.
 */
class EscannerMlKit : EscanerQr {

    private val escaner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override suspend fun escanear(proxy: ImageProxy): String? {
        @Suppress("DEPRECATION")
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return null
        }

        val imagenEntrada = InputImage.fromMediaImage(
            mediaImage,
            proxy.imageInfo.rotationDegrees
        )

        return try {
            val codigosBarras = esperarTareaONulo(escaner.process(imagenEntrada))
            codigosBarras?.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
        } catch (e: Exception) {
            null
        } finally {
            proxy.close()
        }
    }

    override suspend fun escanearBitmap(bitmap: Bitmap): String? {
        val imagenEntrada = InputImage.fromBitmap(bitmap, 0)
        return try {
            val codigosBarras = esperarTareaONulo(escaner.process(imagenEntrada))
            codigosBarras?.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Escaner de respuesto que usa [MultiFormatReader] de ZXing.
 *
 * Se usa en dispositivos que no tienen Google Play Services (ej. dispositivos Huawei
 * o ROMs sin Google). Trabaja con [Bitmap]s, por lo que el llamador debe
 * convertir el [ImageProxy] a un Bitmap antes de escanear.
 */
class EscannerZxing : EscanerQr {

    private val lector = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to
                    listOf(com.google.zxing.BarcodeFormat.QR_CODE)
            )
        )
    }

    override suspend fun escanear(proxy: ImageProxy): String? {
        // Bug 7 fix: antes `convertir(proxy)` podia cerrar el proxy internamente
        // y despues se llamaba `proxy.close()` de nuevo. Ahora el cierre vive en
        // un unico `finally`, consistente con EscannerMlKit (lineas 95-97).
        return try {
            @Suppress("DEPRECATION")
            val bitmap = ConvertidorImageProxyABitmap.convertir(proxy)
            bitmap?.let { decodificarBitmap(it) }
        } finally {
            proxy.close()
        }
    }

    override suspend fun escanearBitmap(bitmap: Bitmap): String? = decodificarBitmap(bitmap)

    private fun decodificarBitmap(bitmap: Bitmap): String? {
        val ancho = bitmap.width
        val alto = bitmap.height
        val pixeles = IntArray(ancho * alto)
        bitmap.getPixels(pixeles, 0, ancho, 0, 0, ancho, alto)

        val fuente = RGBLuminanceSource(ancho, alto, pixeles)
        val bitmapBinario = BinaryBitmap(HybridBinarizer(fuente))

        return try {
            lector.decodeWithState(bitmapBinario).text
        } catch (e: NotFoundException) {
            null
        } catch (e: Exception) {
            null
        } finally {
            lector.reset()
        }
    }
}

// ──────────────────────────────────────────────────────────────────
// Interop de corutinas para la API Task de ML Kit.
// ──────────────────────────────────────────────────────────────────

/**
 * Suspende hasta que el [com.google.android.gms.tasks.Task] de ML Kit dado se complete.
 * Devuelve el resultado en caso de exito, o `null` en caso de fallo (NO propaga la
 * excepcion). Usar este envoltorio para escaneo resiliente que debe pasar al
 * respuesto ZXing en lugar de crashear.
 */
private suspend fun <T> esperarTareaONulo(
    tarea: com.google.android.gms.tasks.Task<T>
): T? = suspendCancellableCoroutine { cont ->
    tarea.addOnSuccessListener { resultado ->
        if (cont.isActive) cont.resume(resultado)
    }.addOnFailureListener { _ ->
        if (cont.isActive) cont.resume(null)
    }
}

/**
 * Utilidad para convertir un [ImageProxy] de CameraX (YUV_420_888) a un [Bitmap] ARGB.
 * Usada solo por la ruta de respuesto ZXing.
 *
 * Bug 8 fix: la version anterior asumia strides fijas (`offsetUv = ancho*alto`,
 * `tamanoUv = ancho*alto/4`) y hardcoded `planoU.buffer.get(...)` ignorando
 * `planoU.pixelStride` y `planoU.rowStride`. En dispositivos con NV21
 * semi-planar (Pixel, algunos MIUI) esto generaba bitmaps corruptos
 * (verde/violeta) y ZXing NotFoundException. Ahora se respeta:
 *   - Y plane: rowStride puede ser mayor que ancho (padding).
 *   - U/V planes: pixelStride puede ser 1 (planar) o 2 (semi-planar NV21),
 *     rowStride puede tener padding. Copiamos fila por fila.
 */
private object ConvertidorImageProxyABitmap {

    @OptIn(ExperimentalGetImage::class)
    fun convertir(proxy: ImageProxy): Bitmap? {
        @Suppress("DEPRECATION")
        val mediaImage = proxy.image ?: return null
        val ancho = mediaImage.width
        val alto = mediaImage.height

        val planoY = mediaImage.planes[0]
        val planoU = mediaImage.planes[1]
        val planoV = mediaImage.planes[2]

        val bufferYuv = ByteArray(ancho * alto * 3 / 2)

        // ── Copia Y plane respetando rowStride (padding) ──
        val yRowStride = planoY.rowStride
        if (yRowStride == ancho) {
            // Sin padding: copia directa.
            planoY.buffer.get(bufferYuv, 0, ancho * alto)
        } else {
            // Con padding: copiar fila por fila.
            var posDestino = 0
            var posOrigen = 0
            for (fila in 0 until alto) {
                planoY.buffer.position(posOrigen)
                planoY.buffer.get(bufferYuv, posDestino, ancho)
                posDestino += ancho
                posOrigen += yRowStride
            }
            planoY.buffer.position(0)
        }

        // ── Copia U/V planes respetando pixelStride y rowStride ──
        // Construimos NV21: V primero, luego U (intercalado semi-planar) o
        // planar segun pixelStride. Comúnmente pixelStride=2 en semi-planar.
        val offsetV = ancho * alto
        val anchoUv = ancho / 2
        val altoUv = alto / 2
        val uRowStride = planoU.rowStride
        val uPixelStride = planoU.pixelStride
        val vRowStride = planoV.rowStride
        val vPixelStride = planoV.pixelStride

        if (uPixelStride == 2 && vPixelStride == 2 &&
            uRowStride == vRowStride && uRowStride == ancho
        ) {
            // Caso semi-planar NV21 comun: U y V intercalados. Copiamos primero
            // el buffer V (NV21 ordena V antes que U).
            val bufferUv = ByteBuffer.allocateDirect(ancho * alto / 2)
            planoV.buffer.position(0)
            bufferUv.put(planoV.buffer)
            planoU.buffer.position(0)
            bufferUv.put(planoU.buffer)
            bufferUv.position(0)
            bufferUv.get(bufferYuv, offsetV, ancho * alto / 2)
        } else {
            // Caso planar (pixelStride=1) o strides no-estandar: copiar pixel por pixel.
            val tamanoUv = anchoUv * altoUv
            val bufferU = ByteArray(tamanoUv)
            val bufferV = ByteArray(tamanoUv)
            planoU.buffer.position(0)
            if (uRowStride == anchoUv && uPixelStride == 1) {
                planoU.buffer.get(bufferU, 0, tamanoUv)
            } else {
                var posDestino = 0
                var posOrigen = 0
                for (fila in 0 until altoUv) {
                    planoU.buffer.position(posOrigen)
                    for (col in 0 until anchoUv) {
                        bufferU[posDestino++] = planoU.buffer.get()
                        planoU.buffer.position(planoU.buffer.position() + uPixelStride - 1)
                    }
                    posOrigen += uRowStride
                }
            }
            planoV.buffer.position(0)
            if (vRowStride == anchoUv && vPixelStride == 1) {
                planoV.buffer.get(bufferV, 0, tamanoUv)
            } else {
                var posDestino = 0
                var posOrigen = 0
                for (fila in 0 until altoUv) {
                    planoV.buffer.position(posOrigen)
                    for (col in 0 until anchoUv) {
                        bufferV[posDestino++] = planoV.buffer.get()
                        planoV.buffer.position(planoV.buffer.position() + vPixelStride - 1)
                    }
                    posOrigen += vRowStride
                }
            }
            // NV21: V primero, luego U (planar).
            System.arraycopy(bufferV, 0, bufferYuv, offsetV, tamanoUv)
            System.arraycopy(bufferU, 0, bufferYuv, offsetV + tamanoUv, tamanoUv)
        }

        val imagenYuv = android.graphics.YuvImage(
            bufferYuv,
            android.graphics.ImageFormat.NV21,
            ancho, alto, null
        )
        val salida = java.io.ByteArrayOutputStream()
        imagenYuv.compressToJpeg(
            android.graphics.Rect(0, 0, ancho, alto),
            100,
            salida
        )
        val bytesJpeg = salida.toByteArray()
        val bitmapCompleto = android.graphics.BitmapFactory.decodeByteArray(
            bytesJpeg, 0, bytesJpeg.size
        )

        val rotacion = proxy.imageInfo.rotationDegrees
        val matriz = android.graphics.Matrix().apply { postRotate(rotacion.toFloat()) }

        return android.graphics.Bitmap.createBitmap(
            bitmapCompleto, 0, 0,
            bitmapCompleto.width, bitmapCompleto.height,
            matriz, true
        )
    }
}
