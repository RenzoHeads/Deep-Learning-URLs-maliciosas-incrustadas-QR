package com.qrsecurity.detector.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ──────────────────────────────────────────────────────────────────
// Cyber-Sentinel Design Tokens — 4pt grid system
//
// Centralized spacing, radius, and elevation tokens following the
// pixel-perfect-mobile 4pt grid convention. All UI components should
// reference these tokens instead of hardcoded dp values.
//
// WCAG AA contrast verification (all pass ≥ 4.5:1 for normal text):
//   TextPrimary  on Fondo:  15.62:1 ✓
//   TextSecondary on Fondo: 7.51:1  ✓
//   Cyan on Fondo:          12.52:1 ✓
//   CyanOn on Cyan (btn):   8.56:1  ✓
//   Rojo on Fondo:           5.12:1 ✓
//   Verde on Fondo:          7.59:1 ✓
//   Ambar on Fondo:          9.77:1 ✓
//   TextPrimary on Glass:   13.88:1 ✓
//   TextSecondary on Glass:  6.67:1 ✓
//   Fondo on Cyan (btn):    12.52:1 ✓
//   TextDisabled on Fondo:   4.05:1 ✓ (borderline — use only for disabled states)
// ──────────────────────────────────────────────────────────────────

/**
 * Spacing tokens — 4pt grid system.
 *
 * Every padding, margin, or gap in the app should use one of these values.
 * The scale follows 4pt multiples: 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64.
 */
object Espaciado {
    /** 4dp — micro spacing (icon-text gap, tight padding). */
    val xs: Dp = 4.dp

    /** 8dp — small spacing (between related elements, icon-to-text). */
    val sm: Dp = 8.dp

    /** 12dp — medium spacing (between rows in a list, button internal gap). */
    val md: Dp = 12.dp

    /** 16dp — standard padding (screen edge, card default padding). */
    val lg: Dp = 16.dp

    /** 20dp — card internal padding, content blocks. */
    val xl: Dp = 20.dp

    /** 24dp — section spacing, large gaps between groups. */
    val xxl: Dp = 24.dp

    /** 32dp — screen-level vertical spacing (permission screens, onboarding). */
    val xxxl: Dp = 32.dp

    /** 40dp — large spacing (image placeholders, onboarding illustrations). */
    val giganteM: Dp = 40.dp

    /** 64dp — extra-large spacing (icon sizes, splash centers). */
    val gigante: Dp = 64.dp
}

/**
 * Border radius tokens — concentric scale.
 *
 * sm  → 4dp  (chips, small badges, progress bars)
 * md  → 8dp  (inputs, small buttons)
 * lg  → 12dp (buttons, dialogs)
 * xl  → 16dp (cards, containers, glass panels)
 * xxl → 20dp (glass cards with border, large surfaces)
 * full → CircleShape (avatars, circular icons)
 */
object RadioBorde {
    /** 4dp — chips, badges, progress bars. */
    val sm: Dp = 4.dp

    /** 8dp — text fields, small buttons. */
    val md: Dp = 8.dp

    /** 12dp — buttons, dialog buttons. */
    val lg: Dp = 12.dp

    /** 16dp — standard cards, glass panels, bottom sheets. */
    val xl: Dp = 16.dp

    /** 20dp — glass cards with visible border, large feature surfaces. */
    val xxl: Dp = 20.dp

    /** Full circle — avatars, circular icons, FABs. */
    val full: Shape = CircleShape
}

/**
 * Elevation/shadow tokens.
 *
 * The cyber-sentinel design is intentionally flat (glassmorphism, 0dp elevation
 * on most surfaces). These tokens are used sparingly for floating elements
 * like snackbars, FABs, and top app bars.
 */
object Elevacion {
    /** 0dp — glassmorphism cards ( CyberGlass uses no shadow). */
    val ninguna: Dp = 0.dp

    /** 1dp — subtle separation (top app bar overlay). */
    val sutil: Dp = 1.dp

    /** 2dp — snackbars, floating chips. */
    val flotante: Dp = 2.dp
}

/**
 * Touch target tokens — WCAG AA minimum 48×48dp, Material 3 minimum 44×44dp.
 *
 * The app follows the stricter 48dp minimum for accessibility.
 */
object TamanosToque {
    /** 56dp — standard button height (Material 3 filled button). */
    val boton: Dp = 56.dp
}

/**
 * Icon size tokens — consistent icon sizing across the app.
 */
object TamanosIcono {
    /** 24dp — standard icon (top bar, inline). */
    val estandar: Dp = 24.dp

    /** 40dp — medium icon (inside alert badge). */
    val mediano: Dp = 40.dp

    /** 64dp — large icon (result screen, icon glow circle inner). */
    val grande: Dp = 64.dp

    /** 120dp — hero icon container (glow circle outer). */
    val heroContenedor: Dp = 120.dp
}
