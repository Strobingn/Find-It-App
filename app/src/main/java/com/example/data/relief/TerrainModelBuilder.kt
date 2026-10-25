package com.example.data.relief

import android.graphics.Bitmap
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt

/** A finished 1 m terrain model: Mapbox terrain-RGB tiles on disk plus where they cover. */
data class TerrainModel(
    val directory: File,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val minZoom: Int,
    val maxZoom: Int,
) {
    val tileUrlTemplate: String get() = "file://${directory.absolutePath}/{z}/{x}/{y}.png"
}

/**
 * Builds a high-detail 3D ground model for an area from USGS 3DEP 1 m elevation: fetches the
 * ~1 m blocks covering it and writes Mapbox terrain-RGB tiles (height = -10000 + code × 0.1 m)
 * that a raster-dem source can read straight from disk.
 */
class TerrainModelBuilder(
    private val client: ThreeDepElevationClient,
    private val outRoot: File,
) {
    /**
     * Blocking; run off the main thread. [onProgress] gets (done, total) block counts.
     * Returns null when USGS has no elevation for the area or the network fails.
     */
    fun build(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): TerrainModel? {
        val nw = ThreeDepElevationClient.tileFor(maxLat, minLon, BLOCK_ZOOM)
        val se = ThreeDepElevationClient.tileFor(minLat, maxLon, BLOCK_ZOOM)
        val keys = (nw.x..se.x).flatMap { x -> (nw.y..se.y).map { y -> TileKey(BLOCK_ZOOM, x, y) } }
        if (keys.isEmpty() || keys.size > MAX_BLOCKS) return null
        val blocks = HashMap<TileKey, ElevationChunk>()
        keys.forEachIndexed { i, key ->
            client.fetch(key)?.let { if (it.hasData) blocks[key] = it }
            onProgress(i + 1, keys.size)
        }
        if (blocks.isEmpty()) return null

        val dir = File(outRoot, "model-${System.currentTimeMillis()}").apply { mkdirs() }
        outRoot.listFiles()?.filter { it.isDirectory && it != dir }?.forEach { it.deleteRecursively() }
        val fallback = blocks.values.flatMap { c -> c.values.filter { !it.isNaN() }.take(2000) }.average().toFloat()
        val pixels = IntArray(TILE * TILE)
        for (z in MIN_ZOOM..MAX_ZOOM) {
            val a = ThreeDepElevationClient.tileFor(maxLat, minLon, z)
            val b = ThreeDepElevationClient.tileFor(minLat, maxLon, z)
            for (tx in a.x..b.x) for (ty in a.y..b.y) {
                for (py in 0 until TILE) for (px in 0 until TILE) {
                    // Position in global block-zoom pixel space.
                    val scale = (1 shl BLOCK_ZOOM).toDouble() / (1 shl z) * ElevationChunk.SIZE
                    val gx = (tx + (px + 0.5) / TILE) * scale
                    val gy = (ty + (py + 0.5) / TILE) * scale
                    val h = sample(blocks, gx, gy) ?: fallback
                    pixels[py * TILE + px] = encode(h)
                }
                val file = File(dir, "$z/$tx/$ty.png")
                file.parentFile?.mkdirs()
                val bitmap = Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        return TerrainModel(dir, minLon, minLat, maxLon, maxLat, MIN_ZOOM, MAX_ZOOM)
    }

    /** Bilinear elevation at a global pixel position of the block grid, or null outside the data. */
    private fun sample(blocks: Map<TileKey, ElevationChunk>, gx: Double, gy: Double): Float? {
        val bx = floor(gx / ElevationChunk.SIZE).toInt()
        val by = floor(gy / ElevationChunk.SIZE).toInt()
        val chunk = blocks[TileKey(BLOCK_ZOOM, bx, by)] ?: return null
        val lx = gx - bx * ElevationChunk.SIZE + ElevationChunk.PAD - 0.5
        val ly = gy - by * ElevationChunk.SIZE + ElevationChunk.PAD - 0.5
        val x0 = floor(lx).toInt().coerceIn(0, ElevationChunk.FULL - 2)
        val y0 = floor(ly).toInt().coerceIn(0, ElevationChunk.FULL - 2)
        val fx = (lx - x0).toFloat().coerceIn(0f, 1f)
        val fy = (ly - y0).toFloat().coerceIn(0f, 1f)
        val f = ElevationChunk.FULL
        val v00 = chunk.values[y0 * f + x0]
        val v10 = chunk.values[y0 * f + x0 + 1]
        val v01 = chunk.values[(y0 + 1) * f + x0]
        val v11 = chunk.values[(y0 + 1) * f + x0 + 1]
        val vs = floatArrayOf(v00, v10, v01, v11)
        if (vs.any { it.isNaN() }) return vs.firstOrNull { !it.isNaN() }
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy
    }

    companion object {
        const val BLOCK_ZOOM = 15
        const val MIN_ZOOM = 12
        const val MAX_ZOOM = 17
        private const val TILE = 256
        /** About 4 km on a side at mid-US latitudes. */
        private const val MAX_BLOCKS = 25

        /** Mapbox terrain-RGB encoding, 0.1 m steps. */
        fun encode(heightMeters: Float): Int {
            val code = ((heightMeters + 10_000.0) * 10.0).roundToInt().coerceIn(0, 0xFFFFFF)
            return (0xFF shl 24) or code
        }

        fun decode(argb: Int): Double = -10_000.0 + (argb and 0xFFFFFF) * 0.1
    }
}
