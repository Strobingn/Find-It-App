package com.example.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Safe Compose bridge for terrain previews while the asynchronous hillshade bitmap is still loading.
 * The transparent pixel is replaced automatically as soon as HillshadeViewModel publishes terrain.
 */
private val transparentFallbackBitmap: Bitmap by lazy {
    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}

internal val Bitmap?.safeWidth: Int
    get() = this?.width?.coerceAtLeast(1) ?: 1

internal val Bitmap?.safeHeight: Int
    get() = this?.height?.coerceAtLeast(1) ?: 1

internal fun Bitmap?.safeAsImageBitmap(): ImageBitmap =
    (this?.takeUnless(Bitmap::isRecycled) ?: transparentFallbackBitmap).asImageBitmap()
