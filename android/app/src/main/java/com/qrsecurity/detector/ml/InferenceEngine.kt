package com.qrsecurity.detector.ml

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.util.Random
import kotlin.math.abs

/**
 * Motor de inferencia — implementacion aleatoria determinista por URL.
 *
 * **Esta version NO carga ningun modelo TFLite.** Sustituye la inferencia
 * CANINE-S on-device por una probabilidad aleatoria uniforme en [0, 1]
 * derivada de un seed estable (hash FNV-1a de la URL limpia).
 *
 * Propiedades clave:
 *  - **No requiere assets ni hardware delegado** — la app arranca sin descargar
 *    un modelo de ~500 MB.
 *  - **Determinismo por URL**: el mismo codigo QR devuelve siempre la misma
 *    probabilidad (y por tanto el mismo [com.qrsecurity.detector.ml.ControladorAlerta.NivelAlerta])
 *    dentro de una misma ejecucion. El cache [com.qrsecurity.detector.cache.CacheResultados]
 *    sigue siendo util porque evita recalcular el mismo hash + Random state.
 *  - **Distribucion uniforme**: cada URL nueva recibe una probabilidad
 *    `Random.nextFloat()` U[0, 1], sin sesgo heuristico.
 *
 * El contrato publico (signature del metodo [inferir] y de [nombreDelegado])
 * coincide con el [MotorInferencia] previo basado en TFLite, de modo que
 * [com.qrsecurity.detector.pipeline.Pipeline] no necesita modificacion.
 *
 * Cuando el modelo real vuelva a estar disponible, basta con restaurar la
 * implementacion previa (Git history) — esta clase es un placeholder
 * deliberadamente sencillo.
 *
 * Infraestructura TFLite lista para la restauracion (A-16 / M-30):
 *  - [crearOpcionesInterpreter].encapsula la seleccion de delegado
 *    GPU -> NNAPI -> CPU, cada uno envuelto en `runCatching` para que un
 *    fallo aislado de un delegado nunca impida obtener un [Interpreter]
 *    funcional. NNAPI solo se intenta con `Build.VERSION.SDK_INT >= O_MR1`
 *    (API 27 = minimo oficial de NNAPI; si SDK < 27, se salta a CPU).
 *  - [resolverRutaModelo] recorre [AssetManager.list] de forma recursiva
 *    para localizar el primer `*.tflite` bajo `assets/`. La ruta
 *    descubierta se cachea en [rutaModeloCache], evitando re-traversal en
 *    llamadas subsiguientes. Si no hay modelo, devuelve `null` (placeholder)
 *    en lugar de crashear.
 *
 * @see crearOpcionesInterpreter
 * @see resolverRutaModelo
 */
