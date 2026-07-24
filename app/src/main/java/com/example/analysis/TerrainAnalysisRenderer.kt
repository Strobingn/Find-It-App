package com.example.analysis

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToInt

enum class AnalysisPalette(val title: String) {
    AUTO("Layer optimized"),
    HIGH_CONTRAST("High contrast"),
    GRAYSCALE("Grayscale"),
}

data class TerrainRenderOptions(
    val palette: AnalysisPalette = AnalysisPalette.AUTO,
    val contrast: Float = 1f,
    val brightness: Float = 1f,
    val opacity: Float = 1f,
    val inverted: Boolean = false,
) {
    fun sanitized(): TerrainRenderOptions = copy(
        contrast = contrast.coerceIn(0.5f, 3f),
        brightness = brightness.coerceIn(0.5f, 1.75f),
        opacity = opacity.coerceIn(0.1f, 1f),
    )
}

object TerrainAnalysisRenderer {

    fun render(
        layer: TerrainAnalysisLayer,
        options: TerrainRenderOptions = TerrainRenderOptions(),
    ): Bitmap {
        val safe = options.sanitized()
        val bitmap = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(layer.values.size)
        val robust = robustRange(layer)
        val range = (robust.second - robust.first).takeIf { abs(it) > 0.000001f } ?: 1f
        val symmetricScale = robustMagnitude(layer)
        val logMaximum = ln1p(layer.maximum.coerceAtLeast(0f).toDouble()).toFloat().coerceAtLeast(0.000001f)

        for (index in layer.values.indices) {
            if (!layer.validData[index]) {
                pixels[index] = Color.TRANSPARENT
                continue
            }
            val value = layer.values[index]
            val normalized = when (layer.type) {
                TerrainAnalysisType.FLOW_ACCUMULATION ->
                    ln1p(value.coerceAtLeast(0f).toDouble()).toFloat() / logMaximum
                TerrainAnalysisType.MULTI_HILLSHADE -> value.coerceIn(0f, 1f)
                else -> ((value - robust.first) / range).coerceIn(0f, 1f)
            }.let { applyContrast(it, safe.contrast, safe.inverted) }

            val baseColor = when {
                layer.type == TerrainAnalysisType.ASPECT -> aspectColor(value, safe)
                layer.type == TerrainAnalysisType.WATERSHED -> watershedColor(value.toInt())
                safe.palette == AnalysisPalette.GRAYSCALE -> grayscale(normalized)
                layer.type in setOf(
                    TerrainAnalysisType.LOCAL_RELIEF_MODEL,
                    TerrainAnalysisType.CURVATURE,
                    TerrainAnalysisType.EROSION_SIMULATION,
                ) -> {
                    val signed = (value / symmetricScale).coerceIn(-1f, 1f)
                    val adjusted = signed.copySignMagnitude(
                        applyContrast(abs(signed), safe.contrast, inverted = false),
                    ) * if (safe.inverted) -1f else 1f
                    divergingColor(adjusted, safe.palette)
                }
                safe.palette == AnalysisPalette.HIGH_CONTRAST -> highContrastColor(normalized)
                layer.type == TerrainAnalysisType.SKY_VIEW_FACTOR -> skyViewColor(normalized)
                layer.type == TerrainAnalysisType.POSITIVE_OPENNESS ||
                    layer.type == TerrainAnalysisType.NEGATIVE_OPENNESS -> opennessColor(normalized)
                layer.type == TerrainAnalysisType.DEPRESSION_DEPTH ||
                    layer.type == TerrainAnalysisType.ANCIENT_STREAM_LIKELIHOOD -> hotspotColor(normalized)
                else -> sequentialColor(normalized)
            }
            pixels[index] = applyDisplayAdjustments(baseColor, safe.brightness, safe.opacity)
        }
        bitmap.setPixels(pixels, 0, layer.width, 0, 0, layer.width, layer.height)
        return bitmap
    }

    private fun applyContrast(value: Float, contrast: Float, inverted: Boolean): Float {
        val clamped = value.coerceIn(0f, 1f)
        val gamma = 1.0 / contrast.coerceIn(0.5f, 3f)
        val adjusted = clamped.toDouble().pow(gamma).toFloat().coerceIn(0f, 1f)
        return if (inverted) 1f - adjusted else adjusted
    }

    private fun applyDisplayAdjustments(color: Int, brightness: Float, opacity: Float): Int {
        val scale = brightness.coerceIn(0.5f, 1.75f)
        val alpha = (Color.alpha(color) * opacity.coerceIn(0.1f, 1f)).roundToInt().coerceIn(0, 255)
        return Color.argb(
            alpha,
            (Color.red(color) * scale).roundToInt().coerceIn(0, 255),
            (Color.green(color) * scale).roundToInt().coerceIn(0, 255),
            (Color.blue(color) * scale).roundToInt().coerceIn(0, 255),
        )
    }

    private fun Float.copySignMagnitude(magnitude: Float): Float = if (this < 0f) -magnitude else magnitude

