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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pruebas Compose UI de [PantallaAcerca] — cubren el fix H3 (Lote H):
 * el boton "Cerrar sesion" debe mostrar un dialogo de confirmacion
 * [AlertDialog] antes de invocar el callback [onCerrarSesion]. Cerrar
 * sesion es irreversible (vacia Room + borra token), asi que el dialogo
 * protege contra taps accidentales.
 *
 * Estrategia de test:
 * - `@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [33])` para
 *   inicializar `Build.FINGERPRINT` y los campos de Robolectric.
 * - `@Config(application = TestApplication::class)` evita que Robolectric
 *   instancie [com.qrsecurity.detector.AppSeguridadQR], cuyo `onCreate`
 *   invoca `MediadorSincronizacion.programarSyncPeriodica()` el cual llama
 *   `WorkManager.getInstance(context)` y lanza `IllegalStateException`
 *   porque WorkManager no esta inicializado en Robolectric. Ver
 *   [TestApplication] para detalle.
 * - `createAndroidComposeRule<ComponentActivity>()` porque Robolectric
 *   necesita una Activity concreta registrada (no createComposeRule).
 * - `MaterialTheme { Surface { ... } }` envuelve el composable para
 *   tener un contenedor raiz con size constraints reales.
 * - `Modifier.testTag(...)` en los 4 nodos clave (btn_logout_pantalla,
 *   dialog_logout_text, btn_confirmar_logout, btn_cancelar_logout) resuelve
 *   la ambiguedad de los dos nodos con texto "Cerrar sesion" (boton
 *   pantalla + confirmButton del dialogo) que hacia que
 *   `onAllNodesWithText("Cerrar sesion")[0].performClick()` fuera no
 *   determinista bajo Robolectric.
 * - `performScrollTo()` antes de `performClick()` en el boton de la
 *   pantalla porque esta al final de un `verticalScroll` Column;
 *   Robolectric no tiene Display real y los bounds del nodo fuera del
 *   viewport son 0, lo que hacia que `performClick()` fuera un no-op.
 * - `onAllNodesWithTag("dialog_logout_text").assertCountEquals(N)` en
 *   vez de `assertIsDisplayed()/assertIsNotDisplayed()` porque Compose UI
 *   test 1.6.x (BOM 2024.03.00) no expone `assertExists`/`assertDoesNotExist`
 *   (se agregaron en 1.7+) y `assertIsDisplayed` reporta 0 bounds bajo
 *   Robolectric sin Display real. `assertCountEquals(1)` verifica
 *   presencia en el arbol de composicion; `assertCountEquals(0)` verifica
 *   ausencia — exactamente lo que controla el flag `mostrarDialogoLogout`.
 * - `mainClock.autoAdvance = true` en `@Before` + `waitForIdle()` tras
 *   cada click para que el reloj de Compose avance y aplique la
 *   recomposicion pendiente antes de la asercion.
 *
 * Casos:
 *  1. Boton "Cerrar sesion" de la pantalla existe al renderizar (tag
 *     `btn_logout_pantalla` count=1, dialog `dialog_logout_text` count=0).
 *  2. Click en `btn_logout_pantalla` hace aparecer el dialogo
 *     (`dialog_logout_text` count=0 → 1).
 *  3. Click en `btn_cancelar_logout` hace desaparecer el dialogo
 *     (`dialog_logout_text` count=1 → 0) sin invocar onCerrarSesion.
 *  4. Click en `btn_confirmar_logout` invoca onCerrarSesion y hace
 *     desaparecer el dialogo (`dialog_logout_text` count=1 → 0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.qrsecurity.detector.TestApplication::class)
class AcercaScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Holder mutable para capturar la invocacion de onCerrarSesion.
     * Como `setContent` solo puede llamarse una vez por test
     * (IllegalStateException en la segunda llamada), el callback se
     * redirige a este holder mutable que cada test resetea a false
     * y lee despues del click.
     */
    private var callbackInvocado = false

    @Before
    fun setUp() {
        // Forzar el reloj de Compose a auto-avanzar (necesario bajo
        // Robolectric donde no hay Choreographer real).
        composeTestRule.mainClock.autoAdvance = true

        // Reset del holder antes de cada test.
        callbackInvocado = false

        // setContent se llama UNA sola vez por test. El callback
        // onCerrarSesion se redirige al holder `callbackInvocado`.
        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    PantallaAcerca(
                        onVolver = {},
                        onCerrarSesion = { callbackInvocado = true }
                    )
                }
            }
        }

        // Esperar a que el layout inicial se estabilice.
        composeTestRule.waitForIdle()
    }

    @Test
    fun botonCerrarSesion_existeAlRenderizar_yDialogoNoVisible() {
        // El boton "Cerrar sesion" de la pantalla existe (count=1).
        composeTestRule.onAllNodesWithTag("btn_logout_pantalla")
            .assertCountEquals(1)

        // El dialogo NO esta visible inicialmente (count=0).
        composeTestRule.onAllNodesWithTag("dialog_logout_text")
            .assertCountEquals(0)
    }

    @Test
    fun clickCerrarSesion_haceAparecerDialogoConfirmacion() {
        // Scroll hasta el boton (esta al final de la lista scrollable).
        composeTestRule.onNodeWithTag("btn_logout_pantalla")
            .performScrollTo()
            .performClick()

        composeTestRule.waitForIdle()

        // El mensaje del dialogo debe aparecer (count=1).
        composeTestRule.onAllNodesWithTag("dialog_logout_text")
            .assertCountEquals(1)
    }

    @Test
    fun clickCancelar_desapareceDialogoSinInvocarCallback() {
        // Abrir dialogo.
        composeTestRule.onNodeWithTag("btn_logout_pantalla")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("dialog_logout_text")
            .assertCountEquals(1)

        // Click en "Cancelar" (dismissButton del dialogo).
        composeTestRule.onNodeWithTag("btn_cancelar_logout")
            .performClick()
        composeTestRule.waitForIdle()

        // El dialogo debe desaparecer (count=0).
        composeTestRule.onAllNodesWithTag("dialog_logout_text")
            .assertCountEquals(0)

        // El callback NO debe haberse invocado.
        assert(!callbackInvocado) {
            "onCerrarSesion no debe invocarse al cancelar el dialogo"
        }
    }

    @Test
    fun clickCerrarSesionEnDialogo_invocaCallbackYDesapareceDialogo() {
        // Abrir dialogo.
        composeTestRule.onNodeWithTag("btn_logout_pantalla")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithTag("dialog_logout_text")
            .assertCountEquals(1)

        // Click en confirmButton del dialogo.
        composeTestRule.onNodeWithTag("btn_confirmar_logout")
            .performClick()
        composeTestRule.waitForIdle()

        // El callback debe haberse invocado.
        assert(callbackInvocado) {
            "onCerrarSesion debe invocarse al confirmar el dialogo"
        }

        // El dialogo debe desaparecer (count=0).
        composeTestRule.onAllNodesWithTag("dialog_logout_text")
            .assertCountEquals(0)
    }
}
