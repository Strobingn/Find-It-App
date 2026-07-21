package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarRasterizerTest {
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
}