class MotorInferencia private constructor(
    private val seedProvider: (urlLimpia: String) -> Long,
    private val assetManager: AssetManager?,
    /**
     * `true` si [inferir] devuelve logits crudos (contrato del futuro motor
     * TFLite CANINE-S); `false` si devuelve una probabilidad ya sigmoidada
     * en [0, 1] (este placeholder).
     *
     * Bug C1 (fix): [com.qrsecurity.detector.pipeline.Pipeline] consulta esta
     * bandera para decidir si invocar [ControladorAlerta.desdeLogits] (aplica
     * sigmoid, camino logits) o [ControladorAlerta.clasificar] directo sobre
     * la probabilidad (camino placeholder). Antes el Pipeline siempre llamaba
     * `desdeLogits`, lo que aplicaba sigmoid **dos veces** sobre la salida del
     * placeholder y comprimia el rango [0,1] a [0.5, 0.731] —— el umbral
     * MALICIOSO (0.7) era casi inalcanzable.
     *
     * Valor por defecto `true` para preservar el contrato del futuro motor
     * TFLite real. Al restaurar Git history del motor real, no hay que tocar
     * esta bandera — basta con restaurar la implementacion previa.
     *
     * El constructor por defecto [MotorInferencia] (Context) lo sobrescribe a
     * `false` para el placeholder actual.
     */
    val devuelveLogits: Boolean
) {

    /**
     * Constructor por defecto — necesario porque [com.qrsecurity.detector.pipeline.Pipeline]
     * lo construye como `MotorInferencia(context)` con un [Context].
     * El contexto se guarda como [AssetManager] para que la futura
     * restauracion TFLite pueda resolver el modelo en `assets/` sin
     * re-abrir el contexto. Esta implementacion placeholder no carga
     * activos ni delegados, pero la infraestructura ya esta lista.
     *
     * Bug C1 (fix): este constructor fija `devuelveLogits = false` porque
     * el placeholder devuelve una probabilidad U[0,1] (no logits). Cuando
     * se restaure el motor TFLite real via Git, basta con cambiar este
     * `false` a `true` — Pipeline detectara el cambio al consultar la
     * bandera y usara [ControladorAlerta.desdeLogits] automaticamente.
     */
    constructor(context: Context) : this(
        seedProvider = { url -> hashFnv1a(url) },
        assetManager = context.assets,
        devuelveLogits = false
    )

    /** Nombre del delegado usado — reportado a la UI como "ALEATORIO". */
    var nombreDelegado: String = "ALEATORIO"
        private set

    /** Ruta del modelo `.tflite` descubierta bajo `assets/`. `null` si no hay. */
    @Volatile
    private var rutaModeloCache: String? = null

    /**
     * Ejecuta una "inferencia" aleatoria determinista sobre la URL tokenizada.
     *
     * Como la tokenizacion preprocesa la URL a codepoints Unicode, para
     * producir un seed estable necesitamos reconstruir la URL original.
     * En lugar de invertir la tokenizacion, derivamos el seed directamente
     * de los codepoints (decodificacion trivial a String UTF-16 via Char conversion).
     *
     * Bug C1 (fix): esta implementacion placeholder devuelve una **probabilidad
     * U[0, 1]** (no logits). La propiedad [devuelveLogits] es `false` para
     * indicar al [com.qrsecurity.detector.pipeline.Pipeline] que debe usar
     * [ControladorAlerta.clasificar] directo sobre `salida[0]`, sin aplicar
     * sigmoid de nuevo. Antes el Pipeline llamaba `desdeLogits` (que aplica
     * sigmoid), comprimiendo el rango [0,1] a [0.5, 0.731] — el umbral
     * MALICIOSO (0.7) era casi inalcanzable, dando siempre SOSPECHOSO.
     *
     * @param entradaTokenizada `[1][MAX_LEN]` IntArray de codepoints.
     * @return array de un float con la probabilidad en [0, 1]
     *  (NO logits en este placeholder; el futuro motor TFLite real
     *  devolvera logits crudos y `devuelveLogits` sera `true`).
     */
    fun inferir(entradaTokenizada: Array<IntArray>): FloatArray {
        val longitudReal = entradaTokenizada.firstOrNull()?.count { it != Preprocesador.PAD_IDX } ?: 0
        val urlReconstruida = reconstructUrl(entradaTokenizada, longitudReal)
        val seed = seedProvider(urlReconstruida)
        val random = Random(seed)
        // Distribucion uniforme: misma probabilidad para toda URL nueva.
        val probabilidad = random.nextFloat()
        return floatArrayOf(probabilidad)
    }

    /**
     * Liberar recursos — sin-op en esta implementacion (no hay Interpreter).
     */
    fun cerrar() {
        // Sin recursos nativos que liberar.
    }

    // ─────────────────────────────────────────────────────────
    // Infraestructura TFLite (dormant — restauracion Git history)
    // ─────────────────────────────────────────────────────────

    /**
     * Construye [Interpreter.Options] seleccionando el primer delegado
     * disponible, en orden GPU -> NNAPI -> CPU. Cada intento de init
     * se envuelve en `runCatching`: si el delegado falla (driver buggy,
     * dispositivo sin soporte, etc.), se loguea y se cae al siguiente.
     * El numero de hilos se limita a 4.
     *
     * NNAPI requiere Android API >= 27 (`O_MR1`); si SDK < 27 se salta
     * directamente al fallback CPU. Esto evita el crash historico en
     * dispositivos sin NNAPI o con drivers defectuosos.
     *
     * @param assets [AssetManager] — actualmente sin uso por la ruta
     *  de delegados, pero aceptado para futura expansion (p.ej. cargar
     *  `libtensorflowlite_gpu_delegate.so` desde assets en algunos
     *  pipelines de plugin).
     * @param modeloPath ruta al modelo `.tflite` (resuelta por
     *  [resolverRutaModelo]); reservada para logging futuro.
     * @return [Interpreter.Options] configurado con el primer delegado
     *  que inicializo correctamente; sin delegado si todos fallaron
     *  (CPU fallback — siempre valido).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun crearOpcionesInterpreter(
        assets: AssetManager,
        modeloPath: String
    ): Interpreter.Options {
        // 1) GPU delegate — requiere org.tensorflow:tensorflow-lite-gpu.
        // Cada bloque se construye sobre un [Interpreter.Options] fresco
        // para que un delegate parcialmente inicializado nunca contamine
        // al siguiente intento.
        runCatching {
            val opciones = Interpreter.Options().setNumThreads(MAX_THREADS)
            opciones.addDelegate(GpuDelegate())
            nombreDelegado = "GPU"
            return opciones
        }.onFailure { fallo ->
            android.util.Log.w(TAG, "GPU delegate fallo: ${fallo.message}")
        }

        // 2) NNAPI delegate — requiere Android API >= 27 (O_MR1) = minimo oficial.
        // SDK < 27 -> se salta al fallback CPU sin intentar (NNAPI no existe).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                val opciones = Interpreter.Options().setNumThreads(MAX_THREADS)
                opciones.addDelegate(NnApiDelegate())
                nombreDelegado = "NNAPI"
                return opciones
            }.onFailure { fallo ->
                android.util.Log.w(TAG, "NNAPI delegate fallo: ${fallo.message}")
            }
        } else {
            android.util.Log.i(
                TAG,
                "NNAPI salteado: SDK ${Build.VERSION.SDK_INT} < ${Build.VERSION_CODES.O_MR1}"
            )
        }

        // 3) CPU fallback — sin delegado, hilos limitados a [MAX_THREADS].
        // Siempre funciona; nunca se cae aqui.
        nombreDelegado = "CPU"
        return Interpreter.Options().setNumThreads(MAX_THREADS)
    }

    /**
     * Resuelve recursivamente la ruta del primer `*.tflite` bajo `assets/`.
     * El resultado se cachea en [rutaModeloCache]; llamadas subsiguientes
     * no re-trabajan el [AssetManager.list]. Si no se encuentra ningun
     * modelo, devuelve `null` (escenario placeholder actual — no crashea).
     *
     * Decision (M-30): en lugar de hard-codar un subfolder como
     * `assets://modelos/modelo.tflite`, recorremos `assets.list(path)`
     * recursivamente hasta encontrar el primer `.tflite`. Esto soporta
     * cualquier layout de assets sin tocar el codigo. Cuando exista un
     * unico modelo canonico, basta con colocarlo en `assets/` (o
     * cualquier subfolder) y sera hallado sin configuracion adicional.
     */
    private fun resolverRutaModelo(assets: AssetManager): String? {
        rutaModeloCache?.let { return it }
        val encontrada = buscarTfliteRecursivo(assets, "")
        if (encontrada != null) {
            rutaModeloCache = encontrada
        }
        return encontrada
    }

    /**
     * DFS sobre [AssetManager.list] — devuelve la primera ruta con
     * extension `.tflite`, en orden lexicografico del listado. Acepta
     * `path` vacio para el raiz de `assets/`.
     */
    private fun buscarTfliteRecursivo(assets: AssetManager, path: String): String? {
        val entradas = runCatching { assets.list(path) }
            .getOrNull()
            ?: return null
        for (entrada in entradas.orEmpty()) {
            val rutaCompleta = if (path.isEmpty()) entrada else "$path/$entrada"
            // Un subfolder se reconoce porque NO tiene extension `.tflite`.
            // `AssetManager.list` devuelve nombres sin trailing slash.
            if (entrada.endsWith(SUFFIX_TFLITE, ignoreCase = true)) {
                return rutaCompleta
            }
            // Heuristica simple: si la entrada no tiene extension,
            // asumimos que es un subdirectorio y bajamos.
            if (!entrada.contains('.')) {
                val enSub = buscarTfliteRecursivo(assets, rutaCompleta)
                if (enSub != null) return enSub
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    /**
     * Reconstruye la URL limpia desde su forma tokenizada a codepoints,
     * truncando los PADDING y convirtiendo los codepoints a Char.
     *
     * Pero como [Preprocesador.tokenizar] mapea codepoint 0 PAD a 1 (PK_NOTE
     * en el comentario), no podemos distinguir PAD real del caracter NUL.
     * En la practica eso no importa — el punto clave es que el seed sea
     * estable para una misma URL. Usamos los codepoints tal cual.
     */
    private fun reconstructUrl(tokenized: Array<IntArray>, longitudReal: Int): String {
        if (longitudReal == 0) return ""
        val sb = StringBuilder(longitudReal)
        for (i in 0 until longitudReal) {
            val codePoint = tokenized[0][i]
            // Convertir codepoint a Char (funciona para BMP) — suficiente
            // para producir un seed estable, no necesitamos la URL exacta.
            if (codePoint <= Char.MAX_VALUE.code) {
                sb.append(codePoint.toChar())
            } else {
                // Para codepoints fuera del BMP, append del lower 16 bits como Char.
                sb.append((codePoint and 0xFFFF).toChar())
            }
        }
        return sb.toString()
    }

    companion object {
        /**
         * Hash FNV-1a 64-bit — algoritmo determinista, rapido y bien distribuido.
         * Produce un [Long] estable para una misma entrada.
         */
        private fun hashFnv1a(entrada: String): Long {
            var hash = FNV_OFFSET_BASIS_64
            for (i in entrada.indices) {
                hash = hash xor (entrada[i].code.toLong() and 0xFFL)
                hash *= FNV_PRIME_64
            }
            return abs(hash)
        }

        private const val FNV_OFFSET_BASIS_64: Long = -3750763034362895579L // 0xCBF29CE484222325
        private const val FNV_PRIME_64: Long = 1099511628211L

        // Infraestructura TFLite (dormant).
        private const val TAG: String = "MotorInferencia"
        private const val MAX_THREADS: Int = 4
        private const val SUFFIX_TFLITE: String = ".tflite"
    }
}
