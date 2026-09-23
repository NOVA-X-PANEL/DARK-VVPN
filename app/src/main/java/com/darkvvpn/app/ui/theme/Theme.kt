package com.darkvvpn.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = BrandViolet,
    onPrimary = PureWhite,
    primaryContainer = Violet40,
    onPrimaryContainer = Violet90,

    secondary = BrandTeal,
    onSecondary = Ink900,
    secondaryContainer = BrandTealDark,
    onSecondaryContainer = PureWhite,

    tertiary = BrandVioletLight,
    onTertiary = Ink900,

    background = Ink850,
    onBackground = Ink100,
    surface = Ink800,
    onSurface = Ink100,
    surfaceVariant = Ink700,
    onSurfaceVariant = Ink200,
    surfaceContainer = Ink750,
    surfaceContainerHigh = Ink700,
    surfaceContainerHighest = Ink600,
    surfaceContainerLow = Ink800,
    surfaceContainerLowest = Ink850,

    outline = Ink500,
    outlineVariant = Ink600,

    error = BrandRed,
    onError = PureWhite,
)

private val LightColors = lightColorScheme(
    primary = BrandVioletDark,
    onPrimary = PureWhite,
    secondary = BrandTealDark,
    onSecondary = PureWhite,
    background = Color_Background_Light,
    onBackground = Ink900,
    surface = Color_Surface_Light,
    onSurface = Ink900,
)

/**
 * Root theme for DARK VVPN.
 *
 * @param darkTheme force the dark palette (defaults to the system setting).
 * @param dynamicColor use Material You wallpaper colours on Android 12+. Off by
 *   default — DARK VVPN ships a deliberate brand palette.
 */
@Composable
fun DarkVvpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DarkVvpnTypography,
        content = content,
    )
}
