package com.qrsecurity.detector.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Analizador de frames QR extraido de [ModuloCamara] — encapsula el estado que
 * antes se filtraba via los 8 parametros posicionales de `procesarCodigosDetectados`
 * (gate pausado/reanudado, debounce, callback mutable).
 *
 * Responsabilidades:
 *  1. Implementar [ImageAnalysis.Analyzer] para que CameraX le entregue cada
 *     [ImageProxy] via [analyze].
 *  2. Convertir el frame a [InputImage] de ML Kit y procesarlo con el
 *     [BarcodeScanner] inyectado (restringido a `FORMAT_QR_CODE` por [ModuloCamara]).
 *  3. Filtrar QR, aplicar gate + debounce via el [DebouncerDeteccion] interno,
 *     extraer el bitmap del frame exacto (para congelar el viewfinder) y
 *     disparar [onQrDetectado] en el hilo principal con un [DeteccionQr].
 *
 * Estado propio (lo que antes se mezclaba en [ModuloCamara]):
 *  - [debouncer]: gate + debounce atomicos (reemplaza `deteccionActiva`,
 *    `ultimoTimestampAceptado`, `debounceMs` y la logica `updateAndGet`
 *    inline). Ver [DebouncerDeteccion] para el historial de bugs que motivaron
 *    cada mecanismo.
 *  - [onQrDetectado]: callback mutable (H1 fix — evitar stale callback tras
 *    recomposition). La pantalla lo refresca via [setOnQrDetectado] en cada
 *    `LaunchedEffect` de modo que el frame entrante dispare el callback fresco.
 *
 * [escanerCodigosBarras] NO es propiedad del analizador — lo crea y cierra
 * [ModuloCamara] (separacion detener/liberar, bug C1). El analizador solo lo
 * usa; nunca debe cerrarlo.
 *
 * [reloj] se expone solo para test (no se usa en produccion — para un reloj
 * controlable en tests del propio analizador; los tests del debounce van
 * directamente contra [DebouncerDeteccion]). No tocar con fin de prod.
 *
 * @param context Contexto para obtener el main executor via
 *     [ContextCompat.getMainExecutor] (post del callback a hilo UI).
 * @param escanerCodigosBarras Scanner ML Kit restringido a QR, provisto por
 *     [ModuloCamara]. No se cierra desde aqui.
 * @param onQrDetectado Callback inicial (la pantalla lo refresca via
 *     [setOnQrDetectado] en cada recomposition — H1 fix).
 * @param debouncer Politica gate+debounce. Default [DebouncerDeteccion].
 *     Inyectar para test.
 * @param reloj Fuente de tiempo para el timestamp de cada deteccion. Default
 *     [System.currentTimeMillis]. Inyectar para test.
 */
