package com.qrsecurity.detector.ml

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Contrato del motor de inferencia para el detector de URLs maliciosas.
 *
 * El [com.qrsecurity.detector.pipeline.Pipeline] depende de esta interfaz
 * (no de la implementacion concreta) para permitir:
 *  - **Produccion**: [MotorInferenciaReal] carga el modelo LSTM char-level
 *    TFLite desde ``assets/ml/lstm_model.tflite`` y ejecuta inferencia
 *    on-device 100% offline.
 *  - **Tests JVM/Robolectric**: [MotorInferenciaFake] (en ``src/test``) sin
 *    dependencias nativas (JNI/TFLite), determinista.
 *
 * El motor devuelve **logits crudos** (sin sigmoid) cuando [devuelveLogits] es
 * ``true`` — el [com.qrsecurity.detector.pipeline.Pipeline] consulta esta bandera
 * para invocar [ControladorAlerta.desdeLogits] que aplica sigmoid antes de clasificar.
 */
interface MotorInferencia {
    /**
     * `true` si [inferir] devuelve logits crudos (contrato del motor TFLite
     * LSTM); el [com.qrsecurity.detector.pipeline.Pipeline] consulta esta
     * bandera para invocar [ControladorAlerta.desdeLogits] que aplica sigmoid.
     */
    val devuelveLogits: Boolean

    /** Nombre del delegado usado — reportado a la UI como ``"GPU"``/``"NNAPI"``/``"CPU"``. */
    val nombreDelegado: String

    /**
     * Ejecuta inferencia sobre la URL tokenizada.
     *
     * @param entradaTokenizada ``[1][MAX_LEN]`` IntArray de indices de vocabulario.
     * @return array de un float con el **logit crudo** de la clase maliciosa
     *  (aplicar sigmoid para obtener probabilidad [0, 1]).
     */
    fun inferir(entradaTokenizada: Array<IntArray>): FloatArray

    /** Liberar recursos nativos. Llamar desde [com.qrsecurity.detector.pipeline.Pipeline.destruir]. */
    fun cerrar()
}

/**
 * Motor de inferencia TFLite real para el modelo LSTM char-level URL detector.
 *
 * Carga ``lstm_model.tflite`` desde ``assets/ml/`` y ejecuta inferencia on-device
 * 100% offline. El modelo devuelve **logits crudos** (sin sigmoid) — [devuelveLogits]
 * es ``true`` para que [com.qrsecurity.detector.pipeline.Pipeline] use
 * [ControladorAlerta.desdeLogits] que aplica sigmoid antes de clasificar.
 *
 * Delegacion de hardware (orden de preferencia, cada uno envuelto en
 * `runCatching` para que un fallo aislado nunca impida obtener un Interpreter):
 *  1. **GPU** — via [GpuDelegate] (TFLite GPU delegate plugin). Mas rapido en
 *     dispositivos con GPU soportada. Requisa CompatibilityList para validar.
 *  2. **NNAPI** — via [Interpreter.Options.setUseNNAPI]. Aprovecha aceleradores
 *     hardware (NPU/DSP). Solo se intenta con `Build.VERSION.SDK_INT >= 27`
 *     (API 27 = minimo oficial de NNAPI).
 *  3. **CPU** — fallback sin delegado. Siempre disponible.
 *
 * El [nombreDelegado] reporta cual delegado se uso efectivamente (``"GPU"``,
 * ``"NNAPI"``, o ``"CPU"``) para fines de auditoria en el historial de escaneos.
 *
 * El modelo TFLite tiene:
 *  - Entrada: tensor int32 shape [1, MAX_LEN] (indices de vocabulario char-level)
 *  - Salida:  tensor float32 shape [1, 1] (logit crudo de clase maliciosa)
 */
