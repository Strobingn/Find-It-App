package com.example.ai

import android.graphics.Bitmap
import com.example.data.NormalizedRasterBounds
import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TerrainVisionSnapshot(
    val bitmap: Bitmap?,
    val bounds: NormalizedRasterBounds = NormalizedRasterBounds.Full,
    val zoom: Float = 1f,
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

/** Current 2D terrain viewport shared with Gemini without duplicating the rendering pipeline. */
object TerrainVisionSession {
    private val _snapshot = MutableStateFlow(TerrainVisionSnapshot(bitmap = null))
    val snapshot: StateFlow<TerrainVisionSnapshot> = _snapshot.asStateFlow()

    fun publish(bitmap: Bitmap?, bounds: NormalizedRasterBounds, zoom: Float) {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            clear()
            return
        }
        _snapshot.value = TerrainVisionSnapshot(
            bitmap = bitmap,
            bounds = bounds.sanitized(),
            zoom = zoom.coerceAtLeast(1f),
        )
    }

    fun clear() {
        _snapshot.value = TerrainVisionSnapshot(bitmap = null)
    }
}

internal object GeminiTerrainImageEncoder {
    private const val MAX_SIDE = 1_600
    private const val MAX_INLINE_BYTES = 6 * 1024 * 1024

    fun encode(snapshot: TerrainVisionSnapshot): GeminiImageInput? {
        val source = snapshot.bitmap?.takeIf {
            !it.isRecycled && it.width > 0 && it.height > 0
        } ?: return null
        val bounds = snapshot.bounds.sanitized()
        val left = floor(bounds.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = floor(bounds.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = ceil(bounds.right * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = ceil(bounds.bottom * source.height).toInt().coerceIn(top + 1, source.height)

        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val cropOwned = crop !== source
        val largestSide = max(crop.width, crop.height)
        val scaled = if (largestSide > MAX_SIDE) {
            val factor = MAX_SIDE.toFloat() / largestSide.toFloat()
            Bitmap.createScaledBitmap(
                crop,
                (crop.width * factor).toInt().coerceAtLeast(1),
                (crop.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            crop
        }
        val scaledOwned = scaled !== crop

        return try {
            var quality = 90
            var bytes: ByteArray
            do {
                bytes = ByteArrayOutputStream().use { output ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                        "Unable to encode terrain viewport"
                    }
                    output.toByteArray()
                }
                quality -= 10
            } while (bytes.size > MAX_INLINE_BYTES && quality >= 50)

            bytes.takeIf { it.size <= MAX_INLINE_BYTES }?.let {
                GeminiImageInput(
                    bytes = it,
                    mimeType = "image/jpeg",
                    description = buildString {
                        append("current rendered terrain viewport at ")
                        append(String.format("%.1fx zoom", snapshot.zoom))
                        append("; normalized bounds left=")
                        append(String.format("%.4f", bounds.left))
                        append(", top=")
                        append(String.format("%.4f", bounds.top))
                        append(", right=")
                        append(String.format("%.4f", bounds.right))
                        append(", bottom=")
                        append(String.format("%.4f", bounds.bottom))
                    },
                )
            }
        } finally {
            if (scaledOwned && !scaled.isRecycled) scaled.recycle()
            if (cropOwned && !crop.isRecycled) crop.recycle()
        }
    }
}
