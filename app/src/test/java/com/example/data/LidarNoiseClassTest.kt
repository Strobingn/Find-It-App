package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ASPRS class 7 (Low Point) marks returns the producer identified as sitting below true ground.
 * Letting one define a cell's minimum carves a false pit into the bare-earth model, and that pit
 * then reads as a cellar hole or refuse pit to the feature detectors.
 */
class LidarNoiseClassTest {
    private fun rasterizer() = LidarRasterizer(
        minX = 0.0,
        maxX = 100.0,
        minY = 0.0,
        maxY = 100.0,
        options = LidarImportOptions(rasterResolution = 64, groundMode = GroundSurfaceMode.AUTO_LOWEST),
        declaredPointCount = 1_000L,
    )

    @Test
    fun lowPointAndHighNoiseAreRecognised() {
        assertTrue("class 7 is Low Point", LidarRasterizer.isNoise(7))
        assertTrue("class 18 is High Noise", LidarRasterizer.isNoise(18))
    }

    @Test
    fun realSurfaceClassesAreNotTreatedAsNoise() {
        listOf(0, 1, 2, 3, 4, 5, 6, 8, 9, 11, 17).forEach { classification ->
            assertFalse("class $classification must not be dropped", LidarRasterizer.isNoise(classification))
        }
    }

    /** The bug this exists to prevent: one noise return dragging a cell's ground down. */
    @Test
    fun aLowNoiseReturnDoesNotBecomeTheGroundSurface() {
        val withoutNoise = rasterizer().apply {
            addPoint(50.0, 50.0, 100f, classification = 1)
            addPoint(50.0, 50.0, 101f, classification = 1)
        }.finish(pointFormat = 1, sourceLabel = "test")

        val withNoise = rasterizer().apply {
            addPoint(50.0, 50.0, 100f, classification = 1)
            addPoint(50.0, 50.0, 101f, classification = 1)
            addPoint(50.0, 50.0, 40f, classification = 7)
        }.finish(pointFormat = 1, sourceLabel = "test")

        val clean = withoutNoise!!.grid.bareEarth.filter { it.isFinite() }.minOrNull()
        val noisy = withNoise!!.grid.bareEarth.filter { it.isFinite() }.minOrNull()

        assertEquals("a 60 m noise spike must not move the surface", clean, noisy)
    }

    @Test
    fun aHighNoiseReturnDoesNotInflateTheCanopy() {
        val withoutNoise = rasterizer().apply {
            addPoint(50.0, 50.0, 100f, classification = 1)
        }.finish(pointFormat = 1, sourceLabel = "test")

        val withNoise = rasterizer().apply {
            addPoint(50.0, 50.0, 100f, classification = 1)
            addPoint(50.0, 50.0, 900f, classification = 18)
        }.finish(pointFormat = 1, sourceLabel = "test")

        val clean = withoutNoise!!.grid.canopySpikes.maxOrNull()
        val noisy = withNoise!!.grid.canopySpikes.maxOrNull()

        assertEquals(clean, noisy)
    }

    /** Noise must not be silently deleted from the picture the diagnostics paint of the file. */
    @Test
    fun noiseStillCountsAsAPointThatWasRead() {
        val result = rasterizer().apply {
            addPoint(50.0, 50.0, 100f, classification = 1)
            addPoint(50.0, 50.0, 40f, classification = 7)
        }.finish(pointFormat = 1, sourceLabel = "test")

        assertEquals(2, result!!.totalPointsRead)
    }

    /** A cell whose only returns are noise carries no usable elevation. */
    @Test
    fun aCellOfPureNoiseContributesNoElevation() {
        val result = rasterizer().apply {
            addPoint(50.0, 50.0, 100f, classification = 1)
            addPoint(10.0, 10.0, 40f, classification = 7)
        }.finish(pointFormat = 1, sourceLabel = "test")

        // The surface is nearest-filled, so the noise cell adopts the real measurement rather
        // than keeping the 40 m reading that produced it.
        assertTrue(result!!.grid.bareEarth.none { it.isFinite() && it < 90f })
    }

    @Test
    fun groundClassificationIsUnaffectedByTheNoiseFilter() {
        val result = rasterizer().apply {
            repeat(200) { addPoint(50.0, 50.0, 100f, classification = 2) }
            addPoint(50.0, 50.0, 40f, classification = 7)
        }.finish(pointFormat = 1, sourceLabel = "test")

        assertTrue(result!!.grid.bareEarth.none { it.isFinite() && it < 90f })
    }
}
