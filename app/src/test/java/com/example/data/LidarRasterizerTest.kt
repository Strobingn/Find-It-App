package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarRasterizerTest {
    @Test
    fun importOptionsAllowHighResolutionWithoutUnboundedAllocation() {
        assertEquals(1_024, LidarImportOptions(rasterResolution = 4_096).sanitized().rasterResolution)
        assertEquals(4, LidarImportOptions(smoothingRadius = 99).sanitized().smoothingRadius)
    }

    @Test
    fun preservesSourceFootprintAndUsesClassifiedGround() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 200.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                rasterResolution = 200,
            ),
            declaredPointCount = 120,
        )
        repeat(120) { index ->
            val x = (index % 20) * 10.0
            val y = (index / 20) * 18.0
            rasterizer.addPoint(x, y, 10f + index % 3, classification = 2)
        }

        val result = requireNotNull(rasterizer.finish(6, "test"))

        assertEquals(200, result.grid.width)
        assertEquals(100, result.grid.height)
        assertEquals(GroundSurfaceMode.SOURCE_CLASSIFIED, result.appliedGroundMode)
        assertTrue(result.usedClassificationFilter)
    }

    @Test
    fun sparseSourceClassesFallBackToAutomaticGround() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 10.0,
            minY = 0.0,
            maxY = 10.0,
            options = LidarImportOptions(groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED),
            declaredPointCount = 50,
        )
        repeat(50) { index ->
            rasterizer.addPoint(
                x = (index % 10).toDouble(),
                y = (index / 10).toDouble(),
                z = index.toFloat(),
                classification = 1,
            )
        }

        val result = requireNotNull(rasterizer.finish(3, "test"))

        assertEquals(GroundSurfaceMode.AUTO_LOWEST, result.appliedGroundMode)
        assertFalse(result.usedClassificationFilter)
        assertTrue(result.note.contains("coverage was sparse"))
    }

    @Test
    fun surfaceModelKeepsHighestReturn() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 1.0,
            minY = 0.0,
            maxY = 1.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.SURFACE_MODEL,
                rasterResolution = 128,
            ),
            declaredPointCount = 2,
        )
        rasterizer.addPoint(0.5, 0.5, 10f, classification = 2)
        rasterizer.addPoint(0.5, 0.5, 22f, classification = 5)

        val result = requireNotNull(rasterizer.finish(3, "test"))

        assertEquals(GroundSurfaceMode.SURFACE_MODEL, result.appliedGroundMode)
        assertTrue(result.grid.bareEarth.any { it == 22f })
        assertTrue(result.grid.canopySpikes.all { it == 0f })
    }

    @Test
    fun nearestFillExpandsFromAllMeasurementsWithoutRowSmearing() {
        val grid = FloatArray(9 * 3) { Float.NaN }
        grid[1 * 9] = 10f
        grid[1 * 9 + 8] = 20f

        fillMissingNearest(grid, width = 9, height = 3)

        assertEquals(10f, grid[1 * 9 + 1])
        assertEquals(20f, grid[1 * 9 + 7])
        assertTrue(grid.all { it.isFinite() })
    }

    @Test
    fun coverageMaskBridgesSmallBinGapsButPreservesLargeNoDataAreas() {
        val width = 100
        val height = 10
        val counts = IntArray(width * height)
        for (y in 3..6) {
            for (x in 0..4) counts[y * width + x] = 1
            for (x in 95..99) counts[y * width + x] = 1
        }

        val mask = buildCoverageMask(counts, width, height)

        assertTrue(mask[5 * width + 6])
        assertFalse(mask[5 * width + 50])
        assertTrue(mask[5 * width + 94])
    }

    @Test
    fun rasterizedPointCloudCarriesItsMeasuredFootprint() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 1.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
                rasterResolution = 200,
            ),
            declaredPointCount = 400,
        )
        repeat(200) { index ->
            rasterizer.addPoint(
                x = (index % 20) * 0.25,
                y = (index % 5) * 0.2,
                z = 10f,
                classification = 2,
            )
            rasterizer.addPoint(
                x = 95.0 + (index % 20) * 0.25,
                y = (index % 5) * 0.2,
                z = 20f,
                classification = 2,
            )
        }

        val result = requireNotNull(rasterizer.finish(6, "two strips"))
        val middle = (result.grid.height / 2) * result.grid.width + result.grid.width / 2

        assertFalse(result.grid.validData[middle])
        assertTrue(result.grid.validData.any { it })
        assertTrue(result.grid.bareEarth.all { it.isFinite() })
    }
    @Test
    fun nestedViewportBoundsComposeAgainstTheCurrentDetailTile() {
        val parent = NormalizedRasterBounds(0.2, 0.1, 0.8, 0.9)
        val child = NormalizedRasterBounds(0.25, 0.25, 0.75, 0.75)

        val absolute = child.inside(parent)

        assertEquals(0.35, absolute.left, 0.000_001)
        assertEquals(0.30, absolute.top, 0.000_001)
        assertEquals(0.65, absolute.right, 0.000_001)
        assertEquals(0.70, absolute.bottom, 0.000_001)
    }

    @Test
    fun detailedViewportBinsOnlyPointsInsideTheSelectedSourceArea() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(
                groundMode = GroundSurfaceMode.AUTO_LOWEST,
                rasterResolution = 128,
                focusBounds = NormalizedRasterBounds(
                    left = 0.25,
                    top = 0.0,
                    right = 0.75,
                    bottom = 1.0,
                ),
            ),
            declaredPointCount = 4,
        )
        rasterizer.addPoint(10.0, 50.0, 1f, classification = 2)
        rasterizer.addPoint(30.0, 50.0, 3f, classification = 2)
        rasterizer.addPoint(70.0, 50.0, 7f, classification = 2)
        rasterizer.addPoint(90.0, 50.0, 9f, classification = 2)

        val result = requireNotNull(rasterizer.finish(6, "focused"))

        assertEquals(2, rasterizer.pointsBinned)
        assertEquals(3f, result.grid.bareEarth.minOrNull() ?: Float.NaN)
        assertEquals(7f, result.grid.bareEarth.maxOrNull() ?: Float.NaN)
        assertTrue(result.note.contains("detailed viewport"))
    }
}
