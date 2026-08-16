package com.qrsecurity.detector.ui

import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import com.qrsecurity.detector.ml.ControladorAlerta
import com.qrsecurity.detector.pipeline.ResultadoAnalisis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests JVM puros para [resolverIdNavegacion] — la cascada de resolucion
 * de id de escaneo extraida del bloque inline de [PantallaAnalisis] (Fase 4 #5).
 *
 * Cobertura:
 *  - Caso 1: idLocal no-nulo → retorna directo (sin tocar historial).
 *  - Caso 2: idLocal null + match exacto (urlLimpia + urlOriginal) en historial.
 *  - Caso 3: idLocal null + sin match exacto + match parcial por urlLimpia
 *    (toma el mas reciente por creadoEnMillis).
 *  - Caso 4: idLocal null + historial vacio → null.
 *  - Caso 5: idLocal null + historial con solo URLs distintas → null.
 *  - Caso 6: idLocal "" (vacio) → cae al match (no se trata como valido).
 *
 * No requiere Robolectric: [resolverIdNavegacion] es una funcion pura sin
 * dependencias de Android (sin Log, sin Context). [EscaneoEntity] es un
 * data class plano.
 */
class ResolverIdNavegacionTest {

    private val resultado = ResultadoAnalisis.ResultadoUrl(
        urlOriginal = "https://www.ejemplo.com/path",
        urlLimpia = "ejemplo.com/path",
        probabilidad = 0.9f,
        nivelAlerta = ControladorAlerta.NivelAlerta.MALICIOSO,
        delegado = "CPU"
    )

    private fun escaneo(
        id: String,
        urlLimpia: String,
        urlOriginal: String,
        creadoEnMillis: Long
    ): EscaneoEntity = EscaneoEntity(
        id = id,
        urlOriginal = urlOriginal,
        urlLimpia = urlLimpia,
        probabilidad = 0.9f,
        nivelAlerta = "MALICIOSO",
        delegado = "CPU",
        esMalicioso = true,
        creadoEnMillis = creadoEnMillis
    )

    @Test
    fun `idLocal no nulo retorna directo sin inspeccionar historial`() {
        val historial = listOf(
            escaneo("id-A", "otra.com", "https://otra.com", 100L)
        )
        val resultado = resolverIdNavegacion(
            idLocal = "id-directo",
            resultado = resultado,
            historial = historial
        )
        assertEquals("id-directo", resultado)
    }

    @Test
    fun `idLocal null y match exacto urlLimpia mas urlOriginal retorna ese id`() {
        val historial = listOf(
            escaneo("id-viejo", "ejemplo.com/path", "https://m.ejemplo.com/path", 100L),
            escaneo("id-exacto", "ejemplo.com/path", "https://www.ejemplo.com/path", 200L),
            escaneo("id-otra", "otra.com/x", "https://otra.com/x", 300L)
        )
        val resultado = resolverIdNavegacion(
            idLocal = null,
            resultado = resultado,
            historial = historial
        )
        assertEquals("id-exacto", resultado)
    }

    @Test
    fun `idLocal null y sin match exacto retorna el mas reciente por urlLimpia`() {
        val historial = listOf(
            escaneo("id-antiguo", "ejemplo.com/path", "https://m.ejemplo.com/path", 100L),
            escaneo("id-reciente", "ejemplo.com/path", "https://www.ejemplo.com/path", 500L),
            escaneo("id-medio", "ejemplo.com/path", "https://www.ejemplo.com/path", 300L),
            escaneo("id-otra-url", "diferente.com/y", "https://diferente.com/y", 900L)
        )
        val resultado = resolverIdNavegacion(
            idLocal = null,
            resultado = resultado,
            historial = historial
        )
        assertEquals("id-reciente", resultado)
    }

    @Test
    fun `idLocal null e historial vacio retorna null`() {
        val resultado = resolverIdNavegacion(
            idLocal = null,
            resultado = resultado,
            historial = emptyList()
        )
        assertNull(resultado)
    }

    @Test
    fun `idLocal null e historial con solo URLs distintas retorna null`() {
        val historial = listOf(
            escaneo("id-A", "otra-a.com", "https://otra-a.com", 100L),
            escaneo("id-B", "otra-b.com", "https://otra-b.com", 200L)
        )
        val resultado = resolverIdNavegacion(
            idLocal = null,
            resultado = resultado,
            historial = historial
        )
        assertNull(resultado)
    }

    @Test
    fun `idLocal vacio se trata como null y cae al match de historial`() {
        val historial = listOf(
            escaneo("id-exacto", "ejemplo.com/path", "https://www.ejemplo.com/path", 200L)
        )
        val resultado = resolverIdNavegacion(
            idLocal = "",
            resultado = resultado,
            historial = historial
        )
        assertEquals("id-exacto", resultado)
    }
}
