package com.example.data.relief

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mapbox terrain-RGB tiles (512 px, zoom 0–[MAX_ZOOM]) built from USGS 3DEP bare-earth elevation,
 * about 1 m per pixel at the top zoom where lidar has been flown. Finished tiles are kept on disk.
 */
class LidarTerrainTiles(
    private val cacheDir: File,
    private val maxCacheBytes: Long = 300L * 1024 * 1024,
    /** Elevation for one tile at [TILE]×[TILE], NaN where there is none; null on network failure. */
    private val fetch: (TileKey) -> FloatArray?,
) {
    sealed interface Result {
        class Png(val bytes: ByteArray) : Result
        /** Outside the USGS coverage, or not a valid tile address. */
        data object NoData : Result
        /** Network failure; worth asking again later. */
        data object Failed : Result
    }

    /** Striped so two requests for one tile never download it twice. */
    private val locks = Array(LOCK_STRIPES) { Any() }
    private val writesSinceTrim = AtomicInteger()

    init {
        cacheDir.mkdirs()
        cacheDir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    /** Blocking; call off the main thread. */
    fun tile(z: Int, x: Int, y: Int): Result {
        if (z !in 0..MAX_ZOOM) return Result.NoData
        val n = 1 shl z
        if (x !in 0 until n || y !in 0 until n) return Result.NoData
        val name = "${z}_${x}_$y"
        synchronized(locks[Math.floorMod(name.hashCode(), LOCK_STRIPES)]) {
            val png = File(cacheDir, "$name.png")
            val none = File(cacheDir, "$name.none")
            if (png.isFile && png.length() > 0) {
                runCatching {
                    val bytes = png.readBytes()
                    png.setLastModified(System.currentTimeMillis())
                    return Result.Png(bytes)
                }
            }
            if (none.isFile) return Result.NoData

            val heights = fetch(TileKey(z, x, y)) ?: return Result.Failed
            if (heights.size != TILE * TILE) return Result.Failed
            if (!TerrainRgb.fillGaps(heights, TILE)) {
                runCatching { none.createNewFile() }
                return Result.NoData
            }
            val bytes = TerrainRgb.encodePng(heights, TILE)
            runCatching {
                val tmp = File.createTempFile(name, ".tmp", cacheDir)
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(png)) tmp.delete()
            }
            if (writesSinceTrim.incrementAndGet() >= TRIM_EVERY) {
                writesSinceTrim.set(0)
                trimCache()
            }
            return Result.Png(bytes)
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxCacheBytes) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        const val TILE = 512

        /** 512 px at zoom 16 is about 0.9 m per pixel at mid-US latitudes, matching 1 m lidar. */
        const val MAX_ZOOM = 16
        private const val TRIM_EVERY = 40
        private const val LOCK_STRIPES = 32
    }
}
