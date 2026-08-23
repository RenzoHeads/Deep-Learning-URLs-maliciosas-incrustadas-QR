package com.qrsecurity.detector.ui

/**
 * Política de validación de credenciales compartida por Login y Registro
 * (S2 — auditoría frontend): antes la regex y los mensajes vivían
 * duplicados byte a byte en ambos ViewModels (`PATRON_CORREO` copiada, mismos
 * strings), y el próximo cambio de política iba a tocar solo una de las dos
 * copias. Funciones puras → testeables en JVM sin Auth0 ni Android.
 */
internal object ValidadorCredenciales {

    /** RFC-lite: algo@algo.algo. */
    private val PATRON_CORREO = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /**
     * Mensaje del primer problema con los campos obligatorios comunes
     * (correo + password), o null si están completos. El blank del correo
     * también lo cubre este chequeo (se evalúa antes que el formato).
     */
    fun validarCamposCompletos(correo: String, password: String): String? =
        if (correo.isBlank() || password.isBlank()) {
            "Completa todos los campos."
        } else {
            null
        }

    /** Mensaje si el correo no matchea el patrón, o null si es válido. */
    fun validarCorreo(correo: String): String? =
        if (!PATRON_CORREO.matches(correo)) {
            "El correo no tiene un formato válido."
        } else {
            null
        }

    /**
     * Política real de la database connection de Auth0 (verificada contra
     * el tenant): longitud 15-72, sin requisitos de clases.
     */
    fun validarPassword(password: String): String? = when {
        password.length < 15 -> "La contraseña debe tener al menos 15 caracteres."
        password.length > 72 -> "La contraseña no puede superar los 72 caracteres."
        else -> null
    }

    /** Mensaje si la confirmación no coincide con la password. */
    fun validarConfirmacion(password: String, confirmarPassword: String): String? =
        if (password != confirmarPassword) {
            "Las contraseñas no coinciden."
        } else {
            null
        }
}
