package com.qrsecurity.detector.qr

import com.qrsecurity.detector.qr.ExtractorUrls.Extraido
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests JVM puros (no Robolectric) para los bugs M6 y M7 en [ExtractorUrls].
 *
 * Bug M6: [ExtractorUrls] tenia un short-circuit que trataba cualquier cadena
 * que comenzara con ``"www."`` como dominio valido, incluyendo ``"www."`` puro
 * (sin TLD). El fix apunta a la regex [ExtractorUrls.PATRON_DOMINIO_DIRECTO]
 * que exige al menos un punto con TLD >=2 letras.
 *
 * Bug M7: [ExtractorUrls.esUrlHttpValida] aceptaba ``ftp``/``ftps`` como
 * esquemas validos, pero el modelo CANINE-S se entreno sobre corpus HTTP/HTTPS
 * unicamente. Aceptar FTP introduciria URLs fuera de distribucion en inferencia.
 * El fix restringe el esquema a {http, https}.
 *
 * Los dos metodos sujetos ([ExtractorUrls.pareceDominioDirecto] y
 * [ExtractorUrls.esUrlHttpValida]) son privados, asi que estos tests los
 * ejercen indirectamente a traves de [ExtractorUrls.extraer] observando el
 * tipo del [Extraido] resultado: ``Extraido.Urls`` si la cadena se acepto
 * como URL, ``Extraido.NoUrl`` si fue rechazada.
 */
class ExtractorUrlsEsquemaDominioTest {

    private val extractor = ExtractorUrls()

    // ──────────────────────────────────────────────────────────────
    // Bug M6 — pareceDominioDirecto
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `pareceDominioDirecto rechaza www punto solo cuando no hay TLD`() {
        // GIVEN: la cadena ``"www."`` (pura, sin TLD).
        // WHEN: se analiza el payload QR.
        val resultado = extractor.extraer("www.")

        // THEN: NO debe aceptarse como URL — debe caer en Extraido.NoUrl.
        assertTrue(
            resultado is Extraido.NoUrl,
            "Esperaba NoUrl para 'www.' puro pero se acepto como URL: $resultado"
        )
    }

    @Test
    fun `pareceDominioDirecto acepta example dot com como dominio`() {
        // GIVEN: ``"example.com"`` (un dominio con TLD de 3 letras).
        val resultado = extractor.extraer("example.com")

        // THEN: aceptado como URL. Se le anade ``http://`` implicitamente.
        assertTrue(resultado is Extraido.Urls, "Esperaba Urls para 'example.com': $resultado")
        val urls = resultado.urls
        assertEquals(1, urls.size)
        assertEquals("http://example.com", urls.first())
    }

    @Test
    fun `pareceDominioDirecto acepta subdominios multi-nivel como sub example co uk`() {
        // GIVEN: ``"sub.example.co.uk"`` (varias etiquetas + TLD multi-parte).
        val resultado = extractor.extraer("sub.example.co.uk")

        assertTrue(resultado is Extraido.Urls, "Esperaba Urls para 'sub.example.co.uk': $resultado")
        val urls = resultado.urls
        assertEquals(1, urls.size)
        assertEquals("http://sub.example.co.uk", urls.first())
    }

    // ──────────────────────────────────────────────────────────────
    // Bug M7 — esUrlHttpValida
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `esUrlHttpValida acepta http`() {
        val resultado = extractor.extraer("http://example.com")

        assertTrue(resultado is Extraido.Urls, "Esperaba Urls para http://: $resultado")
    }

    @Test
    fun `esUrlHttpValida acepta https`() {
        val resultado = extractor.extraer("https://example.com")

        assertTrue(resultado is Extraido.Urls, "Esperaba Urls para https://: $resultado")
    }

    @Test
    fun `esUrlHttpValida rechaza ftp como fuera de distribucion`() {
        val resultado = extractor.extraer("ftp://example.com")

        // Bug D4-P4 (Lote H): ftp:// ya no esta en ESQUEMAS_URL (antes si,
        // pero era rechazado por esUrlHttpValida via SCHEMAS_VALIDOS — doble
        // parseo inutil). Ahora ftp:// cae directo a pareceDominioDirecto,
        // que falla la regex PATRON_DOMINIO_DIRECTO (porque contiene "://"
        // y no coincide con el patron de dominio) -> retorna null -> NoUrl.
        assertFalse(
            resultado is Extraido.Urls,
            "ftp:// NO debe aceptarse (CANINE se entreno sobre HTTP/HTTPS): $resultado"
        )
        assertTrue(resultado is Extraido.NoUrl, "Esperaba NoUrl para ftp://: $resultado")
    }

    @Test
    fun `esUrlHttpValida rechaza ftps como fuera de distribucion`() {
        val resultado = extractor.extraer("ftps://example.com")

        // Bug D4-P4 (Lote H): mismo flujo que ftp:// — ftps:// no esta en
        // ESQUEMAS_URL, cae a pareceDominioDirecto, falla la regex -> NoUrl.
        assertFalse(
            resultado is Extraido.Urls,
            "ftps:// NO debe aceptarse (CANINE se entreno sobre HTTP/HTTPS): $resultado"
        )
        assertTrue(resultado is Extraido.NoUrl, "Esperaba NoUrl para ftps://: $resultado")
    }
}
