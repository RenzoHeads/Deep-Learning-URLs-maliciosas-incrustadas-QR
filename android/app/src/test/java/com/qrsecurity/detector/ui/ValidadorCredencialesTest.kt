package com.qrsecurity.detector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S2 (auditoría frontend): [ValidadorCredenciales] es la política de
 * validación compartida Login/Registro — antes la regex y los mensajes
 * vivían duplicados byte a byte en ambos ViewModels. Test JVM puro.
 */
class ValidadorCredencialesTest {

    // ── campos completos ──

    @Test
    fun `campos completos acepta correo y password no vacios`() {
        assertNull(ValidadorCredenciales.validarCamposCompletos("a@b.com", "una-password-larga"))
    }

    @Test
    fun `campos completos rechaza correo en blanco`() {
        assertEquals(
            "Completa todos los campos.",
            ValidadorCredenciales.validarCamposCompletos("  ", "una-password-larga")
        )
    }

    @Test
    fun `campos completos rechaza password en blanco`() {
        assertEquals(
            "Completa todos los campos.",
            ValidadorCredenciales.validarCamposCompletos("a@b.com", "")
        )
    }

    // ── formato de correo ──

    @Test
    fun `correo valido pasa`() {
        assertNull(ValidadorCredenciales.validarCorreo("usuario@dominio.com"))
    }

    @Test
    fun `correo sin arroba o sin dominio se rechaza`() {
        assertEquals(
            "El correo no tiene un formato válido.",
            ValidadorCredenciales.validarCorreo("usuariodominio.com")
        )
        assertEquals(
            "El correo no tiene un formato válido.",
            ValidadorCredenciales.validarCorreo("usuario@")
        )
    }

    // ── política de password de Auth0 (15-72, sin clases) ──

    @Test
    fun `password de exactamente 15 y 72 caracteres pasa`() {
        assertNull(ValidadorCredenciales.validarPassword("a".repeat(15)))
        assertNull(ValidadorCredenciales.validarPassword("a".repeat(72)))
    }

    @Test
    fun `password de 14 caracteres se rechaza`() {
        assertEquals(
            "La contraseña debe tener al menos 15 caracteres.",
            ValidadorCredenciales.validarPassword("a".repeat(14))
        )
    }

    @Test
    fun `password de 73 caracteres se rechaza`() {
        assertEquals(
            "La contraseña no puede superar los 72 caracteres.",
            ValidadorCredenciales.validarPassword("a".repeat(73))
        )
    }

    // ── confirmación ──

    @Test
    fun `confirmacion distinta se rechaza`() {
        assertEquals(
            "Las contraseñas no coinciden.",
            ValidadorCredenciales.validarConfirmacion("a".repeat(15), "b".repeat(15))
        )
        assertNull(ValidadorCredenciales.validarConfirmacion("a".repeat(15), "a".repeat(15)))
    }
}