internal class AnalizadorFramesQr(
    private val context: Context,
    private val escanerCodigosBarras: com.google.mlkit.vision.barcode.BarcodeScanner,
    onQrDetectado: (DeteccionQr) -> Unit,
    private val debouncer: DebouncerDeteccion = DebouncerDeteccion(),
    private val reloj: () -> Long = System::currentTimeMillis
) : ImageAnalysis.Analyzer {

    /**
     * H1 fix: callback muturable. Antes era un `val` constructor param en
     * [ModuloCamara], lo que significaba que el callback capturaba la lamba
     * del primer mount. Cuando la pantalla se recomponia y la lambda nueva
     * referenciaba un estado actualizado (p.ej. un NavBackStackEntry
     * distinto), el modulo seguia llamando a la antigua — generando
     * navegacion/comportamiento stale. Ahora exponemos un setter
     * ([setOnQrDetectado]) que la pantalla re-aplica via `LaunchedEffect`
     * en cada recomposition, de modo que el frame entrante siempre dispara
     * el callback fresco.
     */
    @Volatile
    private var onQrDetectado: (DeteccionQr) -> Unit = onQrDetectado

    /**
     * Permite a la pantalla host refrescar el callback de QR tras una
     * recomposition, evitando el bug stale-callback (H1 fix).
     */
    fun setOnQrDetectado(callback: (DeteccionQr) -> Unit) {
        onQrDetectado = callback
    }

    /** Pausa el analisis — los frames se descartan sin procesar ML Kit. */
    fun pausarDeteccion() = debouncer.pausar()

    /**
     * Reanuda el analisis. Delega a [DebouncerDeteccion.reanudar], que siembra
     * el timestamp actual (NO epoch) para que el QR que acaba de escanearse
     * no se re-detecte al instante (bug del dialogo "URL ya escaneada" que
     * reaparece de golpe).
     */
    fun reanudarDeteccion() = debouncer.reanudar()

    /**
     * Analizador de frame: convierte el [ImageProxy] a un [InputImage] de ML Kit y
     * escanea buscando codigos de barras QR. Llama a [onQrDetectado] con un
     * [DeteccionQr] (payload + boundingBox + dimensiones post-rotacion) en caso
     * de exito e inmediatamente cierra la imagen para liberar el buffer via el
     * completion listener.
     *
     * El gate (frame pausado) y el debounce viven en [debouncer]; ver su KDoc
     * para el historial completo de bugs (multi-deteccion en el mismo frame,
     * re-deteccion inmediata tras cancelar el dialogo, etc.).
     */
    @Suppress("DEPRECATION")
    override fun analyze(imageProxy: ImageProxy) {
        // Gate: si la deteccion esta pausada, descartar el frame sin tocar ML Kit.
        if (!debouncer.deteccionActiva) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotacion = imageProxy.imageInfo.rotationDegrees
        val imagenEntrada = InputImage.fromMediaImage(mediaImage, rotacion)

        // Dimensiones de la imagen POST-rotacion — el espacio en el que ML Kit
        // reporta barcode.boundingBox. Si rotacion es 90 o 270, las dimensiones
        // se intercambian respecto al buffer original.
        val anchoPostRot: Int
        val altoPostRot: Int
        if (rotacion == 90 || rotacion == 270) {
            anchoPostRot = mediaImage.height
            altoPostRot = mediaImage.width
        } else {
            anchoPostRot = mediaImage.width
            altoPostRot = mediaImage.height
        }

        escanerCodigosBarras.process(imagenEntrada)
            .addOnSuccessListener { codigosBarras ->
                procesarCodigosDetectados(
                    codigosBarras,
                    imageProxy,
                    rotacion,
                    anchoPostRot,
                    altoPostRot
                )
            }
            .addOnFailureListener { e ->
                android.util.Log.w("AnalizadorFramesQr", "frame fail", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Procesa la lista de codigos de barras detectados por ML Kit.
     * Filtra solo QR, aplica debounce via [debouncer] y dispara el callback
     * en hilo principal con un [DeteccionQr] que incluye el boundingBox, las
     * dimensiones de la imagen post-rotacion (necesarias para mapear el bbox
     * a coordenadas de pantalla en la UI) y el [DeteccionQr.instantanea] — el
     * Bitmap del frame exacto que ML Kit analizo para congelar el viewfinder.
     *
     * Refactor: antes recibia 8 parametros posicionales (codigosBarras,
     * context, imageProxy, rotacion, onQrDetectado, ultimoTimestamp,
     * debounceMs, anchoImagen, altoImagen) — sintoma de estado mal
     * encapsulado en [ModuloCamara]. Ahora ese estado vive aqui mismo
     * ([context], [onQrDetectado], [debouncer], [reloj]) y la firma se reduce
     * a los 5 datos propios del frame entrante.
     *
     * @param imageProxy el frame analizado (aun vivo — el close() corre en
     *     el listener de completion, DESPUES de este success listener). Se usa
     *     para extraer el bitmap del frame exacto, sincrono, antes de que
     *     el close libere el buffer.
     * @param rotacion grados de rotacion del sensor (rotationDegrees del
     *     imageProxy). Se usa para rotar el bitmap a la orientacion post-rotacion
     *     (coincide con anchoImagen/altoImagen ya validados).
     */
    private fun procesarCodigosDetectados(
        codigosBarras: List<Barcode>,
        imageProxy: ImageProxy,
        rotacion: Int,
        anchoImagen: Int,
        altoImagen: Int
    ) {
        for (codigo in codigosBarras) {
            if (codigo.format == Barcode.FORMAT_QR_CODE) {
                val valorCrudo = codigo.rawValue
                val bbox = codigo.boundingBox
                if (!valorCrudo.isNullOrBlank() && bbox != null) {
                    // Timestamp unico por frame (preserva el comportamiento
                    // original de `System.currentTimeMillis()` inline). El
                    // debouncer atomico garantiza que dos codigos en el mismo
                    // frame no disparen dos callbacks: el primero acepta, el
                    // segundo ve `ts - prev = 0 < debounceMs` y se rechaza.
                    if (debouncer.debeAceptar(reloj())) {
                        // Capturar el bitmap del frame EXACTO aqui, sincrono,
                        // antes de que el addOnCompleteListener dispare
                        // imageProxy.close() (que corre DESPUES de este
                        // success listener). Si lo postergamos al callback
                        // del hilo principal (ContextCompat main executor)
                        // ya seria tarde: el close() ya habria liberado el buffer.
                        val instantanea = extraerInstantanea(imageProxy, rotacion)
                        val deteccion = DeteccionQr(
                            payload = valorCrudo,
                            boundingBox = bbox,
                            anchoImagen = anchoImagen,
                            altoImagen = altoImagen,
                            instantanea = instantanea
                        )
                        ContextCompat.getMainExecutor(context).execute {
                            onQrDetectado(deteccion)
                        }
                    }
                }
            }
        }
    }

    /**
     * Extrae el Bitmap del frame exacto que ML Kit acaba de analizar, con la
     * rotacion del sensor aplicada para que sus dimensiones coincidan con
     * [DeteccionQr.anchoImagen] x [DeteccionQr.altoImagen] (post-rotacion).
     *
     * [ImageProxy.toBitmap] (camera-core 1.2.0+) retorna el bitmap SIN rotar
     * (orientacion cruda del sensor); por eso aplicamos [Matrix.postRotate] a
     * mano. El bitmap resultante matchea el espacio en el que ML Kit reporta
     * [Barcode.boundingBox], de modo que el overlay de la UI (computado con
     * scale = max(viewW/imgW, viewH/imgH) FILL_CENTER) se alinea 1:1 con el
     * bitmap renderizado via Compose's ContentScale.Crop.
     *
     * Debe llamarse DENTRO del success listener de [escanerCodigosBarras.process]
     * (antes del completion listener que dispara [imageProxy.close]) — de lo
     * contrario el buffer ya estaria liberado.
     *
     * @return el bitmap rotado, o null si la extraccion fallo (degradacion
     *     elegante — la UI sin bitmap cae al comportamiento previo: overlay
     *     sobre el preview en vivo).
     */
    private fun extraerInstantanea(imageProxy: ImageProxy, rotacion: Int): Bitmap? {
        return runCatching {
            val cruda = imageProxy.toBitmap()
            if (rotacion == 0) cruda
            else {
                val matrix = Matrix().apply { postRotate(rotacion.toFloat()) }
                Bitmap.createBitmap(cruda, 0, 0, cruda.width, cruda.height, matrix, true)
            }
        }.getOrNull()
    }
}
