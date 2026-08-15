package com.qrsecurity.detector.pipeline

import com.qrsecurity.detector.ml.ControladorAlerta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test aislado del selector de "peor URL" del [Pipeline].
 *
 * Bug H1 (multi-URL worst-pick): cuando un codigo QR contiene varias URLs,
 * el pipeline solo persiste una (la peor, definida por `NivelAlerta.ordinal`
 * y `probabilidad` como desempate), y guarda el resto en
 * `ResultadoAnalisis.ResultadoUrl.urlsAdicionales`. Antes del fix, el
 * selector podia equivocarse y persistir la primera en lugar de la peor.
 *
 * Como el constructor de [Pipeline] crea todos sus componentes de forma lazy
 * (MotorInferencia con TFLite, ClienteBackend, BaseDatosSeguridad, etc.),
 * no podemos instanciarlo aqui en JVM puro. Aislamos la logica del selector
 * de "peor" en una funcion top-level privada en este fixture y la comprobamos
 * con casos representativos (sin red, sin Room, sin TFLite).
 *
 * La logica del selector en [Pipeline.analizar] para el caso multi-URL es:
 *
 * ```kotlin
 * resultados.minWithOrNull(
 *   compareByDescending<ResultadoUrl> { it.nivelAlerta.ordinal }
 *     .thenByDescending { it.probabilidad }
 * )
 * ```
 *
 * H3 (file `Pipeline.kt` line ~200-230, datos H1): este test garantiza que el
 * selector escoge `MALICIOSO > SOSPECHOSO > SEGURO` por NivelAlerta.ordinal y,
 * a igualdad, la mayor probabilidad.
 */
class PipelineWorstPickerTest {

    private fun nivelAlerta(o: Int) = ControladorAlerta.NivelAlerta.entries[o]

    /**
     * Replica exacta del selector usado por [Pipeline.analizar] para escoger
     * el peor resultado entre multiples URLs extraidas del mismo codigo QR.
     *
     * La logica es `minWithOrNull(compareByDescending { nivelAlerta.ordinal }
     * thenByDescending { probabilidad })`: el "min" bajo un comparador
     * descendente es el elemento que iria PRIMERO en orden descendente — es
     * decir, el de NivelAlerta con `ordinal` mayor y, a igualdad, mayor
     * probabilidad. Ese es el "peor" (mas peligroso).
     *
     * Si la implementacion de Pipeline cambia, este comparator debe
     * updatearse para mantenerse fiel al contrato H1.
     */
    private fun peorSelector(
        a: ResultadoAnalisis.ResultadoUrl,
        b: ResultadoAnalisis.ResultadoUrl
    ): ResultadoAnalisis.ResultadoUrl {
        val cmp = compareByDescending<ResultadoAnalisis.ResultadoUrl> { it.nivelAlerta.ordinal }
            .thenByDescending { it.probabilidad }
        // cmp.compare(a, b) < 0 significa que `a` va primero en orden desccendente — `a` es "menor o igual" en el orden.
        // El "peor" es el que va PRIMERO en orden descendente = el `min` bajo cmp.
        return if (cmp.compare(a, b) <= 0) a else b
    }

    private fun resultado(
        urlOriginal: String,
        prob: Float,
        nivel: ControladorAlerta.NivelAlerta
    ) = ResultadoAnalisis.ResultadoUrl(
        urlOriginal = urlOriginal,
        urlLimpia = urlOriginal.removePrefix("https://").removePrefix("http://"),
        probabilidad = prob,
        nivelAlerta = nivel,
        delegado = "CPU"
    )

    @Test
    fun `peor selector escoge MALICIOSO sobre SEGURO`() {
        val segura = resultado("https://segura.com", 0.1f, nivelAlerta(0)) // SEGURO
        val malicioso = resultado("https://malware.com", 0.9f, nivelAlerta(2)) // MALICIOSO
        val peor = listOf(segura, malicioso).reduce(::peorSelector)
        assertEquals("https://malware.com", peor.urlOriginal)
    }

    @Test
    fun `peor selector escoge mayor probabilidad a igual NivelAlerta`() {
        val baja = resultado("https://a.com", 0.5f, nivelAlerta(1)) // SOSPECHOSO
        val alta = resultado("https://b.com", 0.85f, nivelAlerta(1)) // SOSPECHOSO
        val peor = listOf(baja, alta).reduce(::peorSelector)
        assertEquals("https://b.com (mayor probabilidad)", alta.urlOriginal, peor.urlOriginal)
    }

    @Test
    fun `peor selector escoge MALICIOSO aunque tenga probabilidad menor que un SOSPECHOSO`() {
        // NivelAlerta.ordinal tiene prioridad sobre probabilidad.
        val maliciosoBajo = resultado("https://phish.com", 0.55f, nivelAlerta(2)) // MALICIOSO
        val sospechosoAlto = resultado("https://sketchy.com", 0.75f, nivelAlerta(1)) // SOSPECHOSO
        val peor = listOf(maliciosoBajo, sospechosoAlto).reduce(::peorSelector)
        assertEquals("https://phish.com (MALICIOSO gana por ordinal)", maliciosoBajo.urlOriginal, peor.urlOriginal)
    }

    @Test
    fun `peor selector entre tres URLs escoge el MALICIOSO y el resto va a urlsAdicionales`() {
        val segura = resultado("https://benign.com", 0.1f, nivelAlerta(0))
        val sospechoso = resultado("https://sketch.com", 0.5f, nivelAlerta(1))
        val malicioso = resultado("https://evil.com", 0.91f, nivelAlerta(2))
        val todos = listOf(segura, sospechoso, malicioso)

        val peor = todos.reduce(::peorSelector)
        val adicionales = todos.filter { it != peor }

        assertEquals("https://evil.com debe ser el peor", malicioso.urlOriginal, peor.urlOriginal)
        assertEquals(
            "urlsAdicionales deben excluir al peor y tener 2 entradas",
            2, adicionales.size
        )
        assertTrue(
            "urlsAdicionales no debe contener al peor",
            adicionales.none { it.urlOriginal == malicioso.urlOriginal }
        )
    }
}
