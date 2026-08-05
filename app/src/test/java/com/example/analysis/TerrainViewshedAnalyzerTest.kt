package com.example.analysis

import com.example.data.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainViewshedAnalyzerTest {
    private fun flatGrid(width: Int, height: Int, elevation: Float = 10f): ElevationGrid =
        ElevationGrid(
            width = width,
            height = height,
            bareEarth = FloatArray(width * height) { elevation },
            canopySpikes = FloatArray(width * height),
            cellSizeMeters = 1f,
        )

    private fun index(grid: ElevationGrid, col: Int, row: Int): Int = row * grid.width + col

    @Test
    fun observerOnPeakSeesAllSurroundingLowGround() {
        val grid = flatGrid(21, 21)
        grid.bareEarth[index(grid, 10, 10)] = 50f

        val viewshed = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 50f,
            observerYPercent = 50f,
        )

        assertFalse(viewshed.canceled)
        assertEquals(grid.width * grid.height, viewshed.analyzedCells)
        assertEquals(1f, viewshed.visibilityRatio, 0.0001f)
    }

    @Test
    fun ridgeBlocksCellsBehindItButNotInFront() {
        val grid = flatGrid(21, 21)
        for (row in 0 until 21) {
            grid.bareEarth[index(grid, 10, row)] = 20f
        }

        val viewshed = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 25f, // column 5
            observerYPercent = 50f, // row 10
        )

        assertTrue(viewshed.visibility[index(grid, 8, 10)])
        assertFalse(viewshed.visibility[index(grid, 15, 10)])
        assertFalse(viewshed.visibility[index(grid, 20, 10)])
    }

    @Test
    fun raisingObserverHeightRevealsCellsBehindSmallBerm() {
        val grid = flatGrid(21, 21)
        for (row in 0 until 21) {
            grid.bareEarth[index(grid, 10, row)] = 13f
        }

        val standing = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 25f,
            observerYPercent = 50f,
            observerHeightMeters = 1.7f,
        )
        val elevated = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 25f,
            observerYPercent = 50f,
            observerHeightMeters = 8f,
        )

        val behindBerm = index(grid, 15, 10)
        assertFalse(standing.visibility[behindBerm])
        assertTrue(elevated.visibility[behindBerm])
    }

    @Test
    fun radiusCapExcludesDistantCellsFromAnalysis() {
        val grid = flatGrid(21, 21)

        val viewshed = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 50f,
            observerYPercent = 50f,
            maxRadiusMeters = 5f,
        )

        assertTrue(viewshed.analyzedCells < grid.width * grid.height)
        assertFalse(viewshed.visibility[index(grid, 0, 0)])
        assertTrue(viewshed.visibility[index(grid, 10, 13)])
    }

    @Test
    fun cancellationStopsAnalysisEarly() {
        val grid = flatGrid(100, 100)
        var cancellationChecks = 0

        val viewshed = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 50f,
            observerYPercent = 50f,
            isCanceled = {
                cancellationChecks++
                cancellationChecks >= 2
            },
        )

        assertTrue(viewshed.canceled)
        assertTrue(viewshed.analyzedCells < grid.width * grid.height)
    }

    @Test
    fun horizonReportsBlockingRidgeAzimuthAndOpenDirection() {
        val grid = flatGrid(41, 41)
        for (row in 0 until 41) {
            grid.bareEarth[index(grid, 30, row)] = 25f
        }

        val horizon = TerrainViewshedAnalyzer.horizon(
            grid = grid,
            observerXPercent = 50f,
            observerYPercent = 50f,
            azimuthSteps = 72,
        )

        val east = horizon.samples[18] // 90 degrees
        assertTrue(
            "east horizon should rise steeply, got ${east.elevationAngleDegrees}",
            east.elevationAngleDegrees > 30f,
        )
        assertEquals(10f, east.distanceMeters, 1.5f)
        assertEquals(25f, east.elevationMeters, 0.001f)

        val west = horizon.samples[54] // 270 degrees, open flat ground
        assertTrue(
            "west horizon should dip below eye level, got ${west.elevationAngleDegrees}",
            west.elevationAngleDegrees < 0f,
        )
        assertEquals(20f, west.distanceMeters, 1.5f)
    }

    @Test
    fun vegetationCanopyBlocksLineOfSightUnlessFiltered() {
        val grid = flatGrid(21, 21)
        // Tall trees at column 10; bare earth everywhere stays at 10 m.
        for (row in 0 until 21) {
            grid.canopySpikes[index(grid, 10, row)] = 12f
        }

        val withCanopy = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 25f,
            observerYPercent = 50f,
            vegetationFilter = 0f,
        )
        val bareEarthOnly = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 25f,
            observerYPercent = 50f,
            vegetationFilter = 1f,
        )

        val behindTrees = index(grid, 15, 10)
        assertFalse(withCanopy.visibility[behindTrees])
        assertTrue(bareEarthOnly.visibility[behindTrees])
    }

    @Test
    fun parallelScanProducesIdenticalResultsToSequential() {
        val width = 96
        val height = 96
        val bareEarth = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            10f + 3f * kotlin.math.sin(x * 0.3f) * kotlin.math.cos(y * 0.3f) +
                if (x == 48) 8f else 0f
        }
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = bareEarth,
            canopySpikes = FloatArray(width * height),
            cellSizeMeters = 1f,
        )

        val sequential = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 50f,
            observerYPercent = 50f,
            maxWorkers = 1,
        )
        val parallel = TerrainViewshedAnalyzer.sample(
            grid = grid,
            observerXPercent = 50f,
            observerYPercent = 50f,
            maxWorkers = 4,
        )

        assertTrue(sequential.visibility.contentEquals(parallel.visibility))
        assertEquals(sequential.analyzedCells, parallel.analyzedCells)
        assertEquals(sequential.visibleCells, parallel.visibleCells)
        assertTrue(parallel.analyzedCells > 0)
    }
}
