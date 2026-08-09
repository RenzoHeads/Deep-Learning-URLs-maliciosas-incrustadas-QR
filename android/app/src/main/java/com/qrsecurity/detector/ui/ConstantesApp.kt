package com.qrsecurity.detector.ui

/**
 * Constantes compartidas de la app QR Guardian.
 *
 * Migradas de [OnboardingScreen] (que sera eliminada en F3) para que
 * [NavGuardian] las consuma sin depender de una pantalla eliminada.
 *
 * Nota: el valor real de [PREFS_QR_GUARDIAN] es `"qr_guardian_prefs"` (no
 * `"PrefsQrGuardian"` como se propuso en una version previa del plan). Se
 * preserva el valor historical para no invalidar SharedPreferences
 * existentes en instalaciones en produccion.
 */
object ConstantesApp {
    const val PREFS_QR_GUARDIAN = "qr_guardian_prefs"
    const val CLAVE_ONBOARDING_COMPLETADO = "onboarding_completado"
}
