package com.example.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GeoTiffWriterTest {
    private fun readShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readDoubleLe(bytes: ByteArray, offset: Int): Double =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double

    private fun readFloatLe(bytes: ByteArray, offset: Int): Float =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float

    private fun ifdEntries(bytes: ByteArray): Map<Int, Triple<Int, Int, Int>> {
        val ifdOffset = readIntLe(bytes, 4)
        val count = readShortLe(bytes, ifdOffset)
        val entries = HashMap<Int, Triple<Int, Int, Int>>()
        for (index in 0 until count) {
            val entryOffset = ifdOffset + 2 + index * 12
            val tag = readShortLe(bytes, entryOffset)
            val type = readShortLe(bytes, entryOffset + 2)
            val valueCount = readIntLe(bytes, entryOffset + 4)
            val value = if (type == 3 && valueCount == 1) {
                readShortLe(bytes, entryOffset + 8)
            } else {
                readIntLe(bytes, entryOffset + 8)
            }
            entries[tag] = Triple(type, valueCount, value)
        }
        return entries
    }

    @Test
    fun writesValidFloat32GeotiffWithGeoKeys() {
        val width = 4
        val height = 3
        val elevations = FloatArray(width * height) { index -> 100f + index }

        val bytes = GeoTiffWriter.writeElevation(
            elevations = elevations,
            width = width,
            height = height,
            westLongitude = -74.0,
            northLatitude = 41.5,
            cellWidthDegrees = 0.001,
            cellHeightDegrees = 0.0009,
        )

        assertEquals('I'.code, bytes[0].toInt() and 0xFF)
        assertEquals('I'.code, bytes[1].toInt() and 0xFF)
        assertEquals(42, readShortLe(bytes, 2))

        val entries = ifdEntries(bytes)
        assertEquals(width, entries[256]?.third)
        assertEquals(height, entries[257]?.third)
        assertEquals(32, entries[258]?.third) // 32-bit samples
        assertEquals(1, entries[259]?.third) // uncompressed
        assertEquals(3, entries[339]?.third) // IEEE float
        assertEquals(height, entries[278]?.third)
        assertEquals(width * height * 4, entries[279]?.third)

        val scaleOffset = entries[33550]!!.third
        assertEquals(0.001, readDoubleLe(bytes, scaleOffset), 1e-12)
        assertEquals(0.0009, readDoubleLe(bytes, scaleOffset + 8), 1e-12)

        val tiepointOffset = entries[33922]!!.third
        assertEquals(-74.0, readDoubleLe(bytes, tiepointOffset + 24), 1e-12)
        assertEquals(41.5, readDoubleLe(bytes, tiepointOffset + 32), 1e-12)

        val geoKeyOffset = entries[34735]!!.third
        assertEquals(16, entries[34735]!!.second)
        // Last key: GeographicTypeGeoKey 2048 -> 4326.
        assertEquals(2048, readShortLe(bytes, geoKeyOffset + 24))
        assertEquals(4326, readShortLe(bytes, geoKeyOffset + 30))

        val pixelOffset = entries[273]!!.third
        assertEquals(100f, readFloatLe(bytes, pixelOffset), 0.0001f)
        assertEquals(111f, readFloatLe(bytes, pixelOffset + (width * height - 1) * 4), 0.0001f)
        assertEquals(pixelOffset + width * height * 4, bytes.size)
    }
}
