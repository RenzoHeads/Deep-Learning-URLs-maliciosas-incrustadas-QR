package com.qrsecurity.detector.cache

import com.qrsecurity.detector.cache.CacheResultados.EntradaCache
import com.qrsecurity.detector.ml.ControladorAlerta
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests para M-13 (no sostener el lock durante [calcular]): al invocar
 * [CacheResultados.obtenerOActualizar] en paralelo, la factory [calcular] debe
 * ejecutarse fuera del candado para no serializar corutinas/hilos durante la
 * inferencia TFLite (50-200 ms).
 *
 * Estrategia determinista:
 *  - El hilo A entra a [CacheResultados.obtenerOActualizar] con cache miss;
 *    su primera seccion critica termina, libera el candado y bloquea dentro de
 *    [calcular] en un latch. Esto demuestra que [calcular] corre FUERA del candado.
 *  - Mientras el hilo A esta dentro de [calcular], el hilo principal invoca
 *    [CacheResultados.contiene] y [CacheResultados.tamano]: si el candado
 *    estuviera tomado durante [calcular] (bug M-13), estas llamadas se
 *    bloquearian (timeout real). Como no se sostiene, completan de inmediato.
 *  - El hilo principal inserta via [CacheResultados.poner] una entrada existente.
 *  - Se libera el latch del hilo A; su [calcular] retorna un valor candidato
 *    nuevo (perdedor). El segundo check del hilo A encuentra la entrada
 *    existente (winner) y la devuelve, descartando su propio candidato.
 *  - Aserciones: [calcular] invocada exactamente una vez; el hilo A devuelve
 *    la instancia insertada por el hilo principal; el cache queda con una
 *    sola entrada.
 *
 * Tests JVM puros (no Robolectric).
 */
class ResultCacheNoLockDuringCalcTest {

    /**
     * Mock contador y bloqueante. Cuenta invocaciones y bloquea a [await] del
     * latch [permitirTerminarLatch]; al entrar, libera [primerCalcularEntroLatch]
     * para que el test sepa que ya salio del primer candado.
     */
    private class CalcularContadoBloqueante(
        private val primerCalcularEntroLatch: CountDownLatch,
        private val permitirTerminarLatch: CountDownLatch
    ) : () -> EntradaCache {
        val invocaciones = AtomicInteger(0)

        override fun invoke(): EntradaCache {
            invocaciones.incrementAndGet()
            // Senala al test: la primera invocation->[calcular] ya esta corriendo,
            // lo que implica que salio del primer candado de obtenerOActualizar (M-13).
            primerCalcularEntroLatch.countDown()
            try {
                // Bloquea hasta que el test permita terminar. Si el candado se
                // sostuviera dentro de [calcular] (bug M-13), los lectores externos
                // ([contiene], [tamano]) quedarian bloqueados mientras este await
                // duerme -> el test fallaria por timeout antes de llegar aqui.
                assertTrue(
                    permitirTerminarLatch.await(5, TimeUnit.SECONDS),
                    "Timeout esperando seal para terminar calcular() (hilo A)"
                )
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AssertionError("Hilo interrumpido durante await en mock", e)
            }
            // Esta entrada NO debe insertarse en el cache: el segundo check del hilo A
            // debe encontrar la entrada existente (insertada por el test) y descartar
            // este candidato perdedor (winner-takes-all).
            return EntradaCache(
                url = "https://x.test",
                probabilidad = 0.42f,
                nivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    @Test
    fun `obtenerOActualizar no sostiene el candado durante calcular y el double-check descarta el computo perdedor`() {
        // GIVEN: cache vacio y mock que bloquea la primera invocation de calcular().
        val cache = CacheResultados(maxEntradas = 10)
        val urlX = "https://url-concurrente.test"
        val primerCalcularEntroLatch = CountDownLatch(1)
        val permitirTerminarLatch = CountDownLatch(1)
        val mock = CalcularContadoBloqueante(primerCalcularEntroLatch, permitirTerminarLatch)

        val resultadoHiloA = arrayOfNulls<EntradaCache>(1)
        val hiloATermino = CountDownLatch(1)
        val hiloA = Thread {
            resultadoHiloA[0] = cache.obtenerOActualizar(urlX, calcular = mock)
            hiloATermino.countDown()
        }
        hiloA.isDaemon = true

        assertEquals(0, cache.tamano(), "Cache debe iniciar vacio")

        // WHEN: el hilo A lanza obtenerOActualizar; la primera seccion critica
        // observa un miss, libera el candado y llama a calcular() (hilo A duerme ahi).
        hiloA.start()

        // Espera: el hilo A ya paso la primera seccion critica y esta dentro de calcular().
        assertTrue(
            primerCalcularEntroLatch.await(5, TimeUnit.SECONDS),
            "El hilo A nunca entro a calcular()"
        )

        // Asercion M-13 (empirica): mientras el hilo A esta dentro de calcular(),
        // llamadas externas a contiene()/tamano() deben completar SIN bloquearse.
        // Si el candado se sostuviera dentro de calcular (bug M-13), esta llamada
        // se bloquearia por 5+ segundos. Completar en milisegundos es la prueba.
        val entroSinBloqueo = try {
            cache.contiene(urlX)
            true
        } catch (e: Exception) {
            false
        }
        assertTrue(
            entroSinBloqueo,
            "contiene() debe completar mientras calcular() corre; si el candado " +
                "se sostiene dentro de calcular (bug M-13), esta llamada se bloquearia"
        )
        assertEquals(false, cache.contiene(urlX), "Cache debe estar vacio mientras hilo A esta dentro de calcular()")

        // Insertamos via poner() la entrada existente. Mientras, el hilo A sigue
        // dentro de calcular(). Si el candado se sostuviera, poner() tambien se bloquearia -> timeout.
        val entradaExistente = EntradaCache(
            url = urlX,
            probabilidad = 0.95f,
            nivelAlerta = ControladorAlerta.NivelAlerta.MALICIOSO,
            timestampMs = System.currentTimeMillis()
        )
        cache.poner(urlX, entradaExistente)
        assertEquals(1, cache.tamano(), "poner() debe completar y dejar exactamente una entrada")

        // Liberal el mock del hilo A: el hilo A sale de calcular, entra al segundo
        // check, encuentra `entradaExistente`, la devuelve y descarta su propia entrada.
        permitirTerminarLatch.countDown()

        assertTrue(
            hiloATermino.await(5, TimeUnit.SECONDS),
            "El hilo A nunca termino obtenerOActualizar"
        )

        // THEN:
        // 1) calcular() se invoco exactamente una vez; el double-check descart la
        //    entrada perdedora sin necesidad de un segundo computo.
        assertEquals(
            1, mock.invocaciones.get(),
            "calcular() debe invocarse exactamente una vez; el double-check descarta la entrada perdedora"
        )

        // 2) El hilo A devuelve exactamente la instancia insertada por el hilo
        //    principal (winner-takes-all), no su propio candidato de calcular().
        assertEquals(
            entradaExistente, resultadoHiloA[0],
            "obtenerOActualizar debe devolver la entrada existente (winner), no la recien computada"
        )

        // 3) El cache tiene exactamente una entrada; el candidato perdedor de
        //    calcular() NO debe haberse insertado.
        assertEquals(1, cache.tamano(), "Debe quedar una sola entrada en cache")
        assertEquals(
            entradaExistente, cache.obtener(urlX),
            "La entrada cacheada debe seguir siendo la del ganador (no la de calcular)"
        )
    }
}
