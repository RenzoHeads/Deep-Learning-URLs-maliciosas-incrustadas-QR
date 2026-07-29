package com.qrsecurity.detector.cache

import com.qrsecurity.detector.cache.CacheResultados.EntradaCache
import com.qrsecurity.detector.ml.ControladorAlerta
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests para M-14 (TTL): una entrada cacheada cuya edad supera [CacheResultados.TTL_MS]
 * debe ser expulsada y devolver null en [CacheResultados.obtener], evitando que una
 * clasificacion basada en un modelo obsoleto sobreviva a una actualizacion del modelo.
 *
 * Tests JVM puros (no Robolectric).
 */
class ResultCacheTtlTest {

    @Test
    fun `obtener devuelve null y elimina la entrada cuando la edad supera TTL_MS`() {
        // GIVEN: un cache con una entrada cuya marca de tiempo ya expiro (TTL_MS + 1000 ms atras).
        val cache = CacheResultados(maxEntradas = 10)
        val url = "https://ejemplo-malicioso.com/path"
        val entradaExpirada = EntradaCache(
            url = url,
            probabilidad = 0.95f,
            nivelAlerta = ControladorAlerta.NivelAlerta.MALICIOSO,
            // timestampMs ahora menos (TTL_MS + 1000) -> definitivamente expirada.
            timestampMs = System.currentTimeMillis() - (CacheResultados.TTL_MS + 1_000L)
        )
        cache.poner(url, entradaExpirada)

        // Sanity: la entrada esta presente antes de consultar.
        assertEquals(1, cache.tamano(), "La entrada debe estar presente antes de obtener()")

        // WHEN: invocamos obtener() bajo TTL.
        val resultado = cache.obtener(url)

        // THEN: obtener devuelve null (stale) y la entrada fue removida del cache.
        assertNull(resultado, "obtener() debe devolver null para entradas expiradas (TTL)")
        assertEquals(0, cache.tamano(), "obtener() debe expulsar del cache la entrada expirada")
    }

    @Test
    fun `obtener devuelve la entrada y la conserva cuando la edad es menor a TTL_MS`() {
        // GIVEN: cache con entrada fresca (edad << TTL_MS).
        val cache = CacheResultados(maxEntradas = 10)
        val url = "https://ejemplo-benigno.com/path"
        val entradaFresca = EntradaCache(
            url = url,
            probabilidad = 0.05f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SEGURO,
            // Recien escrita: edad ~0.
            timestampMs = System.currentTimeMillis()
        )
        cache.poner(url, entradaFresca)

        // WHEN: obtener().
        val resultado = cache.obtener(url)

        // THEN: la entrada se devuelve y permanece en cache.
        assertEquals(entradaFresca, resultado, "obtener() debe devolver la entrada fresca")
        assertEquals(1, cache.tamano(), "La entrada fresca debe permanecer en cache tras obtener()")
    }
}
