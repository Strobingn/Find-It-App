package com.example.data

import kotlin.math.abs
import kotlin.math.max
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The old per-tile TIN left a curvature jump on every 32-cell shared edge. The global
 * Delaunay must keep the median |Δ²| on those columns within ~10% of the interior.
 */
class TinSeamRegressionTest {
    @Test fun globalTinHasNoTileBoundarySeams() {
        val width = 160
        val height = 160
        val elevations = FloatArray(width * height) { Float.NaN }
        val measured = BooleanArray(width * height)
        // Sparse measured grid with intentional empties so the TIN actually fills.
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
        assertTrue(result.diagnostics.tilesTriangulated >= 1)
    }

    @Test fun withheldAndNoiseNeverBecomeGround() {
        val r = LidarRasterizer(
            minX = 0.0, maxX = 10.0, minY = 0.0, maxY = 10.0,
            options = LidarImportOptions(rasterResolution = 64).sanitized(),
            declaredPointCount = 100,
            lasVersionMajor = 1, lasVersionMinor = 4, pointFormat = 6,
        )
        // A clean ground return.
        r.addPoint(5.0, 5.0, 10f, classification = 2)
        // Withheld / noise that would otherwise punch a hole or spike.
        r.addPoint(5.1, 5.1, 0f, classification = 2, withheld = true)
        r.addPoint(5.2, 5.2, 0f, classification = 7)
        r.addPoint(5.3, 5.3, 100f, classification = 18)
        val finished = r.finish(pointFormat = 6, sourceLabel = "test")!!
        assertTrue(finished.groundPointsUsed == 1)
        assertTrue(finished.grid.bareEarth.all { !it.isFinite() || abs(it - 10f) < 1f || it.isNaN() })
    }
}

class GridDelaunayTest {
    @Test fun emptyCircumcircleOnIntegerGridPoints() {
        val rnd = java.util.Random(11)
        val seen = HashSet<Long>()
        val pts = ArrayList<Pair<Int, Int>>()
        while (pts.size < 400) {
            val x = rnd.nextInt(60); val y = rnd.nextInt(60)
            if (seen.add(x.toLong() * 1000 + y)) pts += x to y
        }
        val coords = DoubleArray(pts.size * 2) { if (it % 2 == 0) pts[it / 2].first.toDouble() else pts[it / 2].second.toDouble() }
        val d = GridDelaunay(coords)
        assertTrue("triangles ${d.triangleCount}", d.triangleCount > pts.size)
        for (t in 0 until d.triangleCount) {
            val a = d.triangles[3 * t]; val b = d.triangles[3 * t + 1]; val c = d.triangles[3 * t + 2]
            val ax = coords[2 * a]; val ay = coords[2 * a + 1]
            val bx = coords[2 * b]; val by = coords[2 * b + 1]
            val cx = coords[2 * c]; val cy = coords[2 * c + 1]
            for (p in pts.indices) {
                if (p == a || p == b || p == c) continue
                val px = coords[2 * p]; val py = coords[2 * p + 1]
                val dx = ax - px; val dy = ay - py; val ex = bx - px; val ey = by - py; val fx = cx - px; val fy = cy - py
                val det = dx * (ey * (fx * fx + fy * fy) - (ex * ex + ey * ey) * fy) -
                    dy * (ex * (fx * fx + fy * fy) - (ex * ex + ey * ey) * fx) +
                    (dx * dx + dy * dy) * (ex * fy - ey * fx)
                // Standard in-circle: det·orient(a,b,c) > 0 means p is strictly inside.
                val orient = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
                assertTrue("point $p inside triangle $t", det * orient <= 1e-6)
            }
        }
    }
}
