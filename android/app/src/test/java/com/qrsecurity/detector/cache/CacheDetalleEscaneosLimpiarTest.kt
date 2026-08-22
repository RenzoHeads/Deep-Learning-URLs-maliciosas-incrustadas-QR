package com.qrsecurity.detector.cache

import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 3 (pieza c) — CacheDetalleEscaneos NO se limpia en logout.
 *
 * [CacheDetalleEscaneos] es @Singleton (Hilt), asi que su instancia (y el
 * mapa `_cache` de `DetalleEscaneoCacheado` por id) sobrevive a cierres
 * de sesion dentro del mismo proceso. Sin un metodo [limpiar], al cerrar
 * sesion y volver a loguear (otro usuario o el mismo), el detalle de un
 * escaneo apareceria "pre-cargado" con datos stale del usuario anterior.
 * Fuga cross-user.
 *
 * Contrato de este test:
 *  - [limpiar] vacia el cache a `emptyMap()`.
 *  - Tras [limpiar], [obtener] devuelve `null` para cualquier id previamente
 *    cacheado.
 *  - [limpiar] es idempotente (limpiar un cache ya vacio sigue vacio).
 *  - Tras [limpiar], se puede [guardar] de nuevo (el cache sigue funcional).
 *
 * Red: `limpiar()` no existe en [CacheDetalleEscaneos] → no compila.
 * Green: anadir `fun limpiar() { _cache.update { emptyMap() } }`.
 */
class CacheDetalleEscaneosLimpiarTest {

    private fun escaneoDummy(id: String) = EscaneoEntity(
        id = id,
        urlOriginal = "https://example.com/path",
        urlLimpia = "example.com/path",
        probabilidad = 0.5f,
        nivelAlerta = "SEGURO",
        delegado = "CPU",
        esMalicioso = false,
        creadoEnMillis = System.currentTimeMillis()
    )

    private fun cargadoDummy(id: String) = DetalleEscaneoCacheado(
        escaneo = escaneoDummy(id),
        urlBloqueada = false,
        esUltimaVersion = true,
        totalReescaneos = 0
    )

    @Test
    fun `limpiar vacia el cache tras guardar varias entradas`() {
        val cache = CacheDetalleEscaneos()
        cache.guardar(cargadoDummy("id-1"))
        cache.guardar(cargadoDummy("id-2"))
        assertEquals(
            "Deben haber 2 entradas antes de limpiar",
            2,
            cache.cache.value.size
        )

        // Red: limpiar() no existe aun.
        cache.limpiar()

        assertEquals(
            "El cache debe estar vacio tras limpiar",
            emptyMap<String, DetalleEscaneoCacheado>(),
            cache.cache.value
        )
    }

    @Test
    fun `limpiar hace que obtener devuelva null para ids previamente cacheados`() {
        val cache = CacheDetalleEscaneos()
        cache.guardar(cargadoDummy("id-1"))
        // Verificar que estaba antes de limpiar
        assertTrue("El id-1 debe estar cacheado antes de limpiar", cache.obtener("id-1") != null)

        // Red: limpiar() no existe aun.
        cache.limpiar()

        assertNull(
            "obtener debe devolver null tras limpiar",
            cache.obtener("id-1")
        )
    }

    @Test
    fun `limpiar es idempotente — limpiar un cache vacio sigue vacio`() {
        val cache = CacheDetalleEscaneos()
        assertEquals(0, cache.cache.value.size)

        // Red: limpiar() no existe aun.
        cache.limpiar()

        assertEquals(
            "Limpiar un cache ya vacio debe seguir vacio",
            0,
            cache.cache.value.size
        )
    }

    @Test
    fun `tras limpiar el cache sigue funcional — guardar funciona de nuevo`() {
        val cache = CacheDetalleEscaneos()
        cache.guardar(cargadoDummy("id-viejo"))
        // Red: limpiar() no existe aun.
        cache.limpiar()
        assertNull(cache.obtener("id-viejo"))

        // Tras limpiar, guardar debe funcionar normalmente.
        cache.guardar(cargadoDummy("id-nuevo"))
        assertTrue(
            "Tras limpiar, guardar debe funcionar de nuevo",
            cache.obtener("id-nuevo") != null
        )
        assertEquals(1, cache.cache.value.size)
    }
}
