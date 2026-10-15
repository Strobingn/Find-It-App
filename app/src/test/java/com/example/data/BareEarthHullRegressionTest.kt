package com.example.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the data-hull behavior the bare-earth engine depends on after the TIN removal:
 * dense rasters short-circuit, voids narrower than the kill width close, wide true voids do not.
 */
class BareEarthHullRegressionTest {
    @Test
    fun denseMaskHullIsTheWholeRaster() {
        val width = 24
        val height = 16
        val measured = BooleanArray(width * height) { true }
        val hull = buildDataHull(measured, width, height, voidMaxEdgeCells(0.5f))
        assertTrue(hull.all { it })
    }

    @Test
    fun hullClosesVoidsNarrowerThanKillWidthOnly() {
        val width = 40
        val height = 40
        val measured = BooleanArray(width * height) { true }
        // 6 cells x 10 m = 60 m wide void: narrower than the 100 m kill width, must close.
        for (y in 10..15) {
            for (x in 10..15) measured[y * width + x] = false
        }
        // 15 cells x 10 m = 150 m wide void: a true void, must stay outside the hull.
        for (y in 20..34) {
            for (x in 20..34) measured[y * width + x] = false
        }
        val hull = buildDataHull(measured, width, height, voidMaxEdgeCells(10f))
        assertTrue("60 m void must be inside the hull", hull[12 * width + 12])
        assertFalse("150 m void must stay outside the hull", hull[27 * width + 27])
        // Measured cells always belong to the hull.
        for (index in measured.indices) {
            if (measured[index]) assertTrue(hull[index])
        }
    }

    @Test
    fun denseGridNeedsNoInterpolatedCells() {
        val size = 16
        val elevations = FloatArray(size * size) { 10f + it }
        val measured = BooleanArray(size * size) { true }
        val filled = interpolateBareEarth(
            measuredElevations = elevations,
            measuredMask = measured,
            width = size,
            height = size,
            cellSizeMeters = 1f,
            totalPoints = size * size.toLong(),
            groundPoints = size * size,
        )
        assertTrue(filled.interpolatedData.none { it })
        assertTrue(filled.validData.all { it })
        assertTrue(filled.diagnostics.interpolatedCells == 0)
    }
}
