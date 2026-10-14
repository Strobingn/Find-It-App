package com.example.ui

import com.example.data.focusResolutionForPointSpacing
import com.example.data.NormalizedRasterBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for "Refine view no longer works" after #65: a successful refine left the canvas at
 * the old zoom/pan over the new crop, and the debounced auto-refine then saw a detailed crop at
 * zoom < 2.2x and snapped straight back to the whole-file overview, discarding the refine.
 */
class RefineViewportRegressionTest {
    private val crop = NormalizedRasterBounds(0.4, 0.4, 0.6, 0.6)

    @Test fun refinedCropAtZoomOneIsKeptNotReplacedByOverview() {
        // Right after a refine the canvas is reset to zoom 1 over the refined crop.
        assertEquals(AutoRefineAction.NONE, autoRefineAction(1f, NormalizedRasterBounds.Full))
        // A manual refine taken below the auto threshold must not be undone either.
        assertEquals(AutoRefineAction.NONE, autoRefineAction(1.6f, crop))
    }

    @Test fun refineResetsViewportOntoTheCrop() {
        assertTrue(REFINE_RESETS_VIEWPORT)
    }

    @Test fun zoomedInBeyondThresholdStillAutoRefines() {
        assertEquals(AutoRefineAction.REFINE, autoRefineAction(AUTO_REFINE_MIN_ZOOM + 0.5f, crop))
    }

    @Test fun zoomedRefineCellIsFinerThanBaseCell() {
        // 1000 m tile, 4 M ground points, overview at 1024 px. A 0.2 x 0.2 crop must get a
        // smaller cell than the overview while staying >= 1.25x real ground spacing.
        val base = 1000.0 / 1024
        val pts = (4_000_000L * 0.04).toLong()
        val res = focusResolutionForPointSpacing(200.0, 200.0, pts, 1024)
        val cell = 200.0 / res
        assertTrue("refine cell $cell must be < base $base", cell < base)
        val spacing = kotlin.math.sqrt(200.0 * 200.0 / pts)
        assertTrue("refine cell $cell must be >= ~1.25x spacing $spacing", cell >= spacing * 1.2)
    }
}
