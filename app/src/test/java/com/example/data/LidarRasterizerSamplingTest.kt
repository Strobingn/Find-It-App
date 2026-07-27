package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LidarRasterizerSamplingTest {
    @Test
    fun exactGateKeepsEveryReturnForLargePointClouds() {
        val rasterizer = LidarRasterizer(
            minX = 0.0,
            maxX = 100.0,
            minY = 0.0,
            maxY = 100.0,
            options = LidarImportOptions(rasterResolution = 320),
            declaredPointCount = 16_000_000,
        )

        var selected = 0
        repeat(10) {
            if (rasterizer.shouldBinNextPoint()) selected++
        }

        assertEquals(10, selected)
        assertEquals(10L, rasterizer.pointsDecoded)
    }
}
