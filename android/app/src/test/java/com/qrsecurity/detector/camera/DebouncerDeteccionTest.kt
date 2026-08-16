package com.qrsecurity.detector.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests unitarios JVM de [DebouncerDeteccion] — gate + debounce atomico
 * sin dependencias de Android (solo `java.util.concurrent.atomic.AtomicLong`).
 *
 * Estrategia:
 *  - **Reloj mockeable** (`var tiempoActual` + lambda `{ tiempoActual }`) para
 *    progresar el tiempo controladamente sin `Thread.sleep`.
 *  - **Concurrencia real** para el test de atomicidad: un `Executors.FixedThreadPool`
 *    con un `CountDownLatch` como barrera de inicio garantiza que N threads
 *    converjan sobre `getAndUpdate` exactamente al mismo tiempo — sin eso el
 *    test solo probaraía la single-thread safety (`.launch` sobre `runBlocking`
 *    es single-threaded, no_proof).
 *
 * Ver el KDoc de [DebouncerDeteccion] para el historial completo de bugs que
 * motivaron cada mecanismo (gate, debounce, reset a "ahora" en reanudar).
 */
class DebouncerDeteccionTest {

    // ── Gate (pausar / reanudar) ──

    @Test
    fun `pausar desactiva la deteccion y debeAceptar retorna false incluso con timestamp futuro`() {
        var tiempoActual = 10_000L
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { tiempoActual })
        assertTrue("La deteccion arranca activa", debouncer.deteccionActiva)

        debouncer.pausar()
        assertFalse("Tras pausar, deteccionActiva=false", debouncer.deteccionActiva)

