package com.qrsecurity.detector.ml

/**
 * Preprocesamiento para el modelo TFLite CANINE-S.
 *
 * Dos responsabilidades:
 *
 * 1. **[limpiarUrl]** — normaliza una URL cruda quitando el prefijo de protocolo
 *    (``http://``, ``https://``, ``ftp://``, ``ftps://``) y el subdominio ``www.``.
 *    Esto replica el preprocesamiento aplicado al dataset de entrenamiento para que
 *    la distribucion de caracteristicas en inferencia coincida con el entrenamiento.
 *
 * 2. **[tokenizar]** — implementa el tokenizador CANINE como un mapeo simple
 *    **de codepoints Unicode a nivel de caracter** (sin archivo de vocabulario externo).
 *    Cada codepoint Unicode se extrae via [String.codePointAt] + [Character.charCount]
 *    (no ``Char.code``, que devuelve el code unit UTF-16 y rompe suplementarios/IDN/emoji).
 *    La secuencia de enteros resultante se rellena con [PAD_IDX] = 0 hasta [MAX_LEN] = 150
 *    o se trunca si es mas larga.
 *
 * El array de salida es un array Int unidimensional de longitud [MAX_LEN], adecuado para
 * colocarlo en el tensor de entrada del modelo TFLite.
 *
 * Nota: El modelo CANINE publicado usa un mapeo de codepoints Unicode basado en hash
 * para la combinacion de piezas de subpalabra, pero para el despliegue on-device en Android
 * sin archivos de vocabulario/corpus externos, el enfoque pragmatico es un mapeo directo
 * de codepoints, que es sin perdida y rapido.
 */
object Preprocesador {

    /** Longitud maxima de secuencia aceptada por el modelo CANINE-S. */
    const val MAX_LEN = 150

    /** Indice de padding — CANINE usa pad token id = 0. */
    const val PAD_IDX = 0

    /** Prefijos de protocolo a quitar durante la limpieza de URL. */
    private val PREFIJOS_PROTOCOLO = listOf(
        "https://", "http://", "ftps://", "ftp://"
    )

    /**
     * Normalizar una URL para entrada al modelo quitando protocolo y prefijo ``www.``.
     *
     * **WAVE 12 fix (C2 CRITICAL):** antes aplicabamos `.lowercase()` y
     * `.trimEnd('/')`, dos transformaciones que el `clean_url` de Python
     * (dataset de entrenamiento) NO aplica. Eso producía skew: el modelo
     * infería sobre strings que nunca vio en training (e.g.
     * `"Example.com/Path"` → `"example.com/path"`, `"evil.com/"` →
     * `"evil.com"`), degradando silenciosamente la probabilidad. Ahora
     * solo `trim()` (whitespace externo) + quitar protocolo y `www.` —
     * contrato 1:1 con el preprocesador de training.
     *
     * Ejemplos:
     *   "https://www.example.com/path" -> "example.com/path"
     *   "http://example.com"            -> "example.com"
     *   "www.malicious.site/x?a=1"      -> "malicious.site/x?a=1"
     *   "ftp://files.example.com/"      -> "files.example.com/"
     *   "example.com/no_protocol"       -> "example.com/no_protocol"
     *   "Example.COM/Path"              -> "Example.COM/Path"   (preserva case)
     *   "evil.com/"                     -> "evil.com/"           (preserva slash)
     */
    fun limpiarUrl(url: String): String {
        var resultado = url.trim()

        // Quitar prefijo de protocolo (coincidencia mas larga primero).
        for (prefijo in PREFIJOS_PROTOCOLO) {
            if (resultado.startsWith(prefijo)) {
                resultado = resultado.removePrefix(prefijo)
                break
            }
        }

        // Quitar subdominio "www." inicial.
        resultado = resultado.removePrefix("www.")

        return resultado
    }

    /**
     * Tokenizador CANINE: mapeo de codepoints Unicode a nivel de caracter.
     *
     * Cada caracter de [urlLimpia] se convierte a su codepoint [Int].
     * El resultado se rellena hasta [MAX_LEN] con [PAD_IDX] o se trunca.
     *
     * @param urlLimpia La salida de [limpiarUrl].
     * @return [IntArray] de longitud [MAX_LEN] con codepoints + padding.
     */
    fun tokenizar(urlLimpia: String): IntArray {
        // Pre-llenar con padding.
        val resultado = IntArray(MAX_LEN) { PAD_IDX }

        // Bug H5: iterar codepoints Unicode reales, no UTF-16 code units.
        // ``urlLimpia[i].code`` devuelve el code unit UTF-16 (un surrogate
        // aislado para BMP > U+FFFF, ej. emoji o dominios IDN), no el
        // codepoint Unicode. CANINE opera sobre codepoints, por lo que una
        // URL con emoji o etiquetas IDN romperia la inferencia (cada
        // surrogate se tokenizaria como un codepoint distinto y fuera del
        // rango valido del vocabulario CANINE). Usamos [String.codePointAt]
        // + [Character.charCount] para avanzar correctamente por codepoints
        // suplementarios (2 chars UTF-16 por codepoint).
        var idx = 0
        var i = 0
        while (i < urlLimpia.length && idx < MAX_LEN) {
            val cp = urlLimpia.codePointAt(i)
            // CANINE preserva los codepoints tal cual; el padding ocupa el
            // ID 0, lo que significa que un caracter NUL real (codepoint 0)
            // chocaria con pad, pero NUL en URLs es invalido y es improbable
            // que aparezca. Mapeamos cualquier codepoint 0 (teoricamente
            // posible solo si la entrada viene pre-corrupta) a PAD_IDX+1
            // para preservar la distincion.
            resultado[idx] = if (cp == PAD_IDX) PAD_IDX + 1 else cp
            idx++
            i += Character.charCount(cp)
        }

        return resultado
    }

    /**
     * Igual que [tokenizar] pero devuelve un [Array] bidimensional adecuado para
     * entrada TFLite con dimension de lote.
     *
     * @param urlLimpia La URL preprocesada.
     * @return `[1][MAX_LEN]` IntArray para el tensor de entrada del modelo.
     */
    fun tokenizarLote(urlLimpia: String): Array<IntArray> {
        return arrayOf(tokenizar(urlLimpia))
    }
}
