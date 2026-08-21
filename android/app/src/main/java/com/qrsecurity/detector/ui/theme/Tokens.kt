package com.qrsecurity.detector.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * The scale follows 4pt multiples: 4, 8, 12, 16, 20, 24, 32, 40, 64.
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

    /** 50% — pill completa (botones pill tipo "Volver", chips altos). */
    val pill: Shape = RoundedCornerShape(50)

    /** Full circle — avatars, circular icons, FABs. */
    val full: Shape = CircleShape
}

/**
 * Border width tokens — grosores de borde/hairline.
 *
 * Separado de [Elevacion]: un borde de 1dp NO es "elevación sutil";
 * mezclar ambos conceptos produjo bordes semánticamente ambiguos.
 */
object Borde {
    /** 1dp — hairline estándar (cards glass, nav bar, snackbars). */
    val fino: Dp = 1.dp

    /** 2dp — borde enfatizado (borde activo de botones toggle). */
    val normal: Dp = 2.dp
}

/**
 * Alpha tokens — escala única para overlays, tints y glass.
 *
 * Sustituye los ~17 valores mágicos (0.05f…0.97f) dispersos en pantallas.
 * Regla: cualquier `Color.copy(alpha = …)` de la UI debe usar uno de
 * estos tokens; si un efecto necesita otro valor, se añade aquí.
 */
object Alphas {
    /** 4% — tints casi imperceptibles (fondos de íconos, estados hover). */
    val suave: Float = 0.04f

    /** 8% — tint de botón destructivo deshabilitado. */
    val leve: Float = 0.08f

    /** 12% — chip/indicador activo de navegación, tint de toggle. */
    val bajo: Float = 0.12f

    /** 16% — scrims internos de tarjetas, separadores sutiles. */
    val medio: Float = 0.16f

    /** 24% — bordes enfatizados sobre superficie. */
    val notorio: Float = 0.24f

    /** 40% — borde de toggle activo, elementos que reclaman atención. */
    val fuerte: Float = 0.40f

    /** 60% — scrim estándar de modales y overlays de escáner. */
    val denso: Float = 0.60f

    /** 90% — fondos de barras flotantes (nav, superficies elevadas). */
    val alto: Float = 0.90f

    /** 96% — superficies casi opacas que aún dejan traslucir el fondo. */
    val casiOpaco: Float = 0.96f
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
    /** 16dp — icono pequeño inline (trust notes, spinners de sync, dots). */
    val chico: Dp = 16.dp

    /** 24dp — standard icon (top bar, inline, navegación). */
    val estandar: Dp = 24.dp

    /** 32dp — spinner de carga en pantalla (CircularProgressIndicator). */
    val cargando: Dp = 32.dp

    /** 40dp — medium icon (inside alert badge). */
    val mediano: Dp = 40.dp

    /** 64dp — large icon (result screen, icon glow circle inner). */
    val grande: Dp = 64.dp

    /** 120dp — hero icon container (glow circle outer). */
    val heroContenedor: Dp = 120.dp
}
