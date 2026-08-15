package com.qrsecurity.detector.ml

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * Preprocesamiento para el modelo LSTM char-level TFLite.
 *
 * Dos responsabilidades:
 *
 * 1. **[limpiarUrl]** — normaliza una URL cruda quitando el prefijo de protocolo
 *    (``http://``, ``https://``, ``ftp://``, ``ftps://``) y el subdominio ``www.``.
 *    Esto replica el preprocesamiento aplicado al dataset de entrenamiento para que
 *    la distribucion de caracteristicas en inferencia coincida con el entrenamiento.
 *
 * 2. **[tokenizar]** — mapea cada caracter de la URL limpia a su indice en el
 *    vocabulario char-level (``char2idx``) cargado desde ``assets/ml/vocab.json``.
 *    La secuencia de enteros resultante se rellena con [PAD_IDX] hasta [MAX_LEN]
 *    o se trunca si es mas larga. Los caracteres fuera del vocabulario se mapean
 *    a [UNK_IDX].
 *
 * Diferencias con la version CANINE-S anterior:
 *  - **Vocabulario**: char2idx desde vocab.json (124 chars) en vez de codepoints
 *    Unicode directos. El LSTM fue entrenado con este vocabulario fijo.
 *  - **MAX_LEN**: 100 (percentil 95 de longitudes del train set) en vez de 150.
 *  - **PAD_IDX/UNK_IDX**: cargados desde model_metadata.json (0 y 1 respectivamente).
 *
 * El array de salida es un [IntArray] de longitud [MAX_LEN], adecuado para el
 * tensor de entrada del modelo TFLite (dtype int32, shape [1, MAX_LEN]).
 */
object Preprocesador {

    // ── Hiperparametros (cargados desde model_metadata.json) ──

    /** Longitud maxima de secuencia aceptada por el modelo LSTM. */
    @Volatile
    var MAX_LEN: Int = 100
        private set

    /** Indice de padding — fila 0 del embedding (ceros por padding_idx). */
    @Volatile
    var PAD_IDX: Int = 0
        private set

    /** Indice de token desconocido (out-of-vocabulary). */
    @Volatile
    var UNK_IDX: Int = 1
        private set

    /** Tamano del vocabulario (incluye PAD y UNK). */
    @Volatile
    var VOCAB_SIZE: Int = 124
        private set

    // ── Vocabulario char2idx (cargado desde vocab.json) ──

    @Volatile
    private var char2idx: Map<String, Int>? = null

    /** `true` si [inicializar] se ha llamado y el vocabulario esta cargado. */
    val estaInicializado: Boolean
        get() = char2idx != null

    /**
     * Resetear el estado del preprocesador a no inicializado.
     *
     * Uso exclusivo en **tests** para forzar re-inicializacion entre tests
     * con diferentes vocabularios. En produccion, [inicializar] es idempotente
     * y no necesita reset.
     */
    fun reset() {
        char2idx = null
    }

    /**
     * Inicializar el preprocesador con un vocabulario y hiperparametros directos.
     *
     * Uso exclusivo en **tests JVM** (sin [AssetManager] disponible). En
     * produccion, usar [inicializar] con [AssetManager] para cargar desde
     * ``assets/ml/vocab.json`` y ``assets/ml/model_metadata.json``.
     *
     * Es idempotente: si ya esta cargado, no hace nada.
     */
    fun inicializarTest(
        vocab: Map<String, Int>,
        maxLen: Int,
        padIdx: Int,
        unkIdx: Int,
        vocabSize: Int
    ) {
        if (char2idx != null) return
        char2idx = vocab
        MAX_LEN = maxLen
        PAD_IDX = padIdx
        UNK_IDX = unkIdx
        VOCAB_SIZE = vocabSize
    }

    /** Prefijos de protocolo a quitar durante la limpieza de URL. */
    private val PREFIJOS_PROTOCOLO = listOf(
        "https://", "http://", "ftps://", "ftp://"
    )

    /**
     * Inicializar el preprocesador cargando vocab.json y model_metadata.json
     * desde ``assets/ml/``.
     *
     * Debe llamarse una vez antes de cualquier [tokenizar] — tipicamente desde
     * [com.qrsecurity.detector.pipeline.Pipeline] al arrancar.
     *
     * Es idempotente: si ya esta cargado, no hace nada.
     */
    fun inicializar(assets: AssetManager) {
        if (char2idx != null) return

        // Cargar model_metadata.json
        val metadataJson = assets.open("ml/model_metadata.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        MAX_LEN = metadataJson.getInt("max_len")
        PAD_IDX = metadataJson.getInt("pad_idx")
        UNK_IDX = metadataJson.getInt("unk_idx")
        VOCAB_SIZE = metadataJson.getInt("vocab_size")

        // Cargar vocab.json
        val vocabJson = assets.open("ml/vocab.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val char2idxObj = vocabJson.getJSONObject("char2idx")
        val mapa = HashMap<String, Int>(char2idxObj.length())
        val keys = char2idxObj.keys()
        while (keys.hasNext()) {
            val ch = keys.next()
            mapa[ch] = char2idxObj.getInt(ch)
        }
        char2idx = mapa
    }

    /**
     * Normalizar una URL para entrada al modelo quitando protocolo y prefijo ``www.``.
     *
     * Replica 1:1 el ``clean_url`` del dataset de entrenamiento: solo ``trim()``
     * (whitespace externo) + quitar protocolo y ``www.`` — NO aplica ``lowercase()``
     * ni ``trimEnd('/')`` para evitar skew entre training e inference.
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
     * Tokenizar una URL limpia a una secuencia de indices del vocabulario char-level.
     *
     * Cada caracter de [urlLimpia] se mapea a su indice en ``char2idx``. Los
     * caracteres fuera del vocabulario se mapean a [UNK_IDX]. La secuencia se
     * rellena con [PAD_IDX] hasta [MAX_LEN] o se trunca si es mas larga.
     *
     * Requiere que [inicializar] haya sido llamado previamente.
     *
     * @param urlLimpia La salida de [limpiarUrl].
     * @return [IntArray] de longitud [MAX_LEN] con indices de vocabulario + padding.
     */
    fun tokenizar(urlLimpia: String): IntArray {
        val resultado = IntArray(MAX_LEN) { PAD_IDX }
        val vocab = char2idx ?: throw IllegalStateException(
            "Preprocesador no inicializado — llama inicializar(assets) antes de tokenizar"
        )

        var idx = 0
        var i = 0
        while (i < urlLimpia.length && idx < MAX_LEN) {
            val cp = urlLimpia.codePointAt(i)
            // Mapear codepoint a su representacion de string para lookup en char2idx.
            // char2idx usa String de 1 caracter (o "<PAD>"/"<UNK>").
            val key = if (cp <= Char.MAX_VALUE.code) {
                cp.toChar().toString()
            } else {
                String(Character.toChars(cp))
            }
            resultado[idx] = vocab[key] ?: UNK_IDX
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
