package com.example.data.historicmap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray

/** A historic sheet cut to its neatline and squared up, with its WGS84 corners. */
data class ClippedTopoSheet(
    val recordId: String,
    val image: File,
    /** `[lon, lat]` top-left, top-right, bottom-right, bottom-left. */
    val corners: List<List<Double>>,
)

/**
 * Turns a downloaded USGS historical topo GeoTIFF into an overlay that lines up on its own:
 * reads the GeoTIFF's projection, finds the quadrangle's neatline inside the white collar,
 * and warps just the map body to a rectangle whose corners are the neatline's WGS84 positions.
 * Results are cached, so each sheet is processed once.
 */
class HistoricTopoRectifier(context: Context) {
    private val outDir = File(context.applicationContext.filesDir, "historic-topo-clipped").apply { mkdirs() }

    fun cached(recordId: String): ClippedTopoSheet? {
        val image = imageFile(recordId)
        val meta = metaFile(recordId)
        if (!image.isFile || !meta.isFile) return null
        val corners = runCatching {
            val array = JSONArray(meta.readText())
            List(array.length()) { i ->
                val pair = array.getJSONArray(i)
                listOf(pair.getDouble(0), pair.getDouble(1))
            }
        }.getOrNull() ?: return null
        if (corners.size != 4) return null
        return ClippedTopoSheet(recordId, image, corners)
    }

    /** Null when the file isn't a georeferenced JPEG GeoTIFF this code can read. */
    fun rectify(record: HistoricMapRecord, source: File, maxDimension: Int = 2560): ClippedTopoSheet? {
        cached(record.id)?.let { return it }
        val directories = TiffDirectoryReader.read(source)
        val main = directories.firstOrNull { it.subfileType and 1 == 0 } ?: directories.firstOrNull() ?: return null
        val geo = HistoricTopoGeoreference.from(main) ?: return null
        val neat = geo.neatlinePixels(record.minLatitude, record.maxLatitude, record.minLongitude, record.maxLongitude)
        if (neat.any { (x, y) -> !x.isFinite() || !y.isFinite() }) return null
        val neatWidth = max(dist(neat[0], neat[1]), dist(neat[3], neat[2]))
        val neatHeight = max(dist(neat[0], neat[3]), dist(neat[1], neat[2]))
        if (neatWidth < 16 || neatHeight < 16) return null
        val outScale = min(1.0, maxDimension / max(neatWidth, neatHeight))
        val outWidth = (neatWidth * outScale).roundToInt().coerceAtLeast(1)
        val outHeight = (neatHeight * outScale).roundToInt().coerceAtLeast(1)

        // Decode at the coarsest resolution that still gives ~1 source pixel per output pixel:
        // pick a built-in overview, then subsample inside the JPEG decoder for the rest.
        var factor = 1
        while (factor * 2 <= (1.0 / outScale) * 1.25) factor *= 2
        val candidates = directories.filter { it.isJpeg && it.width > 0 && it.offsets.isNotEmpty() }
        val level = candidates
            .map { it to (main.width.toDouble() / it.width).roundToInt().coerceAtLeast(1) }
            .filter { (_, f) -> f <= factor }
            .maxByOrNull { (_, f) -> f }
            ?: return null
        val (dir, levelFactor) = level
        val sample = (factor / levelFactor).coerceAtLeast(1)

        // Only decode the blocks that cover the neatline.
        val minX = floor(neat.minOf { it.first } / levelFactor).toInt().coerceIn(0, dir.width - 1)
        val minY = floor(neat.minOf { it.second } / levelFactor).toInt().coerceIn(0, dir.height - 1)
        val maxX = ceil(neat.maxOf { it.first } / levelFactor).toInt().coerceIn(minX + 1, dir.width)
        val maxY = ceil(neat.maxOf { it.second } / levelFactor).toInt().coerceIn(minY + 1, dir.height)
        val workWidth = ((maxX - minX) + sample - 1) / sample
        val workHeight = ((maxY - minY) + sample - 1) / sample
        val work = Bitmap.createBitmap(workWidth, workHeight, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(work)
            canvas.drawColor(android.graphics.Color.WHITE)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            RandomAccessFile(source, "r").use { raf ->
                val bw = dir.blockWidth
                val bh = dir.blockHeight
                for (index in dir.offsets.indices) {
                    val bx = (index % dir.blocksAcross) * bw
                    val by = (index / dir.blocksAcross) * bh
                    if (bx >= maxX || by >= maxY || bx + bw <= minX || by + bh <= minY) continue
                    val length = dir.byteCounts.getOrNull(index)?.toInt() ?: continue
                    if (length <= 0) continue
                    val data = ByteArray(length)
                    raf.seek(dir.offsets[index])
                    raf.readFully(data)
                    val jpeg = mergeJpegTables(dir.jpegTables, data)
                    val tile = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, decodeOptions) ?: continue
                    val left = (bx - minX) / sample.toFloat()
                    val top = (by - minY) / sample.toFloat()
                    val dst = Rect(
                        left.roundToInt(),
                        top.roundToInt(),
                        (left + bw / sample.toFloat()).roundToInt(),
                        (top + bh / sample.toFloat()).roundToInt(),
                    )
                    canvas.drawBitmap(tile, null, dst, paint)
                    tile.recycle()
                }
            }

            val src = FloatArray(8)
            neat.forEachIndexed { i, (x, y) ->
                src[i * 2] = ((x / levelFactor - minX) / sample).toFloat()
                src[i * 2 + 1] = ((y / levelFactor - minY) / sample).toFloat()
            }
            val dst = floatArrayOf(
                0f, 0f,
                outWidth.toFloat(), 0f,
                outWidth.toFloat(), outHeight.toFloat(),
                0f, outHeight.toFloat(),
            )
            val matrix = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
            val out = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565)
            try {
                Canvas(out).drawBitmap(work, matrix, paint)
                imageFile(record.id).outputStream().use { stream ->
                    out.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                }
            } finally {
                out.recycle()
            }
        } finally {
            work.recycle()
        }
        val corners = geo.neatlineWgs84(record.minLatitude, record.maxLatitude, record.minLongitude, record.maxLongitude)
        metaFile(record.id).writeText(JSONArray(corners.map { JSONArray(it) }).toString())
        return ClippedTopoSheet(record.id, imageFile(record.id), corners)
    }

    private fun imageFile(id: String) = File(outDir, safe(id) + ".jpg")
    private fun metaFile(id: String) = File(outDir, safe(id) + ".json")
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)

    companion object {
        /**
         * JPEG-in-TIFF blocks share Huffman/quantisation tables stored once in the JPEGTables tag.
         * Splice them in: tables without their EOI, then the block without its SOI.
         */
        internal fun mergeJpegTables(tables: ByteArray?, block: ByteArray): ByteArray {
            if (tables == null || tables.size < 4) return block
            val tablesEnd = if (tables[tables.size - 2] == 0xFF.toByte() && tables[tables.size - 1] == 0xD9.toByte()) {
                tables.size - 2
            } else {
                tables.size
            }
            val blockStart = if (block.size >= 2 && block[0] == 0xFF.toByte() && block[1] == 0xD8.toByte()) 2 else 0
            val merged = ByteArray(tablesEnd + block.size - blockStart)
            System.arraycopy(tables, 0, merged, 0, tablesEnd)
            System.arraycopy(block, blockStart, merged, tablesEnd, block.size - blockStart)
            return merged
        }
    }
}
