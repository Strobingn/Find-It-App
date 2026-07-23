package com.example.analysis

import com.example.data.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TerrainAnalysisEngineTest {

    @Test
    fun everyAnalysisTypeProducesFiniteGrid() {
        val grid = rollingGrid(width = 25, height = 23)
        val options = TerrainAnalysisOptions(
            localRadiusMeters = 4f,
            horizonRadiusMeters = 8f,
            directionCount = 8,
            erosionIterations = 3,
            rainfallFactor = 1f,
        )

        TerrainAnalysisType.entries.forEach { type ->
            val layer = TerrainAnalysisEngine.analyze(grid, type, options)
            assertEquals(type, layer.type)
            assertEquals(grid.width * grid.height, layer.values.size)
            assertEquals(grid.width, layer.width)
            assertEquals(grid.height, layer.height)
            assertTrue("$type contains a non-finite value", layer.values.all { it.isFinite() })
            assertTrue(layer.minimum.isFinite())
            assertTrue(layer.maximum.isFinite())
            assertTrue(layer.mean.isFinite())
        }
    }

    @Test
    fun flatTerrainHasZeroSlopeCurvatureAndRelief() {
        val grid = constantGrid(width = 17, height = 17, elevation = 12f)
        val slope = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.SLOPE)
        val curvature = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.CURVATURE)
        val relief = TerrainAnalysisEngine.analyze(
            grid,
            TerrainAnalysisType.LOCAL_RELIEF_MODEL,
            TerrainAnalysisOptions(localRadiusMeters = 4f),
        )
        val skyView = TerrainAnalysisEngine.analyze(
            grid,
            TerrainAnalysisType.SKY_VIEW_FACTOR,
            TerrainAnalysisOptions(horizonRadiusMeters = 6f, directionCount = 8),
        )

        assertTrue(slope.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(curvature.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(relief.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(skyView.values.minOrNull()!! > 0.99f)
    }

    @Test
    fun depressionFinderMeasuresClosedPitDepth() {
        val width = 15
        val height = 15
        val elevations = FloatArray(width * height) { 10f }
        val center = (height / 2) * width + width / 2
        elevations[center] = 5f
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = elevations,
            canopySpikes = FloatArray(elevations.size),
            cellSizeMeters = 1f,
        )

        val depression = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.DEPRESSION_DEPTH)

        assertTrue(depression.values[center] >= 4.99f)
        assertTrue(depression.maximum >= 4.99f)
    }

    @Test
    fun flowAccumulationConvergesDownhill() {
        val width = 20
        val height = 20
        val elevations = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (width - x + height - y).toFloat()
        }
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = elevations,
            canopySpikes = FloatArray(elevations.size),
            cellSizeMeters = 1f,
        )

        val flow = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.FLOW_ACCUMULATION)

        val outlet = flow.values.last()
        assertTrue(outlet > 100f)
        assertTrue(flow.maximum >= outlet)
    }

    @Test
    fun localReliefSeparatesSmallMoundFromBroadSlope() {
        val width = 21
        val height = 21
        val elevations = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x * 0.2f + y * 0.1f
        }
        val center = (height / 2) * width + width / 2
        elevations[center] += 3f
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = elevations,
            canopySpikes = FloatArray(elevations.size),
            cellSizeMeters = 1f,
        )

        val relief = TerrainAnalysisEngine.analyze(
            grid,
            TerrainAnalysisType.LOCAL_RELIEF_MODEL,
            TerrainAnalysisOptions(localRadiusMeters = 5f),
        )

        assertTrue(relief.values[center] > 2f)
    }

    private fun constantGrid(width: Int, height: Int, elevation: Float): ElevationGrid {
        val values = FloatArray(width * height) { elevation }
        return ElevationGrid(
            width = width,
            height = height,
            bareEarth = values,
            canopySpikes = FloatArray(values.size),
            cellSizeMeters = 1f,
        )
    }

    private fun rollingGrid(width: Int, height: Int): ElevationGrid {
        val values = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val base = x * 0.08f + y * 0.04f
            val feature = if (x in 9..13 && y in 8..12) -1.5f else 0f
            base + feature + kotlin.math.sin(x * 0.25f) * 0.4f
        }
        return ElevationGrid(
            width = width,
            height = height,
            bareEarth = values,
            canopySpikes = FloatArray(values.size),
            cellSizeMeters = 1f,
        )
    }
}
