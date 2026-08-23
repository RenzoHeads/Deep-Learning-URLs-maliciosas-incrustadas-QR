package com.qrsecurity.detector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M7 (auditoría frontend): [resolverUrlParaAbrir] devuelve un sealed
 * [UrlParaAbrir] — los tests migraron 1:1 desde la versión `String?` y
 * ganaron dos casos nuevos: la distinción explícita entre Vacia y
 * EsquemaInvalido (antes ambos eran `null`) y la cobertura de
 * [UrlParaAbrir.mensajeSiInvalida] (única fuente de la copy de error).
 *
 * Audit fix (esquema mangling): candidatas que ya contienen "://" con un
 * esquema no permitido (`ftp://x`) se RECHAZAN — antes se les anteponía
 * `https://` produciendo URLs deformadas (`https://ftp://x`) cuyo scheme
 * parseado era https y se abrían igual.
 *
 * Robolectric: `resolverUrlParaAbrir` usa `android.net.Uri.parse`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class UrlParaAbrirTest {

    private fun valida(urlOriginal: String, urlLimpia: String): String? =
        (resolverUrlParaAbrir(urlOriginal, urlLimpia) as? UrlParaAbrir.Valida)?.url

    @Test
    fun `https explicito pasa intacto`() {
        assertEquals(
            "https://example.com/path?q=1",
            valida("https://example.com/path?q=1", "example.com/path?q=1")
        )
    }

    @Test
    fun `http explicito pasa intacto`() {
        assertEquals(
            "http://example.com",
            valida("http://example.com", "http://example.com")
        )
    }

    @Test
    fun `sin esquema se antepone https`() {
        assertEquals(
            "https://example.com/login",
            valida("https://example.com/login", "example.com/login")
        )
    }

    @Test
    fun `prefiere urlLimpia sobre urlOriginal`() {
        assertEquals(
            "https://limpia.com",
            valida("https://original.com", "limpia.com")
        )
    }

    @Test
    fun `urlLimpia vacia cae a urlOriginal`() {
        assertEquals(
            "https://original.com",
            valida("https://original.com", "")
        )
    }

    @Test
    fun `ambas vacias devuelven Vacia (no EsquemaInvalido)`() {
        assertEquals(UrlParaAbrir.Vacia, resolverUrlParaAbrir("", ""))
        assertEquals(UrlParaAbrir.Vacia, resolverUrlParaAbrir("   ", "   "))
    }

    @Test
    fun `esquema ftp explicito se RECHAZA (antes se deformaba en https-ftp)`() {
        // Antes: "ftp://files.example.com" → "https://ftp://files.example.com"
        // (scheme=https, se abria igual). Ahora: EsquemaInvalido.
        assertEquals(
            UrlParaAbrir.EsquemaInvalido,
            resolverUrlParaAbrir("ftp://files.example.com", "ftp://files.example.com")
        )
    }

    @Test
    fun `esquemas peligrosos se rechazan`() {
        assertEquals(
            UrlParaAbrir.EsquemaInvalido,
            resolverUrlParaAbrir("intent://evil.com#Intent;end", "intent://evil.com")
        )
        assertEquals(
            UrlParaAbrir.EsquemaInvalido,
            resolverUrlParaAbrir("javascript:alert(1)", "javascript:alert(1)")
        )
        assertEquals(
            UrlParaAbrir.EsquemaInvalido,
            resolverUrlParaAbrir("file:///etc/passwd", "file:///etc/passwd")
        )
        assertEquals(
            UrlParaAbrir.EsquemaInvalido,
            resolverUrlParaAbrir("content://provider/x", "content://provider/x")
        )
    }

    // ──────────────────────────────────────────────────────────────
    // mensajeSiInvalida — única fuente de la copy de error (M7)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `mensajeSiInvalida es null solo para Valida`() {
        assertTrue(
            "Valida no genera mensaje de error",
            UrlParaAbrir.Valida("https://x.com").mensajeSiInvalida() == null
        )
    }

    @Test
    fun `mensajeSiInvalida distingue Vacia de EsquemaInvalido`() {
        assertEquals("La URL está vacía", UrlParaAbrir.Vacia.mensajeSiInvalida())
        assertEquals(
            "El enlace no se puede abrir de forma segura",
            UrlParaAbrir.EsquemaInvalido.mensajeSiInvalida()
        )
    }
}
