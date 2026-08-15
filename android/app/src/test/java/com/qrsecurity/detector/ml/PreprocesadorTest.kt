package com.qrsecurity.detector.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pruebas JVM puras de [Preprocesador] (logica de preprocesamiento de URL
 * para el modelo LSTM char-level TFLite).
 *
 * Cobertura:
 *  - `limpiarUrl`: normalizacion de URL (quitar protocolo, www., trailing /,
 *    lowercase,trim). 12 casos edge.
 *  - `tokenizar`: mapeo de caracteres a indices char2idx (no codepoints),
 *    padding, truncado, caracteres fuera de vocabulario → UNK_IDX.
 *  - `tokenizarLote`: shape `[1][MAX_LEN]`.
 *
 * Usa [Preprocesador.inicializarTest] para inyectar un vocabulario de prueba
 * sin necesidad de AssetManager (tests JVM puros, sin Robolectric).
 *
 * El vocabulario de prueba replica el orden del vocab.json real:
 *  `<PAD>`=0, `<UNK>`=1, luego caracteres por frecuencia descendente.
 */
class PreprocesadorTest {

    // ── Vocabulario de prueba (subset del vocab.json real) ──
    // Orden: <PAD>=0, <UNK>=1, e=2, o=3, a=4, c=5, i=6, .=7, r=8, t=9, s=10, ...
    private val testVocab = mapOf(
        "<PAD>" to 0,
        "<UNK>" to 1,
        "e" to 2,
        "o" to 3,
        "a" to 4,
        "c" to 5,
        "i" to 6,
        "." to 7,
        "r" to 8,
        "t" to 9,
        "s" to 10,
        "n" to 11,
        "l" to 12,
        "m" to 13,
        "d" to 14,
        "p" to 15,
        "/" to 16,
        "-" to 17,
        "u" to 18,
        "b" to 19,
        "g" to 20,
        "h" to 21,
        "f" to 22,
        "x" to 23,
    )

    private val testMaxLen = 100
    private val testPadIdx = 0
    private val testUnkIdx = 1
    private val testVocabSize = testVocab.size

    @Before
    fun setUp() {
        // Resetear estado del singleton para cada test.
        // inicializarTest es idempotente (si ya cargo, no hace nada),
        // asi que forzamos reset() antes de cada test para que cada uno
        // arranque limpio.
        Preprocesador.reset()
        Preprocesador.inicializarTest(
            vocab = testVocab,
            maxLen = testMaxLen,
            padIdx = testPadIdx,
            unkIdx = testUnkIdx,
            vocabSize = testVocabSize
        )
    }

    // ──────────────────────────────────────────────────────────────
    // limpiarUrl
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `limpiarUrl quita https y www`() {
        assertEquals("example.com/path", Preprocesador.limpiarUrl("https://www.example.com/path"))
    }

    @Test
    fun `limpiarUrl quita http sin www`() {
        assertEquals("example.com", Preprocesador.limpiarUrl("http://example.com"))
    }

    @Test
    fun `limpiarUrl quita ftp`() {
        assertEquals("files.example.com/", Preprocesador.limpiarUrl("ftp://files.example.com/"))
    }

    @Test
    fun `limpiarUrl quita ftps`() {
        assertEquals("files.example.com/", Preprocesador.limpiarUrl("ftps://files.example.com/"))
    }

    @Test
    fun `limpiarUrl quita www sin protocolo`() {
        assertEquals("malicious.site/x?a=1", Preprocesador.limpiarUrl("www.malicious.site/x?a=1"))
    }

    @Test
    fun `limpiarUrl sin protocolo ni www preserva`() {
        assertEquals("example.com/no_protocol", Preprocesador.limpiarUrl("example.com/no_protocol"))
    }

    @Test
    fun `limpiarUrl mayusculas no se aplica lowercase - protocolo no se quita`() {
        assertEquals("HTTPS://WWW.EXAMPLE.COM", Preprocesador.limpiarUrl("HTTPS://WWW.EXAMPLE.COM"))
    }

    @Test
    fun `limpiarUrl case mixto no se aplica lowercase - protocolo no se quita`() {
        assertEquals("Https://WwW.Example.Com", Preprocesador.limpiarUrl("Https://WwW.Example.Com"))
    }

    @Test
    fun `limpiarUrl trim espacios alrededor`() {
        assertEquals("example.com", Preprocesador.limpiarUrl("   https://example.com   "))
    }

    @Test
    fun `limpiarUrl preserva trailing slash`() {
        assertEquals("example.com/", Preprocesador.limpiarUrl("https://example.com/"))
    }

    @Test
    fun `limpiarUrl preserva slash interno y trailing`() {
        assertEquals("example.com/path/", Preprocesador.limpiarUrl("https://example.com/path/"))
    }

    @Test
    fun `limpiarUrl string vacio devuelve vacio`() {
        assertEquals("", Preprocesador.limpiarUrl(""))
    }

    @Test
    fun `limpiarUrl solo espacios devuelve vacio`() {
        assertEquals("", Preprocesador.limpiarUrl("   "))
    }

