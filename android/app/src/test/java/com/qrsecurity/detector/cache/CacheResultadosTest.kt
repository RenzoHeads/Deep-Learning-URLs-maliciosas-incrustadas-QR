package com.qrsecurity.detector.cache

import com.qrsecurity.detector.ml.ControladorAlerta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas JVM puras de [CacheResultados] — LRU eviction, access-order refresh,
 * TTL, y atomicidad get-or-put.
 *
 * Sin Robolectric — solo usa LinkedHashMap, ReentrantLock, y
 * System.currentTimeMillis — 100% puro.
 *
 * Cubre branches principales:
 *  - LRU eviction al superar [maxEntradas] (eldest entry expulsada).
 *  - Access-order refresh: acceso a entrada la mueve al final (mas reciente).
 *  - [obtener] cache hit despues de acceso refresh.
 *  - [obtener] cache miss devuelve null.
 *  - [poner] actualiza entrada existente (no crea duplicado).
 *  - [obtenerOActualizar] cache hit no invoca factory.
 *  - [obtenerOActualizar] cache miss invoca factory una vez.
 *  - [obtenerOActualizar] winner-takes-all (factory descartada si hay entrada).
 *  - [contiene] false/true antes/despues de [poner].
 *  - [limpiar] vacia el cache.
 *  - [tamano] refleja inserciones y evictions.
 *  - [instantanea] ordenada por acceso mas reciente primero.
 *  - TTL: entrada con timestamp antiguo expirada por [obtener].
 *  - TTL: entrada con timestamp antiguo expirada por [obtenerOActualizar] re-computa.
 */
class CacheResultadosTest {

    private fun entrada(url: String, prob: Float = 0.5f, ts: Long = System.currentTimeMillis()) =
        CacheResultados.EntradaCache(
            url = url,
            probabilidad = prob,
            nivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
            timestampMs = ts,
            delegado = "CPU"
        )

    @After
    fun tearDown() {
        ControladorAlerta.reset()
    }

    // ──────────────────────────────────────────────────────────────
    // LRU eviction basica
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `cache con maxEntradas 3 expulsa la entrada mas antigua al insertar la cuarta`() {
        val cache = CacheResultados(maxEntradas = 3)
        cache.poner("url1", entrada("url1"))
        cache.poner("url2", entrada("url2"))
        cache.poner("url3", entrada("url3"))
        assertEquals(3, cache.tamano())
        // Insertar cuarta → eldest (url1) expulsada
        cache.poner("url4", entrada("url4"))
        assertEquals(3, cache.tamano())
        assertFalse("url1 debio ser expulsada por LRU", cache.contiene("url1"))
        assertTrue("url4 debe estar presente", cache.contiene("url4"))
    }

    @Test
    fun `cache con maxEntradas 1 solo retiene la ultima entrada`() {
        val cache = CacheResultados(maxEntradas = 1)
        cache.poner("url1", entrada("url1"))
        cache.poner("url2", entrada("url2"))
        assertEquals(1, cache.tamano())
        assertNull(cache.obtener("url1"))
        assertNotNull(cache.obtener("url2"))
    }

    @Test
    fun `cache con maxEntradas por defecto 50 retiene 50 entradas`() {
        val cache = CacheResultados()
        for (i in 1..50) {
            cache.poner("url$i", entrada("url$i"))
        }
        assertEquals(50, cache.tamano())
        // Insertar 51 → eldest expulsada
        cache.poner("url51", entrada("url51"))
        assertEquals(50, cache.tamano())
        assertFalse(cache.contiene("url1"))
        assertTrue(cache.contiene("url51"))
    }

    // ──────────────────────────────────────────────────────────────
    // Access-order refresh
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `acceso a entrada la mueve al final — no es expulsada en proxima insercion`() {
        val cache = CacheResultados(maxEntradas = 3)
        cache.poner("url1", entrada("url1"))
        cache.poner("url2", entrada("url2"))
        cache.poner("url3", entrada("url3"))
        // Acceder a url1 → la mueve a mas reciente
        cache.obtener("url1")
        // Insertar url4 → eldest ahora es url2 (no url1, que fue accedida)
        cache.poner("url4", entrada("url4"))
        assertTrue("url1 debe sobrevivir porque fue accedida", cache.contiene("url1"))
        assertFalse("url2 debio ser expulsada como eldest", cache.contiene("url2"))
    }

