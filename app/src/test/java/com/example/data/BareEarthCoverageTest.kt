package com.example.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BareEarthCoverageTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun latticeFinerThanSpacingFillsHullUnderFivePercentEmpty() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 40.0,
            minY = 0.0,
            maxY = 40.0,
            options = LidarImportOptions(
                rasterResolution = 128,
                groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
            ),
            declaredPointCount = 21 * 21L,
        )
        for (row in 0..20) {
            for (col in 0..20) {
                rasterizer.addPoint(col * 2.0, row * 2.0, 40f + row * 0.02f, classification = 2)
            }
        }

        val result = requireNotNull(rasterizer.finish(0, "lattice"))
        val emptyShare = emptyShareInsideHull(result.grid)
        val diagnostics = requireNotNull(TerrainCoverageLog.last)

        assertTrue(
            "empty-in-hull ${emptyShare * 100}% must be < 5%",
            emptyShare < CONTINUOUS_HULL_EMPTY_SHARE,
        )
        assertTrue(diagnostics.emptyInsideHullPercent < 5f)
        assertTrue(diagnostics.interpolatedCells > 0)
        assertTrue(result.grid.interpolatedData.any { it })
        assertTrue(result.grid.validData.count { it } > 21 * 21)
        assertTrue(diagnostics.countHistogram[0] >= 0)
    }

    @Test
    fun trueFlightGapStaysInvalidOutsideHull() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 80.0,
            minY = 0.0,
            maxY = 20.0,
            options = LidarImportOptions(
                rasterResolution = 160,
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
            ),
            declaredPointCount = 11 * 11L,
        )
        for (row in 0..10) {
            for (col in 0..10) {
                rasterizer.addPoint(col * 2.0, row * 2.0, 12f, classification = 2)
            }
        }

        val result = requireNotNull(rasterizer.finish(0, "gap"))
        val farX = result.grid.width - 2
        val farY = result.grid.height / 2
        val farIndex = farY * result.grid.width + farX
        assertFalse("far void must stay invalid", result.grid.validData[farIndex])
        assertTrue(result.grid.bareEarth[farIndex].isNaN())
        assertFalse(result.grid.interpolatedData[farIndex])
    }

    @Test
    fun syntheticLasLatticeGuardrail() {
        val file = LasFixture.writeLattice(
            file = folder.newFile("lattice.las"),
            columns = 25,
            rows = 25,
            spacingMeters = 2.0,
        )
        val result = file.inputStream().buffered().use { input ->
            DemGenerator.parseLasStreamDetailed(
                inputStream = input,
                options = LidarImportOptions(
                    rasterResolution = 1_024,
                    groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                ),
            )
        }
        assertNotNull(result)
        val grid = result!!.grid
        val emptyShare = emptyShareInsideHull(grid)
        assertTrue(
            "LAS lattice empty-in-hull ${emptyShare * 100}% must be < 5%",
            emptyShare < CONTINUOUS_HULL_EMPTY_SHARE,
        )
        assertTrue(grid.validData.count { it } > 200)
        assertFalse(result.wasTruncated)
    }

    @Test
    fun interpolatedCellsAreMarkedAndMeasuredStayUnmarked() {
        val measured = BooleanArray(9 * 9)
        val elevations = FloatArray(9 * 9) { Float.NaN }
        for (y in 0..8 step 2) {
            for (x in 0..8 step 2) {
                val index = y * 9 + x
                measured[index] = true
                elevations[index] = 10f + x + y
            }
        }
        val filled = interpolateBareEarth(
            measuredElevations = elevations,
            measuredMask = measured,
            width = 9,
            height = 9,
            cellSizeMeters = 1f,
            totalPoints = 25,
            groundPoints = 25,
        )
        assertTrue(filled.diagnostics.emptyInsideHullPercent < 5f)
        assertEquals(false, filled.interpolatedData[0])
        assertTrue(filled.validData[0])
        assertTrue(filled.interpolatedData[1] || filled.validData[1])
        assertTrue(filled.elevations[1].isFinite())
    }

    @Test
    fun gpuTilesRenderAfterHullFill() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 24.0,
            minY = 0.0,
            maxY = 24.0,
            options = LidarImportOptions(
                rasterResolution = 48,
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
            ),
            declaredPointCount = 13 * 13L,
        )
        for (row in 0..12) {
            for (col in 0..12) {
                rasterizer.addPoint(col * 2.0, row * 2.0, 8f, classification = 2)
            }
        }
        val grid = requireNotNull(rasterizer.finish(0, "gpu")).grid
        val scene = TerrainGpuSceneBuilder.build(grid, maxFinestDimension = 48, tileSize = 16)
        assertTrue(scene.levels.first().batches.isNotEmpty())
        val validShare = grid.validData.count { it }.toFloat() / grid.validData.size
        assertTrue("expected a continuous GPU surface, valid=$validShare", validShare > 0.7f)
    }
}
