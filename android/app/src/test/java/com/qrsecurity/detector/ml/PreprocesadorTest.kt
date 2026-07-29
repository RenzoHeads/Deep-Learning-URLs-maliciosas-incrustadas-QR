package com.qrsecurity.detector.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM puras de [Preprocesador] (lógica de preprocesamiento de URL
 * para el modelo CANINE-S TFLite).
 *
 * Cobertura:
 *  - `limpiarUrl`: normalización de URL (quitar protocolo, www., trailing /,
 *    lowercase,trim). 12 casos edge.
 *  - `tokenizar`: mapeo de codepoints Unicode a IntArray de longitud MAX_LEN,
 *    padding, truncado, codepoints suplementarios (emoji, IDN).
 *  - `tokenizarLote`: shape `[1][MAX_LEN]`.
 *
 * Sin Robolectric — `object Preprocesador` solo usa kotlin/jvm stdlib
 * (`String.codePointAt`, `Character.charCount`, `IntArray`) — 100% puro.
 *
 * Cubre bugs:
 *  - H5 (codepoints suplementarios): antes `urlLimpia[i].code` rompia
 *    suplementarios; ahora con `codePointAt + charCount` un emoji produce
 *    1 token, no 2 surrogate tokens.
 *  - NUL collision (codepoint 0): mapeado a PAD_IDX+1 para distinguir de pad.
 */
class PreprocesadorTest {

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
        assertEquals("files.example.com", Preprocesador.limpiarUrl("ftp://files.example.com/"))
    }

    @Test
    fun `limpiarUrl quita ftps`() {
        assertEquals("files.example.com", Preprocesador.limpiarUrl("ftps://files.example.com/"))
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
    fun `limpiarUrl lowercase la entrada`() {
        assertEquals("example.com", Preprocesador.limpiarUrl("HTTPS://WWW.EXAMPLE.COM"))
    }

    @Test
    fun `limpiarUrl lowercase mixto`() {
        assertEquals("example.com", Preprocesador.limpiarUrl("Https://WwW.Example.Com"))
    }

    @Test
    fun `limpiarUrl trim espacios alrededor`() {
        assertEquals("example.com", Preprocesador.limpiarUrl("   https://example.com   "))
    }

    @Test
    fun `limpiarUrl quita trailing slash`() {
        assertEquals("example.com", Preprocesador.limpiarUrl("https://example.com/"))
    }

    @Test
    fun `limpiarUrl preserva slash interno`() {
        assertEquals("example.com/path", Preprocesador.limpiarUrl("https://example.com/path/"))
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
        // gopher:// no esta en PREFIJOS_PROTOCOLO — se conserva.
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
    // tokenizar
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `tokenizar URL simple empieza con codepoints ASCII`() {
        val url = "example.com"
        val tokens = Preprocesador.tokenizar(url)
        // Longitud fija MAX_LEN
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // 'e' = U+0065 = 101
        assertEquals(101, tokens[0])
        // 'x' = U+0078 = 120
        assertEquals(120, tokens[1])
        // 'a' = U+0061 = 97
        assertEquals(97, tokens[2])
    }

    @Test
    fun `tokenizar rellena con PAD_IDX despues de la URL`() {
        val url = "ab"
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // 'a' y 'b' en posiciones 0 y 1
        assertEquals(97, tokens[0])
        assertEquals(98, tokens[1])
        // PAD_IDX desde posicion 2 hasta el final
        for (i in 2 until Preprocesador.MAX_LEN) {
            assertEquals(Preprocesador.PAD_IDX, tokens[i])
        }
    }

    @Test
    fun `tokenizar truncada a MAX_LEN si URL excede`() {
        // URL de length 200 > MAX_LEN=150
        val url = "a".repeat(200)
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // Todas las posiciones con 'a' = 97 (no padding)
        for (i in 0 until Preprocesador.MAX_LEN) {
            assertEquals(97, tokens[i])
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
    fun `tokenizar codepoint NUL se mapea a PAD_IDX mas 1 para distinguir`() {
        // Codepoint 0 (NUL) — improbable en URLs reales pero testeado por el
        // guard del codigo: `if (cp == PAD_IDX) PAD_IDX + 1 else cp`.
        val url = "a\u0000b"
        val tokens = Preprocesador.tokenizar(url)
        // tokens[0]='a'=97, tokens[1]=PAD_IDX+1=1, tokens[2]='b'=98
        assertEquals(97, tokens[0])
        assertEquals(Preprocesador.PAD_IDX + 1, tokens[1])
        assertEquals(98, tokens[2])
    }

    @Test
    fun `tokenizar emoji produce 1 token no 2 surrogates (bug H5 fix)`() {
        // 👍 = U+1F44D, 2 UTF-16 chars (surrogate pair).
        // Antes del fix H5 `urlLimpia[i].code` devolvia 2 tokens surrogate
        // errados; ahora `codePointAt + charCount` produce 1 token correcto.
        val url = "a👍b"  // 4 UTF-16 code units, 3 codepoints
        val tokens = Preprocesador.tokenizar(url)
        // 'a' = 97
        assertEquals(97, tokens[0])
        // 👍 = U+1F44D = 128077
        assertEquals(128077, tokens[1])
        // 'b' = 98 (en posicion 2, NO 3 — charCount avanza 2 positions)
        assertEquals(98, tokens[2])
    }

    @Test
    fun `tokenizar emoji largo no excede MAX_LEN`() {
        // 200 emojis = 400 UTF-16 code units = 200 codepoints > MAX_LEN
        val url = "👍".repeat(200)
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // Todas las posiciones contienen U+1F44D = 128077
        for (i in 0 until Preprocesador.MAX_LEN) {
            assertEquals(128077, tokens[i])
        }
    }

    @Test
    fun `tokenizar URL con IDN latino mezclado se mapea correctamente`() {
        // 'ñ' = U+00F1 = 241 (BMP plan, 1 UTF-16 char).
        val url = "españa.com"
        val tokens = Preprocesador.tokenizar(url)
        assertEquals(Preprocesador.MAX_LEN, tokens.size)
        // 'e' = 101
        assertEquals(101, tokens[0])
        // 's' = 115
        assertEquals(115, tokens[1])
        // 'p' = 112
        assertEquals(112, tokens[2])
        // 'a' = 97
        assertEquals(97, tokens[3])
        // 'ñ' = 241
        assertEquals(241, tokens[4])
        // 'a' = 97
        assertEquals(97, tokens[5])
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
}
