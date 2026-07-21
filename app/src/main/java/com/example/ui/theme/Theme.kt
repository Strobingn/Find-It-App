package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimary = OnPrimaryDark,
    onPrimaryContainer = Grey100,
    secondary = Grey400,
    onSecondary = Grey900,
    secondaryContainer = Grey700,
    onSecondaryContainer = Grey200,
    tertiary = Grey500,
    onTertiary = Grey100,
    background = DarkBackground,
    onBackground = Grey200,
    surface = DarkSurface,
    onSurface = Grey200,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Grey400,
    outline = Grey600,
    outlineVariant = Grey700,
    scrim = Color.Black.copy(alpha = 0.45f),
    inverseSurface = Grey100,
    inverseOnSurface = Grey900,
    inversePrimary = PrimaryLight,
    error = Error,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimary = OnPrimaryLight,
    onPrimaryContainer = Grey900,
    secondary = Grey600,
    onSecondary = Grey50,
    secondaryContainer = Grey200,
    onSecondaryContainer = Grey800,
    tertiary = Grey500,
    onTertiary = Grey50,
    background = LightBackground,
    onBackground = Grey900,
    surface = LightSurface,
    onSurface = Grey900,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Grey700,
    outline = Grey500,
    outlineVariant = Grey300,
    scrim = Color.Black.copy(alpha = 0.35f),
    inverseSurface = Grey800,
    inverseOnSurface = Grey100,
    inversePrimary = PrimaryDark,
    error = Error,
    onError = Color.White,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            // Transparent system bars; Compose applies safe insets so content is not covered.
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            val controller = WindowCompat.getInsetsController(window, view)
            // Light icons on dark theme; dark icons on light grey theme.
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