    @Test
    fun `contiene NO refresca access-order — containsKey no actualiza orden LRU`() {
        // Documenta que `contiene` usa `containsKey` que en LinkedHashMap con
        // accessOrder=true NO actualiza el orden de acceso (solo `get` lo hace).
        val cache = CacheResultados(maxEntradas = 3)
        cache.poner("url1", entrada("url1"))
        cache.poner("url2", entrada("url2"))
        cache.poner("url3", entrada("url3"))
        // containsKey NO refresca orden → url1 sigue siendo eldest
        cache.contiene("url1")
        cache.poner("url4", entrada("url4"))
        assertFalse("url1 no refrescada por contiene — sigue siendo eldest",
            cache.contiene("url1"))
    }

    // ──────────────────────────────────────────────────────────────
    // obtener — hit / miss
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `obtener cache hit devuelve la entrada`() {
        val cache = CacheResultados()
        cache.poner("url1", entrada("url1", prob = 0.9f))
        val resultado = cache.obtener("url1")
        assertNotNull(resultado)
        assertEquals("url1", resultado!!.url)
        assertEquals(0.9f, resultado.probabilidad, 0.001f)
    }

    @Test
    fun `obtener cache miss devuelve null`() {
        val cache = CacheResultados()
        assertNull(cache.obtener("url-inexistente"))
    }

    // ──────────────────────────────────────────────────────────────
    // poner — update
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `poner sobre entrada existente actualiza el valor sin crear duplicado`() {
        val cache = CacheResultados()
        cache.poner("url1", entrada("url1", prob = 0.3f))
        cache.poner("url1", entrada("url1", prob = 0.8f))
        assertEquals(1, cache.tamano())
        val resultado = cache.obtener("url1")
        assertEquals(0.8f, resultado!!.probabilidad, 0.001f)
    }

    // ──────────────────────────────────────────────────────────────
    // obtenerOActualizar — hit / miss
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `obtenerOActualizar cache hit no invoca factory`() {
        val cache = CacheResultados()
        cache.poner("url1", entrada("url1", prob = 0.5f))
        var factoryInvocada = false
        val resultado = cache.obtenerOActualizar("url1") {
            factoryInvocada = true
            entrada("url1", prob = 0.99f)
        }
        assertFalse("factory no debe invocarse en cache hit", factoryInvocada)
        assertEquals(0.5f, resultado.probabilidad, 0.001f)
    }

    @Test
    fun `obtenerOActualizar cache miss invoca factory y devuelve nueva entrada`() {
        val cache = CacheResultados()
        var factoryInvocada = false
        val resultado = cache.obtenerOActualizar("url1") {
            factoryInvocada = true
            entrada("url1", prob = 0.7f)
        }
        assertTrue("factory debe invocarse en cache miss", factoryInvocada)
        assertEquals(0.7f, resultado.probabilidad, 0.001f)
        assertEquals(1, cache.tamano())
    }

    @Test
    fun `obtenerOActualizar segunda llamada sobre misma URL devuelve entrada cacheada`() {
        val cache = CacheResultados()
        var contadorFactory = 0
        // Primera llamada: miss → factory
        cache.obtenerOActualizar("url1") {
            contadorFactory++
            entrada("url1", prob = 0.5f)
        }
        // Segunda llamada: hit → no factory
        cache.obtenerOActualizar("url1") {
            contadorFactory++
            entrada("url1", prob = 0.99f)
        }
        assertEquals("factory debe invocarse solo una vez", 1, contadorFactory)
    }

    // ──────────────────────────────────────────────────────────────
    // contiene / limpiar / tamano
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `contiene devuelve false antes de poner`() {
        val cache = CacheResultados()
        assertFalse(cache.contiene("url1"))
    }

    @Test
    fun `contiene devuelve true despues de poner`() {
        val cache = CacheResultados()
        cache.poner("url1", entrada("url1"))
        assertTrue(cache.contiene("url1"))
    }

    @Test
    fun `limpiar vacia el cache`() {
        val cache = CacheResultados()
        cache.poner("url1", entrada("url1"))
        cache.poner("url2", entrada("url2"))
        assertEquals(2, cache.tamano())
        cache.limpiar()
        assertEquals(0, cache.tamano())
        assertFalse(cache.contiene("url1"))
        assertFalse(cache.contiene("url2"))
    }

