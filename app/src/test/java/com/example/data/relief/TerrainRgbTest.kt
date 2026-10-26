package com.example.data.relief

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainRgbTest {
    @Test
    fun pngDecodesBackToTheSameHeights() {
        val size = 64
        val heights = FloatArray(size * size) { i -> -86f + (i % size) * 13.7f + (i / size) * 91.3f }
        val png = TerrainRgb.encodePng(heights, size)

        val image = ImageIO.read(ByteArrayInputStream(png)) ?: error("not a PNG")
        assertEquals(size, image.width)
        assertEquals(size, image.height)
        for (y in 0 until size) for (x in 0 until size) {
            val decoded = TerrainRgb.decode(image.getRGB(x, y) and 0xFFFFFF)
            assertEquals("($x, $y)", heights[y * size + x].toDouble(), decoded, 0.051)
        }
    }

    @Test
    fun codeClampsToTheEncodableRange() {
        assertEquals(0, TerrainRgb.code(-20_000f))
        assertEquals(0xFFFFFF, TerrainRgb.code(2_000_000f))
        assertEquals(100_000, TerrainRgb.code(0f))
    }

    @Test
    fun gapsTakeTheNearestValueAlongTheRow() {
        val n = Float.NaN
        val values = floatArrayOf(
            n, 5f, n, n,
            n, n, n, n,
            1f, n, n, 2f,
            n, n, n, n,
        )
        assertTrue(TerrainRgb.fillGaps(values, 4))
        assertEquals(listOf(5f, 5f, 5f, 5f), values.slice(0..3))
        assertEquals(listOf(1f, 1f, 1f, 2f), values.slice(8..11))
        // Rows with no data copy the nearest filled row.
        assertEquals(values.slice(0..3), values.slice(4..7))
        assertEquals(values.slice(8..11), values.slice(12..15))
    }

    @Test
    fun allMissingReportsNoData() {
        assertFalse(TerrainRgb.fillGaps(FloatArray(16) { Float.NaN }, 4))
    }
}
