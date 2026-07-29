package com.qrsecurity.detector.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3 — TDD red phase primera.
 *
 * Verifica que [ClienteBackend.HttpBackendException] expone el codigo HTTP como
 * una propiedad `codigo: Int` (NO parseada de `Exception.message`), y que soporta
 * un `retryAfterSegundos` opcional cuando el backend manda header `Retry-After`
 * (HTTP 429 rate-limiting). Tambien verifica que el [okhttp3.CertificatePinner]
 * placeholder esta presente en el cliente (sin pin todavia).
 */
class ClienteBackendCodigoTest {

    @Test
    fun `HttpBackendException con codigo 429 expone codigo como Int property`() {
        // Given / When
        val ex = ClienteBackend.HttpBackendException(
            codigo = 429,
            mensaje = "rate limit"
        )
        // Then
        assertEquals(429, ex.codigo)
        // `mensaje` se propaga al message de IOException como "$codigo: $mensaje".
        assertEquals("429: rate limit", ex.message)
        assertNull(ex.cuerpo)
    }

    @Test
    fun `HttpBackendException con codigo 500 expone codigo y cuerpo`() {
        // Given / When
        val ex = ClienteBackend.HttpBackendException(
            codigo = 500,
            mensaje = "Internal Server Error",
            cuerpo = "{\"detail\":\"boom\"}"
        )
        // Then
        assertEquals(500, ex.codigo)
        assertEquals("500: Internal Server Error", ex.message)
        assertEquals("{\"detail\":\"boom\"}", ex.cuerpo)
    }

    @Test
    fun `HttpBackendException captura Retry-After cuando el backend lo envia`() {
        // Given: una excepcion 429 con Retry-After=60
        // When
        val ex = ClienteBackend.HttpBackendException(
            codigo = 429,
            mensaje = "Too Many Requests",
            retryAfterSegundos = 60L
        )
        // Then: el backoff respetado por el SyncWorker debe poder leerlo
        assertEquals(429, ex.codigo)
        // Nota: antes habia un assertEquals(Long::class.java, ex.retryAfterSegundos?.javaClass)
        // que era overspecified y fallaba por la diferencia entre long.class
        // (Long::class.java en JVM) y java.lang.Long (boxed en nullable context).
        // El assertEquals(60L, ex.retryAfterSegundos) debajo cubre el contrato.
        assertEquals(60L, ex.retryAfterSegundos)
    }

    @Test
    fun `HttpBackendException sin Retry-After deja retryAfterSegundos en null`() {
        // Given: una excepcion 5xx sin Retry-After
        // When
        val ex = ClienteBackend.HttpBackendException(
            codigo = 503,
            mensaje = "Service Unavailable"
        )
        // Then
        assertEquals(503, ex.codigo)
        assertNull(ex.retryAfterSegundos)
    }
}
