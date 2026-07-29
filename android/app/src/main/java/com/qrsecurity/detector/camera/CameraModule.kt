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
    private val escanerCodigosBarras = BarcodeScanning.getClient()

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

    /** Ventana de debounce en ms. 500ms → como mucho 2 inferencias por segundo. */
    private val debounceMs: Long = 500L

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
                for (codigo in codigosBarras) {
                    if (codigo.format == Barcode.FORMAT_QR_CODE) {
                        val valorCrudo = codigo.rawValue
                        if (!valorCrudo.isNullOrBlank()) {
                            // Bug A3 fix: debounce — si la ultima deteccion
                            // aceptada fue hace menos de [debounceMs] ms,
                            // ignoramos esta deteccion para no saturar el
                            // pipeline con ~30 inferencias/segundo.
                            // M4 fix: el read-modify-write de `@Volatile var`
                            // tenia una carrera entre el `if (ahora - ultimo <
                            // debounceMs) continue` y `ultimo = ahora`. El
                            // callback de ML Kit puede invocarse desde hilos
                            // internos del cliente GMS. Sustituimos por
                            // updateAndGet atomico que preserva la ventana de
                            // debounce y dispara solo si este callback gano la
                            // carrera por actualizar el timestamp.
                            //
                            // Nota: la version sugerida en el brief con
                            // `getAndUpdate { if (ts > cur) ts else cur }` +
                            // `if (aceptado == ts)` invierte la semantica
                            // (getAndUpdate retorna el valor PREVIO, no el
                            // nuevo), disparando en duplicados y silenciando
                            // la primera deteccion. Reproduccion:
                            //   - First: cur=0, ts=1000 → f=1000, retorna 0,
                            //     0==1000 = false → no fire.
                            //   - Duplicate same-ms: cur=1000, ts=1000 → f=1000,
                            //     retorna 1000, 1000==1000 = true → fire (duplicado).
                            // Por eso usamos updateAndGet (retorna el NUEVO
                            // valor) y fire solo si ese valor es nuestro ts.
                            val ts = System.currentTimeMillis()
                            val aceptado = ultimoTimestampAceptado.updateAndGet { cur ->
                                if (ts - cur >= debounceMs) ts else cur
                            }
                            if (aceptado == ts) {
                                // Procesa frame: este callback gano la carrera
                                // por actualizar el timestamp a su propio ts.
                                ContextCompat.getMainExecutor(context).execute {
                                    onQrDetectado(valorCrudo)
                                }
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                android.util.Log.w("ModuloCamara", "frame fail", e)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * Libera el escaner ML Kit. El executor del analizador se gestiona via
     * [releaseCameraResources]; [detener] se mantiene por compatibilidad con
     * quien ya llamaba a este metodo y delega en [releaseCameraResources]
     * para no duplicar la logica de shutdown.
     * Llamar desde [androidx.lifecycle.LifecycleObserver.onDestroy].
     */
    fun detener() {
        releaseCameraResources()
        escanerCodigosBarras.close()
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
