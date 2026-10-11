package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic grid with a known minimum and maximum per cell (plus NaN returns and empty cells):
 * "Auto lowest" must surface the minimum elevation and "Highest-return DSM" the maximum.
 */
class GroundModeExtremesTest {
    private fun rasterize(mode: GroundSurfaceMode): DemGenerator.LasLoadResult {
        val r = LidarRasterizer(
            minX = 0.0, maxX = 100.0, minY = 0.0, maxY = 100.0,
            options = LidarImportOptions(rasterResolution = 48, groundMode = mode),
            declaredPointCount = 100_000,
        )
        // Every other column populated; three corroborated low returns at 10 m, canopy at 30 m,
        // a broad hollow at -5 m (40..60), a broad canopy peak at 80 m (10..30), and NaN returns.
        // (Broad features: single-cell pits are deliberately treated as low noise.)
        for (gx in 0 until 100 step 2) for (gy in 0 until 100 step 2) {
            val x = gx + 0.5
            val y = gy + 0.5
            val base = when {
                gx in 40..60 && gy in 40..60 -> -5f
                else -> 10f
            }
            val top = if (gx in 10..30 && gy in 10..30) 80f else 30f
            r.addPoint(x, y, Float.NaN, 1)
            r.addPoint(x, y, top, 1)
            r.addPoint(x, y, base, 1)
            r.addPoint(x, y, base + 0.05f, 1)
            r.addPoint(x, y, base + 0.1f, 1)
        }
        return requireNotNull(r.finish(0, "synthetic"))
    }

    private fun finiteRange(result: DemGenerator.LasLoadResult): Pair<Float, Float> {
        val values = result.grid.bareEarth.filter { it.isFinite() }
        assertTrue(values.isNotEmpty())
        return values.min() to values.max()
    }

    @Test
    fun autoLowestReturnsMinimumElevation() {
        val result = rasterize(GroundSurfaceMode.AUTO_LOWEST)
        assertEquals(GroundSurfaceMode.AUTO_LOWEST, result.appliedGroundMode)
        val (lo, hi) = finiteRange(result)
        assertEquals(-5f, lo, 0.5f)
        assertTrue("auto lowest must not contain canopy, max=$hi", hi < 11f)
        assertTrue(result.grid.bareEarth.any { it.isNaN() } || result.grid.validData.all { it })
    }

    @Test
    fun highestReturnsMaximumElevation() {
        val result = rasterize(GroundSurfaceMode.SURFACE_MODEL)
        assertEquals(GroundSurfaceMode.SURFACE_MODEL, result.appliedGroundMode)
        val (lo, hi) = finiteRange(result)
        assertEquals(80f, hi, 0.5f)
        assertTrue("DSM must sit on canopy, min=$lo", lo > 29f)
    }
}
