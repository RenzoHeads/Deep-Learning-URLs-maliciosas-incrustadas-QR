package com.qrsecurity.detector.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.qrsecurity.detector.TestApplication
import com.qrsecurity.detector.datos.local.entidades.EscaneoEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pruebas Compose UI de [PantallaDetalleEscaneo] — Bug DETAIL-1 fix.
 *
 * Cobertura:
 *  - Renderiza correctamente con un EscaneoEntity SEGURO.
 *  - Renderiza correctamente con un EscaneoEntity MALICIOSO.
 *  - El testTag "detalle_escaneo_root" esta presente.
 *
 * Estrategia:
 *  - `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33],
 *    application = TestApplication::class)` evita instanciar
 *    `AppSeguridadQR` cuyo `onCreate` llamaria `programarSyncPeriodica`.
 *  - `createAndroidComposeRule<ComponentActivity>()`.
 *  - `MaterialTheme { Surface { ... } }` envuelve el composable.
 *  - `onAllNodesWithTag("detalle_escaneo_root").assertCountEquals(1)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = TestApplication::class)
class DetalleEscaneoScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun escaneoSeguro() = EscaneoEntity(
        id = "test-safe-id",
        urlOriginal = "https://express.adobe.com/page/abc123",
        urlLimpia = "express.adobe.com/page/abc123",
        probabilidad = 0.05f,
        nivelAlerta = "SEGURO",
        delegado = "CANINE-S",
        esMalicioso = false,
        creadoEnMillis = 1753526400000L // 2026-07-26
    )

    private fun escaneoMalicioso() = EscaneoEntity(
        id = "test-malicious-id",
        urlOriginal = "http://evil-phishing.example.com/login",
        urlLimpia = "evil-phishing.example.com/login",
        probabilidad = 0.95f,
        nivelAlerta = "MALICIOSO",
        delegado = "CANINE-S",
        esMalicioso = true,
        creadoEnMillis = 1753526400000L
    )

    @Test
    fun `renderiza pantalla detalle con escaneo seguro`() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    PantallaDetalleEscaneo(
                        escaneo = escaneoSeguro(),
                        onVolver = {}
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("detalle_escaneo_root").assertCountEquals(1)
    }

    @Test
    fun `renderiza pantalla detalle con escaneo malicioso`() {
        composeRule.setContent {
            MaterialTheme {
                Surface {
                    PantallaDetalleEscaneo(
                        escaneo = escaneoMalicioso(),
                        onVolver = {}
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("detalle_escaneo_root").assertCountEquals(1)
    }

    @Test
    fun `renderiza pantalla detalle sin delegado null`() {
        val escaneoSinDelegado = escaneoSeguro().copy(delegado = null)

        composeRule.setContent {
            MaterialTheme {
                Surface {
                    PantallaDetalleEscaneo(
                        escaneo = escaneoSinDelegado,
                        onVolver = {}
                    )
                }
            }
        }

        composeRule.onAllNodesWithTag("detalle_escaneo_root").assertCountEquals(1)
    }
}
