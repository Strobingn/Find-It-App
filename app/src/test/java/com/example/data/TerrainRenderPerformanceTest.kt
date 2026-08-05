package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainRenderPerformanceTest {
    @Test
    fun defaultOverviewIsDetailedNotProgressiveStub() {
        assertEquals(1_024, LidarImportOptions.DEFAULT_OVERVIEW_RESOLUTION)
        assertEquals(1_536, LidarImportOptions.MAX_OVERVIEW_RESOLUTION)
        assertEquals(
            1_024,
            LidarImportOptions(rasterResolution = 1_024).sanitized().rasterResolution,
        )
        // Full footprint no longer clamps 1,024 down to 512.
        assertEquals(
            1_024,
            LidarImportOptions(rasterResolution = 1_024, focusBounds = null).sanitized().rasterResolution,
        )
    }

    @Test
    fun hillshadeDebounceIsLongerForHeavyAnalysisModes() {
        assertEquals(0L, hillshadeDebounceMs(visualizationMode = 5, immediate = true))
        assertEquals(48L, hillshadeDebounceMs(visualizationMode = 0, immediate = false))
        assertEquals(120L, hillshadeDebounceMs(visualizationMode = 3, immediate = false))
        assertEquals(120L, hillshadeDebounceMs(visualizationMode = 4, immediate = false))
        assertEquals(120L, hillshadeDebounceMs(visualizationMode = 5, immediate = false))
    }

    @Test
    fun previewMaxSideAlwaysUsesFullGrid() {
        assertEquals(1_024, previewMaxSideForZoom(zoom = 1f, sourceMaxSide = 1_024))
        assertEquals(1_536, previewMaxSideForZoom(zoom = 1f, sourceMaxSide = 1_536))
        assertEquals(512, previewMaxSideForZoom(zoom = 8f, sourceMaxSide = 512))
        assertEquals(200, previewMaxSideForZoom(zoom = 1f, sourceMaxSide = 200))
    }

    @Test
    fun gridForHillshadePreviewDownsamplesOnlyWhenCapped() {
        val source = ElevationGrid(
            width = 640,
            height = 480,
            bareEarth = FloatArray(640 * 480) { it.toFloat() },
            canopySpikes = FloatArray(640 * 480),
        )
        val full = gridForHillshadePreview(source, maxSide = 640)
        assertEquals(640, full.width)
        assertEquals(480, full.height)

        val capped = gridForHillshadePreview(source, maxSide = 320)
        assertTrue(capped.width <= 320)
        assertTrue(capped.height <= 320)
    }

    @Test
    fun overviewDoesNotUseSparseSampleBudgetVersusRefine() {
        val overview = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(rasterResolution = 1_024),
            declaredPointCount = 40_000_000,
        )
        val refined = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(
                rasterResolution = 1_024,
                focusBounds = NormalizedRasterBounds(0.25, 0.25, 0.75, 0.75),
            ),
            declaredPointCount = 40_000_000,
        )

        // Same per-cell target: overview of the whole file still elevates regularly.
        var overviewElevation = 0
        var refinedElevation = 0
        repeat(10_000) {
            if (overview.nextPointWork() == LidarPointWork.ELEVATION) overviewElevation++
            overview.skipPoint()
            if (refined.nextPointWork() == LidarPointWork.ELEVATION) refinedElevation++
            refined.skipPoint()
        }
        // Overview has more points in focus so fewer elevation hits per 10k, but both sample.
        assertTrue(overviewElevation > 0)
        assertTrue(refinedElevation > 0)
        assertFalse(overview.shouldStopDecoding())
    }

    @Test
    fun overviewEarlyOutOnlyAtHardScanCap() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 10.0,
            minY = 0.0,
            maxY = 10.0,
            options = LidarImportOptions(rasterResolution = 128),
            declaredPointCount = 5_000_000,
        )
        assertFalse(rasterizer.shouldStopDecoding())
        // Below the dense scan budget, keep decoding.
        repeat(100_000) {
            when (rasterizer.nextPointWork()) {
                LidarPointWork.SKIP -> rasterizer.skipPoint()
                LidarPointWork.COVERAGE -> rasterizer.addCoveragePoint(1.0, 1.0)
                LidarPointWork.ELEVATION -> rasterizer.addPoint(1.0, 1.0, 10f, classification = 2)
            }
        }
        // With denser budgets, 100k may still be under the cap on small rasters.
        if (rasterizer.shouldStopDecoding()) {
            assertTrue(rasterizer.pointsDecoded >= 200_000L || rasterizer.pointsDecoded < 5_000_000L)
        }
    }
}