        // Aunque el timestamp avance al futuro, el gate bloquea.
        tiempoActual = 100_000L
        assertFalse(
            "El gate bloquea debeAceptar aunque el timestamp este muy en el futuro",
            debouncer.debeAceptar(100_000L)
        )
    }

    @Test
    fun `reanudar reactiva la deteccion y siembra el timestamp actual`() {
        var tiempoActual = 5_000L
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { tiempoActual })

        debouncer.pausar()
        assertFalse(debouncer.deteccionActiva)

        // Reanudar consume reloj(): ultimoTimestampAceptado = 50_000.
        tiempoActual = 50_000L
        debouncer.reanudar()
        assertTrue(debouncer.deteccionActiva)

        // Justo despues de reanudar (sin avanzar el reloj): un timestamp
        // `reloj() + debounceMs` acepta (delta >= debounceMs).
        tiempoActual = 50_500L
        assertTrue(
            "debeAceptar con ts = relojSiembra + 1500 (>= debounceMs=1000) acepta",
            debouncer.debeAceptar(51_500L)
        )
    }

    @Test
    fun `debeAceptar rechaza justo debajo del umbral (semilla + debounceMs - 1) tras reanudar`() {
        var tiempoActual = 50_000L
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { tiempoActual })
        debouncer.reanudar() // siembra 50_000

        // ts = 50_999 → 50_999 - 50_000 = 999 < 1000 → rechaza.
        assertFalse(debouncer.debeAceptar(50_999L))
    }

    @Test
    fun `reanudar sin pausa previa es idempotente (deteccionActiva sigue true y siembra el ts actual)`() {
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { 100L })
        assertTrue(debouncer.deteccionActiva)

        debouncer.reanudar()
        assertTrue("Reanudar sin pausa previa no rompe el estado", debouncer.deteccionActiva)

        // El primer debeAceptar tras reanudar requiere ts - 100 >= 1000 → ts >= 1100.
        assertTrue(debouncer.debeAceptar(1100L))
    }

    // ── Secuencia normal de debounce ──

    @Test
    fun `secuencia normal - t1 acepta, t1+1 rechaza, t1+debounceMs acepta, t1+debounceMs-1 rechaza`() {
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { 0L })

        // Estado inicial: ultimoTimestamp = 0 — cualquier ts >= 1000 acepta.
        assertTrue("t1 = 2000 → 2000 - 0 = 2000 >= 1000 → acepta", debouncer.debeAceptar(2000L))

        // 1 ms despues → rechaza (delta < debounceMs).
        assertFalse("t2 = 2001 → 2001 - 2000 = 1 < 1000 → rechaza", debouncer.debeAceptar(2001L))

        // Exactamente t1 + debounceMs → acepta (delta == debounceMs).
        assertTrue("t3 = 3000 → 3000 - 2000 = 1000 >= 1000 → acepta", debouncer.debeAceptar(3000L))

        // 1 ms antes del siguiente umbral → rechaza.
        assertFalse("t4 = 2999 → 2999 - 3000 = -1 < 1000 → rechaza", debouncer.debeAceptar(2999L))
    }

    @Test
    fun `primer debeAceptar con ts menor que debounceMs rechaza (ultimoTimestamp arranca en 0)`() {
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { 0L })
        // ts = 500 → 500 - 0 = 500 < 1000 → rechaza.
        assertFalse(debouncer.debeAceptar(500L))
    }

    @Test
    fun `debeAceptar en borde exacto - ts igual a debounceMs es aceptado (delta == debounceMs)`() {
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { 0L })
        assertTrue("ts = 1000 → 1000 - 0 = 1000 >= 1000 → acepta (borde)", debouncer.debeAceptar(1000L))
    }

    // ── Atomicidad (concurrencia real) ──

    /**
     * Race real con N threads concurrentes (no simulada con runBlocking que es
     * single-threaded). Usamos un FixedThreadPool + `CountDownLatch` como
     * barrera de inicio para maximizar la contencion sobre
     * `AtomicLong.getAndUpdate`. Garantia a probar: exactamente UNO de los N
     * threads acepta el mismo timestamp; los demas ven `prev` ya actualizado a
     * `ts` y rechazan (delta = 0 < debounceMs).
     *
     * Sin atomicidad (`@Volatile` + read-modify-write no atomico), multiples
     * threads verian `prev` antes de la actualizacion y aceptarian >1 → bug
     * multi-deteccion del mismo frame.
     */
    @Test
    fun `atomico - varios debeAceptar concurrentes con mismo timestamp aceptan exactamente uno`() {
        val n = 16
        val tsUnico = 12345L
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { tsUnico })

        val executor = Executors.newFixedThreadPool(n)
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(n)
        val aceptados = AtomicInteger(0)
        try {
            repeat(n) {
                executor.submit {
                    startGate.await()
                    if (debouncer.debeAceptar(tsUnico)) {
                        aceptados.incrementAndGet()
                    }
                    doneLatch.countDown()
                }
            }
            startGate.countDown()
            doneLatch.await()
        } finally {
            executor.shutdownNow()
        }

        assertEquals(
            "Exactamente un thread debe aceptar el mismo timestamp (atomico)",
            1,
            aceptados.get()
        )
    }

    /**
     * Mix de concurrencia: N/2 threads con ts=T1 y N/2 con ts=T1+debounceMs.
     * Determinista (no depende del scheduler):
     *  - Uno de los threads con ts=T1 acepta (ultimoTimestamp era 0 → delta >= debMs).
     *  - Despues de eso ultimoTimestamp = T1.
     *  - Los demas T1 ven `prev = T1` → delta = 0 < debMs → rechazan.
     *  - Uno de los threads con ts=T1+debMs acepta (delta = debMs >= debMs).
     *  - Despues ultimoTimestamp = T1+debMs.
     *  - Los demas T1+debMs ven `prev = T1+debMs` → delta = 0 → rechazan.
     *  Total: 2 aceptados, sin importar el orden de llegada al latch.
     *
     *  Existe el caso degenerado en que los T1+debMs ganan primero:
     *   - Uno acepta (ultimoTimestamp = T1+debMs), los demas T1+debMs rechazan.
     *   - Los T1 ven `prev = T1+debMs` → delta = -debMs < debMs → rechazan TODOS.
     *   Total: 1 aceptado (no determinista).
     *
     *  Para evitar esa dependencia de scheduling, este test separa las dos
     *  fases en barreras secuenciales (primero todos los T1, despues todos
     *  los T1+debMs) — aislamiento determinista.
     */
    @Test
    fun `concurrencia por fases - fase 1 acepta 1, fase 2 acepta 1 (determinista con barreras secuenciales)`() {
        val t1 = 1000L
        val t2 = 2000L // t1 + 1000 = t1 + debounceMs
        val n = 8
        val debouncer = DebouncerDeteccion(debounceMs = 1000L, reloj = { 0L })
        val executor = Executors.newFixedThreadPool(n)
        try {
            val aceptadosFase1 = raceConcurrente(executor, n, t1, debouncer)
            val aceptadosFase2 = raceConcurrente(executor, n, t2, debouncer)
            assertEquals("Fase 1: exactamente 1 acepta", 1, aceptadosFase1)
            assertEquals("Fase 2: exactamente 1 acepta (delta = debounceMs)", 1, aceptadosFase2)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Helper: lanza N threads que llaman `debeAceptar(ts)` concurrently usando
     * un latch de inicio (todos empiezan a la vez) y un latch de fin
     * (esperamos a que terminen). Devuelve el numero de threads que aceptaron.
     */
    private fun raceConcurrente(
        executor: java.util.concurrent.ExecutorService,
        n: Int,
        ts: Long,
        debouncer: DebouncerDeteccion
    ): Int {
        val startGate = CountDownLatch(1)
        val doneLatch = CountDownLatch(n)
        val aceptados = AtomicInteger(0)
        repeat(n) {
            executor.submit {
                startGate.await()
                if (debouncer.debeAceptar(ts)) {
                    aceptados.incrementAndGet()
                }
                doneLatch.countDown()
            }
        }
        startGate.countDown()
        doneLatch.await()
        return aceptados.get()
    }
}
