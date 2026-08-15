package com.qrsecurity.detector.ui

import com.qrsecurity.detector.ml.ControladorAlerta
import com.qrsecurity.detector.pipeline.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.qrsecurity.detector.pipeline.Estado
import com.qrsecurity.detector.pipeline.ResultadoAnalisis

/**
 * Bug 2 — Pantalla "Analizando..." visible bajo el dialogo UrlDuplicada.
 *
 * Sintoma: al escanear una URL ya escaneada antes, el dialogo correcto
 * "URL ya escaneada Adevuelve reescanearla?" aparece, PERO debajo del dialogo
 * la pantalla completa sigue mostrando "Analizando contenido...", spinner
 * "Analizando actividad...", tarjeta de 3 checks, barra de progreso y boton
 * Cancelar. Visualmente contradictorio: la app parece estar analizando
 * mientras pregunta si reescanear.
 *
 * Causa raiz: `AnalisisScreen.kt` lineas 214-362 renderiza la `Column` con
 * todo el contenido "Analizando..." SIEMPRE, sin condicional sobre `estado`.
 * Adicionalmente, `urlMostrada` (lineas 201-207) solo se resuelve para
 * `ResultadoListo` via `as? ResultadoListo`, descartando `UrlDuplicada`
 * (que tambien trae `resultado: ResultadoUrl` con la URL).
 *
 * Fix: dos funciones puras extraidas de AnalisisScreen para testear sin
 * Compose UI Test (fragil en Robolectric):
 *  - [urlMostradaParaEstado]: devuelve la URL a mostrar en la tarjeta
 *    "URL DETECTADA" para `ResultadoListo` Y `UrlDuplicada` (no null).
 *  - [debeMostrarContenidoAnalizando]: devuelve `false` para `UrlDuplicada`
 *    ( gatea la Column con spinner/checks/progress; el dialogo ya aparece
 *    encima y no tiene sentido mostrar "Analizando..." debajo).
 *
 * Red: estas funciones no existen aun en AnalisisScreen.kt → no compila.
 * Green: extraerlas y usarlas en AnalisisScreen.kt para gatear la Column
 *   y resolver `urlMostrada`.
 */
class AnalisisScreenUiStateTest {

    // ── Fixtures ──

    private val resultadoUrl = ResultadoAnalisis.ResultadoUrl(
        urlOriginal = "https://www.ejemplo-duplicada.com/pagina",
        urlLimpia = "ejemplo-duplicada.com/pagina",
        probabilidad = 0.42f,
        nivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
        delegado = "CPU",
        urlsAdicionales = emptyList()
    )

    private val resultadoUrlMalicioso = resultadoUrl.copy(
        nivelAlerta = ControladorAlerta.NivelAlerta.MALICIOSO,
        probabilidad = 0.97f
    )

    private val urlDuplicada = Estado.UrlDuplicada(
        resultado = resultadoUrl,
        urlsLimpiaConsultadas = listOf("ejemplo-duplicada.com/pagina"),
        vecesEscaneadaMaxima = 3,
        ultimoEscaneoMillis = 1700000000000L
    )

    private val resultadoListo = Estado.ResultadoListo(
        resultado = resultadoUrl,
        idLocal = "uuid-escaneo-123"
    )

    // ── urlMostradaParaEstado ──

    @Test
    fun `urlMostradaParaEstado devuelve la URL original cuando estado es ResultadoListo con ResultadoUrl`() {
        val actual = urlMostradaParaEstado(resultadoListo, analizando = false)
        assertEquals("https://www.ejemplo-duplicada.com/pagina", actual)
    }

    @Test
    fun `urlMostradaParaEstado devuelve null cuando estado es ResultadoListo con NoUrl`() {
        val noUrl = Estado.ResultadoListo(
            resultado = ResultadoAnalisis.NoUrl(
                valorCrudo = "BEGIN:VCARD\nFN:Test\nEND:VCARD",
                tipoContenido = "vcard"
            )
        )
        val actual = urlMostradaParaEstado(noUrl, analizando = false)
        assertNull(actual)
    }

    @Test
    fun `urlMostradaParaEstado devuelve null cuando analizando es true`() {
        // Mientras analizando == true, la UI muestra "Analizando contenido..."
        // regardless of estado (que sera Escaneando o Inicializando).
        val actual = urlMostradaParaEstado(resultadoListo, analizando = true)
        assertNull(actual)
    }

