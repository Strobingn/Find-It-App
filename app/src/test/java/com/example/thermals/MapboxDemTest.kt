package com.example.thermals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapboxDemTest {

    private fun rgbFor(meters: Double): Int {
        val v = Math.round((meters + 10_000.0) * 10.0).toInt()
        return (0xFF shl 24) or (((v shr 16) and 0xFF) shl 16) or (((v shr 8) and 0xFF) shl 8) or (v and 0xFF)
    }

    @Test
    fun terrainRgbDecodesToMeters() {
        assertEquals(0.0, MapboxDem.heightFromRgb(rgbFor(0.0)), 1e-6)
        assertEquals(312.4, MapboxDem.heightFromRgb(rgbFor(312.4)), 1e-6)
        assertEquals(-10_000.0, MapboxDem.heightFromRgb(0xFF000000.toInt()), 1e-6)
    }

    @Test
    fun pixelMathMatchesWebMercator() {
        val (x0, y0) = MapboxDem.globalPixel(0.0, 0.0)
        val world = MapboxDem.TILE_PX.toDouble() * (1 shl MapboxDem.ZOOM)
        assertEquals(world / 2, x0, 1e-6)
        assertEquals(world / 2, y0, 1e-6)
        val (_, yNorth) = MapboxDem.globalPixel(41.4, -74.1)
        assertTrue("north is up (smaller y)", yNorth < y0)
        // ~3–4 m a pixel at 41°N: 750 yards is a few hundred pixels.
        val (xa, _) = MapboxDem.globalPixel(41.4, -74.1)
        val (xb, _) = MapboxDem.globalPixel(41.4, -74.1 + 0.01) // ~835 m east
        assertTrue((xb - xa) in 200.0..260.0)
    }

    @Test
    fun bilinearSampleBlendsAcrossTileEdges() {
        // Two tiles side by side: the west one 100 m everywhere, the east one 200 m.
        val west = IntArray(MapboxDem.TILE_PX * MapboxDem.TILE_PX) { rgbFor(100.0) }
        val east = IntArray(MapboxDem.TILE_PX * MapboxDem.TILE_PX) { rgbFor(200.0) }
        val tiles = mapOf((10 to 5) to west, (11 to 5) to east)
        val edgeX = 11.0 * MapboxDem.TILE_PX // the boundary between the last west pixel and the first east one
        val y = 5.0 * MapboxDem.TILE_PX + 100.5
        assertEquals(100.0, MapboxDem.sample(edgeX - 1.0, y) { tx, ty -> tiles[tx to ty] }!!, 1e-6) // inside west
        assertEquals(150.0, MapboxDem.sample(edgeX, y) { tx, ty -> tiles[tx to ty] }!!, 1e-6) // halfway between centers
        assertEquals(200.0, MapboxDem.sample(edgeX + 1.0, y) { tx, ty -> tiles[tx to ty] }!!, 1e-6)
        assertTrue(MapboxDem.neighbourTiles(edgeX, y).containsAll(listOf(10 to 5, 11 to 5)))
        assertNull(MapboxDem.sample(edgeX, y) { tx, ty -> if (tx == 11) null else tiles[tx to ty] })
    }

    @Test
    fun rateLimitGetsAPlainExplanation() {
        val msg = StandFinderService.explainNetworkFailure("Elevation service HTTP 429")
        assertTrue(msg.contains("rate-limiting"))
    }
}
