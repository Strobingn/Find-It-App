package com.example.data.relief

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliefRendererTest {
    private val f = ElevationChunk.FULL
    private val p = ElevationChunk.PAD
    private val s = ElevationChunk.SIZE

    /** Flat ground at 100 m with a 2 m mound at (300, 300) and a 1.5 m deep east-west ditch at row 700. */
    private fun terrain(): FloatArray = FloatArray(f * f) { i ->
        val x = i % f - p
        val y = i / f - p
        val mound = 2.0 * exp(-((x - 300.0) * (x - 300.0) + (y - 300.0) * (y - 300.0)) / (2 * 12.0 * 12.0))
        val ditch = if (y in 698..702) -1.5 else 0.0
        (100.0 + mound + ditch).toFloat()
    }

    private fun luminance(pixels: IntArray, x: Int, y: Int): Int {
        val c = pixels[y * s + x]
        return ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
    }

    @Test
    fun hillshadeLightsNorthWestSlopes() {
        val px = ReliefRenderer.render(terrain(), 1.0, ReliefSettings(multiDirection = false))
        // NW flank of the mound faces the 315° sun, SE flank faces away.
        assertTrue(luminance(px, 290, 290) > luminance(px, 310, 310) + 20)
    }

    @Test
    fun localReliefShowsMoundUpAndDitchDown() {
        val px = ReliefRenderer.render(terrain(), 1.0, ReliefSettings(mode = ReliefMode.LOCAL_RELIEF, radiusMeters = 15f))
        val flat = luminance(px, 900, 100)
        assertTrue(luminance(px, 300, 300) > flat + 30)
        assertTrue(luminance(px, 500, 700) < flat - 30)
    }

    @Test
    fun skyViewDarkensTheDitch() {
        val px = ReliefRenderer.render(terrain(), 1.0, ReliefSettings(mode = ReliefMode.SKY_VIEW, radiusMeters = 10f))
        assertTrue(luminance(px, 500, 700) < luminance(px, 900, 100) - 40)
    }

    @Test
    fun everyModeRendersOpaqueOverData() {
        for (mode in ReliefMode.entries) {
            val px = ReliefRenderer.render(terrain(), 1.0, ReliefSettings(mode = mode))
            assertEquals("$mode alpha", 0xFF, px[512 * s + 512] ushr 24)
        }
    }

    @Test
    fun missingElevationIsTransparent() {
        val values = terrain()
        for (y in 0 until f) for (x in 0 until 600) values[y * f + x] = Float.NaN
        val px = ReliefRenderer.render(values, 1.0, ReliefSettings())
        assertEquals(0, px[100 * s + 100] ushr 24)
        assertEquals(0xFF, px[100 * s + 900] ushr 24)
    }

    @Test
    fun terrainRgbRoundTrips() {
        for (h in listOf(-50f, 0f, 87.3f, 4321.9f)) {
            assertEquals(h.toDouble(), TerrainModelBuilder.decode(TerrainModelBuilder.encode(h)), 0.051)
        }
    }

    /** Renders a real 3DEP block to PNGs for eyeballing; skipped unless FINDIT_DEM_TIF is set. */
    @Test
    fun renderRealBlockForInspection() {
        val path = System.getenv("FINDIT_DEM_TIF") ?: return
        val outDir = File(System.getenv("FINDIT_RELIEF_OUT") ?: return).apply { mkdirs() }
        val client = ThreeDepElevationClient(File(outDir, "cache"))
        val values = client.readFloatTiff(File(path)) ?: error("could not read $path")
        val cell = System.getenv("FINDIT_DEM_CELL")?.toDouble() ?: 0.9
        for (mode in ReliefMode.entries) {
            val t0 = System.nanoTime()
            val px = ReliefRenderer.render(values, cell, ReliefSettings(mode = mode))
            val ms = (System.nanoTime() - t0) / 1_000_000
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            img.setRGB(0, 0, s, s, px, 0, s)
            ImageIO.write(img, "png", File(outDir, "${mode.name.lowercase()}.png"))
            println("$mode rendered in $ms ms")
        }
    }
}
