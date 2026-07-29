package com.qrsecurity.detector.ml

/**
 * Convierte los logits crudos del modelo a probabilidad via sigmoid, luego clasifica
 * la URL en uno de tres niveles de alerta usando umbrales configurables.
 *
 * Umbrales (defaults; configurables via [inicializar] al arrancar la app):
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
 * Bug M9 (fix): antes los umbrales eran `const val` hardcodeados (0.3/0.7) y
 * no se podian calibrar desde `assets/thresholds.json`. Ahora son `@Volatile var`
 * con valores por defecto conservando los defaults historicos y se configuran una
 * vez al arrancar la app via [inicializar].
 */
object ControladorAlerta {

    /** Valor por defecto del umbral SEGURO (0.3) — usado si [inicializar] no se llama. */
    const val UMBRAL_SEGURO_DEFAULT: Float = 0.3f

    /** Valor por defecto del umbral MALICIOSO (0.7) — usado si [inicializar] no se llama. */
    const val UMBRAL_MALICIOSO_DEFAULT: Float = 0.7f

    /**
     * Umbral frontera entre SEGURO y SOSPECHOSO.
     *
     * Modificable via [inicializar] al arrancar la app (Phase 6 leera
     * `assets/thresholds.json` y llamara `ControladorAlerta.inicializar(...)`
     * desde `Application.onCreate()`). Por defecto retiene el valor
     * hardcodeado historico (0.3) hasta que la calibracion este lista.
     */
    @Volatile
    var UMBRAL_SEGURO: Float = UMBRAL_SEGURO_DEFAULT
        private set

    /** Umbral frontera entre SOSPECHOSO y MALICIOSO. */
    @Volatile
    var UMBRAL_MALICIOSO: Float = UMBRAL_MALICIOSO_DEFAULT
        private set

    /**
     * Configurar los umbrales de alerta al arrancar la app.
     *
     * Se invoca desde `Application.onCreate()` (o un inicializador equivalente)
     * uma vez leidos los umbrales calibrados desde `assets/thresholds.json`.
     * Si no hay archivo de calibracion, no se llama y se conservan los defaults
     * [UMBRAL_SEGURO_DEFAULT] / [UMBRAL_MALICIOSO_DEFAULT].
     *
     * Thread-safe: los campos son `@Volatile` con escritura sincronizada.
     * Las lecturas en [clasificar] son atomicas sobre Float —— no se requiere
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
     * Restaurar los umbrales a sus valores por defecto — util en tests y en
     * el logout coordinado (Phase 6 podria variar umbrales por usuario).
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
