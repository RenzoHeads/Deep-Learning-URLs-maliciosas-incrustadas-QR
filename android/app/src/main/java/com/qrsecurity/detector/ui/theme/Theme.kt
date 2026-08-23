package com.qrsecurity.detector.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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

// Audit fix M8: se elimino `ColoresClaros` — era inalcanzable (ningun
// caller pasa temaOscuro=false) y ademas replicaba EXACTAMENTE los mismos
// valores del esquema oscuro (no era una paleta clara real). Si algun dia
// se necesita modo claro, definir una paleta light genuina.

/**
 * Tema Compose de la aplicacion — Cyber-Sentinel.
 *
 * La app fue diseñada exclusivamente para modo oscuro con fondo #0A0E1A
 * y acentos cyan; el design system no define esquema claro. La paleta es
 * FIJA: la UI referencia los tokens Cyber y Pencil directamente, no
 * colorScheme.
 */
@Composable
fun TemaDetectorSeguridadQR(content: @Composable () -> Unit) {
    // M22 (auditoría frontend): eliminados los params `temaOscuro` /
    // `colorDinamico` — eran una trampa latente: ningún caller los pasaba y
    // activar `colorDinamico` habría dejado un híbrido roto (el 95% de la
    // UI referencia tokens Cyber*/Pencil* fijos, no colorScheme). La app
    // es siempre oscura con la paleta Pencil.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // Bug 19 fix + deprecation fix: MainActivity ya llama a
            // enableEdgeToEdge(). Aqui solo ajustamos la apariencia de los
            // iconos de la status bar (siempre claros sobre fondo oscuro).
            //
            // Audit fix P8: cast seguro — el host puede no ser una Activity
            // (p.ej. unos entornos de preview/ test); sin window no hay nada
            // que ajustar.
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = EsquemaCyberSentinel,
        typography = TipografiaCyberSentinel,
        content = content
    )
}
