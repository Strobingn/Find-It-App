package com.example.data

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A memory-bounded elevation raster used for terrain visualization and screening. */
class ElevationGrid(
    val width: Int,
    val height: Int,
    val bareEarth: FloatArray,
    val canopySpikes: FloatArray,
    val cellSizeMeters: Float = 1f,
) {
    init {
        require(width > 0 && height > 0) { "Grid dimensions must be positive" }
        require(bareEarth.size == width * height) { "bareEarth size must be width * height" }
        require(canopySpikes.size == width * height) { "canopySpikes size must be width * height" }
    }

    /** 0 shows the full surface model; 1 shows the extracted bare-earth model. */
    fun getElevationAt(col: Int, row: Int, vegetationFilter: Float): Float {
        val c = col.coerceIn(0, width - 1)
        val r = row.coerceIn(0, height - 1)
        val index = r * width + c
        return bareEarth[index] + canopySpikes[index] * (1f - vegetationFilter.coerceIn(0f, 1f))
    }

    /**
     * Render a terrain-analysis bitmap.
     *
     * Modes: 0 single hillshade, 1 multi-directional hillshade, 2 slope, 3 local relief,
     * 4 curvature, 5 disturbance candidates. Candidate views are screening aids, not proof of
     * archaeological origin; field verification and source-quality review remain essential.
     */
    fun renderHillshade(
        sunAzimuth: Float,
        sunAltitude: Float,
        vegetationFilter: Float,
        palette: Int,
        contrast: Float = 1.5f,
        visualizationMode: Int = 0,
        overlayType: Int = 0,
        overlayOpacity: Float = 0.5f,
        zScale: Float = 1f,
        featureScaleMeters: Float = 6f,
        analysisSensitivity: Float = 1.2f,
        contourIntervalMeters: Float = 0f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val cellDistance = cellSizeMeters.coerceAtLeast(0.001f)
        val elevations = FloatArray(width * height)
        for (index in elevations.indices) {
            elevations[index] = bareEarth[index] +
                canopySpikes[index] * (1f - vegetationFilter.coerceIn(0f, 1f))
        }

        val analysisRadius = (featureScaleMeters.coerceAtLeast(cellDistance) / cellDistance)
            .toInt()
            .coerceIn(1, max(1, min(width, height) / 4))
        val local = if (visualizationMode == 3 || visualizationMode == 5) {
            localStatistics(elevations, analysisRadius)
        } else {
            null
        }
        val curvature = if (visualizationMode == 4 || visualizationMode == 5) {
            curvature(elevations, cellDistance)
        } else {
            null
        }
        val residualScale = local?.residual?.let(::robustMagnitudeScale) ?: 1f
        val roughnessScale = local?.roughness?.let(::robustMagnitudeScale) ?: 1f
        val curvatureScale = curvature?.let(::robustMagnitudeScale) ?: 1f

        var minElevation = Float.MAX_VALUE
        var maxElevation = -Float.MAX_VALUE
        for (elevation in elevations) {
            if (elevation < minElevation) minElevation = elevation
            if (elevation > maxElevation) maxElevation = elevation
        }
        val elevationRange = (maxElevation - minElevation).takeIf { it > 0f } ?: 1f
        val azimuth = normalizeDegrees(sunAzimuth)
        val altitude = sunAltitude.coerceIn(1f, 89f)
        val contrastValue = contrast.coerceIn(0.5f, 4f)
        val zMultiplier = zScale.coerceIn(0.25f, 8f)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val gradient = hornGradient(elevations, x, y, cellDistance, zMultiplier)
                val slopeRadians = atan(sqrt(gradient.dx * gradient.dx + gradient.dy * gradient.dy))
                val primaryShade = shade(gradient.dx, gradient.dy, azimuth, altitude)
                val multiShade = multiDirectionalShade(gradient.dx, gradient.dy, azimuth, altitude)
                val elevationPercent = ((elevations[index] - minElevation) / elevationRange).coerceIn(0f, 1f)

                var color = when (visualizationMode.coerceIn(0, 5)) {
                    1 -> shadePalette(getPaletteColor(palette, elevationPercent), multiShade, contrastValue)
                    2 -> slopeColor(getPaletteColor(palette, elevationPercent), slopeRadians)
                    3 -> {
                        val normalized = (requireNotNull(local).residual[index] / residualScale).coerceIn(-1f, 1f)
                        shadePalette(divergingReliefColor(normalized), multiShade, contrastValue * 0.75f)
                    }
                    4 -> {
                        val normalized = (requireNotNull(curvature)[index] / curvatureScale).coerceIn(-1f, 1f)
                        shadePalette(divergingCurvatureColor(normalized), multiShade, contrastValue * 0.6f)
                    }
                    5 -> {
                        val stats = requireNotNull(local)
                        val residual = abs(stats.residual[index]) / residualScale
                        val roughness = stats.roughness[index] / roughnessScale
                        val bend = abs(requireNotNull(curvature)[index]) / curvatureScale
                        val score = ((residual * 0.58f + bend * 0.27f + roughness * 0.15f) *
                            analysisSensitivity.coerceIn(0.4f, 2.5f)).coerceIn(0f, 1f)
                        disturbanceCandidateColor(score, multiShade)
                    }
                    else -> shadePalette(getPaletteColor(palette, elevationPercent), primaryShade, contrastValue)
                }

                if (overlayType > 0 && overlayOpacity > 0f) {
                    historicalOverlayColor(
                        x = x,
                        y = y,
                        elevations = elevations,
                        currentElevation = elevations[index],
                        overlayType = overlayType,
                    )?.let { overlay ->
                        color = blend(color, overlay, overlayOpacity.coerceIn(0f, 1f))
                    }
                }

                val contourInterval = contourIntervalMeters.coerceAtLeast(0f)
                if (contourInterval >= 0.05f && isContourPixel(elevations, x, y, contourInterval)) {
                    color = blend(color, Color.rgb(235, 244, 255), 0.72f)
                }
                pixels[index] = color
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun hornGradient(
        elevations: FloatArray,
        x: Int,
        y: Int,
        cellDistance: Float,
        zScale: Float,
    ): Gradient {
        fun at(px: Int, py: Int): Float = elevations[index(px, py)]
        val z00 = at(x - 1, y - 1)
        val z01 = at(x, y - 1)
        val z02 = at(x + 1, y - 1)
        val z10 = at(x - 1, y)
        val z12 = at(x + 1, y)
        val z20 = at(x - 1, y + 1)
        val z21 = at(x, y + 1)
        val z22 = at(x + 1, y + 1)
        return Gradient(
            dx = ((z02 + 2f * z12 + z22) - (z00 + 2f * z10 + z20)) /
                (8f * cellDistance) * zScale,
            dy = ((z20 + 2f * z21 + z22) - (z00 + 2f * z01 + z02)) /
                (8f * cellDistance) * zScale,
        )
    }

    /** Dot product of a terrain normal and a light vector; azimuth is clockwise from north. */
    private fun shade(dx: Float, dy: Float, azimuthDegrees: Float, altitudeDegrees: Float): Float {
        val azimuth = Math.toRadians(azimuthDegrees.toDouble())
        val altitude = Math.toRadians(altitudeDegrees.toDouble())
        val normalLength = sqrt(dx * dx + dy * dy + 1f)
        val nx = -dx / normalLength
        val ny = -dy / normalLength
        val nz = 1f / normalLength
        val horizontal = cos(altitude).toFloat()
        val lx = (sin(azimuth) * horizontal).toFloat()
        // Raster y increases south, hence north has a negative y component.
        val ly = (-cos(azimuth) * horizontal).toFloat()
        val lz = sin(altitude).toFloat()
        return (nx * lx + ny * ly + nz * lz).coerceIn(0f, 1f)
    }

    private fun multiDirectionalShade(dx: Float, dy: Float, azimuth: Float, altitude: Float): Float {
        val primary = shade(dx, dy, azimuth, altitude)
        val right = shade(dx, dy, azimuth + 90f, altitude)
        val opposite = shade(dx, dy, azimuth + 180f, altitude)
        val left = shade(dx, dy, azimuth + 270f, altitude)
        return (primary * 0.45f + right * 0.2f + opposite * 0.15f + left * 0.2f)
            .coerceIn(0f, 1f)
    }

    private fun shadePalette(baseColor: Int, rawShade: Float, contrast: Float): Int {
        val adjusted = ((rawShade - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
        val illumination = 0.18f + adjusted * 0.82f
        return Color.rgb(
            (Color.red(baseColor) * illumination).toInt().coerceIn(0, 255),
            (Color.green(baseColor) * illumination).toInt().coerceIn(0, 255),
            (Color.blue(baseColor) * illumination).toInt().coerceIn(0, 255),
        )
    }

    private fun slopeColor(baseColor: Int, slopeRadians: Float): Int {
        val strength = (slopeRadians / Math.toRadians(35.0).toFloat()).coerceIn(0f, 1f)
        return blend(baseColor, Color.rgb(255, 70, 0), strength)
    }

    private fun localStatistics(elevations: FloatArray, radius: Int): LocalStatistics {
        val stride = width + 1
        val sum = DoubleArray((width + 1) * (height + 1))
        val squareSum = DoubleArray(sum.size)
        for (y in 0 until height) {
            var rowSum = 0.0
            var rowSquareSum = 0.0
            for (x in 0 until width) {
                val value = elevations[y * width + x].toDouble()
                rowSum += value
                rowSquareSum += value * value
                sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + rowSum
                squareSum[(y + 1) * stride + x + 1] = squareSum[y * stride + x + 1] + rowSquareSum
            }
        }
        val residual = FloatArray(elevations.size)
        val roughness = FloatArray(elevations.size)
        for (y in 0 until height) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(width - 1)
                val count = (x1 - x0 + 1) * (y1 - y0 + 1)
                val localSum = rectangleSum(sum, stride, x0, y0, x1, y1)
                val localSquareSum = rectangleSum(squareSum, stride, x0, y0, x1, y1)
                val mean = localSum / count
                val variance = max(0.0, localSquareSum / count - mean * mean)
                val index = y * width + x
                residual[index] = (elevations[index] - mean).toFloat()
                roughness[index] = sqrt(variance).toFloat()
            }
        }
        return LocalStatistics(residual, roughness)
    }

    private fun curvature(elevations: FloatArray, cellDistance: Float): FloatArray {
        val output = FloatArray(elevations.size)
        val divisor = cellDistance * cellDistance
        for (y in 0 until height) {
            for (x in 0 until width) {
                val center = elevations[index(x, y)]
                output[y * width + x] = (
                    elevations[index(x - 1, y)] + elevations[index(x + 1, y)] +
                        elevations[index(x, y - 1)] + elevations[index(x, y + 1)] - 4f * center
                    ) / divisor
            }
        }
        return output
    }

    private fun robustMagnitudeScale(values: FloatArray): Float {
        var magnitudeSum = 0.0
        var maximum = 0f
        for (value in values) {
            val magnitude = abs(value)
            magnitudeSum += magnitude
            if (magnitude > maximum) maximum = magnitude
        }
        val mean = (magnitudeSum / values.size.coerceAtLeast(1)).toFloat()
        return max(max(mean * 4.5f, maximum * 0.08f), 1e-5f)
    }

    private fun isContourPixel(elevations: FloatArray, x: Int, y: Int, interval: Float): Boolean {
        if (x == 0 && y == 0) return false
        val level = floor(elevations[index(x, y)] / interval).toInt()
        return (x > 0 && floor(elevations[index(x - 1, y)] / interval).toInt() != level) ||
            (y > 0 && floor(elevations[index(x, y - 1)] / interval).toInt() != level)
    }

    private fun historicalOverlayColor(
        x: Int,
        y: Int,
        elevations: FloatArray,
        currentElevation: Float,
        overlayType: Int,
    ): Int? {
        if (overlayType == 1) {
            val sectionX = max(1, width / 2)
            val sectionY = max(1, height / 2)
            val isSectionLine = x % sectionX == 0 || y % sectionY == 0
            val roadY = height * 0.65f + sin(x * 0.08f) * height * 0.12f
            val isRoad = abs(y - roadY) < max(1.5f, height / 100f)
            if (isSectionLine) return Color.rgb(180, 150, 110)
            if (isRoad) return Color.rgb(160, 130, 90)
        } else if (overlayType == 2) {
            val isContour = currentElevation.toInt() % 12 == 0 &&
                (elevations[index(x - 1, y)].toInt() % 12 != 0 ||
                    elevations[index(x, y - 1)].toInt() % 12 != 0)
            if (isContour) return Color.rgb(115, 80, 50)
            if (x == width / 2 || y == height / 2) return Color.rgb(50, 80, 115)
        }
        return null
    }

    private fun divergingReliefColor(value: Float): Int {
        val neutral = Color.rgb(198, 198, 194)
        return if (value < 0f) {
            blend(neutral, Color.rgb(32, 116, 210), -value)
        } else {
            blend(neutral, Color.rgb(232, 82, 30), value)
        }
    }

    private fun divergingCurvatureColor(value: Float): Int {
        val neutral = Color.rgb(190, 190, 184)
        return if (value < 0f) {
            blend(neutral, Color.rgb(24, 180, 196), -value)
        } else {
            blend(neutral, Color.rgb(208, 48, 146), value)
        }
    }

    private fun disturbanceCandidateColor(score: Float, shade: Float): Int {
        val base = Color.rgb(42, 49, 53)
        val heat = when {
            score < 0.35f -> blend(base, Color.rgb(44, 102, 126), score / 0.35f)
            score < 0.7f -> blend(Color.rgb(44, 102, 126), Color.rgb(255, 204, 52), (score - 0.35f) / 0.35f)
            else -> blend(Color.rgb(255, 204, 52), Color.rgb(230, 42, 38), (score - 0.7f) / 0.3f)
        }
        return shadePalette(heat, 0.3f + shade * 0.7f, 0.8f)
    }

    private fun getPaletteColor(palette: Int, percent: Float): Int = when (palette) {
        0 -> {
            val shade = (150 + percent * 60).toInt().coerceIn(0, 255)
            Color.rgb(shade, shade, shade)
        }
        1 -> when {
            percent < 0.3f -> blend(Color.rgb(15, 20, 45), Color.rgb(80, 15, 20), percent / 0.3f)
            percent < 0.7f -> blend(Color.rgb(80, 15, 20), Color.rgb(200, 90, 40), (percent - 0.3f) / 0.4f)
            else -> blend(Color.rgb(200, 90, 40), Color.rgb(250, 180, 70), (percent - 0.7f) / 0.3f)
        }
        2 -> when {
            percent < 0.2f -> blend(Color.rgb(34, 112, 63), Color.rgb(46, 139, 87), percent / 0.2f)
            percent < 0.6f -> blend(Color.rgb(46, 139, 87), Color.rgb(196, 183, 101), (percent - 0.2f) / 0.4f)
            percent < 0.85f -> blend(Color.rgb(196, 183, 101), Color.rgb(130, 80, 40), (percent - 0.6f) / 0.25f)
            else -> blend(Color.rgb(130, 80, 40), Color.rgb(210, 210, 215), (percent - 0.85f) / 0.15f)
        }
        else -> Color.GRAY
    }

    private fun blend(background: Int, foreground: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(background) * (1f - fraction) + Color.red(foreground) * fraction).toInt(),
            (Color.green(background) * (1f - fraction) + Color.green(foreground) * fraction).toInt(),
            (Color.blue(background) * (1f - fraction) + Color.blue(foreground) * fraction).toInt(),
        )
    }

    private fun index(x: Int, y: Int): Int =
        y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)

    private fun rectangleSum(
        integral: DoubleArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Double = integral[(y1 + 1) * stride + x1 + 1] - integral[y0 * stride + x1 + 1] -
        integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]

    private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

    private data class Gradient(val dx: Float, val dy: Float)
    private data class LocalStatistics(val residual: FloatArray, val roughness: FloatArray)
}
