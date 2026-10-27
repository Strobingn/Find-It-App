package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Neutral grayscale only — no hue anywhere in either scheme, including selected /
// highlighted states (FilterChip, NavigationBar indicator, etc.).
//
// Layering follows Apple's grouped-content model: a pure background, grouped cells one step
// lighter, and controls inside cells one step lighter again. Separators are low-contrast
// hairlines rather than outlines.

private val DarkColorScheme = darkColorScheme(
    // Prominent actions are near-white with black text (iOS "filled" style in grayscale).
    primary = Color(0xFFF2F2F2),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF3A3A3A),
    onPrimaryContainer = Color.White,
    // Material draws selected nav labels, chips and toggles in `secondary`; it must read clearly.
    secondary = Color(0xFFEDEDED),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2E2E2E),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF000000),
    tertiaryContainer = Color(0xFF2E2E2E),
    onTertiaryContainer = Color(0xFFE0E0E0),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F2),
    // Must differ from every surfaceContainer* value: contentColorFor() matches containers by
    // value and checks surfaceVariant first, so a collision turns card titles grey.
    surfaceVariant = Color(0xFF202020),
    // Secondary label: ~60% white, the iOS secondaryLabel weight.
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF111111),
    surfaceContainer = Color(0xFF1C1C1C),
    // Material 3 cards default to `surfaceContainerHighest`, so keep the top of the ramp low.
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF2C2C2C),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color.Black.copy(alpha = 0.6f),
    inverseSurface = Color(0xFFF2F2F2),
    inverseOnSurface = Color(0xFF1C1C1C),
    inversePrimary = Color(0xFF3A3A3A),
    error = Color(0xFFCF6679),
    onError = Color(0xFF000000),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1C1C1C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5E5),
    onPrimaryContainer = Color(0xFF1C1C1C),
    secondary = Color(0xFF1C1C1C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Color(0xFF1C1C1C),
    tertiary = Color(0xFF616161),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEEEEE),
    onTertiaryContainer = Color(0xFF1C1C1C),
    background = Color(0xFFF2F2F2),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF2F2F2),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFE6E6E6),
    onSurfaceVariant = Color(0xFF6E6E6E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEAEAEA),
    surfaceContainerHighest = Color(0xFFE0E0E0),
    outline = Color(0xFFC6C6C6),
    outlineVariant = Color(0xFFDDDDDD),
    scrim = Color.Black.copy(alpha = 0.4f),
    inverseSurface = Color(0xFF1C1C1C),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFFBDBDBD),
    error = Color(0xFFB00020),
    onError = Color.White,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}
