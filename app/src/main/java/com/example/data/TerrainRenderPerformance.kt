package com.example.data

import kotlin.math.max

/** Debounce before hillshade work. Heavy analysis modes recompute local stats/curvature. */
internal fun hillshadeDebounceMs(visualizationMode: Int, immediate: Boolean): Long = when {
    immediate -> 0L
    // Tighter than the previous 180/80 ms so slider drags feel snappier while still
    // coalescing intermediate frames (renderGeneration already drops superseded work).
    visualizationMode in HEAVY_HILLSHADE_MODES -> 120L
    else -> 48L
}

/**
 * Display hillshade always uses the full analysis grid. Earlier zoom-LOD caps (320/512) made
 * first paint look soft even when the DEM was already 1,024+.
 */
internal fun previewMaxSideForZoom(zoom: Float, sourceMaxSide: Int): Int {
    @Suppress("UNUSED_PARAMETER")
    val ignoredZoom = zoom
    return sourceMaxSide.coerceAtLeast(1)
}

/** Downsamples [source] for display-only hillshade when the preview side is smaller. */
internal fun gridForHillshadePreview(source: ElevationGrid, maxSide: Int): ElevationGrid {
    val sourceMax = max(source.width, source.height)
    if (sourceMax <= maxSide) return source
    return TerrainLodPyramid.build(
        source = source,
        maxFinestDimension = maxSide.coerceAtLeast(64),
        minDimension = 32,
        maxLevels = 1,
    ).finest.grid
}

private val HEAVY_HILLSHADE_MODES = setOf(3, 4, 5)
