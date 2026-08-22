package com.qrsecurity.detector.ml

/**
 * Convierte los logits crudos del modelo a probabilidad via sigmoid, luego clasifica
 * la URL en uno de tres niveles de alerta usando umbrales configurables.
 *
 * Umbrales (defaults; configurables via [inicializar]):
 *  - **prob < UMBRAL_SEGURO (default 0.3)** → [NivelAlerta.SEGURO] (verde)
 *  - **UMBRAL_SEGURO ≤ prob < UMBRAL_MALICIOSO (default 0.7)** → [NivelAlerta.SOSPECHOSO] (amarillo)
 *  - **prob ≥ UMBRAL_MALICIOSO** → [NivelAlerta.MALICIOSO] (rojo)
 *
 * La funcion sigmoid mapea un logit crudo [z] a una probabilidad en [0, 1]:
 *
 *   σ(z) = 1 / (1 + e^(-z))
 *
 * Para clasificacion binaria con una sola neurona de salida, z es el log-odds
 * de que la URL sea maliciosa. Una probabilidad mayor significa mayor riesgo.
 *
 * Si el modelo produce dos logits (softmax de dos clases), [desdeLogits] toma
 * el logit de la clase 1 (malicioso).
 *
 * Los umbrales son `@Volatile var` con valores por defecto conservando los
 * defaults historicos (0.3 / 0.7). [inicializar] y [reset] exponen un API de
 * calibracion forward-compatible — el runtime actual usa los defaults, pero
 * la firma permite ajustarlos en caliente si en el futuro se incorpora un
 * archivo de calibracion (p. ej. `assets/thresholds.json`) cargado desde
 * `Application.onCreate()`. Hoy nadie los invoca fuera de tests; estan
 * reservados a proposito para no romper a futuro a los consumers que ya
 * lean [UMBRAL_SEGURO] / [UMBRAL_MALICIOSO] en el hot-path de [clasificar].
 */
object ControladorAlerta {

    /** Valor por defecto del umbral SEGURO (0.3) — usado si [inicializar] no se llama. */
    const val UMBRAL_SEGURO_DEFAULT: Float = 0.3f

    /** Valor por defecto del umbral MALICIOSO (0.7) — usado si [inicializar] no se llama. */
    const val UMBRAL_MALICIOSO_DEFAULT: Float = 0.7f

    /**
     * Umbral frontera entre SEGURO y SOSPECHOSO.
     *
     * Modificable via [inicializar] (API de calibracion forward-compatible).
     * Hoy el runtime usa el valor por defecto (0.3); si en el futuro se
     * incorpora un archivo de calibracion `assets/thresholds.json` cargado
     * desde `Application.onCreate()`, bastara con invocar [inicializar].
     */
    @Volatile
    var UMBRAL_SEGURO: Float = UMBRAL_SEGURO_DEFAULT
        private set

    /** Umbral frontera entre SOSPECHOSO y MALICIOSO. */
    @Volatile
    var UMBRAL_MALICIOSO: Float = UMBRAL_MALICIOSO_DEFAULT
        private set

    /**
     * Configurar los umbrales de alerta.
     *
     * API forward-compatible: hoy el runtime no la invoca y se conservan
     * los defaults [UMBRAL_SEGURO_DEFAULT] / [UMBRAL_MALICIOSO_DEFAULT].
     * Reservada para cuando se incorpore un archivo de calibracion
     * `assets/thresholds.json` cargado desde `Application.onCreate()`.
     *
     * Thread-safe: los campos son `@Volatile` con escritura sincronizada.
     * Las lecturas en [clasificar] son atomicas sobre Float — no se requiere
     * bloqueo en el hot-path.
     *
     * @param umbralSeguro frontera SEGURO/SOSPECHOSO en [0, 1].
     * @param umbralMalicioso frontera SOSPECHOSO/MALICIOSO en [0, 1].
     * @throws IllegalArgumentException si los umbrales no cumplen
     *  `0 <= umbralSeguro <= umbralMalicioso <= 1`.
     */
    @Synchronized
    fun inicializar(umbralSeguro: Float, umbralMalicioso: Float) {
        require(umbralSeguro in 0f..1f) {
            "umbralSeguro fuera de rango [0,1]: $umbralSeguro"
        }
        require(umbralMalicioso in 0f..1f) {
            "umbralMalicioso fuera de rango [0,1]: $umbralMalicioso"
        }
        require(umbralSeguro <= umbralMalicioso) {
            "umbralSeguro ($umbralSeguro) > umbralMalicioso ($umbralMalicioso)"
        }
        UMBRAL_SEGURO = umbralSeguro
        UMBRAL_MALICIOSO = umbralMalicioso
    }

