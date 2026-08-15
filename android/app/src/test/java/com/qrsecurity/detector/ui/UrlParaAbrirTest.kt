package com.qrsecurity.detector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Audit fix (esquema mangling): [urlParaAbrir] debe RECHAZAR candidatas que
 * ya contienen "://" con un esquema no permitido (`ftp://x`) — antes se les
 * anteponia `https://` produciendo URLs deformadas (`https://ftp://x`) cuyo
 * scheme parseado era https y se abrian igual.
 *
 * Robolectric: `urlParaAbrir` usa `android.net.Uri.parse`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class UrlParaAbrirTest {

    @Test
    fun `https explicito pasa intacto`() {
        assertEquals(
            "https://example.com/path?q=1",
            urlParaAbrir("https://example.com/path?q=1", "example.com/path?q=1")
        )
    }

    @Test
    fun `http explicito pasa intacto`() {
        assertEquals(
            "http://example.com",
            urlParaAbrir("http://example.com", "http://example.com")
        )
    }

    @Test
    fun `sin esquema se antepone https`() {
        assertEquals(
            "https://example.com/login",
            urlParaAbrir("https://example.com/login", "example.com/login")
        )
    }

    @Test
    fun `prefiere urlLimpia sobre urlOriginal`() {
        assertEquals(
            "https://limpia.com",
            urlParaAbrir("https://original.com", "limpia.com")
        )
    }

    @Test
    fun `urlLimpia vacia cae a urlOriginal`() {
        assertEquals(
            "https://original.com",
            urlParaAbrir("https://original.com", "")
        )
    }

    @Test
    fun `ambas vacias devuelven null`() {
        assertNull(urlParaAbrir("", ""))
        assertNull(urlParaAbrir("   ", "   "))
    }

    @Test
    fun `esquema ftp explicito se RECHAZA (antes se deformaba en https-ftp)`() {
        // Antes: "ftp://files.example.com" → "https://ftp://files.example.com"
        // (scheme=https, se abria igual). Ahora: null.
        assertNull(urlParaAbrir("ftp://files.example.com", "ftp://files.example.com"))
    }

    @Test
    fun `esquemas peligrosos se rechazan`() {
        assertNull(urlParaAbrir("intent://evil.com#Intent;end", "intent://evil.com"))
        assertNull(urlParaAbrir("javascript:alert(1)", "javascript:alert(1)"))
        assertNull(urlParaAbrir("file:///etc/passwd", "file:///etc/passwd"))
        assertNull(urlParaAbrir("content://provider/x", "content://provider/x"))
    }
}
