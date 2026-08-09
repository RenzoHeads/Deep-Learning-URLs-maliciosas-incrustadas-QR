package com.qrsecurity.detector.datos.sync

import com.qrsecurity.detector.datos.sync.DecisionPull.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C3 — TDD red phase segunda.
 *
 * Prueba la logica pura [SyncWorker.decidirResultadoPull] (extraida como
 * funcion pura para no requerir Robolectric /androidx.work):
 *
 *  - 200 (no falla) → no se invoca desde SyncWorker; pero la funcion pura
 *   a股na Success() si codigo == null y retryAfter == null (es decir,
 *    fallback defensivo). El caso "exito" real no pasa nunca por decidir
 *    porque el repositorio ya devolvio Exitoso. Aqui testeamos la rama
 *    codigo exacto:
 *
 *  - 429 con Retry-After=60 → Decision.RETRY con backoff >= 60s.
 *  - 200 (no falla) → Success (caso teorico).
 *  - 401 → Failure (no reintenta — auth).
 *  - 500 → Retry (backoff exponencial server falla).
 *  - IOException pura (codigo=null) → Retry (problema de red transitorio).
 *  - 4xx !=401 (p.ej. 422) → Failure (request malformado).
 *
 * Como `SyncWorker.decidirResultadoPull` es private y vive en una clase
 * worker (necesita Context), extraemos la logica pura a una funcion
 * top-level `decidirResultadoPull(codigo, retryAfterSegundos)` en el
 * archivo SyncWorker.kt. Esta prueba la invoca directamente.
 */
class SyncWorkerRetryTest {

    @Test
    fun `HTTP 429 con Retry-After 60 devuelve Retry con backoff al menos 60s`() {
        // Given: backend responde 429 con Retry-After=60s
        // When
        val decision = decidirResultadoPull(codigo = 429, retryAfterSegundos = 60L)
        // Then
        assertTrue("429 debe ser retry, no failure ni success", decision is Decision.Retry)
        val backoff = (decision as Decision.Retry).backoffSegundos
        assertTrue("Backoff ($backoff) debe respetar Retry-After (>= 60s)", backoff >= 60L)
    }

    @Test
    fun `HTTP 429 sin Retry-After devuelve Retry con backoff exponencial por defecto`() {
        // Given: backend responde 429 sin header Retry-After
        // When
        val decision = decidirResultadoPull(codigo = 429, retryAfterSegundos = null)
        // Then
        assertTrue(decision is Decision.Retry)
        // Sin Retry-After: backoff minimo del worker (>=1s) — solo verificamos Retry.
        assertTrue((decision as Decision.Retry).backoffSegundos >= 1L)
    }

    @Test
    fun `HTTP 200 devuelve Success`() {
        // Given: caso teorico (200 nunca llega a decidirResultadoPull en el flujo real)
        // When
        val decision = decidirResultadoPull(codigo = 200, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Success, decision)
    }

    @Test
    fun `HTTP 401 devuelve Failure`() {
        // Given: auth no recuperable
        // When
        val decision = decidirResultadoPull(codigo = 401, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Failure, decision)
    }

    @Test
    fun `HTTP 403 devuelve Failure`() {
        // Given: auth no recuperable
        // When
        val decision = decidirResultadoPull(codigo = 403, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Failure, decision)
    }

    @Test
    fun `HTTP 500 devuelve Retry`() {
        // Given: error del servidor — transitorio
        // When
        val decision = decidirResultadoPull(codigo = 500, retryAfterSegundos = null)
        // Then
        assertTrue(decision is Decision.Retry)
    }

    @Test
    fun `HTTP 503 devuelve Retry`() {
        // Given: error del servidor transitorio
        // When
        val decision = decidirResultadoPull(codigo = 503, retryAfterSegundos = null)
        // Then
        assertTrue(decision is Decision.Retry)
    }

    // ──────────────────────────────────────────────────────────────
    // WAVE 18 regression: 5xx respeta Retry-After (mismo patron que 429).
    // Antes usaba BACKOFF_MIN_SEGUNDOS_TOTAL fijo (10s) ignorando el header;
    // ahora usa el valor del header si viene (>=10s piso).
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `HTTP 503 con Retry-After 60 respeta el header (backoff 60s, no 10s)`() {
        // When
        val decision = decidirResultadoPull(codigo = 503, retryAfterSegundos = 60L)
        // Then
        assertTrue(decision is Decision.Retry)
        assertEquals(60L, (decision as Decision.Retry).backoffSegundos)
    }

    @Test
    fun `HTTP 503 sin Retry-After usa backoff min por defecto (10s)`() {
        // When
        val decision = decidirResultadoPull(codigo = 503, retryAfterSegundos = null)
        // Then
        assertTrue(decision is Decision.Retry)
        assertEquals(10L, (decision as Decision.Retry).backoffSegundos)
    }

    @Test
    fun `HTTP 422 (4xx no-401-403) devuelve Failure`() {
        // Given: 4xx request malformado — reintentar no ayuda
        // When
        val decision = decidirResultadoPull(codigo = 422, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Failure, decision)
    }

    @Test
    fun `IOException pura (codigo null) devuelve Retry — fallo de red transitorio`() {
        // Given: no HTTP code (UnknownHostException, SocketTimeout, etc.)
        // When
        val decision = decidirResultadoPull(codigo = null, retryAfterSegundos = null)
        // Then
        assertTrue(decision is Decision.Retry)
    }

    // ──────────────────────────────────────────────────────────────
    // Bug G-6 fix regression tests:
    //  2xx no-200 (201/202/204) → Success (no Retry — antes era Retry infinito).
    //  3xx (301/302/304)       → Failure (no Retry — antes era Retry infinito).
    //  Sin estos tests un backend que responda 201 Created a POST /escaneos
    //  dispararia `Result.retry()` infinito en WorkManager. Bug detectado por
    //  el explore agent de cableado backend-frontend.
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `HTTP 201 Created devuelve Success — no debe disparar Retry infinito`() {
        // When
        val decision = decidirResultadoPull(codigo = 201, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Success, decision)
    }

    @Test
    fun `HTTP 202 Accepted devuelve Success — respuesta asincrona valida`() {
        // When
        val decision = decidirResultadoPull(codigo = 202, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Success, decision)
    }

    @Test
    fun `HTTP 204 No Content devuelve Success — respuesta valida sin cuerpo`() {
        // When
        val decision = decidirResultadoPull(codigo = 204, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Success, decision)
    }

    @Test
    fun `HTTP 301 Moved Permanently devuelve Failure — no seguimos redirects`() {
        // Given: redirects no deberian llegar a esta funcion — el OkHttp client
        //        sigue redirects automaticamente. Si uno llega, Failure para
        //        evitar loops infinitos de retry.
        // When
        val decision = decidirResultadoPull(codigo = 301, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Failure, decision)
    }

    @Test
    fun `HTTP 302 Found devuelve Failure — no seguimos redirects`() {
        // When
        val decision = decidirResultadoPull(codigo = 302, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Failure, decision)
    }

    @Test
    fun `HTTP 304 Not Modified devuelve Failure — cache sin cambios, no reintenta`() {
        // When
        val decision = decidirResultadoPull(codigo = 304, retryAfterSegundos = null)
        // Then
        assertEquals(Decision.Failure, decision)
    }
}
