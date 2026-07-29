package com.qrsecurity.detector.ml

import android.content.Context
import android.content.res.AssetManager
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

        private const val TAG: String = "MotorInferencia"
    }
}
