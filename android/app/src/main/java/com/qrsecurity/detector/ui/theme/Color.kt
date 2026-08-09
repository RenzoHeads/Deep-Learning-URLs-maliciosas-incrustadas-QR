package com.qrsecurity.detector.ui.theme

import androidx.compose.ui.graphics.Color

// ──────────────────────────────────────────────────────────────────
// QR Guardian Design System — paleta Pencil (F3 rediseño).
//
// Tokens extraídos de los 11 frames Pencil:
//   #0A0F16  fondo base
//   #2DD4BF  acento teal (primary)
//   #F1F5F9  texto principal (white)
//   #8CA3B8  texto secundario (muted)
//   #111B27  superficie card
//   #1B2735  brand mark background
//   #4ADE80  success green
//   #0B1019  modal overlay background
//
// Los nombres legacy (CyberCyan, CyberFondo, ...) se conservan como
// alias para que las ~20 pantallas/componentes existentes que los
// referencian sigan compilando sin cambios. Los valores subyacentes
// se actualizaron a la paleta Pencil.
// ──────────────────────────────────────────────────────────────────

/** Fondo base de la app — azul-negro profundo Pencil. */
val CyberFondo = Color(0xFF0A0F16)
val CyberFondoSuperficie = Color(0xFF0B1019)

/** Superficies card — #111B27 Pencil. */
val CyberGlass = Color(0xFF111B27)
val CyberGlassBorde = Color(0x1AFFFFFF)
val CyberGlassAlto = Color(0xFF1B2735)
val CyberGlassVariant = Color(0xFF1B2735)

/** Teal — acento primario Pencil #2DD4BF. */
val CyberCyan = Color(0xFF2DD4BF)
val CyberCyanClaro = Color(0xFFF1F5F9)
val CyberCyanOn = Color(0xFF0A0F16)

/** Texto — jerarquía Pencil. */
val CyberTextoPrincipal = Color(0xFFF1F5F9)
val CyberTextoSecundario = Color(0xFF8CA3B8)

/** Rojo — alertas maliciosas. */
val CyberRojo = Color(0xFFEF4444)
val CyberRojoClaro = Color(0xFFFFB4AB)
val CyberRojoFondo = Color(0xFF4A1C1C)
val CyberRojoContainer = Color(0xFF93000A)
val CyberRojoOn = Color(0xFFFFDAD6)

/** Ámbar — alertas sospechosas. */
val CyberAmbar = Color(0xFFF9A825)
val CyberAmbarFondo = Color(0xFF3D3500)

/** Verde — veredicto SEGURO + snackbars de éxito Pencil #4ADE80. */
val CyberVerdeAlerta = Color(0xFF4ADE80)
val CyberVerdeAlertaClaro = Color(0xFF86F0B5)
val CyberVerdeFondo = Color(0xFF0F2A1E)

/** Outline / bordes sutiles. */
val CyberOutlineMedio = Color(0xFF8CA3B8)

// ──────────────────────────────────────────────────────────────────
// Nuevos tokens Pencil — usados por las 11 pantallas rediseñadas.
// ──────────────────────────────────────────────────────────────────

/** Brand mark background — #1B2735 Pencil (caja del logo shield). */
val PencilBrandMark = Color(0xFF1B2735)

/** Modal overlay background — #0B1019 Pencil. */
val PencilModalFondo = Color(0xFF0B1019)

/** Overlay scrim para modales — negotiable 60% black. */
val PencilOverlay = Color(0x99000000)

/** Success icon background tint — #4ADE801C (12% alpha). */
val PencilSuccessTint = Color(0x1C4ADE80)

// ──────────────────────────────────────────────────────────────────
// Alias M3 — mapeo a los nombres que MaterialTheme espera.
// ──────────────────────────────────────────────────────────────────

val md_primary = CyberCyan
val md_onPrimary = CyberCyanOn
val md_primaryContainer = PencilBrandMark
val md_onPrimaryContainer = CyberCyan

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