    @Test
    fun `limpiarUrl protocolo desconocido no se quita`() {
        assertEquals("gopher://example.com", Preprocesador.limpiarUrl("gopher://example.com"))
    }

    @Test
    fun `limpiarUrl solo www sin nada mas devuelve vacio`() {
        assertEquals("", Preprocesador.limpiarUrl("www."))
    }

    @Test
    fun `limpiarUrl solo protocolo sin host devuelve vacio`() {
        assertEquals("", Preprocesador.limpiarUrl("https://"))
    }

    // ──────────────────────────────────────────────────────────────
    // tokenizar (char2idx, no codepoints)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `tokenizar URL simple mapea a indices char2idx`() {
        val url = "example.com"
        val tokens = Preprocesador.tokenizar(url)
        // Longitud fija MAX_LEN
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // 'e' → 2 (no codepoint 101)
        assertEquals(2, tokens[0])
        // 'x' → 23 (no codepoint 120)
        assertEquals(23, tokens[1])
        // 'a' → 4 (no codepoint 97)
        assertEquals(4, tokens[2])
    }

    @Test
    fun `tokenizar rellena con PAD_IDX despues de la URL`() {
        val url = "ab"
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // 'a' → 4, 'b' → 19 en nuestro vocab de prueba
        assertEquals(4, tokens[0])
        assertEquals(19, tokens[1])
        // PAD_IDX desde posicion 2 hasta el final
        for (i in 2 until Preprocesador.MAX_LEN) {
            assertEquals(Preprocesador.PAD_IDX, tokens[i])
        }
    }

    @Test
    fun `tokenizar truncada a MAX_LEN si URL excede`() {
        val url = "e".repeat(200)  // 'e' esta en el vocab
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // Todas las posiciones con 'e' → 2 (no padding)
        for (i in 0 until Preprocesador.MAX_LEN) {
            assertEquals(2, tokens[i])
        }
    }

    @Test
    fun `tokenizar string vacio devuelve todo PAD`() {
        val tokens = Preprocesador.tokenizar("")
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        for (i in 0 until Preprocesador.MAX_LEN) {
            assertEquals(Preprocesador.PAD_IDX, tokens[i])
        }
    }

    @Test
    fun `tokenizar caractere fuera de vocab se mapea a UNK_IDX`() {
        // 'z' no esta en nuestro vocab de prueba → UNK_IDX=1
        val url = "eza"
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(2, tokens[0])   // 'e' → 2
        assertEquals(1, tokens[1])   // 'z' → UNK_IDX
        assertEquals(4, tokens[2])   // 'a' → 4
    }

    @Test
    fun `tokenizar caracteres no-ASCII se mapea a UNK_IDX si no estan en vocab`() {
        // 'ñ' (U+00F1) no esta en nuestro vocab de prueba → UNK_IDX
        val url = "españa"
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(2, tokens[0])   // 'e' → 2
        assertEquals(10, tokens[1])  // 's' → 10
        assertEquals(15, tokens[2])  // 'p' → 15
        assertEquals(4, tokens[3])   // 'a' → 4
        assertEquals(1, tokens[4])   // 'ñ' → UNK_IDX
        assertEquals(4, tokens[5])   // 'a' → 4
    }

    // ──────────────────────────────────────────────────────────────
    // tokenizarLote
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `tokenizarLote devuelve array de size 1 con IntArray de MAX_LEN`() {
        val lote = Preprocesador.tokenizarLote("example.com")
        assertEquals(1, lote.size)
        assertEquals(Preprocesador.MAX_LEN, lote[0].size)
    }

    @Test
    fun `tokenizarLote contenido coincide con tokenizar`() {
        val url = "test.com"
        val directo = Preprocesador.tokenizar(url)
        val lote = Preprocesador.tokenizarLote(url)
        // Contenido identico
        for (i in 0 until Preprocesador.MAX_LEN) {
            assertEquals(directo[i], lote[0][i])
        }
    }

    @Test
    fun `tokenizarLote string vacio devuelve lote con todo PAD`() {
        val lote = Preprocesador.tokenizarLote("")
        assertEquals(1, lote.size)
        for (i in 0 until Preprocesador.MAX_LEN) {
            assertEquals(Preprocesador.PAD_IDX, lote[0][i])
        }
    }

    // ──────────────────────────────────────────────────────────────
    // inicializacion
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `estaInicializado es true despues de inicializarTest`() {
        assertTrue(Preprocesador.estaInicializado)
    }

    @Test
    fun `MAX_LEN coincide con el valor inyectado`() {
        assertEquals(testMaxLen, Preprocesador.MAX_LEN)
    }

    @Test
    fun `PAD_IDX coincide con el valor inyectado`() {
        assertEquals(testPadIdx, Preprocesador.PAD_IDX)
    }

    @Test
    fun `UNK_IDX coincide con el valor inyectado`() {
        assertEquals(testUnkIdx, Preprocesador.UNK_IDX)
    }
}
