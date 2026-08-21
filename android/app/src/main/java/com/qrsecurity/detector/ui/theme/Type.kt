package com.qrsecurity.detector.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.qrsecurity.detector.R

// ──────────────────────────────────────────────────────────────────
// QR Guardian Type Scale — Pencil rediseño (F3).
//
// Pencil design tokens:
//   - Headings (Space Grotesk 700): títulos de pantalla, brand name
//   - Body (Inter 400/500/600): texto general, labels, botones
//   - Eyebrow (Inter 600 uppercase letter-spacing): "ACCESO SEGURO", etc.
//
// Fuentes bundladas en res/font/ (subset latin, incluye acentos/ñ/¿¡):
//   - Inter Regular/Medium/SemiBold/Bold
//   - Space Grotesk Bold
// ──────────────────────────────────────────────────────────────────

private val FamiliaInter = FontFamily(
    Font(R.font.inter_400, FontWeight.Normal),
    Font(R.font.inter_500, FontWeight.Medium),
    Font(R.font.inter_600, FontWeight.SemiBold),
    Font(R.font.inter_700, FontWeight.Bold),
)

private val FamiliaSpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_700, FontWeight.Bold),
)

val TipografiaCyberSentinel = Typography(
    // ── Display — Space Grotesk Bold (títulos grandes de pantalla) ──
    displayLarge = TextStyle(
        fontFamily = FamiliaSpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.02).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FamiliaSpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.01).sp
    ),
    displaySmall = TextStyle(
        fontFamily = FamiliaSpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.01).sp
    ),

    // ── Headlines — Space Grotesk Bold (títulos de sección) ──
    headlineLarge = TextStyle(
        fontFamily = FamiliaSpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FamiliaSpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FamiliaSpaceGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),

    // ── Titles — Inter SemiBold/Medium ──
    titleLarge = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),

    // ── Body — Inter Normal/Medium ──
    bodyLarge = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),

    // ── Labels — Inter Medium (eyebrow, chips, captions) ──
    labelLarge = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.06.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FamiliaInter,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.08.sp
    )
)
