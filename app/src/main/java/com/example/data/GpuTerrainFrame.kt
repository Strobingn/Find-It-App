package com.example.data

import android.graphics.Bitmap
import kotlin.math.*

/** Raw 24-bit, tile-relative display encoding. The authoritative DEM remains float32. */
internal object TerrainHeightEncoding {
    const val MAX = 16_777_215
    fun encode(z: Float, base: Float, range: Float): Int =
        (((z.toDouble() - base) / range * MAX).roundToLong().coerceIn(0, MAX.toLong())).toInt()
    fun decode(q: Int, base: Float, range: Float): Float = (base.toDouble() + q.toDouble() / MAX * range).toFloat()
}
class GpuHeightData(val bitmap: Bitmap, val base: Float, val range: Float,
                    val cellX: Float, val cellY: Float, val gridIdentity: ElevationGrid, val vegetation: Float) {
    companion object {
        fun build(grid: ElevationGrid, vegetation: Float, shouldContinue: () -> Boolean): GpuHeightData {
            val values = FloatArray(grid.bareEarth.size)
            var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
            for (y in 0 until grid.height) {
                if (!shouldContinue()) throw java.util.concurrent.CancellationException("Height preparation superseded")
                for (x in 0 until grid.width) {
                    val i = y * grid.width + x
                    val z = grid.bareEarth[i] + grid.canopySpikes[i] * (1f - vegetation.coerceIn(0f, 1f))
                    values[i] = z
                    if (grid.validData[i] && z.isFinite()) { lo = min(lo, z); hi = max(hi, z) }
                }
            }
            if (!lo.isFinite()) { lo = 0f; hi = 1f }
            val range = (hi - lo).takeIf { it > 0 && it.isFinite() } ?: 1f
            val pixels = IntArray(values.size) { i ->
                if (grid.validData[i] && values[i].isFinite()) 0xff000000.toInt() or TerrainHeightEncoding.encode(values[i], lo, range) else 0
            }
            val bitmap = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
            bitmap.setPremultiplied(false)
            bitmap.setPixels(pixels, 0, grid.width, 0, 0, grid.width, grid.height)
            return GpuHeightData(bitmap, lo, range, grid.cellSizeXMeters, grid.cellSizeYMeters, grid, vegetation)
        }
    }
}
data class GpuTerrainFrame(val data: GpuHeightData, val azimuth: Float, val altitude: Float,
                           val contrast: Float, val zScale: Float, val mode: Int, val palette: Int) {
    companion object {
        fun supported(mode: Int, overlay: Int, contour: Float) = mode in setOf(0,1,2,6,7) && overlay == 0 && contour < .05f
    }
}
