package com.example.analysis

import java.util.EnumMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomesiteProbabilityMapTest {

    @Test
    fun `probability peaks near homesite cluster and stays low on empty ground`() {
        val grid = HomesiteProbabilityMap.compute(structuredLayers())
        val cluster = regionMean(grid, x0 = 12, y0 = 14, x1 = 30, y1 = 46)
        val background = regionMean(grid, x0 = 30, y0 = 50, x1 = 45, y1 = 62)
        assertTrue(
            "cluster $cluster should exceed background $background",
            cluster > background + 0.05f,
        )
    }

    @Test
    fun `wet corridor is penalized despite flat ground`() {
        val grid = HomesiteProbabilityMap.compute(structuredLayers())
        val wet = regionMean(grid, x0 = 50, y0 = 24, x1 = 56, y1 = 36)
        val dryFlat = regionMean(grid, x0 = 30, y0 = 50, x1 = 45, y1 = 62)
        assertTrue("wet $wet should stay below dry flat $dryFlat", wet < dryFlat)
    }

    @Test
    fun `steep slope is penalized`() {
        val grid = HomesiteProbabilityMap.compute(structuredLayers())
        val steep = regionMean(grid, x0 = 44, y0 = 6, x1 = 58, y1 = 18)
        val flat = regionMean(grid, x0 = 30, y0 = 50, x1 = 45, y1 = 62)
        assertTrue("steep $steep should stay below flat $flat", steep < flat)
    }

    @Test
    fun `values stay within 0 to 1`() {
        val grid = HomesiteProbabilityMap.compute(structuredLayers())
        for (value in grid.values) {
            assertTrue("value $value out of range", value >= 0f && value <= 1f)
        }
    }

    @Test
    fun `binned overlay has expected shape and range`() {
        val binned = HomesiteProbabilityMap.compute(structuredLayers()).binned(96)
        assertEquals(96 * 96, binned.size)
        for (value in binned) {
            assertTrue("value $value out of range", value >= 0f && value <= 1f)
        }
        // Average pooling preserves the ordering signal at overlay resolution.
        val clusterBin = binned[20 * 96 / 64 * 96 + 20 * 96 / 64]
        val wetBin = binned[30 * 96 / 64 * 96 + 53 * 96 / 64]
        assertTrue("cluster bin $clusterBin should exceed wet bin $wetBin", clusterBin > wetBin)
    }

    @Test
    fun `missing stream layer is tolerated`() {
        val layers = structuredLayers()
        val withoutStream = layers.copy(values = layers.values - TerrainDerivedLayer.ANCIENT_STREAM)
        val grid = HomesiteProbabilityMap.compute(withoutStream)
        assertEquals(64 * 64, grid.values.size)
    }

    private fun regionMean(
        grid: HomesiteProbabilityGrid,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Float {
        var sum = 0f
        var count = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                sum += grid.values[y * grid.width + x]
                count++
            }
        }
        return sum / count
    }

    /**
     * Mirrors the refiner test's historic fixture: flat building platform with a linear rim,
     * a wagon-road corridor, a cellar hole with raised rim, plus a wet stream band and a steep
     * hillside so the veto components have something to push against.
     */
    private fun structuredLayers(): TerrainDerivedLayers {
        val width = 64
        val height = 64
        val size = width * height
        val slope = FloatArray(size) { 0.08f }
        val curvature = FloatArray(size)
        val relief = FloatArray(size)
        val depression = FloatArray(size)
        val rugged = FloatArray(size) { 0.08f }
        val linearity = FloatArray(size)
        val stream = FloatArray(size)

        fun index(x: Int, y: Int) = y * width + x

        // Flat building platform with a raised linear perimeter.
        for (y in 12..27) {
            for (x in 9..29) {
                slope[index(x, y)] = 0.01f
                rugged[index(x, y)] = 0.01f
                val onPerimeter = x <= 10 || x >= 28 || y <= 13 || y >= 26
                if (onPerimeter) {
                    linearity[index(x, y)] = 1f
                    curvature[index(x, y)] = if ((x + y) % 2 == 0) 1f else -1f
                    relief[index(x, y)] = 0.55f
                }
            }
        }

        // Continuous low-gradient wagon-road corridor.
        for (x in 4..44) {
            for (y in 37..41) {
                linearity[index(x, y)] = 1f
                slope[index(x, y)] = 0.005f
                rugged[index(x, y)] = 0.005f
                relief[index(x, y)] = if (y in 39..40) -0.35f else 0.25f
            }
        }

        // Deep compact cellar hole with a broad raised rim.
        for (y in 43..53) {
            for (x in 12..22) {
                val dx = x - 17
                val dy = y - 48
                val distanceSquared = dx * dx + dy * dy
                when {
                    distanceSquared <= 8 -> {
                        depression[index(x, y)] = 1f
                        curvature[index(x, y)] = 1f
                        relief[index(x, y)] = -1f
                    }
                    distanceSquared <= 24 -> {
                        relief[index(x, y)] = 1f
                        linearity[index(x, y)] = 0.75f
                    }
                }
            }
        }

        // Wet ancient-stream band: flat, but should be vetoed.
        for (y in 0 until height) {
            for (x in 50..56) {
                stream[index(x, y)] = 1f
                slope[index(x, y)] = 0.02f
                rugged[index(x, y)] = 0.02f
            }
        }

        // Steep hillside: dry but unbuildable.
        for (y in 5..20) {
            for (x in 42..60) {
                slope[index(x, y)] = 1f
                rugged[index(x, y)] = 0.7f
            }
        }

        val values = EnumMap<TerrainDerivedLayer, FloatArray>(TerrainDerivedLayer::class.java).apply {
            put(TerrainDerivedLayer.SLOPE, slope)
            put(TerrainDerivedLayer.ASPECT, FloatArray(size))
            put(TerrainDerivedLayer.CURVATURE, curvature)
            put(TerrainDerivedLayer.LOCAL_RELIEF, relief)
            put(TerrainDerivedLayer.HILLSHADE_COMPARISON, FloatArray(size))
            put(TerrainDerivedLayer.POSITIVE_OPENNESS, FloatArray(size) { 0.82f })
            put(TerrainDerivedLayer.NEGATIVE_OPENNESS, FloatArray(size) { 0.82f })
            put(TerrainDerivedLayer.SKY_VIEW_FACTOR, FloatArray(size) { 0.85f })
            put(TerrainDerivedLayer.DEPRESSION_DEPTH, depression)
            put(TerrainDerivedLayer.RUGGEDNESS, rugged)
            put(TerrainDerivedLayer.LINEARITY, linearity)
            put(TerrainDerivedLayer.ANCIENT_STREAM, stream)
        }
        return TerrainDerivedLayers(width, height, 1f, values)
    }
}
