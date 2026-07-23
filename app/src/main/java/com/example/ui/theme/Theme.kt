package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBDBDBD),
    primaryContainer = Color(0xFF424242),
    onPrimary = Color(0xFF000000),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF424242),
    onSecondary = Color(0xFFEEEEEE),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFE0E0E0),
    tertiary = Color(0xFF616161),
    onTertiary = Color(0xFFF5F5F5),
    background = Color(0xFF000000),  // Pure black background
    onBackground = Color(0xFFE0E0E0),
    surface = Color(0xFF121212),    // Very dark surface
    onSurface = Color(0xFFEEEEEE),
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF424242),
    outlineVariant = Color(0xFF2A2A2A),
    scrim = Color.Black.copy(alpha = 0.6f),
    inverseOnSurface = Color(0xFF212121),
    inversePrimary = Color(0xFF616161),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF616161),
    primaryContainer = Color(0xFFE0E0E0),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF212121),
    secondary = Color(0xFF616161),
    onSecondary = Color(0xFFF5F5F5),
    secondaryContainer = Color(0xFFE0E0E0),
    onSecondaryContainer = Color(0xFF212121),
    tertiary = Color(0xFF757575),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF212121),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212121),
    surfaceVariant = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF424242),
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
