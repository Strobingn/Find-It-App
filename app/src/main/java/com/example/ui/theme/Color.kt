package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Greyscale only — no hue in primary, secondary, or surface tokens.

val Grey900 = Color(0xFF212121)
val Grey800 = Color(0xFF424242)
val Grey700 = Color(0xFF616161)
val Grey600 = Color(0xFF757575)
val Grey500 = Color(0xFF9E9E9E)
val Grey400 = Color(0xFFBDBDBD)
val Grey300 = Color(0xFFE0E0E0)
val Grey200 = Color(0xFFEEEEEE)
val Grey100 = Color(0xFFF5F5F5)
val Grey50 = Color(0xFFFAFAFA)

val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2A2A2A)

val LightBackground = Color(0xFFF5F5F5)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE0E0E0)

// Subtle warm-neutral accent kept as grey (no gold/amber hue)
val GoldAmber = Color(0xFFBDBDBD)

val PrimaryLight = Grey700
val PrimaryContainerLight = Grey300
val OnPrimaryLight = Color(0xFFFFFFFF)
val OnPrimaryContainerLight = Grey900

val PrimaryDark = Grey400
val PrimaryContainerDark = Grey800
val OnPrimaryDark = Color(0xFF000000)
val OnPrimaryContainerDark = Color(0xFFFFFFFF)

// Functional status only (not brand chrome)
val Success = Color(0xFF9E9E9E)
val Warning = Color(0xFFBDBDBD)
val Error = Color(0xFFCF6679)

/** iOS dark-mode system colors (neutral greys only). */
object IosDark {
    val GroupedBackground = Color(0xFF000000)
    val Cell = Color(0xFF1C1C1E)
    val RaisedCell = Color(0xFF2C2C2E)
    val Separator = Color(0xFF38383A)
    val Label = Color(0xFFFFFFFF)
    val SecondaryLabel = Color(0xFF98989F)
    val TertiaryLabel = Color(0xFF5B5B60)
    val Gray = Color(0xFF8E8E93)
    val Gray2 = Color(0xFF636366)
    val Gray3 = Color(0xFF48484A)
    val Gray4 = Color(0xFF3A3A3C)
    val Gray5 = Color(0xFF2C2C2E)
    val Tint = Color(0xFFF2F2F7)
    val Red = Color(0xFFFF453A)
}

/** iOS light-mode system colors (neutral greys only). */
object IosLight {
    val GroupedBackground = Color(0xFFF2F2F7)
    val Cell = Color(0xFFFFFFFF)
    val RaisedCell = Color(0xFFF2F2F7)
    val Separator = Color(0xFFC6C6C8)
    val Label = Color(0xFF000000)
    val SecondaryLabel = Color(0xFF8A8A8E)
    val TertiaryLabel = Color(0xFFC4C4C6)
    val Gray = Color(0xFF8E8E93)
    val Gray2 = Color(0xFFAEAEB2)
    val Gray3 = Color(0xFFC7C7CC)
    val Gray5 = Color(0xFFE5E5EA)
    val Gray6 = Color(0xFFF2F2F7)
    val Tint = Color(0xFF1C1C1E)
    val Red = Color(0xFFFF3B30)
}
