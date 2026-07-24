package com.example.analysis

import com.example.data.ElevationGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

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
            assertTrue(layer.standardDeviation.isFinite())
        }
    }

    @Test
    fun phaseOneCatalogContainsExactlyNineCoreProducts() {
        assertEquals(9, TerrainAnalysisType.phaseOneEntries.size)
        assertTrue(TerrainAnalysisType.phaseOneEntries.all { it.phase == TerrainAnalysisPhase.CORE })
        assertTrue(TerrainAnalysisType.FLOW_ACCUMULATION !in TerrainAnalysisType.phaseOneEntries)
    }

    @Test
    fun flatTerrainHasExpectedNeutralProducts() {
        val grid = constantGrid(width = 25, height = 25, elevation = 12f)
        val options = TerrainAnalysisOptions(horizonRadiusMeters = 8f, directionCount = 16)
        val slope = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.SLOPE)
        val curvature = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.CURVATURE)
        val ruggedness = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.RUGGEDNESS_INDEX)
        val relief = TerrainAnalysisEngine.analyze(
            grid,
            TerrainAnalysisType.LOCAL_RELIEF_MODEL,
            TerrainAnalysisOptions(localRadiusMeters = 4f),
        )
        val skyView = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.SKY_VIEW_FACTOR, options)
        val positive = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.POSITIVE_OPENNESS, options)
        val negative = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.NEGATIVE_OPENNESS, options)
        val aspect = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.ASPECT)
        val center = centerIndex(grid.width, grid.height)

        assertTrue(slope.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(curvature.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(ruggedness.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(relief.values.maxOf { abs(it) } < 0.0001f)
        assertTrue(skyView.values[center] > 0.999f)
        assertTrue(abs(positive.values[center] - 90f) < 0.01f)
        assertTrue(abs(negative.values[center] - 90f) < 0.01f)
        assertTrue(aspect.values.all { it == -1f })
    }

    @Test
    fun eastRisingPlaneHasExpectedSlopeAndEastwardGradientAspect() {
        val width = 21
        val height = 21
        val elevations = FloatArray(width * height) { index -> (index % width).toFloat() }
        val grid = grid(width, height, elevations)
        val slope = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.SLOPE)
        val aspect = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.ASPECT)
        val center = centerIndex(width, height)

        assertTrue(abs(slope.values[center] - 45f) < 0.25f)
        assertTrue(abs(aspect.values[center] - 90f) < 0.25f)
    }

    @Test
    fun closedBowlReducesSkyViewAtItsCenter() {
        val width = 41
        val height = 41
        val cx = width / 2
        val cy = height / 2
        val elevations = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val dx = (x - cx).toFloat()
            val dy = (y - cy).toFloat()
            0.035f * (dx * dx + dy * dy)
        }
        val grid = grid(width, height, elevations)
        val skyView = TerrainAnalysisEngine.analyze(
            grid,
            TerrainAnalysisType.SKY_VIEW_FACTOR,
            TerrainAnalysisOptions(horizonRadiusMeters = 18f, directionCount = 24),
        )
        val center = centerIndex(width, height)
        val shoulder = cy * width + (cx + 12)

        assertTrue("bowl center should have restricted sky", skyView.values[center] < 0.9f)
        assertTrue("bowl shoulder should see more sky", skyView.values[shoulder] > skyView.values[center])
    }

    @Test
    fun moundPitRidgeAndValleySeparateOpenness() {
        val options = TerrainAnalysisOptions(horizonRadiusMeters = 10f, directionCount = 16)
        val mound = radialFeatureGrid(size = 31, amplitude = 5f)
        val pit = radialFeatureGrid(size = 31, amplitude = -5f)
        val ridge = lineFeatureGrid(size = 31, amplitude = 4f)
        val valley = lineFeatureGrid(size = 31, amplitude = -4f)

        fun pair(grid: ElevationGrid): Pair<Float, Float> {
            val center = centerIndex(grid.width, grid.height)
            val positive = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.POSITIVE_OPENNESS, options)
            val negative = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.NEGATIVE_OPENNESS, options)
            return positive.values[center] to negative.values[center]
        }

        val moundPair = pair(mound)
        val pitPair = pair(pit)
        val ridgePair = pair(ridge)
        val valleyPair = pair(valley)

        assertTrue(moundPair.first > moundPair.second)
        assertTrue(pitPair.second > pitPair.first)
        assertTrue(ridgePair.first > ridgePair.second)
        assertTrue(valleyPair.second > valleyPair.first)
    }

    @Test
    fun curvatureSignMatchesConvexMoundAndConcavePit() {
        val mound = radialFeatureGrid(size = 31, amplitude = 5f)
        val pit = radialFeatureGrid(size = 31, amplitude = -5f)
        val moundCurvature = TerrainAnalysisEngine.analyze(mound, TerrainAnalysisType.CURVATURE)
        val pitCurvature = TerrainAnalysisEngine.analyze(pit, TerrainAnalysisType.CURVATURE)
        val center = centerIndex(mound.width, mound.height)

        assertTrue("raised convex mound should be negative", moundCurvature.values[center] < -0.01f)
        assertTrue("concave pit should be positive", pitCurvature.values[center] > 0.01f)
    }

    @Test
    fun missingDataStaysExcludedWithoutCorruptingNeighbors() {
        val width = 25
        val height = 25
        val elevations = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x * 0.1f + y * 0.05f
        }
        val valid = BooleanArray(width * height) { true }
        for (y in 10..14) for (x in 10..14) valid[y * width + x] = false
        val grid = ElevationGrid(width, height, elevations, FloatArray(elevations.size), 1f, valid)

        TerrainAnalysisType.phaseOneEntries.forEach { type ->
            val layer = TerrainAnalysisEngine.analyze(
                grid,
                type,
                TerrainAnalysisOptions(horizonRadiusMeters = 8f, directionCount = 8),
            )
            assertEquals(valid.count { it }, layer.validCellCount)
            assertTrue("$type contains non-finite values", layer.values.all { it.isFinite() })
            assertTrue("$type changed the NoData mask", layer.validData.contentEquals(valid))
        }
    }

    @Test
    fun layerReportsCoverageAndStandardDeviation() {
        val grid = rollingGrid(width = 13, height = 11)
        val layer = TerrainAnalysisEngine.analyze(grid, TerrainAnalysisType.SLOPE)

        assertEquals(grid.width * grid.height, layer.validCellCount)
        assertTrue(abs(layer.coveragePercent - 100f) < 0.001f)
        assertTrue(layer.standardDeviation >= 0f)
        assertTrue(layer.aiSummary().contains("standard deviation"))
    }

    @Test
    fun depressionFinderMeasuresClosedPitDepth() {
        val width = 15
        val height = 15
        val elevations = FloatArray(width * height) { 10f }
        val center = centerIndex(width, height)
        elevations[center] = 5f
        val depression = TerrainAnalysisEngine.analyze(grid(width, height, elevations), TerrainAnalysisType.DEPRESSION_DEPTH)

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
        val flow = TerrainAnalysisEngine.analyze(grid(width, height, elevations), TerrainAnalysisType.FLOW_ACCUMULATION)
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
        val center = centerIndex(width, height)
        elevations[center] += 3f
        val relief = TerrainAnalysisEngine.analyze(
            grid(width, height, elevations),
            TerrainAnalysisType.LOCAL_RELIEF_MODEL,
            TerrainAnalysisOptions(localRadiusMeters = 5f),
        )

        assertTrue(relief.values[center] > 2f)
    }

    private fun constantGrid(width: Int, height: Int, elevation: Float): ElevationGrid =
        grid(width, height, FloatArray(width * height) { elevation })

    private fun radialFeatureGrid(size: Int, amplitude: Float): ElevationGrid {
        val center = size / 2
        val radius = 8f
        val values = FloatArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val distance = sqrt(((x - center) * (x - center) + (y - center) * (y - center)).toFloat())
            amplitude * (1f - distance / radius).coerceIn(0f, 1f)
        }
        return grid(size, size, values)
    }

    private fun lineFeatureGrid(size: Int, amplitude: Float): ElevationGrid {
        val center = size / 2
        val values = FloatArray(size * size) { index ->
            val x = index % size
            amplitude * (1f - abs(x - center) / 6f).coerceIn(0f, 1f)
        }
        return grid(size, size, values)
    }

    private fun rollingGrid(width: Int, height: Int): ElevationGrid {
        val values = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val base = x * 0.08f + y * 0.04f
            val feature = if (x in 9..13 && y in 8..12) -1.5f else 0f
            base + feature + kotlin.math.sin(x * 0.25f) * 0.4f
        }
        return grid(width, height, values)
    }

    private fun grid(width: Int, height: Int, values: FloatArray): ElevationGrid = ElevationGrid(
        width = width,
        height = height,
        bareEarth = values,
        canopySpikes = FloatArray(values.size),
        cellSizeMeters = 1f,
    )

    private fun centerIndex(width: Int, height: Int): Int = (height / 2) * width + width / 2
}