    /**
     * Bug 2 contrato clave: UrlDuplicada TRAE la URL en `resultado.urlOriginal`.
     * La UI debe mostrarla en la tarjeta "URL DETECTADA" en vez de
     * "Analizando contenido..." (comportamiento previo: `as? ResultadoListo`
     * descarta UrlDuplicada → urlMostrada=null → "Analizando contenido...").
     */
    @Test
    fun `urlMostradaParaEstado devuelve la URL original cuando estado es UrlDuplicada`() {
        val actual = urlMostradaParaEstado(urlDuplicada, analizando = false)
        assertEquals("https://www.ejemplo-duplicada.com/pagina", actual)
    }

    @Test
    fun `urlMostradaParaEstado devuelve null cuando estado es Escaneando`() {
        val actual = urlMostradaParaEstado(Estado.Escaneando, analizando = false)
        assertNull(actual)
    }

    /**
     * Bug F: Analizando = inference en progreso. Sin URL detectada aun
     * (la URL se asigna al completar la inference y emitir ResultadoListo).
     */
    @Test
    fun `urlMostradaParaEstado devuelve null cuando estado es Analizando`() {
        val actual = urlMostradaParaEstado(Estado.Analizando, analizando = false)
        assertNull(actual)
    }

    @Test
    fun `urlMostradaParaEstado devuelve null cuando estado es Inicializando`() {
        val actual = urlMostradaParaEstado(Estado.Inicializando, analizando = false)
        assertNull(actual)
    }

    @Test
    fun `urlMostradaParaEstado devuelve null cuando estado es Error`() {
        val actual = urlMostradaParaEstado(
            Estado.Error("Algo salio mal"), analizando = false
        )
        assertNull(actual)
    }

    // ── debeMostrarContenidoAnalizando ──

    /**
     * Bug 2 contrato clave: cuando estado es UrlDuplicada, NO se debe
     * renderizar la Column con spinner "Analizando actividad...", tarjeta
     * de 3 checks, barra de progreso y boton Cancelar. El dialogo ya aparece
     * encima y la pantalla subyacente "Analizando..." es contradictoria.
     */
    @Test
    fun `debeMostrarContenidoAnalizando devuelve false cuando estado es UrlDuplicada`() {
        val actual = debeMostrarContenidoAnalizando(urlDuplicada)
        assertFalse(
            "No se debe mostrar el contenido Analizando bajo el dialogo UrlDuplicada",
            actual
        )
    }

    @Test
    fun `debeMostrarContenidoAnalizando devuelve true cuando estado es Escaneando`() {
        val actual = debeMostrarContenidoAnalizando(Estado.Escaneando)
        assertTrue("Escaneando debe mostrar el contenido Analizando", actual)
    }

    /**
     * Bug F: Analizando = inference en progreso. El contenido "Analizando..."
     * (spinner, tarjeta URL, checks, progreso) DEBE mostrarse — es el feedback
     * visual de que la inference esta corriendo.
     */
    @Test
    fun `debeMostrarContenidoAnalizando devuelve true cuando estado es Analizando`() {
        val actual = debeMostrarContenidoAnalizando(Estado.Analizando)
        assertTrue("Analizando debe mostrar el contenido Analizando (inference en progreso)", actual)
    }

    @Test
    fun `debeMostrarContenidoAnalizando devuelve true cuando estado es Inicializando`() {
        val actual = debeMostrarContenidoAnalizando(Estado.Inicializando)
        assertTrue("Inicializando debe mostrar el contenido Analizando", actual)
    }

    @Test
    fun `debeMostrarContenidoAnalizando devuelve true cuando estado es ResultadoListo`() {
        // ResultadoListo: el LaunchedEffect navega fuera casi inmediatamente,
        // pero mientras tanto el contenido Analizando es acceptable (muestra
        // la URL detectada en la tarjeta, sin spinner porque analizando=false).
        val actual = debeMostrarContenidoAnalizando(resultadoListo)
        assertTrue("ResultadoListo debe mostrar el contenido Analizando", actual)
    }

    @Test
    fun `debeMostrarContenidoAnalizando devuelve true cuando estado es Error`() {
        val actual = debeMostrarContenidoAnalizando(Estado.Error("fail"))
        assertTrue("Error debe mostrar el contenido Analizando (mensaje caera via onMensaje)", actual)
    }

    @Test
    fun `debeMostrarContenidoAnalizando devuelve false para UrlDuplicada malicioso tambien`() {
        val duplicadaMaliciosa = urlDuplicada.copy(
            resultado = resultadoUrlMalicioso,
            vecesEscaneadaMaxima = 5
        )
        val actual = debeMostrarContenidoAnalizando(duplicadaMaliciosa)
        assertFalse(
            "No se debe mostrar Analizando bajo UrlDuplicada incluso si es malicioso",
            actual
        )
    }
}