    @Test
    fun `tamano refleja inserciones y evictions`() {
        val cache = CacheResultados(maxEntradas = 2)
        cache.poner("url1", entrada("url1"))
        assertEquals(1, cache.tamano())
        cache.poner("url2", entrada("url2"))
        assertEquals(2, cache.tamano())
        cache.poner("url3", entrada("url3"))
        assertEquals(2, cache.tamano()) // url1 expulsada
        assertFalse(cache.contiene("url1"))
    }

    // ──────────────────────────────────────────────────────────────
    // instantanea — orden por acceso mas reciente primero
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `instantanea devuelve lista ordenada por acceso mas reciente primero`() {
        val cache = CacheResultados(maxEntradas = 5)
        cache.poner("url1", entrada("url1"))
        cache.poner("url2", entrada("url2"))
        cache.poner("url3", entrada("url3"))
        // Acceder a url1 → ahora es mas reciente
        cache.obtener("url1")
        val snapshot = cache.instantanea()
        assertEquals(3, snapshot.size)
        // reversed(): url1 fue accedida ultima → aparece primero
        assertEquals("url1", snapshot[0].url)
        assertEquals("url3", snapshot[1].url)
        assertEquals("url2", snapshot[2].url)
    }

    @Test
    fun `instantanea de cache vacio devuelve lista vacia`() {
        val cache = CacheResultados()
        assertEquals(0, cache.instantanea().size)
    }

    // ──────────────────────────────────────────────────────────────
    // TTL — expiracion de entradas obsoletas
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `obtener expulsa entrada con timestamp antiguo — TTL expirada`() {
        val cache = CacheResultados()
        // Insertar entrada con timestamp de hace 25 horas (> 24h TTL)
        val tsAntiguo = System.currentTimeMillis() - (25L * 60L * 60L * 1_000L)
        cache.poner("url1", entrada("url1", ts = tsAntiguo))
        // obtener debe expulsar y devolver null
        assertNull(cache.obtener("url1"))
        // Y la entrada debe haber sido removida del cache
        assertFalse(cache.contiene("url1"))
    }

    @Test
    fun `obtenerOActualizar recomputa entrada con timestamp antiguo — TTL expirada`() {
        val cache = CacheResultados()
        val tsAntiguo = System.currentTimeMillis() - (25L * 60L * 60L * 1_000L)
        cache.poner("url1", entrada("url1", prob = 0.3f, ts = tsAntiguo))
        var factoryInvocada = false
        val resultado = cache.obtenerOActualizar("url1") {
            factoryInvocada = true
            entrada("url1", prob = 0.9f)
        }
        assertTrue("factory debe invocarse porque la entrada expiro", factoryInvocada)
        assertEquals(0.9f, resultado.probabilidad, 0.001f)
    }

    @Test
    fun `obtener NO expulsa entrada con timestamp reciente — dentro de TTL`() {
        val cache = CacheResultados()
        // timestamp actual → dentro de TTL
        cache.poner("url1", entrada("url1", prob = 0.5f))
        assertNotNull(cache.obtener("url1"))
        assertTrue(cache.contiene("url1"))
    }

    @Test
    fun `obtener entrada en frontera TTL 24h exacta no expira`() {
        // Reloj controlado: la frontera exacta no expira (`> TTL_MS` es false),
        // y 1ms despues si expira. Con reloj real este test era flaky: entre
        // `poner` y `obtener` el System.currentTimeMillis avanzaba >= 1ms y la
        // entrada quedaba "expirada".
        var ahora = 1_000_000L
        val cache = CacheResultados(reloj = { ahora })
        val tsFrontera = ahora - CacheResultados.TTL_MS
        cache.poner("url1", entrada("url1", ts = tsFrontera))
        assertNotNull("en frontera exacta no expira", cache.obtener("url1"))
        // Avanzar 1ms → ahora si supera TTL y expira
        ahora += 1L
        assertNull("1ms despues de la frontera expira", cache.obtener("url1"))
    }

    @Test
    fun `obtener entrada 1ms despues de TTL expira`() {
        var ahora = 1_000_000L
        val cache = CacheResultados(reloj = { ahora })
        // 1ms despues de TTL → expira
        val tsExpirado = ahora - CacheResultados.TTL_MS - 1L
        cache.poner("url1", entrada("url1", ts = tsExpirado))
        assertNull(cache.obtener("url1"))
    }
}
