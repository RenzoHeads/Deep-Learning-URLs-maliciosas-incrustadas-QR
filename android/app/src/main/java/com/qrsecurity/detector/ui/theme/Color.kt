package com.qrsecurity.detector.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────
// Cyber-Sentinel Design System — paleta oscura extraída de Stitch.
// Fondo profundo #0A0E1A, acentos cyan #00e5ff, alertas rojo #ef4444.
// Tarjetas glassmorphism: rgba(22,27,44,0.6) + blur(12px).
// ──────────────────────────────────────────────────────────────────

/** Fondo base de la app — azul-negro profundo. */
val CyberFondo = Color(0xFF0A0E1A)
val CyberFondoSuperficie = Color(0xFF0D1516)


/** Superficies glassmorphism — translúcidas con tinte azulado. */
val CyberGlass = Color(0xFF161B2C)
val CyberGlassBorde = Color(0x0DFFFFFF)
val CyberGlassAlto = Color(0xFF242B2D)
val CyberGlassVariant = Color(0xFF2E3638)

/** Cyan — acento primario del design system. */
val CyberCyan = Color(0xFF00E5FF)           // primary-container (acento principal)
val CyberCyanClaro = Color(0xFFC3F5FF)      // primary text sobre oscuro
val CyberCyanOn = Color(0xFF00363D)         // on-primary (texto sobre cyan)
val CyberCyanContainerOn = Color(0xFF001F24)// on-primary-fixed

/** Texto — jerarquía sobre fondo oscuro. */
val CyberTextoPrincipal = Color(0xFFE2E8F0)  // on-surface
val CyberTextoSecundario = Color(0xFF94A3B8) // on-surface-variant

/** Rojo — alertas maliciosas. */
val CyberRojo = Color(0xFFEF4444)            // error / amenaza principal
val CyberRojoClaro = Color(0xFFFFB4AB)       // error light
val CyberRojoFondo = Color(0xFF4A1C1C)      // fondo sutil rojo
val CyberRojoContainer = Color(0xFF93000A)   // error-container
val CyberRojoOn = Color(0xFFFFDAD6)          // on-error-container

/** Ámbar — alertas sospechosas. */
val CyberAmbar = Color(0xFFF9A825)
val CyberAmbarFondo = Color(0xFF3D3500)

/** Verde esmeralda — veredicto SEGURO + snackbars de exito. */
val CyberVerdeAlerta = Color(0xFF10B981)        // verde esmeralda — veredicto seguro
val CyberVerdeAlertaClaro = Color(0xFF6EE7B7)   // variante clara para texto/icono
val CyberVerdeAlertaFondo = Color(0xFF0D3326)   // fondo sutil verde
val CyberVerdeFondo = Color(0xFF0F2A1E)         // snackbar exito bg

/** Outline / bordes sutiles. */
val CyberOutlineMedio = Color(0xFF849396)    // outline

// ──────────────────────────────────────────────────────────────────
// Alias M3 — mapeo a los nombres que MaterialTheme espera.
// ──────────────────────────────────────────────────────────────────

val md_primary = CyberCyanClaro
val md_onPrimary = CyberCyanOn
val md_primaryContainer = CyberCyan
val md_onPrimaryContainer = CyberCyanContainerOn

val md_secondary = CyberRojoClaro
val md_onSecondary = Color(0xFF690003)
val md_secondaryContainer = Color(0xFFC5020B)
val md_onSecondaryContainer = Color(0xFFFFD2CC)

val md_tertiary = Color(0xFFE4EDFF)
val md_onTertiary = Color(0xFF213145)
val md_tertiaryContainer = Color(0xFFC1D2EC)
val md_onTertiaryContainer = Color(0xFF4A5A70)

val md_background = CyberFondo
val md_onBackground = CyberTextoPrincipal
val md_surface = CyberFondoSuperficie
val md_onSurface = CyberTextoPrincipal
val md_surfaceVariant = CyberGlassVariant
val md_onSurfaceVariant = CyberTextoSecundario
val md_outline = CyberOutlineMedio
val md_error = CyberRojoClaro
val md_onError = Color(0xFF690005)
val md_errorContainer = CyberRojoContainer
val md_onErrorContainer = CyberRojoOn

// ──────────────────────────────────────────────────────────────────
// Colores de alerta — base (alias Alerta* eliminados con AlertUI.kt).
// Los colores subyacentes CyberVerdeAlerta, CyberAmbar, CyberRojo se
// mantienen activos via ResultadoSeguroScreen / ResultadoMaliciosoScreen.
// ──────────────────────────────────────────────────────────────────
