package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusGroundSpacingClampTest {
    @Test fun refineCellNeverFinerThanGroundSpacing() {
        // NY-like viewport: 225 m square with 132k ground points → ~0.62 m spacing.
        val res = focusResolutionForPointSpacing(225.0, 225.0, 132_000L, requestedResolution = 1536)
        val cell = 225.0 / (res - 1)
        val spacing = 1.0 / kotlin.math.sqrt(132_000.0 / (225.0 * 225.0))
        assertTrue("cell $cell must be >= spacing $spacing", cell >= spacing * 1.2)
        assertTrue("cell $cell should stay near 1.25x spacing", cell <= spacing * 1.35)
    }

    @Test fun denseDataKeepsRequestedResolution() {
        assertEquals(1024, focusResolutionForPointSpacing(100.0, 100.0, 50_000_000L, requestedResolution = 1024))
    }

    @Test fun sparseDataNeverBelowMinimum() {
        assertEquals(
            LidarImportOptions.MIN_RASTER_RESOLUTION,
            focusResolutionForPointSpacing(1000.0, 1000.0, 10L, requestedResolution = 1536),
        )
    }
}
