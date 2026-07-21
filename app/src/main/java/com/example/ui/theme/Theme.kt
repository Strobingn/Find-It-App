package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = GoldAmber,
    secondary = CyberBlue,
    tertiary = LidarCopper,
    background = EarthSlateBackground,
    surface = DarkSteelSurface,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFF5F5F7),
    onSurface = Color(0xFFF5F5F7)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF765B00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE16B),
    onPrimaryContainer = Color(0xFF241A00),
    secondary = Color(0xFF006494),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCBE6FF),
    onSecondaryContainer = Color(0xFF001E30),
    tertiary = Color(0xFF8B5000),
    background = Color(0xFFFFF8F2),
    surface = Color(0xFFFFF8F2),
    surfaceVariant = Color(0xFFE9E2D9),
    onBackground = Color(0xFF201B16),
    onSurface = Color(0xFF201B16),
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

