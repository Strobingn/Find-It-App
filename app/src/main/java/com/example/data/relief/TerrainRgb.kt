package com.example.data.relief

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.roundToInt

/**
 * Mapbox terrain-RGB elevation tiles: height = -10000 + (R × 65536 + G × 256 + B) × 0.1 m.
 * Pure Kotlin (no Android Bitmap) so tiles can be built on any thread and checked in JVM tests.
 */
object TerrainRgb {
    fun code(heightMeters: Float): Int =
        ((heightMeters + 10_000.0) * 10.0).roundToInt().coerceIn(0, 0xFFFFFF)

    fun decode(code: Int): Double = -10_000.0 + (code and 0xFFFFFF) * 0.1

    /**
     * Replaces NaN gaps in a [size]×[size] grid with the nearest valid value along rows, then
     * along columns for rows with no data at all. Shorelines and survey edges carry on level
     * instead of dropping to sea level. Returns false when the grid has no valid value.
     */
    fun fillGaps(values: FloatArray, size: Int): Boolean {
        require(values.size == size * size)
        var any = false
        for (y in 0 until size) {
            val row = y * size
            var last = Float.NaN
            for (x in 0 until size) {
                val v = values[row + x]
                if (v.isNaN()) values[row + x] = last else last = v
            }
            last = Float.NaN
            for (x in size - 1 downTo 0) {
                val v = values[row + x]
                if (v.isNaN()) values[row + x] = last else last = v
            }
            if (!last.isNaN()) any = true
        }
        if (!any) return false
        for (x in 0 until size) {
            var last = Float.NaN
            for (y in 0 until size) {
                val v = values[y * size + x]
                if (v.isNaN()) values[y * size + x] = last else last = v
            }
            last = Float.NaN
            for (y in size - 1 downTo 0) {
                val v = values[y * size + x]
                if (v.isNaN()) values[y * size + x] = last else last = v
            }
        }
        return true
    }

    /** Encodes [size]×[size] heights (no NaN) as an 8-bit RGB PNG. */
    fun encodePng(heights: FloatArray, size: Int): ByteArray {
        require(heights.size == size * size)
        val stride = size * 3
        val raw = ByteArray(size * (stride + 1))
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        var o = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                val c = code(heights[y * size + x])
                cur[x * 3] = (c shr 16).toByte()
                cur[x * 3 + 1] = (c shr 8).toByte()
                cur[x * 3 + 2] = c.toByte()
            }
            // "Up" filter: neighbouring rows of smooth ground differ little, so it deflates well.
            if (y == 0) {
                raw[o++] = 0
                cur.copyInto(raw, o)
            } else {
                raw[o++] = 2
                for (i in 0 until stride) raw[o + i] = (cur[i] - prev[i]).toByte()
            }
            o += stride
            cur.copyInto(prev)
        }

        val idat = ByteArrayOutputStream(raw.size / 3)
        val deflater = Deflater(6)
        try {
            DeflaterOutputStream(idat, deflater).use { it.write(raw) }
        } finally {
            deflater.end()
        }

        val png = ByteArrayOutputStream(idat.size() + 64)
        png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteArrayOutputStream(13)
        DataOutputStream(header).apply {
            writeInt(size)
            writeInt(size)
            writeByte(8) // bit depth
            writeByte(2) // colour type: RGB
            writeByte(0) // deflate
            writeByte(0) // adaptive filtering
            writeByte(0) // no interlace
        }
        writeChunk(png, "IHDR", header.toByteArray())
        writeChunk(png, "IDAT", idat.toByteArray())
        writeChunk(png, "IEND", ByteArray(0))
        return png.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        DataOutputStream(out).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.value.toInt())
        }
    }
}
