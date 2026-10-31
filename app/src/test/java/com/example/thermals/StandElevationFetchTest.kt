package com.example.thermals

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.net.HttpURLConnection
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stand-search elevation: Open-Meteo batches of 100, one call at a time, cached per spot,
 * and HTTP 429 retried from Retry-After or exponential backoff. A fake HTTP client, no network.
 */
@RunWith(RobolectricTestRunner::class)
class StandElevationFetchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val points = StandFinder.GRID_SIZE * StandFinder.GRID_SIZE

    @Before
    fun clearMapboxMemory() {
        MapboxDem.clearMemoryForTests()
    }

    @After
    fun clearMapboxMemoryAfter() {
        MapboxDem.clearMemoryForTests()
    }

    @Test
    fun openMeteoUsesBatchesOf100OneAtATime() = runBlocking {
        val counts = mutableListOf<Int>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val http = fakeHttp { request ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, now) }
            try {
                val n = coordCount(request, "latitude")
                assertEquals(n, coordCount(request, "longitude"))
                counts += n
                json(request, 200, elevationJson(n))
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val fetched = service(http).fetchGrid(42.75, -75.5)
        assertEquals(points, counts.sum())
        assertEquals(9, counts.size)
        assertEquals(listOf(100, 100, 100, 100, 100, 100, 100, 100, 41), counts)
        assertEquals(1, maxInFlight.get())
        assertEquals("Elevation model (90 m)", fetched.sourceLabel)
        assertEquals(points, fetched.grid.meters.count { it.isFinite() })
    }

    @Test
    fun repeatFindAndAFewMetersOfJitterDoNotCallAgain() = runBlocking {
        var calls = 0
        val http = fakeHttp { request ->
            calls++
            json(request, 200, elevationJson(coordCount(request, "latitude")))
        }
        val dir = tempDir()
        val waits = mutableListOf<Long>()
        val first = service(http, ElevationGridCache(dir, linkedMapOf()), waits)
        val fetched = first.fetchGrid(42.75, -75.5)
        val afterFirst = calls
        assertEquals(9, afterFirst)
        assertTrue(waits.isEmpty())

        val again = first.fetchGrid(42.75, -75.5)
        val jittered = first.fetchGrid(42.75004, -75.5)
        assertEquals(afterFirst, calls)
        assertEquals(fetched.grid.meters[400], again.grid.meters[400], 0.0)
        assertEquals(fetched.grid.meters[400], jittered.grid.meters[400], 0.0)
        assertEquals(ElevationGridCache.key(42.75, -75.5), ElevationGridCache.key(42.75004, -75.5))
        assertTrue(ElevationGridCache.key(42.75, -75.5) != ElevationGridCache.key(42.751, -75.5))

        val fromDisk = service(http, ElevationGridCache(dir, linkedMapOf()), waits).fetchGrid(42.75, -75.5)
        assertEquals(afterFirst, calls)
        assertEquals(fetched.sourceLabel, fromDisk.sourceLabel)
        assertEquals(fetched.grid.meters[400], fromDisk.grid.meters[400], 0.0)
    }

    @Test
    fun http429WaitsForRetryAfterThenSucceeds() = runBlocking {
        val seen = mutableMapOf<String, Int>()
        val waits = mutableListOf<Long>()
        var calls = 0
        val http = fakeHttp { request ->
            calls++
            val hit = seen.merge(request.url.toString(), 1, Int::plus)!!
            val n = coordCount(request, "latitude")
            if (hit == 1) json(request, 429, """{"reason":"limit"}""", retryAfter = "2")
            else json(request, 200, elevationJson(n))
        }
        val fetched = service(http, waits = waits).fetchGrid(42.75, -75.5)
        assertEquals(18, calls)
        assertEquals(List(9) { 2_000L }, waits)
        assertEquals(points, fetched.grid.meters.count { it.isFinite() })
    }

    @Test
    fun http429WithoutRetryAfterUsesExponentialBackoffWithJitter() = runBlocking {
        val seen = mutableMapOf<String, Int>()
        val waits = mutableListOf<Long>()
        val http = fakeHttp { request ->
            val hit = seen.merge(request.url.toString(), 1, Int::plus)!!
            val n = coordCount(request, "latitude")
            if (hit < 3) json(request, 429, "{}") else json(request, 200, elevationJson(n))
        }
        service(http, waits = waits, random = { 0.5 }).fetchGrid(42.75, -75.5)
        assertEquals(List(9) { listOf(500L, 1_000L) }.flatten(), waits)

        assertEquals(500L, RateLimitBackoff.delayMs(1, null, { 0.5 }))
        assertEquals(1_000L, RateLimitBackoff.delayMs(2, null, { 0.5 }))
        assertEquals(2_000L, RateLimitBackoff.delayMs(3, null, { 0.5 }))
        assertEquals(400L, RateLimitBackoff.delayMs(1, null, { 0.0 }))
        assertEquals(RateLimitBackoff.MAX_DELAY_MS, RateLimitBackoff.delayMs(1, "120", { 0.5 }))
        val whenMs = 1_700_000_000_000L
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val header = fmt.format(Date(whenMs + 5_000))
        assertEquals(5_000L, RateLimitBackoff.retryAfterMillis(header, whenMs))
    }

    @Test
    fun http429GivesUpAfterAFewTriesAndShowsTheRateLimitMessage() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val http = fakeHttp { request ->
            calls++
            json(request, 429, "{}", retryAfter = "1")
        }
        val dir = tempDir()
        try {
            service(
                http,
                ElevationGridCache(dir, linkedMapOf()),
                waits,
                mapbox = { _, _ ->
                    MapboxDem.lastError = "Mapbox terrain HTTP 429"
                    null
                },
            ).fetchGrid(42.75, -75.5)
            fail("expected the rate limit to surface")
        } catch (e: java.io.IOException) {
            val shown = "Terrain: " + StandFinderService.explainNetworkFailure(e.message!!)
            assertEquals(
                "Terrain: The free elevation service is rate-limiting your connection (HTTP 429). " +
                    "Wait a minute and tap Find again. (Mapbox terrain failed: Mapbox terrain HTTP 429)",
                shown,
            )
        }
        assertEquals(RateLimitBackoff.MAX_ATTEMPTS, calls)
        assertEquals(List(RateLimitBackoff.MAX_ATTEMPTS - 1) { 1_000L }, waits)
        val before = calls
        try {
            service(http, ElevationGridCache(dir, linkedMapOf()), waits).fetchGrid(42.75, -75.5)
            fail("a failed fetch must not be cached")
        } catch (_: java.io.IOException) {
        }
        assertTrue(calls > before)
    }

    @Test
    fun http500IsNotRetried() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val http = fakeHttp { request ->
            calls++
            json(request, 500, "{}")
        }
        try {
            service(http, waits = waits).fetchGrid(42.75, -75.5)
            fail("expected HTTP 500")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("500"))
        }
        assertEquals(1, calls)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun nullSampleIsUncoveredNanAndIsNotScoredAsSeaLevel() = runBlocking {
        val hole = 5 * StandFinder.GRID_SIZE + 5
        var offset = 0
        val http = fakeHttp { request ->
            val n = coordCount(request, "latitude")
            val body = elevationJson(n) { arr ->
                val local = hole - offset
                if (local in 0 until n) arr.put(local, JSONObject.NULL)
            }
            offset += n
            json(request, 200, body)
        }
        val dir = tempDir()
        val grid = service(http, ElevationGridCache(dir, linkedMapOf())).fetchGrid(42.75, -75.5).grid
        assertFalse(grid.isCovered(5, 5))
        assertTrue(grid.meters[hole].isNaN())
        assertTrue(grid.isCovered(14, 14))
        assertTrue(grid.at(14, 14).isFinite() && grid.at(14, 14) != 0.0)
        val candidates = StandFinder.findCandidates(grid, null, null)
        assertTrue(candidates.none { it.elevationFeet == 0.0 || !it.elevationFeet.isFinite() })
        val (lat, lon) = grid.latLon(5, 5)
        assertTrue(candidates.none { StandFinder.distanceMeters(it.latitude, it.longitude, lat, lon) < 1.0 })

        offset = 0
        var calls = 0
        val http2 = fakeHttp { request ->
            calls++
            json(request, 500, "{}")
        }
        val restored = service(http2, ElevationGridCache(dir, linkedMapOf())).fetchGrid(42.75, -75.5).grid
        assertEquals(0, calls)
        assertTrue(restored.meters[hole].isNaN())
        assertFalse(restored.isCovered(5, 5))
    }

    @Test
    fun aShortBodyIsAnErrorAndRealSeaLevelStaysZero() {
        try {
            StandFinderService.parseElevationBody("""{"elevation":[1.0,2.0]}""", 3)
            fail("short body")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("2 of 3"))
        }
        val parsed = StandFinderService.parseElevationBody("""{"elevation":[100.0, null, 0.0]}""", 3)
        assertEquals(100.0, parsed[0]!!, 0.0)
        assertNull(parsed[1])
        assertEquals(0.0, parsed[2]!!, 0.0)

        val samples = List(points) { index -> if (index == 0) null else 0.0 }
        val grid = StandFinderService.gridFromSamples(42.75, -75.5, samples)
        assertTrue(grid.meters[0].isNaN())
        assertFalse(grid.isCovered(0, 0))
        assertEquals(0.0, grid.at(14, 14), 0.0)
        assertTrue(grid.isCovered(14, 14))
        assertTrue(StandFinder.findCandidates(grid, null, null).none { !it.elevationFeet.isFinite() })
    }

    @Test
    fun missingCenterIsNotCachedAsAZeroGrid() = runBlocking {
        val center = (StandFinder.GRID_SIZE / 2) * StandFinder.GRID_SIZE + StandFinder.GRID_SIZE / 2
        var offset = 0
        var calls = 0
        val http = fakeHttp { request ->
            calls++
            val n = coordCount(request, "latitude")
            val body = elevationJson(n) { arr ->
                val local = center - offset
                if (local in 0 until n) arr.put(local, JSONObject.NULL)
            }
            offset += n
            json(request, 200, body)
        }
        val dir = tempDir()
        try {
            service(http, ElevationGridCache(dir, linkedMapOf())).fetchGrid(42.75, -75.5)
            fail("expected no usable terrain")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("no usable terrain"))
        }
        val before = calls
        offset = 0
        try {
            service(http, ElevationGridCache(dir, linkedMapOf())).fetchGrid(42.75, -75.5)
            fail("failed fetch was cached")
        } catch (_: java.io.IOException) {
        }
        assertTrue(calls > before)
    }

    @Test
    fun usgsGridIsUsedWhenMapboxFailsAndThenCached() = runBlocking {
        var openMeteo = 0
        var usgs = 0
        val waits = mutableListOf<Long>()
        val http = fakeHttp(passUsgs = true) { request ->
            if (request.url.encodedPath.contains("getSamples")) {
                usgs++
                if (usgs == 1) json(request, 429, "{}", retryAfter = "1")
                else json(request, 200, usgsBody(points))
            } else {
                openMeteo++
                json(request, 500, "{}")
            }
        }
        val dir = tempDir()
        val cache = ElevationGridCache(dir, linkedMapOf())
        val fetched = service(http, cache, waits).fetchGrid(42.75, -75.5)
        assertEquals(StandFinderService.USGS_SOURCE, fetched.sourceLabel)
        assertEquals(30.5, fetched.grid.at(14, 14), 0.0)
        assertEquals(0, openMeteo)
        assertEquals(2, usgs)
        assertEquals(listOf(1_000L), waits)
        service(http, cache, waits).fetchGrid(42.75, -75.5)
        assertEquals(2, usgs)
        assertEquals(0, openMeteo)
    }

    @Test
    fun lidarWinsOverMapboxAndOpenMeteoAndOverACachedModel() = runBlocking {
        var httpCalls = 0
        var mapboxCalls = 0
        val http = fakeHttp { request ->
            httpCalls++
            json(request, 200, elevationJson(coordCount(request, "latitude"), z = 40.0))
        }
        val mapbox: suspend (List<Pair<Double, Double>>, File?) -> List<Double?>? = { pts, _ ->
            mapboxCalls++
            List(pts.size) { 11.0 }
        }
        val dir = tempDir()
        val cache = ElevationGridCache(dir, linkedMapOf())
        val fetched = service(http, cache, mapbox = mapbox).fetchGrid(42.75, -75.5)
        assertEquals(MapboxDem.SOURCE_LABEL, fetched.sourceLabel)
        assertEquals(0, httpCalls)
        assertEquals(1, mapboxCalls)
        assertEquals(11.0, fetched.grid.at(14, 14), 0.0)

        service(http, cache, mapbox = mapbox).fetchGrid(42.75, -75.5)
        assertEquals(1, mapboxCalls)
        assertEquals(0, httpCalls)

        val lidar = SiteDem { _, _ -> 250.0 }
        val fromLidar = service(http, cache, mapbox = mapbox).fetchGrid(42.75, -75.5, lidar)
        assertEquals("LiDAR on this phone", fromLidar.sourceLabel)
        assertEquals(250.0, fromLidar.grid.at(14, 14), 0.0)
        assertTrue(fromLidar.grid.meters.none { it == 0.0 })
        assertEquals(1, mapboxCalls)
        assertEquals(0, httpCalls)
    }

    @Test
    fun mapboxVoidPixelIsAHoleNotZeroMeters() = runBlocking {
        val hole = 5 * StandFinder.GRID_SIZE + 5
        val http = fakeHttp { request -> json(request, 500, "{}") }
        val fetched = service(http, mapbox = { pts, _ ->
            List(pts.size) { index -> if (index == hole) -10_000.0 else 90.0 }
        }).fetchGrid(42.75, -75.5)
        assertEquals(MapboxDem.SOURCE_LABEL, fetched.sourceLabel)
        assertTrue(fetched.grid.meters[hole].isNaN())
        assertFalse(fetched.grid.isCovered(5, 5))
        assertEquals(90.0, fetched.grid.at(14, 14), 0.0)
        assertTrue(StandFinder.findCandidates(fetched.grid, null, null).none { it.elevationFeet == 0.0 })
    }

    @Test
    fun mapboxTilesAreOneRequestPerTileNotPerPointAnd429BacksOff() = runBlocking {
        val samplePoints = ElevationGrid.samplePoints(42.75, -75.5, StandFinder.GRID_SIZE, StandFinder.GRID_SPACING_M)
        val tiles = MapboxDem.tilesFor(samplePoints)
        assertTrue(samplePoints.size > 800)
        assertTrue(tiles.size in 1..12)
        assertTrue(tiles.size * 20 < samplePoints.size)
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        var calls = 0
        val waits = mutableListOf<Long>()
        val http = fakeHttp { request ->
            calls++
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { max -> maxOf(max, now) }
            try {
                assertTrue(request.url.encodedPath.contains("mapbox-terrain-dem-v1"))
                json(request, 429, "{}", retryAfter = "1")
            } finally {
                inFlight.decrementAndGet()
            }
        }
        val result = MapboxDem.elevationSamples(
            samplePoints,
            cacheDir = tempDir(),
            http = http,
            sleeper = { waits += it },
            random = { 0.5 },
            tokenOptional = true,
        )
        assertNull(result)
        assertTrue(MapboxDem.lastError!!.contains("429"))
        assertEquals(1, maxInFlight.get())
        // The first tile is retried, then the search stops. It does not fan out to every tile, and
        // it does not make one request per sample.
        assertEquals(RateLimitBackoff.MAX_ATTEMPTS, calls)
        assertTrue(calls < samplePoints.size)
        assertEquals(List(RateLimitBackoff.MAX_ATTEMPTS - 1) { 1_000L }, waits)
    }

    @Test
    fun mapboxTileIsFetchedOnceAndReusedFromMemoryAndDisk() = runBlocking {
        val png = solidTerrainPng(150.0)
        var calls = 0
        val http = fakeHttp { request ->
            calls++
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(HttpURLConnection.HTTP_OK)
                .message("OK")
                .body(png.toResponseBody("image/png".toMediaType()))
                .build()
        }
        val (lat, lon) = latLonOfPixel(100 * 512 + 200.0, 200 * 512 + 200.0)
        val spot = listOf(lat to lon)
        val tiles = MapboxDem.tilesFor(spot)
        assertEquals(1, tiles.size)
        val dir = tempDir()
        val first = MapboxDem.elevationSamples(spot, dir, http, sleeper = {}, random = { 0.5 }, tokenOptional = true)
        assertNotNull(first)
        assertEquals(150.0, first!![0]!!, 0.6)
        assertEquals(1, calls)
        val second = MapboxDem.elevationSamples(spot, dir, http, sleeper = {}, random = { 0.5 }, tokenOptional = true)
        assertEquals(150.0, second!![0]!!, 0.6)
        assertEquals(1, calls)
        MapboxDem.clearMemoryForTests()
        val third = MapboxDem.elevationSamples(spot, dir, http, sleeper = {}, random = { 0.5 }, tokenOptional = true)
        assertEquals(150.0, third!![0]!!, 0.6)
        assertEquals(1, calls)
    }

    @Test
    fun mapboxAuthFailureIsNotRetried() = runBlocking {
        var calls = 0
        val http = fakeHttp { request ->
            calls++
            json(request, 401, "{}")
        }
        val spot = listOf(latLonOfPixel(50 * 512 + 80.0, 60 * 512 + 80.0))
        val tiles = MapboxDem.tilesFor(spot)
        val result = MapboxDem.elevations(spot, tempDir(), http, sleeper = { fail("should not wait") }, tokenOptional = true)
        assertNull(result)
        assertEquals(tiles.size, calls)
        assertTrue(MapboxDem.lastError!!.contains("401"))
    }

    private fun service(
        http: OkHttpClient,
        cache: ElevationGridCache = ElevationGridCache(tempDir(), linkedMapOf()),
        waits: MutableList<Long> = mutableListOf(),
        random: () -> Double = { 0.5 },
        mapbox: (suspend (List<Pair<Double, Double>>, File?) -> List<Double?>?)? = { _, _ -> null },
    ) = StandFinderService(
        context = context,
        httpClient = http,
        elevationCache = cache,
        sleeper = { waits.add(it) },
        randomUnit = random,
        mapboxElevations = mapbox,
    )

    private fun fakeHttp(passUsgs: Boolean = false, handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (!passUsgs && request.url.encodedPath.contains("getSamples")) {
                json(request, 404, """{"error":"skip"}""")
            } else {
                handler(request)
            }
        }).build()

    private fun coordCount(request: Request, name: String): Int =
        request.url.queryParameter(name)!!.split(',').count { it.isNotBlank() }

    private fun usgsBody(n: Int): String {
        val samples = JSONArray()
        repeat(n) { i ->
            samples.put(JSONObject().put("locationId", i).put("value", "30.5"))
        }
        return JSONObject().put("samples", samples).toString()
    }

    private fun elevationJson(n: Int, z: Double = 120.0, edit: (JSONArray) -> Unit = {}): String {
        val arr = JSONArray()
        repeat(n) { arr.put(z + (it % 5)) }
        edit(arr)
        return JSONObject().put("elevation", arr).toString()
    }

    private fun json(request: Request, code: Int, body: String, retryAfter: String? = null): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
        if (retryAfter != null) builder.header("Retry-After", retryAfter)
        return builder.build()
    }

    private fun tempDir(): File = Files.createTempDirectory("stand-dem").toFile()

    /** Inverse of [MapboxDem.globalPixel], so a test point lands in the middle of one tile. */
    private fun latLonOfPixel(x: Double, y: Double): Pair<Double, Double> {
        val world = MapboxDem.TILE_PX.toDouble() * (1 shl MapboxDem.ZOOM)
        val lon = x / world * 360.0 - 180.0
        val merc = Math.PI - 2.0 * Math.PI * y / world
        val lat = Math.toDegrees(Math.atan(Math.sinh(merc)))
        return lat to lon
    }

    /** 512×512 terrain-RGB PNG at a constant height. */
    private fun solidTerrainPng(meters: Double): ByteArray {
        val v = Math.round((meters + 10_000.0) * 10.0).toInt()
        val rgb = ((v shr 16) and 0xFF shl 16) or ((v shr 8) and 0xFF shl 8) or (v and 0xFF)
        val image = java.awt.image.BufferedImage(MapboxDem.TILE_PX, MapboxDem.TILE_PX, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, rgb)
        }
        val out = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
