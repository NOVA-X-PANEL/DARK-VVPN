package com.darkvvpn.app.ui.theme

import androidx.compose.ui.graphics.Color

/* ---------------------------------------------------------------------------
 * DARK VVPN palette — Clean Neon theme (pitch dark, electric cyan & amber).
 * ------------------------------------------------------------------------- */

// Accent (electric cyan / turquoise)
val BrandCyan = Color(0xFF00F5D4)
val BrandCyanDark = Color(0xFF009F89)
val BrandCyanLight = Color(0xFF70FFF0)
val BrandCyanContainer = Color(0xFF072B25)

// Color aliases for brand compatibility
val BrandViolet = BrandCyan
val BrandVioletDark = BrandCyanDark
val BrandVioletLight = BrandCyanLight
val Violet10 = Color(0xFF021B17)
val Violet40 = Color(0xFF0A4D43)
val Violet80 = Color(0xFF70FFF0)
val Violet90 = Color(0xFFB8FFF8)

// Secondary (neon amber / cyber yellow)
val BrandAmber = Color(0xFFFFD600)
val BrandAmberDeep = Color(0xFFE09B12)
val InkOnAmber = Color(0xFF2A1D00)

val BrandTeal = Color(0xFF00F5D4)
val BrandTealDark = Color(0xFF008F7A)

// Error
val BrandRed = Color(0xFFFF5A6E)

// Neutrals — Obsidian pitch canvas & dark slate containers
val Ink900 = Color(0xFF040508)
val Ink850 = Color(0xFF08090F) // main background
val Ink800 = Color(0xFF0D0F17) // nav & surface
val Ink750 = Color(0xFF10131D) // card surface
val Ink700 = Color(0xFF181D2C) // containers & badges
val Ink600 = Color(0xFF1F2538) // borders & dividers
val Ink500 = Color(0xFF333D5C) // outline
val Ink300 = Color(0xFF64748B) // muted
val Ink200 = Color(0xFF94A3B8) // secondary text
val Ink100 = Color(0xFFF1F5F9) // high contrast text
val PureWhite = Color(0xFFFFFFFF)

// Light-theme surfaces (the app is dark-first, but the light palette must be
// coherent so `dynamicColor = false` + light system theme still looks sane).
val Color_Background_Light = Color(0xFFF5F8FA)
val Color_Surface_Light = Color(0xFFFFFFFF)

