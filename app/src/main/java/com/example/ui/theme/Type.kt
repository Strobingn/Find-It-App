package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * iOS-style type ramp (Large Title 34 · Title 28/22/20 · Headline 17 · Body 17 · Subheadline 15 ·
 * Footnote 13 · Caption 11) on the system font. Slightly negative tracking at body sizes gives
 * Roboto the tighter SF look; line heights stay compact so dense field screens don't grow.
 */
private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

val Typography = Typography(
    displaySmall = style(34, 41, FontWeight.Bold, 0.0),
    headlineLarge = style(34, 41, FontWeight.Bold, 0.0),
    headlineMedium = style(28, 34, FontWeight.Bold, 0.0),
    headlineSmall = style(22, 28, FontWeight.Bold, 0.0),
    titleLarge = style(20, 25, FontWeight.SemiBold, 0.0),
    titleMedium = style(17, 22, FontWeight.SemiBold, -0.3),
    titleSmall = style(15, 20, FontWeight.SemiBold, -0.2),
    bodyLarge = style(17, 22, FontWeight.Normal, -0.3),
    bodyMedium = style(15, 20, FontWeight.Normal, -0.2),
    bodySmall = style(13, 17, FontWeight.Normal, -0.05),
    labelLarge = style(15, 20, FontWeight.Medium, -0.2),
    labelMedium = style(13, 16, FontWeight.Medium, 0.0),
    labelSmall = style(11, 13, FontWeight.Medium, 0.05),
)
