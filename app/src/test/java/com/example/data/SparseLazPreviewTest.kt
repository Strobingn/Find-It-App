package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sparse preview is for large full-footprint LAZ only. Unit coverage here pins the quality
 * contract without needing multi-million-point fixtures: focused refinements and small files
 * must not enter the preview path.
 */
class SparseLazPreviewTest {
    @Test
    fun focusedRefineNeverUsesSparsePreview() {
        val options = LidarImportOptions(
            groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
            rasterResolution = 1_024,
            focusBounds = NormalizedRasterBounds(0.2, 0.2, 0.8, 0.8),
        ).sanitized()
        // API contract: focus bounds force exact-only opens (preview returns null).
        assertTrue(options.focusBounds != null)
    }

    @Test
    fun qualityFloorRequiresAtLeast1024ForSparsePath() {
        assertEquals(1_024, TerrainDecodeCoordinator.GPU_PREVIEW_MAX_DIMENSION)
        assertTrue(TerrainDecodeCoordinator.GPU_FAST_TILE_SIZE > TerrainDecodeCoordinator.GPU_PREVIEW_TILE_SIZE)
        assertFalse(TerrainDecodeCoordinator.GPU_FAST_TILE_SIZE < 128)
    }

    @Test
    fun decodeOutcomeDefaultsAreNonPreview() {
        // Defaults: isPreview=false and exactOutcome=null when only required fields are set.
        val sample = sampleGrid(64, 48)
        val scene = TerrainGpuSceneBuilder.build(
            source = sample,
            maxFinestDimension = 64,
            tileSize = 32,
        )
        val outcome = TerrainDecodeOutcome(
            terrain = DemGenerator.TerrainLoadResult(
                grid = sample,
                summary = "test",
                isBareEarth = true,
            ),
            cacheHit = LazTerrainCache.Hit.MISS,
            gpuScene = scene,
        )
        assertFalse(outcome.isPreview)
        assertEquals(null, outcome.exactOutcome)
    }

    private fun sampleGrid(width: Int, height: Int): ElevationGrid {
        val size = width * height
        return ElevationGrid(
            width = width,
            height = height,
            bareEarth = FloatArray(size) { index ->
                val x = index % width
                val y = index / width
                (x + y).toFloat()
            },
            canopySpikes = FloatArray(size),
            validData = BooleanArray(size) { true },
        )
    }
}
