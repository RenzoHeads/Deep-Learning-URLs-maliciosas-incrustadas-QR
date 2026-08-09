package com.qrsecurity.detector.datos.sync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Regression tests for A4 + C2 audit findings.
 *
 * **A4 — SyncWorkers simultaneos hacen markInProgress sobre mismo op →
 * intentos duplica → fallida prematura.** El MediadorSincronizacion encola
 * el one-shot bajo `NOMBRE_TRABAJO` y el periodic bajo
 * `NOMBRE_TRABAJO + "_periodica"` (nombres distintos en namespaces distintos
 * → pueden correr concurrentemente). Fix: serializar doWork() via un Mutex
 * a nivel companion object — solo un SyncWorker puede procesar a la vez.
 *
 * **C2 — Pending ops marcadas fallida tras 3 reintentos transitorios
 * (~70s) → pérdida silenciosa de datos.** Con backoff exponencial de
 * WorkManager (10s+20s+40s), 3 fallos transitorios consecutivos en ~70s
 * marcan el op como `fallida=1` permanente. Fix: subir el threshold de
 * MAX_INTENTOS_OP de 3 a 10, dando ~43 min de margen acumulado para
 * outages transitorios (10s+20s+...+1280s = 2550s = ~43 min).
 *
 * Estas pruebas ejercen la primitiva [SyncWorker.executionLock] y el
 * helper [withSyncLock] directamente (siguiendo el patron de
 * [SyncWorkerRetryTest] que prueba [decidirResultadoPull] como funcion
 * pura). No requieren instanciar SyncWorker (que necesitaria Hilt + Context).
 */
class SyncWorkerConcurrencyTest {

    @After
    fun cleanup() {
        // Reset lock state between tests — defensivo.
        if (SyncWorker.executionLock.isLocked) {
            SyncWorker.executionLock.unlock()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // A4 fix — Mutex serializa doWork() entre one-shot y periodic workers
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `executionLock tryLock succeeds when free`() {
        // When: lock esta libre
        // Then: tryLock debe retornar true
        assertTrue("tryLock debe retornar true cuando el lock esta libre",
            SyncWorker.executionLock.tryLock())
        SyncWorker.executionLock.unlock()
    }

    @Test
    fun `executionLock tryLock fails when already held — mutual exclusion`() {
        // Given: lock ya adquirido por worker A
        assertTrue(SyncWorker.executionLock.tryLock())

        // When: worker B intenta adquirir
        val segundoIntento = SyncWorker.executionLock.tryLock()

        // Then: debe fallar (exclusion mutua — evita que dos workers
        // procesen pending_ops concurrentemente, lo que duplicaria
        // intentos y dispararia marcarFallida prematura).
        assertFalse(
            "Segundo tryLock debe retornar false ( Mutex entrega exclusion mutua)",
            segundoIntento
        )

        // Cleanup
        SyncWorker.executionLock.unlock()
    }

    @Test
    fun `executionLock tryLock succeeds after release`() {
        // Given: lock adquirido y liberado
        assertTrue(SyncWorker.executionLock.tryLock())
        SyncWorker.executionLock.unlock()

        // When: tercer intento
        val tercerIntento = SyncWorker.executionLock.tryLock()

        // Then: debe exitir (unlock libera correctamente)
        assertTrue(
            "tryLock debe exitir despues de unlock (release restaura disponibilidad)",
            tercerIntento
        )
        SyncWorker.executionLock.unlock()
    }

    // ══════════════════════════════════════════════════════════════════
    // A4 fix — withSyncLock helper retorna null (skip) cuando lock ocupado
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `withSyncLock ejecuta block cuando lock libre`() = runBlocking {
        // When: lock libre, llamamos withSyncLock
        val resultado = withSyncLock { 42 }

        // Then: block se ejecuta y retorna su valor
        assertEquals(42, resultado)
    }

    @Test
    fun `withSyncLock retorna null cuando lock ya held — segundo worker se skip`() = runBlocking {
        // Given: lock ya adquirido por worker A (simulado con tryLock directo)
        assertTrue(SyncWorker.executionLock.tryLock())

        // When: worker B (segundo) llama withSyncLock
        val resultadoSegundo = withSyncLock { 99 }

        // Then: helper retorna null (senal de skip), no ejecuta el block
        assertNull(
            "withSyncLock debe retornar null cuando lock esta held — el segundo worker se salta sin ejecutar el block",
            resultadoSegundo
        )

        // Cleanup
        SyncWorker.executionLock.unlock()
    }

    @Test
    fun `withSyncLock libera el lock tras ejecutar block`() = runBlocking {
        // When: llamamos withSyncLock
        withSyncLock { "cualquier cosa" }

        // Then: lock debe quedar libre tras el block (reentrante)
        assertTrue(
            "withSyncLock debe liberar el lock tras el block — un segundo call debe poder adquirirlo",
            SyncWorker.executionLock.tryLock()
        )
        SyncWorker.executionLock.unlock()
    }

    // ══════════════════════════════════════════════════════════════════
    // C2 fix — MAX_INTENTOS_OP subido a 10 para tolerar outages transitorios
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `MAX_INTENTOS_OP threshold es al menos 10 para sobrevivir fallos transitorios`() {
        // Bug C2 (audit): ~70s de flaky network mataba el op con MAX=3.
        // Con backoff exponencial de WorkManager 10s+20s+40s = 70s = 3 retries,
        // un brief outage de red dispara marcarFallida permanente.
        //
        // Threshold >= 10 da ~43 min de margen (10s+20s+...+1280s = 2550s)
        // para que un outage de red se recupere sin perder datos.
        assertTrue(
            "MAX_INTENTOS_OP debe ser >= 10 (actual=${SyncWorker.MAX_INTENTOS_OP}) — bug C2 fix",
            SyncWorker.MAX_INTENTOS_OP >= 10
        )
    }

    @Test
    fun `MAX_INTENTOS_OP muestra valor configurado para documentar el threshold`() {
        // Test de documentacion: el valor exacto del threshold se hardcodea en el
        // test para forzar revision explicita si alguien lo baja en el futuro.
        assertEquals(
            "MAX_INTENTOS_OP = 10 (subido de 3 a 10 por audit C2 fix)",
            10,
            SyncWorker.MAX_INTENTOS_OP
        )
    }
}