    private fun robustRange(layer: TerrainAnalysisLayer): Pair<Float, Float> {
        val selected = FloatArray(layer.validCellCount)
        var count = 0
        for (index in layer.values.indices) {
            if (!layer.validData[index]) continue
            val value = layer.values[index]
            if (!value.isFinite() || layer.type == TerrainAnalysisType.ASPECT && value < 0f) continue
            selected[count++] = value
        }
        if (count == 0) return 0f to 1f
        val sorted = selected.copyOf(count)
        sorted.sort()
        val low = sorted[(sorted.lastIndex * 0.02f).roundToInt()]
        val high = sorted[(sorted.lastIndex * 0.98f).roundToInt()]
        return if (high - low > 0.000001f) low to high else {
            val padding = maxOf(abs(low) * 0.02f, 0.0001f)
            low - padding to high + padding
        }
    }

    private fun robustMagnitude(layer: TerrainAnalysisLayer): Float {
        val magnitudes = FloatArray(layer.validCellCount)
        var count = 0
        for (index in layer.values.indices) {
            if (!layer.validData[index]) continue
            val value = layer.values[index]
            if (!value.isFinite()) continue
            magnitudes[count++] = abs(value)
        }
        if (count == 0) return 1f
        val sorted = magnitudes.copyOf(count)
        sorted.sort()
        return sorted[(sorted.lastIndex * 0.98f).roundToInt()].coerceAtLeast(0.000001f)
    }

    private fun grayscale(value: Float): Int {
        val channel = (20f + value * 235f).toInt().coerceIn(0, 255)
        return Color.rgb(channel, channel, channel)
    }

    private fun highContrastColor(value: Float): Int = when {
        value < 0.2f -> blend(Color.BLACK, Color.rgb(35, 0, 95), value / 0.2f)
        value < 0.4f -> blend(Color.rgb(35, 0, 95), Color.rgb(0, 80, 230), (value - 0.2f) / 0.2f)
        value < 0.6f -> blend(Color.rgb(0, 80, 230), Color.rgb(0, 230, 185), (value - 0.4f) / 0.2f)
        value < 0.8f -> blend(Color.rgb(0, 230, 185), Color.YELLOW, (value - 0.6f) / 0.2f)
        else -> blend(Color.YELLOW, Color.WHITE, (value - 0.8f) / 0.2f)
    }

    private fun skyViewColor(value: Float): Int = when {
        value < 0.33f -> blend(Color.rgb(15, 22, 48), Color.rgb(34, 92, 160), value / 0.33f)
        value < 0.66f -> blend(Color.rgb(34, 92, 160), Color.rgb(70, 190, 205), (value - 0.33f) / 0.33f)
        else -> blend(Color.rgb(70, 190, 205), Color.rgb(250, 250, 225), (value - 0.66f) / 0.34f)
    }

    private fun opennessColor(value: Float): Int = when {
        value < 0.33f -> blend(Color.rgb(30, 18, 50), Color.rgb(110, 45, 135), value / 0.33f)
        value < 0.66f -> blend(Color.rgb(110, 45, 135), Color.rgb(235, 115, 55), (value - 0.33f) / 0.33f)
        else -> blend(Color.rgb(235, 115, 55), Color.rgb(255, 244, 180), (value - 0.66f) / 0.34f)
    }

    private fun sequentialColor(value: Float): Int = when {
        value < 0.25f -> blend(Color.rgb(20, 28, 58), Color.rgb(25, 94, 145), value / 0.25f)
        value < 0.5f -> blend(Color.rgb(25, 94, 145), Color.rgb(35, 170, 160), (value - 0.25f) / 0.25f)
        value < 0.75f -> blend(Color.rgb(35, 170, 160), Color.rgb(213, 210, 90), (value - 0.5f) / 0.25f)
        else -> blend(Color.rgb(213, 210, 90), Color.rgb(250, 245, 220), (value - 0.75f) / 0.25f)
    }

    private fun hotspotColor(value: Float): Int = when {
        value < 0.33f -> blend(Color.rgb(20, 28, 58), Color.rgb(40, 120, 190), value / 0.33f)
        value < 0.66f -> blend(Color.rgb(40, 120, 190), Color.rgb(255, 193, 7), (value - 0.33f) / 0.33f)
        else -> blend(Color.rgb(255, 193, 7), Color.rgb(220, 45, 45), (value - 0.66f) / 0.34f)
    }

    private fun divergingColor(value: Float, palette: AnalysisPalette): Int {
        val neutral = if (palette == AnalysisPalette.HIGH_CONTRAST) Color.WHITE else Color.rgb(225, 225, 218)
        return if (value < 0f) {
            blend(neutral, if (palette == AnalysisPalette.HIGH_CONTRAST) Color.BLUE else Color.rgb(25, 82, 205), -value)
        } else {
            blend(neutral, if (palette == AnalysisPalette.HIGH_CONTRAST) Color.RED else Color.rgb(220, 42, 38), value)
        }
    }

    private fun aspectColor(value: Float, options: TerrainRenderOptions): Int {
        if (value < 0f) return Color.rgb(135, 135, 135)
        if (options.palette == AnalysisPalette.GRAYSCALE) return grayscale((value % 360f) / 360f)
        val hue = if (options.inverted) (360f - value) % 360f else value % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.95f))
    }

    private fun watershedColor(label: Int): Int {
        val hue = ((label * 137.508f) % 360f + 360f) % 360f
        return Color.HSVToColor(floatArrayOf(hue, 0.58f, 0.9f))
    }

    private fun blend(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt().coerceIn(0, 255),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt().coerceIn(0, 255),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt().coerceIn(0, 255),
        )
    }
}
