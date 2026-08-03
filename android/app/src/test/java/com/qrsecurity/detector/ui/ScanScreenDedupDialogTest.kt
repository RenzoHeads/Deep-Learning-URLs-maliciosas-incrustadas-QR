package com.qrsecurity.detector.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qrsecurity.detector.ml.ControladorAlerta
import com.qrsecurity.detector.pipeline.Pipeline
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests del diálogo de deduplicación [DialogoUrlDuplicada] (Task 6b) —
 * el diálogo "URL ya escaneada" que aparece cuando el Pipeline emite
 * [Pipeline.Estado.UrlDuplicada] (todas las URLs del QR ya estaban en el
 * cache maestro `urls_catalogo`).
 *
 * Cobertura:
 *  - El diálogo se renderiza con los test tags expuestos
 *    (`dialogo_url_duplicada`, `btn_reescanear`, `btn_cancelar_reescaneo`,
 *    `texto_url_duplicada`, `texto_veces_escaneada`, `texto_veredicto_previo`).
 *  - El veredicto previo se muestra con el nombre y porcentaje correctos.
 *  - El contador "veces escaneada" refleja `vecesEscaneadaMaxima`.
 *  - El botón "Reescanear" invoca `onConfirmarReescaneo`.
 *  - El botón "Cancelar" invoca `onCancelarReescaneo`.
 *  - Caso multi-URL: muestra "N URLs ya escaneada(s)".
 *  - Caso sin `ultimoEscaneoMillis` (0L): no renderiza el tag `texto_ultima_vez`.
 *
 * Estrategia: Robolectric + `createComposeRule` (sin Activity real — el
 * `setContent` del rule monta el composable directo). No requiere Room ni
 * Pipeline real: construimos un [Pipeline.Estado.UrlDuplicada] sintético con
 * un [Pipeline.ResultadoAnalisis.ResultadoUrl] fijo.
 *
 * También cubre la helper pura [formatearTiempoRelativo] (unit tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ScanScreenDedupDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ──────────────────────────────────────────────────────────────
    // Fixtures — construyen un Estado.UrlDuplicada sintético.
    // ──────────────────────────────────────────────────────────────

    private fun resultadoUrl(
        nivel: ControladorAlerta.NivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
        probabilidad: Float = 0.5f
    ): Pipeline.ResultadoAnalisis.ResultadoUrl =
        Pipeline.ResultadoAnalisis.ResultadoUrl(
            urlOriginal = "https://malicioso.example.com/path",
            urlLimpia = "malicioso.example.com/path",
            probabilidad = probabilidad,
            nivelAlerta = nivel,
            delegado = "NNAPI"
        )

    private fun estadoDuplicada(
        nivel: ControladorAlerta.NivelAlerta = ControladorAlerta.NivelAlerta.SOSPECHOSO,
        probabilidad: Float = 0.5f,
        urls: List<String> = listOf("malicioso.example.com/path"),
        veces: Int = 3,
        ultimoMillis: Long = System.currentTimeMillis() - 3_600_000L
    ): Pipeline.Estado.UrlDuplicada = Pipeline.Estado.UrlDuplicada(
        resultado = resultadoUrl(nivel, probabilidad),
        urlsLimpiaConsultadas = urls,
        vecesEscaneadaMaxima = veces,
        ultimoEscaneoMillis = ultimoMillis
    )

    // ──────────────────────────────────────────────────────────────
    // Renderizado — tags y contenido
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `dialogo muestra el titulo y tags de test principales`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithTag("dialogo_url_duplicada").assertIsDisplayed()
        composeRule.onNodeWithText("URL ya escaneada").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_reescanear").assertIsDisplayed()
        composeRule.onNodeWithTag("btn_cancelar_reescaneo").assertIsDisplayed()
        composeRule.onNodeWithTag("texto_url_duplicada").assertIsDisplayed()
        composeRule.onNodeWithTag("texto_veces_escaneada").assertIsDisplayed()
        composeRule.onNodeWithTag("texto_veredicto_previo").assertIsDisplayed()
    }

    @Test
    fun `veredicto previo muestra nombre y porcentaje del nivel MALICIOSO`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(
                    nivel = ControladorAlerta.NivelAlerta.MALICIOSO,
                    probabilidad = 0.92f
                ),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        // 0.92 * 100 = 92 → "Veredicto anterior: Malicioso (92%)"
        composeRule.onNodeWithText("Veredicto anterior: Malicioso (92%)")
            .assertIsDisplayed()
    }

    @Test
    fun `veredicto previo muestra SEGURO con porcentaje bajo`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(
                    nivel = ControladorAlerta.NivelAlerta.SEGURO,
                    probabilidad = 0.1f
                ),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithText("Veredicto anterior: Seguro (10%)")
            .assertIsDisplayed()
    }

    @Test
    fun `veces escaneada muestra el contador plural cuando veces mayor a 1`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(veces = 5),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithText("Escaneada 5 veces.").assertIsDisplayed()
    }

    @Test
    fun `veces escaneada muestra singular cuando veces es 1`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(veces = 1),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithText("Escaneada 1 vez.").assertIsDisplayed()
    }

    @Test
    fun `ultima vez se muestra cuando ultimoEscaneoMillis es positivo`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(ultimoMillis = System.currentTimeMillis() - 3_600_000L),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithTag("texto_ultima_vez").assertIsDisplayed()
    }

    @Test
    fun `ultima vez NO se muestra cuando ultimoEscaneoMillis es 0`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(ultimoMillis = 0L),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        // El nodo NO debe existir → assertDoesNotExist
        composeRule.onNodeWithTag("texto_ultima_vez").assertDoesNotExist()
    }

    @Test
    fun `caso multi-URL muestra N URLs ya escaneadas`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(
                    urls = listOf(
                        "a.example.com/x",
                        "b.example.com/y",
                        "c.example.com/z"
                    ),
                    veces = 2
                ),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithText("3 URLs ya escaneada(s) antes.")
            .assertIsDisplayed()
    }

    @Test
    fun `caso single-URL muestra 1 URL ya escaneada`() {
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(
                    urls = listOf("a.example.com/x"),
                    veces = 1
                ),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithText("1 URL ya escaneada(s) antes.")
            .assertIsDisplayed()
    }

    // ──────────────────────────────────────────────────────────────
    // Callbacks — botones disparan las acciones correctas
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `boton Reescanear invoca onConfirmarReescaneo`() {
        var confirmado = false
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(),
                onConfirmarReescaneo = { confirmado = true },
                onCancelarReescaneo = {}
            )
        }

        composeRule.onNodeWithTag("btn_reescanear").performClick()

        assertEquals("botón Reescanear debe invocar onConfirmarReescaneo", true, confirmado)
    }

    @Test
    fun `boton Cancelar invoca onCancelarReescaneo`() {
        var cancelado = false
        composeRule.setContent {
            DialogoUrlDuplicada(
                estado = estadoDuplicada(),
                onConfirmarReescaneo = {},
                onCancelarReescaneo = { cancelado = true }
            )
        }

        composeRule.onNodeWithTag("btn_cancelar_reescaneo").performClick()

        assertEquals("botón Cancelar debe invocar onCancelarReescaneo", true, cancelado)
    }

    // ──────────────────────────────────────────────────────────────
    // formatearTiempoRelativo — helper pura
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `formatearTiempoRelativo devuelve justo ahora para menos de 1 minuto`() {
        val hace30s = System.currentTimeMillis() - 30_000L
        assertEquals("justo ahora", formatearTiempoRelativo(hace30s))
    }

    @Test
    fun `formatearTiempoRelativo devuelve hace N min para menos de 1 hora`() {
        val hace5min = System.currentTimeMillis() - 5 * 60_000L
        assertEquals("hace 5 min", formatearTiempoRelativo(hace5min))
    }

    @Test
    fun `formatearTiempoRelativo devuelve hace N h para menos de 1 dia`() {
        val hace2h = System.currentTimeMillis() - 2 * 3_600_000L
        assertEquals("hace 2 h", formatearTiempoRelativo(hace2h))
    }

    @Test
    fun `formatearTiempoRelativo devuelve hace N dias para menos de 1 mes`() {
        val hace3dias = System.currentTimeMillis() - 3 * 86_400_000L
        assertEquals("hace 3 días", formatearTiempoRelativo(hace3dias))
    }
}
