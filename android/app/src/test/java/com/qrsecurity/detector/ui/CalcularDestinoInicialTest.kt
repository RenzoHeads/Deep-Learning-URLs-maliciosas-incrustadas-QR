package com.qrsecurity.detector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests unitarios de [calcularDestinoInicial] — la funcion pura que
 * decide la ruta de inicio del NavHost en funcion del estado de sesion
 * y onboarding (Bug A11 fix).
 *
 * Cubre las 3 ramas del `when`:
 *  1. No logueado -> LOGIN (sin importar onboardingDone).
 *  2. Logueado + onboarding incompleto -> ONBOARDING.
 *  3. Logueado + onboarding completo -> ESCANEAR (home).
 *
 * Adicionalmente verifica el orden de prioridad:
 *  - `logueado=false` siempre da LOGIN incluso si `onboardingDone=true`
 *    (no tiene sentido mostrar onboarding sin sesion, porque la app
 *    requiere login antes de pantallas con datos).
 *
 * Estrategia:
 *  - Funcion pura, sin Context ni SharedPreferences -> NO requiere
 *    Robolectric. Test JVM puro, milisegundos.
 *  - Cubre los 8 pares (logueado, onboardingDone) aunque solo 3 son
 *    alcanzables en produccion, para documentar el comportamiento
 *    completo y prevenir regresiones si alguien reordena el `when`.
 */
class CalcularDestinoInicialTest {

    // ── Rama 1: no logueado -> LOGIN ──

    @Test
    fun noLogueado_onboardingFalse_devuelveLogin() {
        assertEquals(
            "Sin sesion, la app debe arrancar en Login",
            Rutas.LOGIN,
            calcularDestinoInicial(logueado = false, onboardingDone = false)
        )
    }

    @Test
    fun noLogueado_onboardingTrue_devuelveLogin() {
        // Prioridad: sesion > onboarding. Aunque onboarding este hecho,
        // si no hay sesion, vamos a Login (no podemos mostrar Onboarding
        // a un usuario no autenticado porque la pantalla asume sesion).
        assertEquals(
            "Sin sesion, siempre LOGIN aunque onboarding este completo",
            Rutas.LOGIN,
            calcularDestinoInicial(logueado = false, onboardingDone = true)
        )
    }

    // ── Rama 2: logueado + onboarding incompleto -> ONBOARDING ──

    @Test
    fun logueado_onboardingFalse_devuelveOnboarding() {
        assertEquals(
            "Logueado sin onboarding previo, debe arrancar en Onboarding",
            Rutas.ONBOARDING,
            calcularDestinoInicial(logueado = true, onboardingDone = false)
        )
    }

    // ── Rama 3: logueado + onboarding completo -> ESCANEAR ──

    @Test
    fun logueado_onboardingTrue_devuelveEscanear() {
        assertEquals(
            "Logueado y onboarding completo, debe arrancar en Escanear (home)",
            Rutas.ESCANEAR,
            calcularDestinoInicial(logueado = true, onboardingDone = true)
        )
    }

    // ── Orden de prioridad: sesion > onboarding ──

    @Test
    fun sesionTienePrioridadSobreOnboarding() {
        // La combinacion (logueado=false, onboardingDone=true) NO debe
        // producir ESCANEAR — sesion siempre gana.
        val destino = calcularDestinoInicial(logueado = false, onboardingDone = true)
        assertEquals(
            "Sesion debe tener prioridad sobre onboarding",
            Rutas.LOGIN,
            destino
        )
    }
}
