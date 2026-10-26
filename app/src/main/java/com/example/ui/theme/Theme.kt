package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// iOS-style system palette, still neutral greyscale (project rule: no brand hue in chrome).
// Dark mirrors iOS dark "grouped" surfaces: true-black canvas, #1C1C1E cells, #2C2C2E raised
// cells, #38383A hairline separators, #98989F secondary labels. The tint is graphite (near-white)
// instead of iOS blue.

private val DarkColorScheme = darkColorScheme(
    primary = IosDark.Tint,
    onPrimary = Color.Black,
    primaryContainer = IosDark.Gray4,
    onPrimaryContainer = Color.White,
    secondary = IosDark.Gray,
    onSecondary = Color.Black,
    secondaryContainer = IosDark.Gray5,
    onSecondaryContainer = Color.White,
    tertiary = IosDark.Gray2,
    onTertiary = Color.White,
    tertiaryContainer = IosDark.Gray5,
    onTertiaryContainer = Color.White,
    background = IosDark.GroupedBackground,
    onBackground = IosDark.Label,
    surface = IosDark.GroupedBackground,
    onSurface = IosDark.Label,
    surfaceVariant = IosDark.Cell,
    onSurfaceVariant = IosDark.SecondaryLabel,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = IosDark.GroupedBackground,
    surfaceContainerLow = IosDark.Cell,
    surfaceContainer = IosDark.Cell,
    surfaceContainerHigh = IosDark.RaisedCell,
    surfaceContainerHighest = IosDark.RaisedCell,
    outline = IosDark.Gray3,
    outlineVariant = IosDark.Separator,
    scrim = Color.Black.copy(alpha = 0.55f),
    inverseSurface = IosDark.Label,
    inverseOnSurface = Color.Black,
    inversePrimary = IosDark.Gray2,
    error = IosDark.Red,
    onError = Color.Black,
)

private val LightColorScheme = lightColorScheme(
    primary = IosLight.Tint,
    onPrimary = Color.White,
    primaryContainer = IosLight.Gray5,
    onPrimaryContainer = Color.Black,
    secondary = IosLight.Gray,
    onSecondary = Color.White,
    secondaryContainer = IosLight.Gray5,
    onSecondaryContainer = Color.Black,
    tertiary = IosLight.Gray2,
    onTertiary = Color.White,
    tertiaryContainer = IosLight.Gray6,
    onTertiaryContainer = Color.Black,
    background = IosLight.GroupedBackground,
    onBackground = IosLight.Label,
    surface = IosLight.GroupedBackground,
    onSurface = IosLight.Label,
    surfaceVariant = IosLight.Cell,
    onSurfaceVariant = IosLight.SecondaryLabel,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = IosLight.GroupedBackground,
    surfaceContainerLow = IosLight.Cell,
    surfaceContainer = IosLight.Cell,
    surfaceContainerHigh = IosLight.RaisedCell,
    surfaceContainerHighest = IosLight.RaisedCell,
    outline = IosLight.Gray3,
    outlineVariant = IosLight.Separator,
    scrim = Color.Black.copy(alpha = 0.35f),
    inverseSurface = Color.Black,
    inverseOnSurface = Color.White,
    inversePrimary = IosLight.Gray3,
    error = IosLight.Red,
    onError = Color.White,
)

/** iOS-like corner radii: 10 for controls, 12 for grouped cells/cards, 14+ for sheets. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
