package com.example.ui

/**
 * Zoom and pan persisted for the Terrain tab.
 *
 * Refine and Show whole replace the raster with a new crop (or the full footprint). The
 * previous magnification no longer refers to that image, so the saved viewport must go back
 * to fit-to-canvas. Otherwise the Terrain tab may not be composed yet — its first
 * `viewportRestoreToken` effect would re-apply the pre-refine zoom on the new crop.
 */
data class PersistedMapViewport(
    val zoom: Float,
    val panX: Float,
    val panY: Float,
) {
    companion object {
        val FitToCanvas = PersistedMapViewport(zoom = 1f, panX = 0f, panY = 0f)
    }
}

fun persistViewportAfterTerrainSwap(
    current: PersistedMapViewport,
    discardSavedViewport: Boolean,
): PersistedMapViewport {
    return if (discardSavedViewport) PersistedMapViewport.FitToCanvas else current
}
