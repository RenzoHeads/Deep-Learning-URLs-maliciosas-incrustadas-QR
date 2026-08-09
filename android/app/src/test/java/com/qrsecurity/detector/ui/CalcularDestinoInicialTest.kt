package com.qrsecurity.detector.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests unitarios de [calcularDestinoInicial] — la funcion pura que
 * decide la ruta de inicio del NavHost en funcion del estado de sesion.
 *
 * F2.2: onboarding eliminado. Solo 2 ramas:
 *  1. No logueado -> LOGIN.
 *  2. Logueado -> HOME.
 *
 * Estrategia:
 *  - Funcion pura, sin Context ni SharedPreferences -> NO requiere
 *    Robolectric. Test JVM puro, milisegundos.
 */
class CalcularDestinoInicialTest {

    // ── Rama 1: no logueado -> LOGIN ──

    @Test
    fun noLogueado_devuelveLogin() {
        assertEquals(
            "Sin sesion, la app debe arrancar en Login",
            Rutas.LOGIN,
            calcularDestinoInicial(logueado = false),
        )
    }

    // ── Rama 2: logueado -> HOME ──

    @Test
    fun logueado_devuelveHome() {
        assertEquals(
            "Con sesion, debe arrancar en Home",
            Rutas.HOME,
            calcularDestinoInicial(logueado = true),
        )
    }
}
