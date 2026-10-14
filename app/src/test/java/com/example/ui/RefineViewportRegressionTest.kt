package com.example.ui

import androidx.compose.ui.geometry.Offset
import com.example.data.focusResolutionForPointSpacing
import com.example.ui.components.clampedPan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refine is manual-only (as at 8c20c3a); zoom/pan never start one and pan stays free. */
class RefineViewportRegressionTest {
    private fun vmSource(): String = listOf(
        "src/main/java/com/example/ui/HillshadeViewModel.kt",
        "app/src/main/java/com/example/ui/HillshadeViewModel.kt",
    ).map(::File).first { it.isFile }.readText()

    @Test fun zoomNeverTriggersRefine() {
        val src = vmSource()
        val start = src.indexOf("fun updateViewport(")
        assertTrue(start >= 0)
        val body = src.substring(start, src.indexOf("\n    }\n", start))
        assertFalse("updateViewport must not start a refine:\n$body", body.contains("efine", ignoreCase = true))
        assertFalse(src.contains("scheduleAutoRefine"))
    }

    @Test fun refineResetsViewportOntoTheCrop() {
        assertTrue(REFINE_RESETS_VIEWPORT)
    }

    @Test fun panIsFreeAtZoomAfterRefine() {
        // Refined crop bitmap 1024x1024 in a 1080x2000 viewport, user zoomed 3x after refine.
        val fit = minOf(1080f / 1024f, 2000f / 1024f)
        val moved = clampedPan(Offset.Zero, Offset(-300f, 250f), 3f, fit, 1024f, 1024f, 1080f, 2000f)
        assertEquals(-300f, moved.x, 0.01f)
        assertEquals(250f, moved.y, 0.01f)
        // At zoom 1 the image fits; horizontal pan is (correctly) pinned.
        assertEquals(0f, clampedPan(Offset.Zero, Offset(-300f, 0f), 1f, fit, 1024f, 1024f, 1080f, 2000f).x, 0.01f)
    }

    @Test fun zoomedRefineCellIsFinerThanBaseCell() {
        val base = 1000.0 / 1024
        val pts = (4_000_000L * 0.04).toLong()
        val cell = 200.0 / focusResolutionForPointSpacing(200.0, 200.0, pts, 1024)
        assertTrue("refine cell $cell must be < base $base", cell < base)
        assertTrue(cell >= kotlin.math.sqrt(200.0 * 200.0 / pts) * 1.2)
    }
}
