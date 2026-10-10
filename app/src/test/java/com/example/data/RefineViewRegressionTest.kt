package com.example.data

import com.example.ui.chooseAiRefineResolution
import com.example.ui.isEffectivelyWholeTerrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Refine-view sharpening as it worked before PRs #47–#52.
 *
 * Zooming into 10% of a tile and tapping Refine must re-rasterize that crop at the requested
 * pixel count (typically 1,024–1,536). The post-#48 density cap is correct for full-footprint
 * overviews; applying it to a crop kept the overview cell size and made Refine look like a no-op.
 */
class RefineViewRegressionTest {
    @Test
    fun focusedRefineKeepsTheRequestedResolution() {
        val focus = NormalizedRasterBounds(0.10, 0.10, 0.20, 0.20)
        val options = LidarImportOptions(
            rasterResolution = 1_024,
            focusBounds = focus,
        )
        val decoded = options.forTerrainDecode(
            rangeX = 100.0,
            rangeY = 100.0,
            pointCount = 100,
        )
        assertEquals(
            "a 10% crop must stay at 1,024 px so the zoomed hillshade is sharper than the overview",
            1_024,
            decoded.rasterResolution,
        )
        assertNotNull(decoded.focusBounds)
    }

    @Test
    fun fullFootprintStillUsesTheDensityCap() {
        val overview = LidarImportOptions(rasterResolution = 1_024).forTerrainDecode(
            rangeX = 100.0,
            rangeY = 100.0,
            pointCount = 100,
        )
        assertEquals(128, overview.rasterResolution)
        assertNull(overview.focusBounds)
    }

    @Test
    fun focusedRasterIsTheRequestedWidthOnTheLongSide() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 1_000.0,
            minY = 0.0,
            maxY = 1_000.0,
            options = LidarImportOptions(
                rasterResolution = 1_024,
                focusBounds = NormalizedRasterBounds(0.40, 0.40, 0.50, 0.50),
            ),
            declaredPointCount = 8_000_000,
        )
        assertEquals(1_024, maxOf(rasterizer.width, rasterizer.height))
    }

    @Test
    fun refinedGpuSceneKeepsTheSharpenedGridSize() {
        val width = 1_024
        val height = 768
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = FloatArray(width * height) { index -> (index % width).toFloat() },
            canopySpikes = FloatArray(width * height),
            validData = BooleanArray(width * height) { true },
        )
        val scene = TerrainGpuSceneBuilder.build(grid)
        assertEquals(width, scene.sourceWidth)
        assertEquals(height, scene.sourceHeight)
        assertEquals(1, scene.levels.first().reductionFactor)
    }

    @Test
    fun refineResolutionStillScalesWithDeviceRam() {
        assertEquals(768, chooseAiRefineResolution(memoryClassMb = 128, isLowRamDevice = true, availableProcessors = 4))
        assertEquals(1_024, chooseAiRefineResolution(memoryClassMb = 384, isLowRamDevice = false, availableProcessors = 8))
        assertEquals(
            1_536,
            chooseAiRefineResolution(
                memoryClassMb = 512,
                isLowRamDevice = false,
                availableProcessors = 8,
                totalRamMb = 4_096,
            ),
        )
    }

    @Test
    fun wholeFootprintRefineIsRejectedSoUserMustZoomFirst() {
        assertTrue(isEffectivelyWholeTerrain(NormalizedRasterBounds.Full))
        assertTrue(isEffectivelyWholeTerrain(NormalizedRasterBounds(0.02, 0.02, 0.98, 0.98)))
        assertFalse(isEffectivelyWholeTerrain(NormalizedRasterBounds(0.25, 0.25, 0.75, 0.75)))
    }

    @Test
    fun focusedRefineNeverEntersSparsePreview() {
        val focused = LidarImportOptions(
            rasterResolution = 1_024,
            focusBounds = NormalizedRasterBounds(0.2, 0.2, 0.8, 0.8),
        ).sanitized()
        assertNotNull(focused.focusBounds)
        assertNull(
            LazTerrainReader.readSparsePreview(
                file = kotlin.io.path.createTempFile(suffix = ".laz").toFile().apply { writeBytes(ByteArray(8)) },
                options = focused,
            ),
        )
    }

    @Test
    fun previewAndExactJobHooksAreStillPresent() {
        assertTrue(TerrainDecodeCoordinator.GPU_PREVIEW_MAX_DIMENSION > 0)
        val sample = ElevationGrid(
            width = 64,
            height = 64,
            bareEarth = FloatArray(64 * 64) { 1f },
            canopySpikes = FloatArray(64 * 64),
            validData = BooleanArray(64 * 64) { true },
        )
        val scene = TerrainGpuSceneBuilder.build(sample, maxFinestDimension = 64, tileSize = 16)
        val outcome = TerrainDecodeOutcome(
            terrain = DemGenerator.TerrainLoadResult(sample, "preview", isBareEarth = true),
            cacheHit = LazTerrainCache.Hit.MISS,
            gpuScene = scene,
            isPreview = true,
        )
        assertTrue(outcome.isPreview)
        assertNull(outcome.exactOutcome)
        assertEquals(2_048, LidarImportOptions.MAX_REFINED_RESOLUTION)
    }
}
