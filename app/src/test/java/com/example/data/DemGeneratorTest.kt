package com.example.data

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemGeneratorTest {
    @Test
    fun customMatrixRejectsRaggedAndInvalidRows() {
        assertNull(DemGenerator.parseCustomGrid("1 2 3\n4 5"))
        assertNull(DemGenerator.parseCustomGrid("1 2\n3 nope"))
    }

    @Test
    fun ascNoDataIsFilledFromTerrainInsteadOfSeaLevel() {
        val asc = """
            ncols 3
            nrows 3
            xllcorner 0
            yllcorner 0
            cellsize 2
            NODATA_value -9999
            10 10 10
            10 -9999 10
            10 10 10
        """.trimIndent()

        val grid = DemGenerator.parseAscDem(BufferedReader(StringReader(asc)))

        assertNotNull(grid)
        assertTrue(grid!!.bareEarth.all { it == 10f })
        assertEquals(2f, grid.cellSizeMeters)
    }

    @Test
    fun lasCanBeParsedAsAStream() {
        val bytes = minimalLasPoint()

        val result = DemGenerator.parseLasStreamDetailed(ByteArrayInputStream(bytes))

        assertNotNull(result)
        assertEquals(1, result!!.totalPointsRead)
        assertEquals(0, result.pointFormat)
        assertFalse(result.grid.bareEarth.any { it.isNaN() })
    }

    private fun minimalLasPoint(): ByteArray {
        val bytes = ByteArray(247)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "LASF".toByteArray().copyInto(bytes)
        bytes[24] = 1
        bytes[25] = 2
        buffer.putInt(96, 227)
        bytes[104] = 0
        buffer.putShort(105, 20)
        buffer.putInt(107, 1)
        buffer.putDouble(131, 0.01)
        buffer.putDouble(139, 0.01)
        buffer.putDouble(147, 0.01)
        buffer.putDouble(155, 0.0)
        buffer.putDouble(163, 0.0)
        buffer.putDouble(171, 0.0)
        buffer.putDouble(179, 1.0)
        buffer.putDouble(187, 0.0)
        buffer.putDouble(195, 1.0)
        buffer.putDouble(203, 0.0)
        buffer.putInt(227, 50)
        buffer.putInt(231, 50)
        buffer.putInt(235, 1_000)
        bytes[242] = 2
        return bytes
    }
}