class MotorInferenciaReal private constructor(
    private val assetManager: AssetManager,
    override val devuelveLogits: Boolean
) : MotorInferencia {

    /**
     * Constructor por defecto — carga el modelo TFLite desde ``assets/ml/``.
     *
     * Si el modelo no existe (placeholder sin assets), lanza
     * [IllegalStateException] — a diferencia de la version placeholder anterior
     * que usaba random, esta version ES el motor real.
     */
    constructor(context: Context) : this(
        assetManager = context.assets,
        devuelveLogits = true
    )

    override var nombreDelegado: String = "CPU"
        private set

    /** Interpreter TFLite — creado lazy para no bloquear el inicio de la app. */
    private var interpreter: Interpreter? = null

    /** Buffer del modelo mapeado en memoria (mmap) — se retiene para evitar GC. */
    private var modelBuffer: MappedByteBuffer? = null

    /** ByteBuffer de entrada reutilizado para evitar allocs por inference. */
    private var inputBuffer: ByteBuffer? = null

    /** Array de salida reutilizado para evitar allocs por inference. Shape [1][1]. */
    private var outputArray: Array<FloatArray>? = null

    /** Tamano del tensor de entrada (MAX_LEN). */
    private var inputMaxLen: Int = 0

    override fun inferir(entradaTokenizada: Array<IntArray>): FloatArray {
        val interp = ensureInterpreter()

        // Construir ByteBuffer de entrada: int32 * MAX_LEN = 4 bytes * MAX_LEN
        val maxLen = entradaTokenizada[0].size
        val buf = inputBuffer ?: ByteBuffer.allocateDirect(maxLen * 4).also {
            inputBuffer = it
        }
        buf.rewind()
        buf.order(ByteOrder.nativeOrder())
        for (i in 0 until maxLen) {
            buf.putInt(entradaTokenizada[0][i])
        }

        // Array de salida: float32[1][1] — el modelo devuelve shape [1,1]
        val out = outputArray ?: arrayOf(FloatArray(1)).also { outputArray = it }

        interp.run(buf, out)

        return floatArrayOf(out[0][0])
    }

    override fun cerrar() {
        interpreter?.close()
        interpreter = null
        modelBuffer = null
        inputBuffer = null
        outputArray = null
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    /**
     * Crear el Interpreter lazy — carga el modelo desde assets y selecciona
     * el mejor delegado disponible (GPU → NNAPI → CPU).
     *
     * Audit fix (thread-safety): `@Synchronized` — dos inferencias
     * concurrentes (primer arranque en frío) podían crear dos Interpreters
     * y filtrar uno. La primera inference paga el lock; las siguientes
     * encuentran `interpreter != null` y retornan inmediato.
     */
    @Synchronized
    private fun ensureInterpreter(): Interpreter {
        interpreter?.let { return it }

        val modelFile = loadModelFromAssets()
        FileChannel.open(
            modelFile.toPath(),
            StandardOpenOption.READ
        ).use { channel ->
            val buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                0,
                modelFile.length()
            ).also { modelBuffer = it }

            // Fallback secuencial: GPU → NNAPI → CPU.
            // Cada etapa envuelve tanto la creacion del delegate como la del
            // Interpreter, porque el error "internal error: Error applying delegate"
            // se lanza al construir el Interpreter, no al crear el delegate.
            val interp = tryGpu(buffer)
                ?: tryNnapi(buffer)
                ?: tryCpu(buffer)

            nombreDelegado = when {
                interp.first == "GPU" -> "GPU"
                interp.first == "NNAPI" -> "NNAPI"
                else -> "CPU"
            }

            val inputDetails = interp.second.getInputTensor(0)
            inputMaxLen = inputDetails.shape()[inputDetails.shape().size - 1]

            interpreter = interp.second
        }

        // Audit fix (temp file leak): cada re-init del interpreter (tras
        // `cerrar()` — p.ej. rotación de Activity) copiaba el modelo a un
        // temp file nuevo que nunca se borraba (~830 KB por ciclo). En
        // Linux/Android el mmap sobrevive al unlink, así que podemos borrar
        // el archivo en cuanto el canal está mapeado.
        modelFile.delete()

        return interpreter ?: error("Interpreter no inicializado tras ensureInterpreter()")
    }

    private fun tryGpu(buffer: MappedByteBuffer): Pair<String, Interpreter>? {
        return try {
            val compatList = CompatibilityList()
            if (!compatList.isDelegateSupportedOnThisDevice) return null

            val gpuDelegate = GpuDelegate(
                org.tensorflow.lite.gpu.GpuDelegate.Options().also {
                    it.setPrecisionLossAllowed(true)
                }
            )
            val options = Interpreter.Options().addDelegate(gpuDelegate)
            Pair("GPU", Interpreter(buffer, options))
        } catch (e: Exception) {
            // GPU delegate fallo al crear o al aplicarse durante Interpreter
            null
        }
    }

    private fun tryNnapi(buffer: MappedByteBuffer): Pair<String, Interpreter>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        return try {
            val options = Interpreter.Options().setUseNNAPI(true)
            Pair("NNAPI", Interpreter(buffer, options))
        } catch (e: Exception) {
            null
        }
    }

    private fun tryCpu(buffer: MappedByteBuffer): Pair<String, Interpreter> {
        // CPU siempre disponible — sin delegado
        return Pair("CPU", Interpreter(buffer, Interpreter.Options()))
    }

    private fun loadModelFromAssets(): File {
        val modelPath = "ml/lstm_model.tflite"
        val tempFile = File.createTempFile("lstm_model", ".tflite")

        assetManager.open(modelPath).use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    companion object {
        private const val TAG: String = "MotorInferenciaReal"
    }
}
