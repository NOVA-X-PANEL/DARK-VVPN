package com.darkvvpn.app.ui.theme

import androidx.compose.ui.graphics.Color

/* ---------------------------------------------------------------------------
 * DARK VVPN palette — Streisand iOS theme (OLED pitch black, royal blue, iOS green).
 * ------------------------------------------------------------------------- */

// Accent (Streisand Royal Blue)
val BrandBlue = Color(0xFF0A84FF)
val BrandBlueDark = Color(0xFF0066CC)
val BrandBlueLight = Color(0xFF64D2FF)
val BrandBlueContainer = Color(0xFF0D253F)

val BrandGreen = Color(0xFF34C759)
val BrandGreenDark = Color(0xFF248A3D)

// Color aliases for brand compatibility
val BrandCyan = BrandBlue
val BrandCyanDark = BrandBlueDark
val BrandCyanLight = BrandBlueLight
val BrandCyanContainer = BrandBlueContainer

val BrandViolet = BrandBlue
val BrandVioletDark = BrandBlueDark
val BrandVioletLight = BrandBlueLight
val Violet10 = Color(0xFF021B17)
val Violet40 = Color(0xFF0D253F)
val Violet80 = Color(0xFF64D2FF)
val Violet90 = Color(0xFFB8EAFF)

// Secondary (iOS green / amber)
val BrandAmber = Color(0xFFFFD60A)
val BrandAmberDeep = Color(0xFFE09B12)
val InkOnAmber = Color(0xFF2A1D00)

val BrandTeal = BrandGreen
val BrandTealDark = BrandGreenDark

// Error
val BrandRed = Color(0xFFFF453A)

// Neutrals — Apple iOS Inset Grouped dark surfaces & OLED black
val Ink900 = Color(0xFF000000)
val Ink850 = Color(0xFF0A0A0C) // main background
val Ink800 = Color(0xFF141416) // nav & surface
val Ink750 = Color(0xFF1C1C1E) // card surface (iOS grouped cell)
val Ink700 = Color(0xFF242426) // containers & badges
val Ink600 = Color(0xFF2C2C2E) // borders & dividers (iOS separator)
val Ink500 = Color(0xFF3A3A3C) // outline
val Ink300 = Color(0xFF8E8E93) // secondary label
val Ink200 = Color(0xFFAEAEB2) // tertiary label
val Ink100 = Color(0xFFF2F2F7) // primary label
val PureWhite = Color(0xFFFFFFFF)

// Light-theme surfaces (the app is dark-first, but the light palette must be
// coherent so `dynamicColor = false` + light system theme still looks sane).
val Color_Background_Light = Color(0xFFF5F8FA)
val Color_Surface_Light = Color(0xFFFFFFFF)

