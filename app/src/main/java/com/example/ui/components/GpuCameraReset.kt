package com.example.ui.components

import com.example.data.TerrainGpuScene

/**
 * When to reset the 3D camera after a GPU scene swap.
 *
 * Dimension-only checks miss a common Refine case: a square 1024×1024 overview replaced by a
 * square 1024×1024 crop of a different geographic extent. The [viewportResetKey] from
 * [com.example.ui.HillshadeViewModel] is the explicit signal that the raster was replaced and
 * the camera should fill the new crop.
 */
fun shouldResetGpuCamera(
    previousScene: TerrainGpuScene?,
    previousResetKey: Int,
    nextScene: TerrainGpuScene,
    nextResetKey: Int,
): Boolean {
    if (previousScene == null) return true
    if (previousResetKey != nextResetKey) return true
    return previousScene.sourceWidth != nextScene.sourceWidth ||
        previousScene.sourceHeight != nextScene.sourceHeight
}
