package com.example.data.relief

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarTerrainServerTest {
    private val png = byteArrayOf(1, 2, 3, 4, 5)
    private val server = LidarTerrainServer({ z, x, y ->
        when {
            z == 1 -> LidarTerrainTiles.Result.NoData
            z == 2 -> LidarTerrainTiles.Result.Failed
            z == 3 -> error("boom")
            else -> LidarTerrainTiles.Result.Png(png + byteArrayOf(z.toByte(), x.toByte(), y.toByte()))
        }
    })

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String, method: String = "GET"): Pair<Int, HttpURLConnection> {
        val connection = URL("http://127.0.0.1:${server.port}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return connection.responseCode to connection
    }

    @Test
    fun servesTilesAtTheAdvertisedUrl() {
        val url = server.tileUrl.replace("{z}", "14").replace("{x}", "7").replace("{y}", "9")
        assertTrue(url.startsWith("http://127.0.0.1:${server.port}/"))
        val (code, connection) = get(url.substringAfter(server.port.toString()) + "?sku=ignored")
        assertEquals(200, code)
        assertEquals("image/png", connection.contentType)
        assertTrue((png + byteArrayOf(14, 7, 9)).contentEquals(connection.inputStream.readBytes()))
        assertEquals(1, server.stats.value.served)
        assertEquals(0, server.stats.value.inFlight)
    }

    @Test
    fun headReturnsHeadersOnly() {
        val (code, connection) = get("/lidar-terrain/14/7/9.png", "HEAD")
        assertEquals(200, code)
        assertEquals(8, connection.contentLength)
    }

    @Test
    fun mapsResultsToStatusCodes() {
        assertEquals(404, get("/lidar-terrain/1/0/0.png").first)
        assertEquals(503, get("/lidar-terrain/2/0/0.png").first)
        assertEquals(503, get("/lidar-terrain/3/0/0.png").first)
        assertEquals(404, get("/elsewhere/1/2/3.png").first)
        assertEquals(404, get("/lidar-terrain/1/2.png").first)
        val stats = server.stats.value
        assertEquals(3, stats.requested)
        assertEquals(1, stats.noData)
        assertEquals(2, stats.failed)
    }

    @Test
    fun answersManyRequestsAtOnce() {
        val pool = Executors.newFixedThreadPool(12)
        val done = CountDownLatch(40)
        val codes = IntArray(40)
        repeat(40) { i ->
            pool.execute {
                codes[i] = runCatching { get("/lidar-terrain/16/$i/$i.png").first }.getOrDefault(-1)
                done.countDown()
            }
        }
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(codes.all { it == 200 })
        assertEquals(40, server.stats.value.served)
    }

    /**
     * Builds one real tile from the live USGS service and checks it against Mapbox's own DEM
     * tile for the same place. Skipped unless FINDIT_LIVE_3DEP points at a scratch directory
     * (and FINDIT_MAPBOX_DEM_PNG at a Mapbox terrain-dem-v1 tile 14/3037/6157, 514 px).
     */
    @Test
    fun liveTileMatchesMapboxElevation() {
        val scratch = File(System.getenv("FINDIT_LIVE_3DEP") ?: return).apply { mkdirs() }
        val client = ThreeDepElevationClient(File(scratch, "3dep"))
        val tiles = LidarTerrainTiles(File(scratch, "tiles")) { key -> client.fetchTile(key, LidarTerrainTiles.TILE) }
        val live = LidarTerrainServer(tiles::tile)
        try {
            val connection = URL("http://127.0.0.1:${live.port}/lidar-terrain/14/3037/6157.png").openConnection() as HttpURLConnection
            connection.readTimeout = 120_000
            assertEquals(200, connection.responseCode)
            val ours = javax.imageio.ImageIO.read(connection.inputStream)
            assertEquals(512, ours.width)
            val mapboxPath = System.getenv("FINDIT_MAPBOX_DEM_PNG") ?: return
            val mapbox = javax.imageio.ImageIO.read(File(mapboxPath))
            // Mapbox's 514 px tile has a 1 px border; inner pixel i is ours at i.
            var sum = 0.0
            var worst = 0.0
            var n = 0
            for (y in 0 until 512 step 4) for (x in 0 until 512 step 4) {
                val a = TerrainRgb.decode(ours.getRGB(x, y) and 0xFFFFFF)
                val b = TerrainRgb.decode(mapbox.getRGB(x + 1, y + 1) and 0xFFFFFF)
                sum += a - b
                worst = maxOf(worst, abs(a - b))
                n++
            }
            println("live tile vs Mapbox: mean diff %.2f m, worst %.2f m".format(sum / n, worst))
            assertTrue("mean offset ${sum / n}", abs(sum / n) < 3.0)
            assertTrue("worst $worst", worst < 15.0)
        } finally {
            live.close()
        }
    }
}
