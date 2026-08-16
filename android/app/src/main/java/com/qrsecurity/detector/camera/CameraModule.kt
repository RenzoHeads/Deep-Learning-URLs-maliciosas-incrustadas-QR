package com.qrsecurity.detector.camera

import android.content.Context
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Encapsula toda la configuracion CameraX y delega el analisis de frames QR
 * a [AnalizadorFramesQr].
 *
 * Responsabilidades:
 *  1. Construir (una sola vez) el [BarcodeScanner] de ML Kit restringido a
 *     `FORMAT_QR_CODE` y pasarlo al analizador.
 *  2. Vincular un caso de uso [Preview] al [PreviewView] provisto para que el
 *     usuario vea la camara en vivo.
 *  3. Vincular un caso de uso [ImageAnalysis] cuyo analyzer es
 *     [AnalizadorFramesQr] — alli vive la logica de deteccion QR, debounce y
 *     gate de pausa/reanudacion (extraida de este archivo).
 *  4. Controlar el ciclo de vida del executor del analizador (bug A14 fix) y
 *     del scanner ML Kit (bug C1 fix: pausa vs liberacion separadas).
 *  5. Exponer los switchs de control de deteccion
 *     ([setOnQrDetectado], [pausarDeteccion], [reanudarDeteccion]) delegando
 *     al analizador.
 *
 * La clase es consciente del ciclo de vida: obtiene un [ProcessCameraProvider]
 * y vincula los casos de uso al ciclo de vida del [LifecycleOwner] para que la
 * camara se detenga automaticamente cuando la actividad pase a segundo plano.
 *
 * Refactor: antes este archivo contenia TAMBIEN el analizador de frames, la
 * data class [DeteccionQr], el estado del debounce ([DebouncerDeteccion] no
 * existia como clase) y los 8 parametros posicionales de
 * `procesarCodigosDetectados`. Toda esa logica vive ahora en
 * [AnalizadorFramesQr] / [DebouncerDeteccion] / [DeteccionQr] — testeables sin
 * camara/ML Kit. Este archivo queda como thin wrapper de orquestacion.
 */
class ModuloCamara(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    onQrDetectado: (DeteccionQr) -> Unit
) {

    /**
     * Scanner ML Kit restringido a QR. Creado una sola vez en la construccion
     * del modulo y cerrado una sola vez en [liberarEscaner] (bug C1 fix: el
     * scanner no se cierra en cada ON_PAUSE, solo al salir definitivamente de
     * la pantalla — ver [detener] vs [liberarEscaner]).
     */
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
     * Analizador de frames QR con estado propio (gate + debounce + callback
     * mutable). El modulo le inyecta el scanner ML Kit (que controla el ciclo
     * de vida) y expone los switchs de control delegando en el.
     */
    private val analizador: AnalizadorFramesQr = AnalizadorFramesQr(
        context = context,
        escanerCodigosBarras = escanerCodigosBarras,
        onQrDetectado = onQrDetectado
    )

    /**
     * Permite a la pantalla host refrescar el callback de QR tras una
     * recomposition, evitando el bug stale-callback (H1 fix). Delega en
     * [AnalizadorFramesQr.setOnQrDetectado].
     */
    fun setOnQrDetectado(callback: (DeteccionQr) -> Unit) {
        analizador.setOnQrDetectado(callback)
    }

    /**
     * Pausa el analisis de frames. Los frames entrantes se cierran sin
     * procesar ML Kit. La camara sigue viva (preview visible). Delega en
     * [AnalizadorFramesQr.pausarDeteccion] (que a su vez llama
     * [DebouncerDeteccion.pausar]).
     *
     * Ver [DebouncerDeteccion] para el historial del bug que motivo este gate
     * (multi-deteccion del mismo QR entre el frame que dispara el callback y
     * la propagacion de `analizando=true` al estado de Compose).
     */
    fun pausarDeteccion() = analizador.pausarDeteccion()

    /**
     * Reanuda el analisis y resetea el debounce al timestamp actual (no a
     * 0L) para que el QR que acaba de escanearse no se re-detecte
     * inmediatamente dentro de la ventana de debounce. Delega en
     * [AnalizadorFramesQr.reanudarDeteccion] (que a su vez llama
     * [DebouncerDeteccion.reanudar]).
     *
     * Ver [DebouncerDeteccion] para el historial del bug del dialogo
     * "URL ya escaneada" que reaparece de golpe si el reset iba a epoch.
     */
    fun reanudarDeteccion() = analizador.reanudarDeteccion()

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
            // Mismo aspect ratio que ImageAnalysis para que el bounding box
            // de ML Kit (en espacio de imagen) mapee correctamente al espacio
            // de pantalla (FILL_CENTER). Si Preview usa 16:9 y ImageAnalysis
            // 4:3, el FOV difiere y el highlight del QR queda desalineado.
            val vistaPrevia = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            // Bug A14 fix: antes de re-vincular el analizador, shutDownear el
            // executor previo si quedaba vivo (rotacion, re-entrada, navegacion).
            //
            // Audit fix (main-thread block): solo shutdown() — el
            // awaitTermination(2s) previo corría DENTRO del listener del
            // future (main executor) y podía bloquear el hilo principal
            // hasta 2s si el analyzer estaba procesando un frame.
            // shutdown() ya impide que se encolen tareas nuevas; una tarea
            // en vuelo termina sola (el analyzer cierra el ImageProxy).
            runCatching {
                executorAnalizador?.shutdown()
            }

            // ── Nuevo executor para este ciclo de analisis ──
            val executorNuevo = Executors.newSingleThreadExecutor()
            executorAnalizador = executorNuevo

            // ── Caso de uso de analisis de imagen ──
            // Mismo aspect ratio que Preview (RATIO_16_9) para que el
            // bounding box de ML Kit mapee 1:1 al espacio de pantalla.
            // El analyzer es [analizador] (implementa ImageAnalysis.Analyzer
            // con estado propio — gate, debounce, callback mutable).
            val analisisImagen = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executorNuevo, analizador)
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
     * Pausa la camara: detiene el executor del analizador y desvincula los
     * casos de uso de CameraX, pero NO cierra el BarcodeScanner de ML Kit.
     *
     * Bug C1 fix: antes `detener()` llamaba `escanerCodigosBarras.close()`,
     * destruyendo permanentemente el scanner ML Kit. Como el scanner es un
     * campo creado una sola vez y nunca se recrea, al volver de
     * ON_PAUSE → ON_RESUME la llamada `iniciar()` → `analyze` →
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
     * Audit fix (main-thread block): `detener()` (ON_PAUSE) y el cleanup de
     * Compose corren en el hilo principal — antes este metodo hacia
     * `awaitTermination(2, TimeUnit.SECONDS)`, bloqueando el main thread
     * hasta 2s por rotación/backgrounding. Ahora solo shutdown() (no
     * bloqueante); una tarea en vuelo termina sola.
     */
    fun releaseCameraResources() {
        val executor = executorAnalizador ?: return
        runCatching {
            executor.shutdown()
        }
        executorAnalizador = null
    }
}
