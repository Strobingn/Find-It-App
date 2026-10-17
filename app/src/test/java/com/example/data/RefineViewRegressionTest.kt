package com.example.data

import com.example.ui.PersistedMapViewport
import com.example.ui.chooseAiRefineResolution
import com.example.ui.components.shouldResetGpuCamera
import com.example.ui.isEffectivelyWholeTerrain
import com.example.ui.persistViewportAfterTerrainSwap
import kotlin.math.sqrt
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
    fun zoomedRefineCellIsFinerThanOverviewAndNearNativeSpacing() {
        val fullRange = 1_000.0
        val overviewResolution = 1_024
        val overviewCell = fullRange / (overviewResolution - 1)
        val cropRange = 100.0
        val pointsInCrop = 80_000L
        val refineResolution = focusResolutionForPointSpacing(
            rangeX = cropRange,
            rangeY = cropRange,
            pointsInFocus = pointsInCrop,
            requestedResolution = overviewResolution,
        )
        val refineCell = cropRange / (refineResolution - 1)
        val spacing = 1.0 / sqrt(pointsInCrop / (cropRange * cropRange))
        assertTrue(
            "refined cell $refineCell m must be smaller than overview cell $overviewCell m",
            refineCell < overviewCell,
        )
        assertTrue("refine cell must not go finer than the point spacing ($spacing)", refineCell >= spacing)
        assertTrue(
            "refine cell should stay around 1–2× ground spacing ($spacing), was $refineCell",
            refineCell <= spacing * 2.05,
        )
    }

    @Test
    fun refineDiscardsSavedZoomSoALaterTerrainTabCannotRemagnifyTheCrop() {
        val zoomed = PersistedMapViewport(zoom = 4.5f, panX = 180f, panY = -90f)
        val afterRefine = persistViewportAfterTerrainSwap(zoomed, discardSavedViewport = true)
        assertEquals(PersistedMapViewport.FitToCanvas, afterRefine)
        // First composition of Terrain runs the restore-token effect after the reset-key
        // effect. Restore wins, so it must read the cleared values rather than 4.5×.
        val restoreToken = 1
        val shown = if (restoreToken > 0) afterRefine else PersistedMapViewport.FitToCanvas
        assertEquals(1f, shown.zoom, 0f)
        assertEquals(0f, shown.panX, 0f)
        assertEquals(0f, shown.panY, 0f)
    }

    @Test
    fun loadingANewLidarKeepsThePersistedViewportForColdStartRestore() {
        val saved = PersistedMapViewport(zoom = 3f, panX = 40f, panY = 12f)
        assertEquals(saved, persistViewportAfterTerrainSwap(saved, discardSavedViewport = false))
    }

    @Test
    fun sameSizedRefinedGpuSceneStillResetsTheCamera() {
        val overview = TerrainGpuSceneBuilder.build(sampleGrid(1_024, 1_024, cellSizeMeters = 0.98f))
        val crop = TerrainGpuSceneBuilder.build(sampleGrid(1_024, 1_024, cellSizeMeters = 0.12f))
        assertEquals(overview.sourceWidth, crop.sourceWidth)
        assertEquals(overview.sourceHeight, crop.sourceHeight)
        assertFalse(
            "dimensions alone must not keep the old 3D camera on a same-size crop",
            shouldResetGpuCamera(
                previousScene = overview,
                previousResetKey = 1,
                nextScene = crop,
                nextResetKey = 1,
            ),
        )
        assertTrue(
            "the refine viewport-reset key must reset the GPU camera even when pixel size is unchanged",
            shouldResetGpuCamera(
                previousScene = overview,
                previousResetKey = 1,
                nextScene = crop,
                nextResetKey = 2,
            ),
        )
    }

    @Test
    fun refinedProductIsWhatTheGpuRendererShows() {
        val overview = sampleGrid(1_024, 768, cellSizeMeters = 0.98f)
        val refined = sampleGrid(227, 227, cellSizeMeters = 0.44f)
        TerrainPerformanceSession.publish(TerrainGpuSceneBuilder.build(overview))
        val refinedScene = TerrainGpuSceneBuilder.build(refined)
        TerrainPerformanceSession.publish(refinedScene)
        val shown = TerrainPerformanceSession.gpuScene.value
        assertNotNull(shown)
        assertEquals(refined.width, shown!!.sourceWidth)
        assertEquals(refined.height, shown.sourceHeight)
        assertTrue(refined.cellSizeMeters < overview.cellSizeMeters)
        TerrainPerformanceSession.clear()
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

    private fun sampleGrid(
        width: Int,
        height: Int,
        cellSizeMeters: Float = 1f,
    ): ElevationGrid {
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
            cellSizeMeters = cellSizeMeters,
        )
    }
}