    /**
     * Restaurar los umbrales a sus valores por defecto — util en tests y para
     * reestablecer la calibracion si [inicializar] se invoca en caliente.
     */
    @Synchronized
    fun reset() {
        UMBRAL_SEGURO = UMBRAL_SEGURO_DEFAULT
        UMBRAL_MALICIOSO = UMBRAL_MALICIOSO_DEFAULT
    }

    /**
     * Tres niveles discretos de alerta para el estilo de UI (verde, amarillo, rojo).
     */
    enum class NivelAlerta {
        SEGURO,
        SOSPECHOSO,
        MALICIOSO;

        /**
         * Verdadero si este nivel de alerta indica una URL potencialmente peligrosa.
         * Usado para decidir si mostrar la advertencia intersticial [MALICIOSO].
         */
        val esPeligroso: Boolean get() = this != SEGURO

        companion object {
            /**
             * Lookup fail-safe (Audit S4): resuelve el nivel desde el string
             * serializado (el `name` del enum, ver `ResultadoUrlDto.aDto`).
             * Cae a [SOSPECHOSO] ante un id desconocido en lugar de lanzar
             * [IllegalArgumentException] via `Enum.valueOf` — mismo criterio
             * fail-safe que `ui.NivelAlerta.de` (fallar hacia lo prudente, no
             * hacia lo inocuo).
             */
            fun de(id: String): NivelAlerta =
                entries.firstOrNull { it.name == id } ?: SOSPECHOSO
        }
    }

    /**
     * Resultado de evaluar la salida cruda del modelo.
     *
     * @property probabilidad probabilidad sigmoid de que la URL sea maliciosa, en [0, 1].
     * @property nivel [NivelAlerta] discreto para el estilo de UI.
     */
    data class ResultadoAlerta(
        val probabilidad: Float,
        val nivel: NivelAlerta
    )

    /**
     * Convertir logits crudos del modelo a un [ResultadoAlerta].
     *
     * Maneja dos formas de salida comunes:
     *  1. Salida unica (logit crudo de clase maliciosa)
     *  2. Salida de dos clases (probabilidades softmax [seguro, malicioso])
     *
     * @param logits Salida cruda del modelo. Un float (logit binario) o dos floats (logits softmax).
     * @return [ResultadoAlerta] con [ResultadoAlerta.probabilidad] en [0, 1].
     */
    fun desdeLogits(logits: FloatArray): ResultadoAlerta {
        val probabilidad = when (logits.size) {
            0 -> 0f
            1 -> sigmoid(logits[0])
            else -> {
                // Dos clases: tomar el puntaje de clase "malicioso" = logits[1].
                sigmoid(logits[1] - logits[0])
            }
        }
        return ResultadoAlerta(
            probabilidad = probabilidad,
            nivel = clasificar(probabilidad)
        )
    }

    /**
     * Clasificar directamente una probabilidad en un [NivelAlerta].
     *
     * @param probabilidad Probabilidad sigmoid en [0, 1].
     */
    fun clasificar(probabilidad: Float): NivelAlerta {
        return when {
            probabilidad < UMBRAL_SEGURO -> NivelAlerta.SEGURO
            probabilidad < UMBRAL_MALICIOSO -> NivelAlerta.SOSPECHOSO
            else -> NivelAlerta.MALICIOSO
        }
    }

    /**
     * Calcular la funcion sigmoid: 1 / (1 + e^(-z)).
     */
    fun sigmoid(z: Float): Float {
        // Recortar para evitar overflow en Math.exp.
        val recortado = z.coerceIn(-30f, 30f)
        return (1.0f / (1.0f + kotlin.math.exp(-recortado)))
    }
}
