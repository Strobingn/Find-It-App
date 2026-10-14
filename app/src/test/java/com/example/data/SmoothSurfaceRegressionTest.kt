package com.example.data

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hole fill must not draw tile seams or flat facets. The per-tile TIN put a curvature jump on
 * every 32-cell edge and painted constant-slope blocks whose borders were horizontal or vertical.
 */
class SmoothSurfaceRegressionTest {
    @Test fun oldTileBoundariesAreNotSeams() {
        val width = 160
        val height = 160
        val elevations = FloatArray(width * height) { Float.NaN }
        val measured = BooleanArray(width * height)
        val rnd = java.util.Random(5)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (rnd.nextFloat() > 0.18f) continue
                val i = y * width + x
                measured[i] = true
                elevations[i] = (0.02f * x + 0.015f * y + 0.3f * kotlin.math.sin(x * 0.17f) * kotlin.math.cos(y * 0.13f))
            }
        }
        val result = interpolateBareEarth(
            measuredElevations = elevations,
            measuredMask = measured,
            width = width,
            height = height,
            cellSizeMeters = 1f,
            totalPoints = measured.count { it }.toLong(),
            groundPoints = measured.count { it },
        )
        val z = result.elevations
        val tile = 32
        fun colCurvature(c: Int): Double {
            var sum = 0.0; var n = 0
            for (y in 1 until height - 1) {
                val a = z[(y - 1) * width + c]
                val b = z[y * width + c]
                val d = z[(y + 1) * width + c]
                if (a.isFinite() && b.isFinite() && d.isFinite()) {
                    sum += abs(a - 2 * b + d); n++
                }
            }
            return if (n == 0) 0.0 else sum / n
        }
        fun rowCurvature(r: Int): Double {
            var sum = 0.0; var n = 0
            for (x in 1 until width - 1) {
                val a = z[r * width + (x - 1)]
                val b = z[r * width + x]
                val d = z[r * width + (x + 1)]
                if (a.isFinite() && b.isFinite() && d.isFinite()) {
                    sum += abs(a - 2 * b + d); n++
                }
            }
            return if (n == 0) 0.0 else sum / n
        }
        val edgeCols = (tile until width step tile).toList()
        val interiorCols = (1 until width - 1).filter { it % tile !in listOf(0, tile - 1) }
        val edgeRows = (tile until height step tile).toList()
        val interiorRows = (1 until height - 1).filter { it % tile !in listOf(0, tile - 1) }
        val edgeX = edgeCols.map(::colCurvature).average()
        val intX = interiorCols.map(::colCurvature).average().coerceAtLeast(1e-9)
        val edgeY = edgeRows.map(::rowCurvature).average()
        val intY = interiorRows.map(::rowCurvature).average().coerceAtLeast(1e-9)
        assertTrue("col seam ratio ${edgeX / intX}", edgeX / intX < 1.12)
        assertTrue("row seam ratio ${edgeY / intY}", edgeY / intY < 1.12)
        assertTrue(result.diagnostics.interpolatedCells > 0)
        assertTrue(result.diagnostics.emptyInsideHullPercent < 5f)
    }

    @Test fun fineGridHasNoAxisAlignedFacetLines() {
        val width = 96
        val height = 96
        val elevations = FloatArray(width * height) { Float.NaN }
        val measured = BooleanArray(width * height)
        for (y in 0 until height step 6) {
            for (x in 0 until width step 6) {
                val index = y * width + x
                measured[index] = true
                val hill = kotlin.math.sin(x * 0.11) * kotlin.math.cos(y * 0.09)
                elevations[index] = 0.04f * x + 0.02f * y + hill.toFloat()
            }
        }
        val result = interpolateBareEarth(
            measuredElevations = elevations,
            measuredMask = measured,
            width = width,
            height = height,
            cellSizeMeters = 0.25f,
            totalPoints = measured.count { it }.toLong(),
            groundPoints = measured.count { it },
        )
        val z = result.elevations
        assertTrue(result.diagnostics.emptyInsideHullPercent < 5f)
        assertEquals(false, result.interpolatedData[0])
        assertTrue(abs(z[0] - elevations[0]) < 0.001f)
        fun score(values: List<Double>): Double {
            val sorted = values.sorted()
            return sorted.last() / sorted[sorted.size / 2].coerceAtLeast(1e-6)
        }
        val rows = (1 until height - 1).map { y ->
            var sum = 0.0
            var n = 0
            for (x in 1 until width - 1) {
                val a = z[y * width + x - 1]
                val b = z[y * width + x]
                val d = z[y * width + x + 1]
                if (a.isFinite() && b.isFinite() && d.isFinite()) {
                    sum += abs(a - 2 * b + d)
                    n++
                }
            }
            if (n == 0) 0.0 else sum / n
        }
        val cols = (1 until width - 1).map { x ->
            var sum = 0.0
            var n = 0
            for (y in 1 until height - 1) {
                val a = z[(y - 1) * width + x]
                val b = z[y * width + x]
                val d = z[(y + 1) * width + x]
                if (a.isFinite() && b.isFinite() && d.isFinite()) {
                    sum += abs(a - 2 * b + d)
                    n++
                }
            }
            if (n == 0) 0.0 else sum / n
        }
        assertTrue("row crease ${score(rows)}", score(rows) < 4.0)
        assertTrue("col crease ${score(cols)}", score(cols) < 4.0)
    }
}
