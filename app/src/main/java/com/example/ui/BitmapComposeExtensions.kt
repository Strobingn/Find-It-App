package com.example.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Safe Compose bridge for terrain previews while the asynchronous hillshade bitmap is still loading.
 * The transparent fallback is replaced automatically when HillshadeViewModel publishes terrain.
 */
private val transparentFallbackBitmap: Bitmap by lazy {
    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
}

internal val Bitmap?.safeWidth: Int
    get() = this?.takeUnless(Bitmap::isRecycled)?.width?.coerceAtLeast(1) ?: 1

internal val Bitmap?.safeHeight: Int
    get() = this?.takeUnless(Bitmap::isRecycled)?.height?.coerceAtLeast(1) ?: 1

internal fun Bitmap?.safeAsImageBitmap(): ImageBitmap =
    (this?.takeUnless(Bitmap::isRecycled) ?: transparentFallbackBitmap).asImageBitmap()
