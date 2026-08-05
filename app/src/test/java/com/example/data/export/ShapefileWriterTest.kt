package com.example.data.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShapefileWriterTest {
    private fun intBe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun doubleLe(bytes: ByteArray, offset: Int): Double =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).double

    private val fields = listOf(
        ShapefileField("NAME", ShapefileFieldType.CHARACTER, length = 12),
        ShapefileField("SCORE", ShapefileFieldType.NUMBER, length = 10, decimals = 2),
    )
    private val points = listOf(
        ShapefilePoint(-74.01, 41.44, listOf("Cellar A", "0.85")),
        ShapefilePoint(-73.99, 41.42, listOf("Wall B", "0.62")),
    )

    @Test
    fun shpHeaderAndRecordsRoundTrip() {
        val bundle = ShapefileWriter.writePoints(fields, points)
        val shp = bundle.shp

        assertEquals(9994, intBe(shp, 0))
        assertEquals(shp.size / 2, intBe(shp, 24))
        assertEquals(1000, intLe(shp, 28))
        assertEquals(1, intLe(shp, 32))
        assertEquals(-74.01, doubleLe(shp, 36), 1e-12)
        assertEquals(41.42, doubleLe(shp, 44), 1e-12)
        assertEquals(-73.99, doubleLe(shp, 52), 1e-12)
        assertEquals(41.44, doubleLe(shp, 60), 1e-12)

        // First record starts after the 100-byte header.
        assertEquals(1, intBe(shp, 100))
        assertEquals(10, intBe(shp, 104))
        assertEquals(1, intLe(shp, 108))
        assertEquals(-74.01, doubleLe(shp, 112), 1e-12)
        assertEquals(41.44, doubleLe(shp, 120), 1e-12)

        // Second record immediately follows.
        assertEquals(2, intBe(shp, 128))
        assertEquals(-73.99, doubleLe(shp, 140), 1e-12)
        assertEquals(41.42, doubleLe(shp, 148), 1e-12)
    }

    @Test
    fun shxIndexPointsAtEveryRecord() {
        val bundle = ShapefileWriter.writePoints(fields, points)
        val shx = bundle.shx

        assertEquals(9994, intBe(shx, 0))
        assertEquals(shx.size / 2, intBe(shx, 24))
        assertEquals(100 + 2 * 8, shx.size)
        assertEquals(50, intBe(shx, 100)) // first record offset in 16-bit words
        assertEquals(10, intBe(shx, 104))
        assertEquals(50 + 14, intBe(shx, 108)) // second record offset
    }

    @Test
    fun dbfCarriesAttributesWithCorrectTypes() {
        val bundle = ShapefileWriter.writePoints(fields, points)
        val dbf = bundle.dbf

        assertEquals(0x03, dbf[0].toInt() and 0xFF)
        assertEquals(2, intLe(dbf, 4))
        val headerLength = shortLe(dbf, 8)
        val recordLength = shortLe(dbf, 10)
        assertEquals(32 + 2 * 32 + 1, headerLength)
        assertEquals(1 + 12 + 10, recordLength)
        assertEquals(0x0D, dbf[headerLength - 1].toInt() and 0xFF)

        val first = String(dbf, headerLength, recordLength, Charsets.US_ASCII)
        assertEquals(" Cellar A          0.85", first)
        val second = String(dbf, headerLength + recordLength, recordLength, Charsets.US_ASCII)
        assertEquals(" Wall B            0.62", second)
        assertEquals(0x1A, dbf[dbf.size - 1].toInt() and 0xFF)
    }

    @Test
    fun longNamesAndValuesAreTruncatedNotRejected() {
        val widePoints = listOf(
            ShapefilePoint(-74.0, 41.4, listOf("A very long cellar name", "0.123456789")),
        )

        val bundle = ShapefileWriter.writePoints(fields, widePoints)
        val headerLength = shortLe(bundle.dbf, 8)
        val recordLength = shortLe(bundle.dbf, 10)
        val record = String(bundle.dbf, headerLength, recordLength, Charsets.US_ASCII)

        assertEquals(" A very long 0.12345678", record)
    }
}
