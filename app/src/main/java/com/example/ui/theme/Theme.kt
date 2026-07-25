package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE9B98A),
    primaryContainer = Color(0xFF68462F),
    onPrimary = Color(0xFF321B0B),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFBCCCA8),
    onSecondary = Color(0xFF28341F),
    secondaryContainer = Color(0xFF3E4B34),
    onSecondaryContainer = Color(0xFFD8E8C3),
    tertiary = Color(0xFFA5CDCF),
    onTertiary = Color(0xFF0B3537),
    background = Color(0xFF12100E),
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF1A1714),
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF2B2521),
    onSurfaceVariant = Color(0xFFD1C2B8),
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color.Black.copy(alpha = 0.6f),
    inverseOnSurface = Color(0xFF212121),
    inversePrimary = Color(0xFF616161),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF80552F),
    primaryContainer = Color(0xFFFFDCC0),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF212121),
    secondary = Color(0xFF56634A),
    onSecondary = Color(0xFFF5F5F5),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF212121),
    tertiary = Color(0xFF757575),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFCF8F4),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFF0E5DD),
    onSurfaceVariant = Color(0xFF51443D),
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFF9E9E9E),
    scrim = Color.Black.copy(alpha = 0.4f),
    inverseOnSurface = Color(0xFFFAFAFA),
    inversePrimary = Color(0xFFBDBDBD),
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
