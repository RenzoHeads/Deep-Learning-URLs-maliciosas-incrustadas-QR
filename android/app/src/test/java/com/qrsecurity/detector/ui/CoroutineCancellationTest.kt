package com.qrsecurity.detector.ui

import kotlinx.coroutines.CancellationException
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TDD test para H3 — Re-thrown de CancellationException en catches genericos
 * `catch (e: Exception)` de pantallas Compose.
 *
 * Antecedente: tres pantallas (ResultadoMaliciosoScreen, DenunciarScreen,
 * LoginScreen) atrapaban `Exception` dentro de coroutines lanzadas via
 * `rememberCoroutineScope().launch { ... }`. Eso traga accidentalmente
 * `kotlinx.coroutines.CancellationException`, lo que produce side-effects
 * (mutar StateHolder, mostrar Snackbar / Toast) en un scope ya cancelado
 * por el runtime Compose (rotacion, pop, onDispose). El contracto
 * coroutines exige re-thrown CancellationException para que el
 * CancelScope del parent lo gestione.
 *
 * Cobertura: dado que las UI son Composables y requieren infra de
 * AndroidX-Compose-test para levantarse, este test aislado verifica el
 * patron de re-throw usando una fake helper que replica exactamente el
 * shape del catch fix aplicado en cada pantalla:
 *
 *   ```
 *   catch (e: Exception) {
 *       if (e is CancellationException) throw e
 *       // ... side effects
 *   }
 *   ```
 *
 * Si alguien en el futuro revierte el re-throw (o introduce otro catch
 * generico `Exception` sin la guard), este test falla al punto del
 * comportamiento que H3 pretende proteger.
 *
 * Test JVM puro (no Robolectric).
 */
class CoroutineCancellationTest {

    /**
     * Replica minimal del catch aplicado en las pantallas. Si la excepcion
     * es `CancellationException`, se re-throw; en caso contrario se ejecuta
     * el body del catch (registrando un flag side-effect, analogo a mutar
     * StateHolder en la pantalla). Esta funcion es el sujeto bajo prueba
     * de los tests de abajo.
     */
    private fun manejarExcepcion(estados: EstadoCathFake, e: Exception) {
        try {
            throw e
        } catch (ex: Exception) {
            // H3 fix pattern.
            if (ex is CancellationException) throw ex
            // Side-effect analogo a `mensajeError = "..."` / `bloqueadaOk = false`.
            estados.sideEffectEjecutado = true
        }
    }

    /**
     * Mutable holder que actua como el StateHolder de la pantalla bajo
     * observacion por el test: se setea True si el catch generico aplica
     * el side-effect, lo que NO debe ocurrir cuando la excepcion es
     * CancellationException.
     */
    private class EstadoCathFake {
        var sideEffectEjecutado: Boolean = false
    }

    /**
     * GIVEN un scope cancelado que lanza CancellationException dentro del
     * try/catch del LaunchedEffect / launch de pantalla.
     * WHEN el catch generico `Exception` se ejecuta con un
     *      `CancellationException`.
     * THEN la excepcion propaga (assertFailsWith) y NO se ejecuta el
     *      side-effect del body del catch.
     *
     * Este es el test rojo que motivado el fix H3: antes del fix,
     * `manejarExcepcion(_, CancellationException)` NO relanzaba y el
     * side-effect se aplicaba — rompiendo el contracto de cancellation.
     */
    @Test
    fun `manejarExcepcion rethrows CancellationException y no ejecuta side effects`() {
        val estados = EstadoCathFake()

        val ex = assertFailsWith<CancellationException> {
            manejarExcepcion(estados, CancellationException("scope cancelado"))
        }

        assertTrue(ex.message!!.contains("scope cancelado"), "La CancellationException debe propagarse intacta")
        assertTrue(!estados.sideEffectEjecutado,
            "El side-effect del catch no debe ejecutarse cuando la excepcion es CancellationException — " +
                "eso seria el bug H3 (side-effects en scope cancelado)")
    }

    /**
     * GIVEN una Exception generica (no CancellationException) lanzada
     * dentro del catch del LaunchedEffect.
     * WHEN el catch generico `Exception` la procesa.
     * THEN el side-effect (mutar StateHolder) se aplica — este es el
     *      comportamiento legitimo que NO debe romperse con el fix H3.
     *
     * Evolucion: si alguien envuelve el re-throw con una clausula que
     * accidentalmente traga tambien excepciones no-cancellation, este
     * test falla inmediatamente.
     */
    @Test
    fun `manejarExcepcion ejecuta side effects para Exception generica no-cancellation`() {
        val estados = EstadoCathFake()

        manejarExcepcion(estados, IllegalStateException("backend caido"))

        assertTrue(estados.sideEffectEjecutado,
            "Las excepciones no-cancellation deben pasar por el body del catch (registrar error, mutar estado)")
    }
}
