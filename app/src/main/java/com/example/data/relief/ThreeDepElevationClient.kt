package com.example.data.relief

import com.example.BuildConfig
import com.example.data.historicmap.TiffDirectoryReader
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import okhttp3.OkHttpClient
import okhttp3.Request

/** Web Mercator tile address. */
data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * A square block of bare-earth elevations covering one Web Mercator tile at zoom [key.z], sampled at
 * [SIZE]×[SIZE] plus a [PAD]-pixel margin on every side so neighbourhood filters see across edges.
 * NaN where USGS has no data.
 */
class ElevationChunk(
    val key: TileKey,
    val values: FloatArray,
    /** Ground metres per pixel at the chunk's centre latitude. */
    val cellMeters: Double,
) {
    val stride: Int get() = FULL
    val hasData: Boolean = values.any { !it.isNaN() }

    companion object {
        const val SIZE = 1024
        const val PAD = 32
        const val FULL = SIZE + 2 * PAD
    }
}

/**
 * Fetches USGS 3DEP bare-earth elevation (1 m where lidar exists, coarser elsewhere) from the
 * public 3DEPElevation ImageServer, one block per call, and keeps blocks on disk.
 */
class ThreeDepElevationClient(
    private val cacheDir: File,
    private val maxCacheBytes: Long = 400L * 1024 * 1024,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    init {
        cacheDir.mkdirs()
        // Downloads cut short by the app being killed leave temp files behind.
        val stale = System.currentTimeMillis() - STALE_TEMP_MS
        cacheDir.listFiles { f ->
            (f.name.endsWith(".part") || f.name.startsWith("tile_")) && f.lastModified() < stale
        }?.forEach { it.delete() }
    }

    /** Blocking; call off the main thread. Returns null on network failure. */
    fun fetch(key: TileKey): ElevationChunk? {
        val file = File(cacheDir, "${key.z}_${key.x}_${key.y}.tif")
        if (!file.isFile || file.length() == 0L) {
            val (minX, minY, maxX, maxY) = mercatorBounds(key)
            val pad = ElevationChunk.PAD * (maxX - minX) / ElevationChunk.SIZE
            // Unique temp name: the relief overlay and the terrain tiles may fetch the same block at once.
            val tmp = File.createTempFile(file.name, ".part", cacheDir)
            if (!download(exportUrl(minX - pad, minY - pad, maxX + pad, maxY + pad, ElevationChunk.FULL), tmp) ||
                !tmp.renameTo(file)
            ) {
                tmp.delete()
                return null
            }
            trimCache()
        } else {
            file.setLastModified(System.currentTimeMillis())
        }
        val values = runCatching { readFloatTiff(file) }.getOrNull()
        if (values == null || values.size != ElevationChunk.FULL * ElevationChunk.FULL) {
            file.delete()
            return null
        }
        return ElevationChunk(key, values, groundMetersPerPixel(key))
    }

    /**
     * Blocking; call off the main thread. Elevation covering exactly one Web Mercator tile at
     * [size]×[size], pixel centres at the tile's (i + 0.5) / size like a Mapbox DEM tile.
     * NaN where USGS has none; null on network failure.
     */
    fun fetchTile(key: TileKey, size: Int): FloatArray? {
        val (minX, minY, maxX, maxY) = mercatorBounds(key)
        val tmp = File.createTempFile("tile_${key.z}_${key.x}_${key.y}_", ".tif", cacheDir)
        try {
            if (!download(exportUrl(minX, minY, maxX, maxY, size), tmp)) return null
            return runCatching { readFloatTiff(tmp) }.getOrNull()?.takeIf { it.size == size * size }
        } finally {
            tmp.delete()
        }
    }

    private fun download(url: String, dest: File): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FindIt-LidarSurveyApp/${BuildConfig.VERSION_NAME} (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: return@use false
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful || type.contains("json") || type.contains("html")) return@use false
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
            true
        }
    }.getOrDefault(false)

    private fun exportUrl(minX: Double, minY: Double, maxX: Double, maxY: Double, size: Int): String {
        val bbox = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f", minX, minY, maxX, maxY)
        return "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/exportImage" +
            "?bbox=$bbox&bboxSR=3857&imageSR=3857&size=$size,$size&format=tiff&pixelType=F32" +
            "&noData=-9999&noDataInterpretation=esriNoDataMatchAny&interpolation=RSP_BilinearInterpolation" +
            "&renderingRule=%7B%22rasterFunction%22%3A%22None%22%7D&f=image"
    }

    /** Reads the uncompressed float32 GeoTIFF the ImageServer returns (tiled or stripped). */
    internal fun readFloatTiff(file: File): FloatArray? {
        val dir = TiffDirectoryReader.read(file).firstOrNull() ?: return null
        if (dir.compression != 1) return null
        val width = dir.width
        val height = dir.height
        val out = FloatArray(width * height) { Float.NaN }
        val order = RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(2).also { raf.readFully(it) }
            if (b[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        }
        RandomAccessFile(file, "r").use { raf ->
            val bw = dir.blockWidth
            val bh = dir.blockHeight
            for (index in dir.offsets.indices) {
                val bx = (index % dir.blocksAcross) * bw
                val by = (index / dir.blocksAcross) * bh
                val length = dir.byteCounts.getOrNull(index)?.toInt() ?: continue
                val bytes = ByteArray(length)
                raf.seek(dir.offsets[index])
                raf.readFully(bytes)
                val floats = ByteBuffer.wrap(bytes).order(order).asFloatBuffer()
                val rows = minOf(bh, length / 4 / bw)
                for (r in 0 until rows) {
                    val y = by + r
                    if (y >= height) break
                    for (c in 0 until bw) {
                        val x = bx + c
                        if (x >= width) break
                        val v = floats.get(r * bw + c)
                        out[y * width + x] = if (v.isNaN() || v < MIN_PLAUSIBLE || v > MAX_PLAUSIBLE) Float.NaN else v
                    }
                }
            }
        }
        return out
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".tif") && !f.name.startsWith("tile_") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxCacheBytes) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        /**
         * Heights outside this range are NoData or resampling garbage: next to NoData the server
         * returns values such as -9999 and 5e36. Death Valley (-86 m) and Denali (6190 m) fit easily.
         */
        private const val MIN_PLAUSIBLE = -500f
        private const val MAX_PLAUSIBLE = 9000f
        private const val STALE_TEMP_MS = 60 * 60 * 1000L
        private const val EARTH_RADIUS = 6_378_137.0
        private const val ORIGIN = PI * EARTH_RADIUS

        data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double)

        fun mercatorBounds(key: TileKey): Bounds {
            val size = 2 * ORIGIN / (1 shl key.z)
            val minX = -ORIGIN + key.x * size
            val maxY = ORIGIN - key.y * size
            return Bounds(minX, maxY - size, minX + size, maxY)
        }

        fun tileCenterLatitude(key: TileKey): Double {
            val b = mercatorBounds(key)
            val y = (b.minY + b.maxY) / 2
            return Math.toDegrees(2 * atan(exp(y / EARTH_RADIUS)) - PI / 2)
        }

        fun groundMetersPerPixel(key: TileKey): Double {
            val b = mercatorBounds(key)
            return (b.maxX - b.minX) / ElevationChunk.SIZE * cos(Math.toRadians(tileCenterLatitude(key)))
        }

        fun tileFor(lat: Double, lon: Double, z: Int): TileKey {
            val n = 1 shl z
            val x = ((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
            val latRad = Math.toRadians(lat)
            val y = ((1.0 - kotlin.math.asinh(kotlin.math.tan(latRad)) / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
            return TileKey(z, x, y)
        }

        fun tileLonLat(z: Int, x: Double, y: Double): Pair<Double, Double> {
            val n = (1 shl z).toDouble()
            val lon = x / n * 360.0 - 180.0
            val lat = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * y / n))))
            return lon to lat
        }
    }
}
