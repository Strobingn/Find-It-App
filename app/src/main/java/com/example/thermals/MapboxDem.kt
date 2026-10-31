package com.example.thermals

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.BuildConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Elevation from Mapbox terrain-RGB tiles (USGS 3DEP-based in the US, ~10 m; z14 tiles are ~9 m a pixel),
 * decoded on the phone. A 750-yard stand search needs about four tiles instead of one request per cell.
 * Each tile is fetched once, one after another (a parallel burst is what draws HTTP 429), cached on disk,
 * and a 429 is retried with Retry-After or exponential backoff before the search gives up on Mapbox.
 */
object MapboxDem {
    const val ZOOM = 14
    const val TILE_PX = 512
    private const val CACHE_DIR = "mapbox-dem-v2"
    const val SOURCE_LABEL = "Mapbox terrain (USGS ~10 m)"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Decoded tiles kept in memory (each 512×512 ints ≈ 1 MB). */
    private val memory = object : LinkedHashMap<Long, IntArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, IntArray>?) = size > 12
    }

    /** Why the last [elevations] call returned null (shown when the fallback fails too). */
    @Volatile var lastError: String? = null
        internal set

    val isAvailable: Boolean get() = BuildConfig.MAPBOX_ACCESS_TOKEN.trim().startsWith("pk.")

    /** Terrain-RGB void (0,0,0). A blend with this pixel is not a real elevation. */
    const val VOID_METERS = -9_999.0

    internal fun clearMemoryForTests() {
        synchronized(memory) { memory.clear() }
        lastError = null
    }

    /** Elevations in meters for [points] (lat, lon), or null without a Mapbox token / when a tile can't be had. */
    suspend fun elevations(
        points: List<Pair<Double, Double>>,
        cacheDir: File? = null,
        http: OkHttpClient = this.http,
        sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        random: () -> Double = { Math.random() },
        /** Unit tests have no pk. token. Production leaves this false. */
        tokenOptional: Boolean = false,
    ): List<Double>? {
        val samples = elevationSamples(points, cacheDir, http, sleeper, random, tokenOptional, rejectVoid = false) ?: return null
        if (samples.any { it == null }) return null
        return samples.map { it!! }
    }

    /**
     * One sample per point. Null entries are void pixels (not 0 m). The whole call is null only when
     * a tile still can't be downloaded after retries — callers must not invent elevations for that.
     */
    internal suspend fun elevationSamples(
        points: List<Pair<Double, Double>>,
        cacheDir: File? = null,
        http: OkHttpClient = this.http,
        sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        random: () -> Double = { Math.random() },
        tokenOptional: Boolean = false,
        rejectVoid: Boolean = true,
    ): List<Double?>? {
        if (points.isEmpty()) return null
        if (!tokenOptional && !isAvailable) {
            lastError = "no Mapbox token in this build"
            return null
        }
        lastError = null
        return runCatching {
            val pixels = points.map { (lat, lon) -> globalPixel(lat, lon) }
            val tiles = pixels.flatMap { (x, y) -> neighbourTiles(x, y) }.toSet()
            val loaded = loadTiles(tiles, cacheDir, http, sleeper, random)
            pixels.map { (x, y) ->
                sample(x, y, rejectVoid) { tx, ty -> loaded[key(tx, ty)] }
            }
        }.onFailure { lastError = it.message ?: it.javaClass.simpleName }.getOrNull()
    }

    /** Tiles a stand search needs, in a stable order, fetched one at a time. */
    internal fun tilesFor(points: List<Pair<Double, Double>>): Set<Pair<Int, Int>> =
        points.flatMap { (lat, lon) ->
            val (x, y) = globalPixel(lat, lon)
            neighbourTiles(x, y)
        }.toSet()

    private suspend fun loadTiles(
        tiles: Set<Pair<Int, Int>>,
        cacheDir: File?,
        http: OkHttpClient,
        sleeper: suspend (Long) -> Unit,
        random: () -> Double,
    ): Map<Long, IntArray> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<Long, IntArray>(tiles.size)
        for ((tx, ty) in tiles) {
            out[key(tx, ty)] = loadTile(tx, ty, cacheDir, http, sleeper, random)
        }
        out
    }

    private suspend fun loadTile(
        tx: Int,
        ty: Int,
        cacheDir: File?,
        http: OkHttpClient,
        sleeper: suspend (Long) -> Unit,
        random: () -> Double,
    ): IntArray {
        val k = key(tx, ty)
        synchronized(memory) { memory[k] }?.let { return it }
        val file = cacheDir?.let { File(File(it, CACHE_DIR), "${ZOOM}_${tx}_$ty.png") }
        // A cached file that no longer decodes (an older build saved a different tile format) is dropped and re-fetched.
        file?.takeIf { it.isFile && it.length() > 0 }?.let { f ->
            runCatching { decode(f.readBytes()) }.onSuccess { decoded ->
                synchronized(memory) { memory[k] = decoded }
                return decoded
            }.onFailure { f.delete() }
        }
        val decoded = RateLimitBackoff.withRetries(sleeper, random) {
            val bytes = download(tx, ty, http)
            decode(bytes).also {
                file?.let { f -> runCatching { f.parentFile?.mkdirs(); f.writeBytes(bytes) } }
            }
        }
        synchronized(memory) { memory[k] = decoded }
        return decoded
    }

    private fun download(tx: Int, ty: Int, http: OkHttpClient): ByteArray {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN.trim()
        val url = String.format(
            Locale.US,
            "https://api.mapbox.com/v4/mapbox.mapbox-terrain-dem-v1/%d/%d/%d.pngraw?access_token=%s",
            ZOOM, tx, ty, token,
        )
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (r.code == 429 || r.code == 503) throw RateLimitedException("Mapbox terrain", r.code, r.header("Retry-After"))
            if (!r.isSuccessful) throw IOException("Mapbox terrain HTTP ${r.code}")
            return r.body?.bytes() ?: throw IOException("Mapbox terrain returned nothing")
        }
    }

    /** PNG → RGB ints, untouched by premultiplication or color management (the RGB *is* the elevation). */
    private fun decode(bytes: ByteArray): IntArray {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw IOException("bad terrain tile")
        try {
            // Mapbox terrain-dem-v1 tiles are 514 px: the 512 px tile plus a 1 px border from its neighbours.
            val border = when {
                bmp.width == TILE_PX + 2 && bmp.height == TILE_PX + 2 -> 1
                bmp.width == TILE_PX && bmp.height == TILE_PX -> 0
                else -> throw IOException("unexpected tile size ${bmp.width}×${bmp.height}")
            }
            val px = IntArray(TILE_PX * TILE_PX)
            bmp.getPixels(px, 0, TILE_PX, border, border, TILE_PX, TILE_PX)
            return px
        } finally {
            bmp.recycle()
        }
    }

    // ───────────── pure math (unit tested) ─────────────

    /** Mapbox terrain-RGB: height = -10000 + (R·65536 + G·256 + B) × 0.1 m. */
    fun heightFromRgb(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return -10_000.0 + (r * 65_536 + g * 256 + b) * 0.1
    }

    /** Web-Mercator pixel (in the whole z[ZOOM] @2x world) of a point. */
    fun globalPixel(lat: Double, lon: Double): Pair<Double, Double> {
        val world = TILE_PX.toDouble() * (1 shl ZOOM)
        val x = (lon + 180.0) / 360.0 * world
        val phi = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        val y = (1.0 - ln(tan(phi) + 1.0 / kotlin.math.cos(phi)) / PI) / 2.0 * world
        return x to y
    }

    /** Tiles holding the four pixels a bilinear sample at (x, y) reads. */
    fun neighbourTiles(x: Double, y: Double): List<Pair<Int, Int>> {
        val px = floor(x - 0.5)
        val py = floor(y - 0.5)
        return listOf(px to py, px + 1 to py, px to py + 1, px + 1 to py + 1)
            .map { (a, b) -> floor(a / TILE_PX).toInt() to floor(b / TILE_PX).toInt() }
            .distinct()
    }

    /** Bilinear elevation between pixel centers; [tile] gives a tile's pixels (or null when missing). */
    fun sample(x: Double, y: Double, tile: (Int, Int) -> IntArray?): Double? = sample(x, y, rejectVoid = false, tile)

    /**
     * Same bilinear sample. When [rejectVoid] is set, a terrain-RGB void pixel (or a blend with one)
     * is missing data, not an elevation of -10 km and not 0 m.
     */
    fun sample(x: Double, y: Double, rejectVoid: Boolean, tile: (Int, Int) -> IntArray?): Double? {
        val fx = x - 0.5
        val fy = y - 0.5
        val x0 = floor(fx).toLong()
        val y0 = floor(fy).toLong()
        val ax = fx - x0
        val ay = fy - y0
        fun h(px: Long, py: Long): Double? {
            val tx = Math.floorDiv(px, TILE_PX.toLong()).toInt()
            val ty = Math.floorDiv(py, TILE_PX.toLong()).toInt()
            val t = tile(tx, ty) ?: return null
            val ix = Math.floorMod(px, TILE_PX.toLong()).toInt()
            val iy = Math.floorMod(py, TILE_PX.toLong()).toInt()
            return heightFromRgb(t[iy * TILE_PX + ix])
        }
        val h00 = h(x0, y0) ?: return null
        val h10 = h(x0 + 1, y0) ?: return null
        val h01 = h(x0, y0 + 1) ?: return null
        val h11 = h(x0 + 1, y0 + 1) ?: return null
        if (rejectVoid && (h00 <= VOID_METERS || h10 <= VOID_METERS || h01 <= VOID_METERS || h11 <= VOID_METERS)) {
            return null
        }
        val top = h00 + (h10 - h00) * ax
        val bottom = h01 + (h11 - h01) * ax
        return top + (bottom - top) * ay
    }

    private fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)
}
