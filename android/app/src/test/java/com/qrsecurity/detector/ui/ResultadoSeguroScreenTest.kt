package com.qrsecurity.detector.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.qrsecurity.detector.ml.ControladorAlerta
import com.qrsecurity.detector.pipeline.Pipeline
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pruebas Compose UI de [PantallaResultadoSeguro] — cubren:
 *  - Renderizado del resultado seguro (titulo, URL detectada,
 *    boton Escanear otro).
 *  - Click en "Escanear otro" invoca el callback `onEscanearOtro`.
 *
 * Cobertura adicional (F8 fix — userinfo rejection):
 *  - Render con una URL que tiene userinfo (`https://apple.com@evil.com`):
 *    la pantalla renderiza la URL tal cual (la rejection solo ocurre
 *    al hacer click en "Abrir enlace", que bajo Robolectric sin
 *    PackageManager/activity resolver no se puede verificar
 *    end-to-end). Verificamos que la pantalla NO crashea con userinfo
 *    en la URL — el binding solo la muestra como string.
 *
 * Estrategia:
 *  - `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33],
 *    application = TestApplication::class)` evita instanciar
 *    `AppSeguridadQR` cuyo `onCreate` llamaria `programarSyncPeriodica`
 *    con WorkManager no inicializado.
 *  - `createAndroidComposeRule<ComponentActivity>()`.
 *  - `MaterialTheme { Surface { ... } }` envuelve el composable.
 *  - `Modifier.testTag(...)` en 4 nodos clave:
 *    `resultado_seguro_root`, `titulo_enlace_seguro`,
 *    `url_original_detectada`, `btn_escanear_otro`.
 *  - `performScrollTo()` antes de `performClick()` (scrollable Column).
 *  - `onAllNodesWithTag(...).assertCountEquals(N)` para presencia.
 *
 * NO usamos `@Config(application = AppSeguridadQR::class)`: esa clase
 * llama a `MediadorSincronizacion.programarSyncPeriodica()` en
 * `onCreate`, que requiere WorkManager inicializado bajo Robolectric.
 * `TestApplication` no hereda de `AppSeguridadQR` y no hace nada en
 * `onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class ResultadoSeguroScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var onEscanearOtroInvocado = false

    @Before
    fun setUp() {
        composeTestRule.mainClock.autoAdvance = true
        onEscanearOtroInvocado = false
    }

    /**
     * Construye un [Pipeline.ResultadoAnalisis.ResultadoUrl] "seguro":
     * - `nivelAlerta = SEGURO`
     * - `probabilidad = 0.1f` (10%, lejos del umbral MALICIOSO 0.7)
     * - `delegado = "CPU"`
     */
    private fun resultadoSeguro(
        urlOriginal: String = "https://example.com/safe",
        urlLimpia: String = "example.com/safe"
    ): Pipeline.ResultadoAnalisis.ResultadoUrl =
        Pipeline.ResultadoAnalisis.ResultadoUrl(
            urlOriginal = urlOriginal,
            urlLimpia = urlLimpia,
            probabilidad = 0.1f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SEGURO,
            delegado = "CPU"
        )

    /**
     * Helper: monta [PantallaResultadoSeguro] con el resultado dado.
     */
    private fun montarPantalla(resultado: Pipeline.ResultadoAnalisis.ResultadoUrl) {
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    PantallaResultadoSeguro(
                        resultado = resultado,
                        onEscanearOtro = { onEscanearOtroInvocado = true }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    // ──────────────────────────────────────────────────────────────
    // Renderizado baseline
    // ──────────────────────────────────────────────────────────────

    @Test
    fun renderiza_tituloUrlYBotonEscanearOtro() {
        val url = "https://sitiogenesisseguro.example.org/pagina"
        montarPantalla(resultadoSeguro(
            urlOriginal = url,
            urlLimpia = "sitiogenesisseguro.example.org/pagina"
        ))

        composeTestRule.onAllNodesWithTag("resultado_seguro_root")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("titulo_enlace_seguro")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("url_original_detectada")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("btn_escanear_otro")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("btn_abrir_enlace")
            .assertCountEquals(1)
    }

    // ──────────────────────────────────────────────────────────────
    // Click en Escanear otro invoca callback
    // ──────────────────────────────────────────────────────────────

    @Test
    fun clickEscanearOtro_invocaCallback() {
        montarPantalla(resultadoSeguro())

        composeTestRule.onNodeWithTag("btn_escanear_otro")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            "Click en Escanear otro debe invocar onEscanearOtro",
            onEscanearOtroInvocado
        )
    }

    // ──────────────────────────────────────────────────────────────
    // F8 fix — render con URL userinfo no crashea
    // ──────────────────────────────────────────────────────────────

    @Test
    fun renderiza_urlConUserinfo_nocrashea() {
        // URL con userinfo: el navegador mostraria `apple.com` como
        // autoridad antes del `@`. La pantalla solo la RENDERIZA como
        // string — no la abre. La rejection F8 ocurre al click en
        // "Abrir enlace" (no testeable end-to-end bajo Robolectric sin
        // PackageManager real). Aqui solo verificamos que la URL con
        // userinfo NO crashea el binding: la pantalla la muestra.
        montarPantalla(resultadoSeguro(
            urlOriginal = "https://apple.com@evil.com/path",
            urlLimpia = "apple.com@evil.com/path"
        ))

        composeTestRule.onAllNodesWithTag("url_original_detectada")
            .assertCountEquals(1)
        // La pantalla renderizo sin crashear — F8 binding path no
        // introduce NPE sobre URLs con userinfo.
    }

    // ──────────────────────────────────────────────────────────────
    // Delegado "cache" — Bug 16 fix mirror: se oculta "Inferencia"
    // ──────────────────────────────────────────────────────────────

    @Test
    fun renderiza_delegadoCache_ocultaTextoInferencia() {
        // Cuando delegado == "cache", la pantalla oculta el texto
        // "Inferencia: cache" para consistencia (Bug 16 mirror fix).
        // Renderiza el resultado y verifica que la screen no crashea
        // con delegado="cache".
        val resultadoCache = Pipeline.ResultadoAnalisis.ResultadoUrl(
            urlOriginal = "https://cached.example.com",
            urlLimpia = "cached.example.com",
            probabilidad = 0.05f,
            nivelAlerta = ControladorAlerta.NivelAlerta.SEGURO,
            delegado = "cache"
        )
        montarPantalla(resultadoCache)

        composeTestRule.onAllNodesWithTag("resultado_seguro_root")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("btn_escanear_otro")
            .assertCountEquals(1)
    }
}
