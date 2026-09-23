package com.darkvvpn.app.ui.theme

import androidx.compose.ui.graphics.Color

/* ---------------------------------------------------------------------------
 * DARK VVPN palette — dark-first, violet accent.
 * ------------------------------------------------------------------------- */

// Accent (violet)
val Violet10 = Color(0xFF21005D)
val Violet40 = Color(0xFF6750A4)
val Violet80 = Color(0xFFD0BCFF)
val Violet90 = Color(0xFFEADDFF)

val BrandViolet = Color(0xFF7C4DFF)
val BrandVioletDark = Color(0xFF5B2BD6)
val BrandVioletLight = Color(0xFFB9A3FF)

// Secondary (teal-ish, used for "connected")
val BrandTeal = Color(0xFF3DDCB4)
val BrandTealDark = Color(0xFF009F7F)

// Error
val BrandRed = Color(0xFFFF5A6E)

/**
 * The "an update is ready" amber.
 *
 * Deliberately not the brand violet: violet is this app's colour for everything,
 * so an update notice in violet reads as decoration. Amber is not used for any
 * other state, which is what makes it scan as a notice at a glance.
 */
val BrandAmber = Color(0xFFFFC24B)
val BrandAmberDeep = Color(0xFFE09B12)
val InkOnAmber = Color(0xFF2A1D00)

// Neutrals — the app's true canvas
val Ink900 = Color(0xFF07070E)
val Ink850 = Color(0xFF0B0B14)
val Ink800 = Color(0xFF10101B)
val Ink750 = Color(0xFF14141F)
val Ink700 = Color(0xFF1B1B29)
val Ink600 = Color(0xFF26263A)
val Ink500 = Color(0xFF3A3A52)
val Ink300 = Color(0xFF8A8AA3)
val Ink200 = Color(0xFFB4B4C7)
val Ink100 = Color(0xFFE4E4EE)
val PureWhite = Color(0xFFFFFFFF)

// Light-theme surfaces (the app is dark-first, but the light palette must be
// coherent so `dynamicColor = false` + light system theme still looks sane).
val Color_Background_Light = Color(0xFFF7F5FC)
val Color_Surface_Light = Color(0xFFFFFFFF)

