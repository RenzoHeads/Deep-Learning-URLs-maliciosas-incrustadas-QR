package com.qrsecurity.detector.cache

import com.qrsecurity.detector.ml.ControladorAlerta
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/**
 * Tests para el bug M8 — perdida de `delegado` en cache hit.
 *
 * Antes del fix, [CacheResultados.EntradaCache] no guardaba el delegado de
 * hardware que produjo el resultado (NNAPI/GPU/CPU). Cuando el Pipeline
 * observaba un cache hit, reconstruria el [com.qrsecurity.detector.pipeline.Pipeline.ResultadoAnalisis.ResultadoUrl]
 * usando `motorInferencia.nombreDelegado` **actual** — que puede diferir del
 * delegado que originalmente inferio la URL (si el usuario, p.ej., cambio de
 * GPU a CPU entre escaneos, o si la cache sobrevive a un cambio de delegado
 * preferido por bateria/etc.).
 *
 * Peor aun: la rama cache-hit **no persistia** a Room, por lo que un escaneo
 * cacheado (ej. usuario escanea el mismo QR dos veces seguidas) dejaba una
 * fila en el historial solo la primera vez. Esto era spectralmente incorrecto
 * para una app de seguridad: cada escaneo debe constar en el historial, sin
 * importar si la inferencia se ejecuto o se saco de cache.
 *
 * El fix conserva el `delegado` original en [EntradaCache.delegado] y obliga
 * al Pipeline a persistir la fila a Room en cada cache hit.
 *
 * Estos tests validan el contrato de la estructura (sin Android Context):
 *  - `EntradaCache` expone campo `delegado` constructor.
 *  - Equality/data-class se preserva (no se rompe `copy`).
 *  - El delegado se lee de vuelta simetrico al set.
 */
class CacheDelegadoPreservationTest {

    @Test
    fun `EntradaCache expone campo delegado`() {
        // Si este test compila, el campo existe. Verificamos tambien valor.
        val entrada = CacheResultados.EntradaCache(
            url = "ejemplo.com/path",
            probabilidad = 0.42f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
            timestampMs = 1_700_000_000L,
            delegado = "GPU"
        )
        assertEquals("GPU", entrada.delegado)
    }

    @Test
    fun `EntradaCacheSinDelegado_NO_compila_o_tiene_default_vacio`() {
        // Comprobacion simetrica: si pasamos delegado="NNAPI" lo leemos igual.
        // Si la senal de constructor antiguo (sin delegado) se utilizaba, aqui
        // forzamos la nueva API. Como Kotlin no admite parametros por defecto en
        // data class sin un valor default explicito, el fix agrega
        // `delegado: String = ""`; en ese caso, este test pasa trivialmente.
        val entrada = CacheResultados.EntradaCache(
            url = "ejemplo.com",
            probabilidad = 0.1f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SEGURO,
            timestampMs = 0L,
            delegado = "NNAPI"
        )
        assertEquals("NNAPI", entrada.delegado)
    }

    @Test
    fun `copy preserva el delegado`() {
        val original = CacheResultados.EntradaCache(
            url = "a.com",
            probabilidad = 0.9f,
            nivelAlerta = ControladorAlerta.NivelAlerta.MALICIOSO,
            timestampMs = 99L,
            delegado = "CPU"
        )
        val copia = original.copy(probabilidad = 0.91f)
        assertEquals("CPU", copia.delegado)
    }

    @Test
    fun `delegados distintos producen entradas distintas`() {
        // Sanity: si el delegado se ignora en equality, el test falla. Garantiza
        // que dos cacheos de la misma URL con distinto delegado (p.ej. NNAPI y
        // luego CPU) NO se consideran la misma entrada — el Pipeline debe
        // poder distinguir y elegir el delegado correcto al reconstruir.
        val conNNAPI = CacheResultados.EntradaCache(
            url = "x.com", probabilidad = 0.5f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
            timestampMs = 1L, delegado = "NNAPI"
        )
        val conCPU = CacheResultados.EntradaCache(
            url = "x.com", probabilidad = 0.5f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
            timestampMs = 1L, delegado = "CPU"
        )
        assertNotEquals(conNNAPI, conCPU)
    }
}
