package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimary = OnPrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = Grey400,
    onSecondary = Grey900,
    secondaryContainer = Grey800,
    onSecondaryContainer = Grey200,
    tertiary = Grey600,
    onTertiary = Grey100,
    background = DarkBackground,
    onBackground = Grey200,
    surface = DarkSurface,
    onSurface = Grey300,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Grey400,
    outline = Grey700,
    outlineVariant = Grey800,
    scrim = Color.Black.copy(alpha = 0.4f),
    inverseOnSurface = Grey900,
    inversePrimary = PrimaryLight,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimary = OnPrimaryLight,
    onPrimaryContainer = Color(0xFF21005E),
    secondary = Grey600,
    onSecondary = Grey50,
    secondaryContainer = Grey200,
    onSecondaryContainer = Grey800,
    tertiary = Grey700,
    onTertiary = Grey50,
    background = LightBackground,
    onBackground = Grey900,
    surface = LightSurface,
    onSurface = Grey900,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Grey700,
    outline = Grey500,
    outlineVariant = Grey400,
    scrim = Color.Black.copy(alpha = 0.4f),
    inverseOnSurface = Grey100,
    inversePrimary = Color(0xFFEADDFF),
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
        content = content
    )
}
