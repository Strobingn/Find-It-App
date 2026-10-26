package com.example.data.relief

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarTerrainTilesTest {
    private val dir: File = Files.createTempDirectory("lidar-terrain-test").toFile()
    private val size = LidarTerrainTiles.TILE

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun buildsTileOnceThenServesItFromDisk() {
        var calls = 0
        val tiles = LidarTerrainTiles(dir, fetch = { key ->
            calls++
            FloatArray(size * size) { 250f + key.z }
        })
        val first = tiles.tile(14, 3037, 6157) as LidarTerrainTiles.Result.Png
        val second = tiles.tile(14, 3037, 6157) as LidarTerrainTiles.Result.Png
        assertEquals(1, calls)
        assertTrue(first.bytes.contentEquals(second.bytes))

        val image = ImageIO.read(ByteArrayInputStream(first.bytes))
        assertEquals(size, image.width)
        assertEquals(264.0, TerrainRgb.decode(image.getRGB(10, 10) and 0xFFFFFF), 0.051)
    }

    @Test
    fun networkFailureIsRetriedNextTime() {
        var calls = 0
        val tiles = LidarTerrainTiles(dir, fetch = {
            calls++
            if (calls == 1) null else FloatArray(size * size) { 10f }
        })
        assertSame(LidarTerrainTiles.Result.Failed, tiles.tile(12, 1, 1))
        assertTrue(tiles.tile(12, 1, 1) is LidarTerrainTiles.Result.Png)
        assertEquals(2, calls)
    }

    @Test
    fun noDataIsRememberedSoTheServerIsNotAskedAgain() {
        var calls = 0
        val tiles = LidarTerrainTiles(dir, fetch = {
            calls++
            FloatArray(size * size) { Float.NaN }
        })
        assertSame(LidarTerrainTiles.Result.NoData, tiles.tile(10, 5, 5))
        assertSame(LidarTerrainTiles.Result.NoData, tiles.tile(10, 5, 5))
        assertEquals(1, calls)
    }

    @Test
    fun invalidAddressesNeverReachTheNetwork() {
        val tiles = LidarTerrainTiles(dir, fetch = { error("should not fetch") })
        assertSame(LidarTerrainTiles.Result.NoData, tiles.tile(LidarTerrainTiles.MAX_ZOOM + 1, 0, 0))
        assertSame(LidarTerrainTiles.Result.NoData, tiles.tile(3, 8, 0))
        assertSame(LidarTerrainTiles.Result.NoData, tiles.tile(3, 0, -1))
    }

    @Test
    fun wrongSizedElevationCountsAsFailure() {
        val tiles = LidarTerrainTiles(dir, fetch = { FloatArray(16) })
        assertSame(LidarTerrainTiles.Result.Failed, tiles.tile(5, 1, 1))
    }
}
