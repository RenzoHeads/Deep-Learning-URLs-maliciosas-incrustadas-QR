package com.qrsecurity.detector.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ──────────────────────────────────────────────────────────────────
// QR Guardian — esquema de color oscuro fijo (Pencil rediseño F3).
// La app es siempre oscura; la paleta Pencil (#0A0F16 fondo,
// #2DD4BF teal, #F1F5F9 texto) se diseñó exclusivamente para modo oscuro.
// ──────────────────────────────────────────────────────────────────

private val EsquemaCyberSentinel = darkColorScheme(
    primary = md_primary,
    onPrimary = md_onPrimary,
    primaryContainer = md_primaryContainer,
    onPrimaryContainer = md_onPrimaryContainer,
    secondary = md_secondary,
    onSecondary = md_onSecondary,
    secondaryContainer = md_secondaryContainer,
    onSecondaryContainer = md_onSecondaryContainer,
    tertiary = md_tertiary,
    onTertiary = md_onTertiary,
    tertiaryContainer = md_tertiaryContainer,
    onTertiaryContainer = md_onTertiaryContainer,
    background = md_background,
    onBackground = md_onBackground,
    surface = md_surface,
    onSurface = md_onSurface,
    surfaceVariant = md_surfaceVariant,
    onSurfaceVariant = md_onSurfaceVariant,
    outline = md_outline,
    error = md_error,
    onError = md_onError,
    errorContainer = md_errorContainer,
    onErrorContainer = md_onErrorContainer
)

// Esquema claro — mantenido por completitud pero la app siempre usa oscuro.
private val ColoresClaros = lightColorScheme(
    primary = md_primary,
    onPrimary = md_onPrimary,
    primaryContainer = md_primaryContainer,
    onPrimaryContainer = md_onPrimaryContainer,
    secondary = md_secondary,
    onSecondary = md_onSecondary,
    secondaryContainer = md_secondaryContainer,
    onSecondaryContainer = md_onSecondaryContainer,
    tertiary = md_tertiary,
    onTertiary = md_onTertiary,
    tertiaryContainer = md_tertiaryContainer,
    onTertiaryContainer = md_onTertiaryContainer,
    background = md_background,
    onBackground = md_onBackground,
    surface = md_surface,
    onSurface = md_onSurface,
    surfaceVariant = md_surfaceVariant,
    onSurfaceVariant = md_onSurfaceVariant,
    outline = md_outline,
    error = md_error,
    onError = md_onError,
    errorContainer = md_errorContainer,
    onErrorContainer = md_onErrorContainer
)

/**
 * Tema Compose de la aplicacion — Cyber-Sentinel.
 *
 * La app fue diseñada exclusivamente para modo oscuro con fondo #0A0E1A
 * y acentos cyan; el design system de Stitch no define esquema claro.
 *
 * Bug 19 fix: antes los parametros `temaOscuro` y `colorDinamico` estaban
 * declarados con defaults (true / false) pero el cuerpo los ignoraba y
 * hardcodeaba `EsquemaCyberSentinel`. El contrato de la firma sugeria que
 * el llamador controlaba el modo, pero en realidad no tenia efecto.
 *
 * Ahora honramos los parametros:
 *  - `temaOscuro` = true (default)  → EsquemaCyberSentinel
 *  - `temaOscuro` = false          → ColoresClaros
 *  - `colorDinamico` = true (API >= 31) → dynamicDark/LightColorScheme(LocalContext)
 *  - `colorDinamico` = false (default)  → paleta cyber-sentinel fija
 *
 * Nota: los callers actuales pasan defaults (oscuro, no dinamico), asi que
 * el comportamiento runtime de la app no cambia; el cambio hace que la
 * firma sea honesta y permite pruebas con esquema claro o Material You.
 *
 * @param temaOscuro true → esquema oscuro (default, recomendado).
 * @param colorDinamico true → Material You dynamic colors (API >= 31).
 */
@Composable
fun TemaDetectorSeguridadQR(
    temaOscuro: Boolean = true,
    colorDinamico: Boolean = false,
    content: @Composable () -> Unit
) {
    // Bug 19 fix: honrar los parametros en lugar de ignorarlos.
    val context = LocalContext.current
    val esquemaColor = when {
        colorDinamico && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (temaOscuro) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        temaOscuro -> EsquemaCyberSentinel
        else -> ColoresClaros
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Bug 19 fix + deprecation fix: MainActivity ya llama a enableEdgeToEdge()
            // que gestiona traslucidez de barras del sistema. Aqui solo ajustamos
            // la apariencia de los iconos de la status bar segun el modo activo.
            // No tocamos statusBarColor/navigationBarColor (deprecated en API 35+).
            WindowCompat.getInsetsController((view.context as Activity).window, view)
                .isAppearanceLightStatusBars = !temaOscuro
        }
    }

    MaterialTheme(
        colorScheme = esquemaColor,
        typography = TipografiaCyberSentinel,
        content = content
    )
}
