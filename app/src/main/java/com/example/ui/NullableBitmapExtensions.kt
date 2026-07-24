package com.example.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap as composeAsImageBitmap

/**
 * Allows the Analysis screen to render safely while the terrain hillshade is still loading.
 * A transparent 1x1 bitmap is used only until HillshadeViewModel publishes the real bitmap.
 */
private val transparentFallbackBitmap: Bitmap by lazy {
    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}

internal val Bitmap?.width: Int
    get() = this?.width?.coerceAtLeast(1) ?: 1

internal val Bitmap?.height: Int
    get() = this?.height?.coerceAtLeast(1) ?: 1

internal fun Bitmap?.asImageBitmap(): ImageBitmap =
    (this?.takeUnless(Bitmap::isRecycled) ?: transparentFallbackBitmap).composeAsImageBitmap()
