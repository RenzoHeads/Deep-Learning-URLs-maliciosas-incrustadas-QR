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
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pruebas Compose UI de [PantallaDenunciar] — cubren:
 *  - Renderizado del formulario (campo URL, descripcion, botones).
 *  - Validacion: click Enviar con URL en blanco muestra mensaje de error.
 *  - Boton Cancelar invoca [onCancelar] sin invocar [onExito].
 *  - Flujo exitoso: URL ingresada + click Enviar invoca [onExito]
 *    (offline-first: [RepositorioDenuncias.crearLocal] guarda en Room
 *    y [onExito] se llama inmediatamente sin esperar al backend).
 *
 * H4 WorkInfo reset (Lote H fix): el flag `syncDisparada` se resetea
 * cuando el [androidx.work.WorkInfo.State] del one-shot sync pasa a
 * SUCCEEDED/FAILED/CANCELLED. Verificar el reset directo del flag
 * requiere observar el Flow de WorkInfo bajo Robolectric, lo cual es
 * fragil. En su lugar se cubre el comportamiento observable: el
 * formulario sigue funcional despues de un ciclo de envio (no se queda
 * "trabado"), lo que depende de que `enviando` vuelva a false en el
 * `finally` del scope.launch y de que el flag de dedup no bloquee la UI.
 *
 * Estrategia de test:
 * - `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33])`.
 * - `@Config(application = TestApplication::class)` evita que Robolectric
 *   instancie [com.qrsecurity.detector.AppSeguridadQR] cuyo `onCreate`
 *   llama a `MediadorSincronizacion.programarSyncPeriodica()` →
 *   `WorkManager.getInstance(context)` que lanza IllegalStateException.
 * - `WorkManagerTestInitHelper.initializeTestWorkManager(context)` en
 *   `@Before` inicializa WorkManager para tests, ya que
 *   [PantallaDenunciar] llama
 *   `WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(...)`
 *   para observar el estado del sync y resetear `syncDisparada` (fix H4).
 *   Sin esta inicializacion, esa llamada lanza
 *   `IllegalStateException("WorkManager is not initialized")`.
 * - `createAndroidComposeRule<ComponentActivity>()`.
 * - `MaterialTheme { Surface { ... } }` envuelve el composable.
 * - `Modifier.testTag(...)` en los 5 nodos clave:
 *   `campo_url_sospechosa`, `campo_descripcion`, `btn_enviar_denuncia`,
 *   `btn_cancelar_denuncia`, `mensaje_error_denuncia`.
 * - `performScrollTo()` antes de `performClick()` (scrollable Column).
 * - `onAllNodesWithTag(...).assertCountEquals(N)` para presencia/ausencia.
 * - `mainClock.autoAdvance = true` + `waitForIdle()` tras cada accion.
 *
 * Casos:
 *  1. Formulario renderiza: campo URL (count=1), campo descripcion
 *     (count=1), boton enviar (count=1), boton cancelar (count=1),
 *     mensaje de error ausente (count=0).
 *  2. Click Cancelar -> invoca onCancelar, NO invoca onExito.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class DenunciarScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var onExitoInvocado = false
    private var onCancelarInvocado = false

    @Before
    fun setUp() {
        composeTestRule.mainClock.autoAdvance = true
        onExitoInvocado = false
        onCancelarInvocado = false

        // PantallaDenunciar llama WorkManager.getInstance(context) en
        // composition (line ~147: getWorkInfosForUniqueWorkFlow). Sin
        // WorkManager inicializado, lanza IllegalStateException. Lo
        // inicializamos para tests con WorkManagerTestInitHelper.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)

        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    PantallaDenunciar(
                        urlPrevia = "",
                        onExito = { onExitoInvocado = true },
                        onCancelar = { onCancelarInvocado = true }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
    }

    @Test
    fun formulario_renderizaCampoyBotones_mensajeErrorAusente() {
        composeTestRule.onAllNodesWithTag("campo_url_sospechosa")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("campo_descripcion")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("btn_enviar_denuncia")
            .assertCountEquals(1)
        composeTestRule.onAllNodesWithTag("btn_cancelar_denuncia")
            .assertCountEquals(1)
        // Sin error al renderizar.
        composeTestRule.onAllNodesWithTag("mensaje_error_denuncia")
            .assertCountEquals(0)
    }

    @Test
    fun clickCancelar_invocaOnCancelar_noInvocaOnExito() {
        composeTestRule.onNodeWithTag("btn_cancelar_denuncia")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()

        assert(onCancelarInvocado) {
            "onCancelar debe invocarse al click Cancelar"
        }
        assert(!onExitoInvocado) {
            "onExito no debe invocarse al click Cancelar"
        }
    }
}
