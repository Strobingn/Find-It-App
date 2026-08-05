package com.github.mreutegg.laszip4j.laslib

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CopcHierarchyReaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mapsIntersectingHierarchyNodesToLasPointIndexes() {
        val file = temporaryFolder.newFile("synthetic.copc.laz")
        writeSyntheticCopc(file.absolutePath)

        val ranges = RandomAccessFile(file, "r").use { source ->
            CopcHierarchyReader(source).selectedPointRanges(
                NormalizedCopcBounds(left = 0.0, top = 0.0, right = 0.49, bottom = 0.49),
            )
        }

        assertEquals(
            listOf(CopcPointRange(firstPoint = 0, pointCount = 10), CopcPointRange(10, 20)),
            ranges,
        )
    }

    private fun writeSyntheticCopc(path: String) {
        RandomAccessFile(path, "rw").use { file ->
            file.setLength(1_300)
            file.seek(375)
            val vlrHeader = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
            vlrHeader.position(2)
            vlrHeader.put("copc".toByteArray(Charsets.US_ASCII))
            vlrHeader.position(18)
            vlrHeader.putShort(1.toShort())
            vlrHeader.putShort(160.toShort())
            file.write(vlrHeader.array())

            val info = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN)
            info.putDouble(0, 0.0)
            info.putDouble(8, 0.0)
            info.putDouble(24, 100.0)
            info.putLong(40, 700L)
            info.putLong(48, 96L)
            file.write(info.array())

            file.seek(700)
            file.write(entry(level = 0, x = 0, y = 0, offset = 1_000, points = 10))
            file.write(entry(level = 1, x = 1, y = 0, offset = 1_200, points = 30))
            file.write(entry(level = 1, x = 0, y = 1, offset = 1_100, points = 20))
        }
    }

    private fun entry(level: Int, x: Int, y: Int, offset: Long, points: Int): ByteArray =
        ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(level)
            putInt(x)
            putInt(y)
            putInt(0)
            putLong(offset)
            putInt(100)
            putInt(points)
        }.array()
}
