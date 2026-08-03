package com.qrsecurity.detector.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Encapsula toda la configuracion CameraX y el analisis de frames ML Kit.
 *
 * Responsabilidades:
 *  1. Vincular un caso de uso [Preview] al [PreviewView] provisto para que el usuario vea la camara en vivo.
 *  2. Vincular un caso de uso [ImageAnalysis] que alimente cada frame al
 *     [BarcodeScanner] de ML Kit.
 *  3. Invocar [onQrDetectado] con la cadena de payload QR cruda cuando se encuentra un codigo QR.
 *
 * La clase es consciente del ciclo de vida: obtiene un [ProcessCameraProvider] y vincula los casos
 * de uso al ciclo de vida del [LifecycleOwner] para que la camara se detenga automaticamente cuando
 * la actividad pase a segundo plano.
 */
class ModuloCamara(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    onQrDetectado: (String) -> Unit
) {

    /**
     * H1 fix: callback muturable. Antes era un `val` constructor param, lo
     * que significaba que el callback capturaba la lamba del primer mount.
     * Cuando la pantalla se recomponia y la lambda `onQrDetectado` nueva
     * referenciaba un estado actualizado (p.ej. un NavBackStackEntry
     * distinto), el modulo seguia llamando a la antigua — generando
     * navegacion/comportamiento stale. Ahora exponemos un setter
     * (`setOnQrDetectado`) que la pantalla re-aplica via `LaunchedEffect`
     * en cada recomposition, de modo que el frame entrante siempre
     * dispara el callback fresco.
     */
    @Volatile
    private var onQrDetectado: (String) -> Unit = onQrDetectado

    /**
     * Permite a la pantalla host (ver VistaPreviaCamaraCyberSentinel) refrescar
     * el callback de QR tras una recomposition, evitando el bug stale-callback.
     */
    fun setOnQrDetectado(callback: (String) -> Unit) {
        onQrDetectado = callback
    }

    /**
     * Bug A14 fix: ciclo de vida explicito del executor del analizador.
     * Antes el campo era un ``val`` inicializado con ``Executors.newSingleThreadExecutor()``
     * y nunca se ShutDowneaba entre re-vinculos. Ahora se trackea en un ``var`` y se
     * shutDownea el anterior antes de asignar uno nuevo, evitando fugas de hilos
     * JVM y callbacks escribiendo a un analizador muerto en rebindings repetidos
     * (rotacion de pantalla, navegacion in/out, etc.).
     *
     * Nulable para distinguir el estado "sin executor vivo" del estado "con executor".
     */
    private var executorAnalizador: ExecutorService? = null
    // Bug fix: restringir el scanner a FORMAT_QR_CODE unicamente. Antes
    // BarcodeScanning.getClient() sin opciones escaneaba TODOS los formatos
    // de barcode (UPC-A, UPC-E, EAN-8, EAN-13, Code 39, Code 93, Code 128,
    // Codabar, ITF, Data Matrix, PDF-417, Aztec). La CPU procesa cada frame
    // buscando todos esos formatos innecesariamente — la app solo acepta QR.
    private val escanerCodigosBarras = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    /**
     * Bug A3 fix: debouncing de detecciones QR. Antes cada frame con un QR
     * visible disparaba ``onQrDetectado`` — a 30fps eso generaba ~30 llamadas
     * por segundo al pipeline (cada una re-ejecutando tokenizacion + inferencia
     * TFLite). Ahora exigimos un delay de [DEBOUNCE_MS] ms desde la ultima
     * deteccion aceptada antes de disparar otra, lo que reduce a ~1-2
     * inferencias por segundo mientras el usuario mantiene el QR enfocado.
     *
     * Volatil en un solo field — el analizador de frame corre en
     * [executorAnalizador] (single-thread executor), asi que no hay data race
     * entre frames concurrentes.
     *
     * M4 fix: AtomicLong con getAndUpdate sustituye al @Volatile con
     * read-modify-write. Aunque el executor es single-thread, el callback de
     * ML Kit puede invocarse desde distintos hilos internos del cliente GMS;
     * la atomicidad de getAndUpdate garantiza que dos callbacks concurrentes
     * nunca acepten el mismo timestamp ni sobreescriban un valor recien
     * actualizado con uno viejo.
     */
    private val ultimoTimestampAceptado: AtomicLong = AtomicLong(0L)

    /** Ventana de debounce en ms. 1200ms → el usuario ve el QR encuadrado
     *  antes de que dispare el analisis (UX: estabilizacion visual). Antes
     *  era 500ms — demasiado rapido, el usuario no alcanzaba a ver que el
     *  QR estaba bien cuadrado y el modal aparecia de golpe. 1200ms da
     *  tiempo suficiente para que el usuario confirme visualmente el
     *  encuadre antes de que comience el analisis. */
    private val debounceMs: Long = 1200L

    /** Selector de camara trasera. */
    private val selectorCamara: CameraSelector =
        CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

    /**
     * Inicia el flujo de camara: vista previa + analisis.
     * Seguro de llamar desde el hilo principal.
     */
    fun iniciar() {
        val futuroProveedorCamara = ProcessCameraProvider.getInstance(context)

        futuroProveedorCamara.addListener({
            val proveedorCamara = futuroProveedorCamara.get()

            // ── Caso de uso de vista previa ──
            val vistaPrevia = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Bug A14 fix: antes de re-vincular el analizador, shutDownear el
            // executor previo si quedaba vivo (rotacion, re-entrada, navegacion).
            // runCatching: nunca propagar excepciones de shutdown; un hilo
            // interruptado no debe romper el rebinding.
            runCatching {
                executorAnalizador?.let { previo ->
                    previo.shutdown()
                    previo.awaitTermination(2, TimeUnit.SECONDS)
                }
            }

            // ── Nuevo executor para este ciclo de analisis ──
            val executorNuevo = Executors.newSingleThreadExecutor()
            executorAnalizador = executorNuevo

            // ── Caso de uso de analisis de imagen ──
            val analisisImagen = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executorNuevo, ::analizarFrame)
                }

            try {
                // Desvincular casos de uso previos antes de re-vincular.
                proveedorCamara.unbindAll()
                proveedorCamara.bindToLifecycle(
                    lifecycleOwner,
                    selectorCamara,
                    vistaPrevia,
                    analisisImagen
                )
            } catch (exc: Exception) {
                android.util.Log.e("ModuloCamara", "bindToLifecycle fallo", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Analizador de frame: convierte el [ImageProxy] a un [InputImage] de ML Kit y
     * escanea buscando codigos de barras QR. Llama a [onQrDetectado] en caso de exito
     * e inmediatamente cierra la imagen para liberar el buffer.
     */
    @OptIn(ExperimentalGetImage::class)
    private fun analizarFrame(imageProxy: ImageProxy) {
        @Suppress("DEPRECATION")
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val imagenEntrada = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        escanerCodigosBarras.process(imagenEntrada)
            .addOnSuccessListener { codigosBarras ->
                procesarCodigosDetectados(
                    codigosBarras,
                    context,
                    onQrDetectado,
                    ultimoTimestampAceptado,
                    debounceMs
                )
            }
            .addOnFailureListener { e ->
                android.util.Log.w("ModuloCamara", "frame fail", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Procesa la lista de codigos de barras detectados por ML Kit.
     * Filtra solo QR, aplica debounce y dispara el callback en hilo principal.
     */
    private fun procesarCodigosDetectados(
        codigosBarras: List<Barcode>,
        context: Context,
        onQrDetectado: (String) -> Unit,
        ultimoTimestamp: AtomicLong,
        debounceMs: Long
    ) {
        for (codigo in codigosBarras) {
            if (codigo.format == Barcode.FORMAT_QR_CODE) {
                val valorCrudo = codigo.rawValue
                if (!valorCrudo.isNullOrBlank()) {
                    val ts = System.currentTimeMillis()
                    val aceptado = ultimoTimestamp.updateAndGet { cur ->
                        if (ts - cur >= debounceMs) ts else cur
                    }
                    if (aceptado == ts) {
                        ContextCompat.getMainExecutor(context).execute {
                            onQrDetectado(valorCrudo)
                        }
                    }
                }
            }
        }
    }

    /**
     * Pausa la camara: detiene el executor del analizador y desvincula los
     * casos de uso de CameraX, pero NO cierra el BarcodeScanner de ML Kit.
     *
     * Bug C1 fix: antes `detener()` llamaba `escanerCodigosBarras.close()`,
     * destruyendo permanentemente el scanner ML Kit. Como el scanner es un
     * campo creado una sola vez (linea 74) y nunca se recrea, al volver de
     * ON_PAUSE → ON_RESUME la llamada `iniciar()` → `analizarFrame` →
     * `escanerCodigosBarras.process()` lanzaba
     * `IllegalStateException: Attempting to use a closed client.` en cada
     * background→foreground.
     *
     * Ahora `detener()` solo pausa (shutdown del executor + unbind de
     * CameraX). El scanner se cierra una sola vez en [liberarEscaner],
     * invocado desde `onDispose` cuando ScanScreen sale de composicion.
     *
     * Llamar desde `Lifecycle.Event.ON_PAUSE`.
     */
    fun detener() {
        releaseCameraResources()
        // Desvincular casos de uso de CameraX para que la camara se detenga,
        // pero sin cerrar el scanner ML Kit.
        runCatching {
            val futuroProveedor = ProcessCameraProvider.getInstance(context)
            futuroProveedor.addListener({
                runCatching { futuroProveedor.get().unbindAll() }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    /**
     * Cierra permanentemente el BarcodeScanner de ML Kit.
     *
     * Bug C1 fix: separar la pausa (`detener`) del cierre del scanner.
     * Este metodo debe llamarse una sola vez, cuando la pantalla sale de
     * composicion permanentemente (onDispose del DisposableEffect en
     * ScanScreen), NO en cada ON_PAUSE.
     *
     * Idempotente: seguro llamarlo multiples veces (close en un scanner ya
     * cerrado es no-op en ML Kit).
     */
    fun liberarEscaner() {
        runCatching { escanerCodigosBarras.close() }
    }

    /**
     * Bug A14 fix: liberacion explicita del executor del analizador.
     * Llamar desde el [androidx.lifecycle.LifecycleObserver.onDestroy] del
     * dueno del ciclo de vida, o desde un ``DisposableEffect`` / cleanup de
     * la pantalla al desmontar la camara. Es seguro llamarlo multiples veces:
     * si el executor ya era ``null`` o ya estaba shutDowneado, no hace nada.
     *
     * El shutdown + awaitTermination se envuelve en ``runCatching``: nunca
     * propagamos excepciones de shutdown (un hilo interruptado o forzado a
     * cerrarse no debe romper el teardown).
     */
    fun releaseCameraResources() {
        val executor = executorAnalizador ?: return
        runCatching {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
        executorAnalizador = null
    }
}
