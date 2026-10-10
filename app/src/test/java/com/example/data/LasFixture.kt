package com.example.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.random.Random

/**
 * Writes a minimal, valid, uncompressed LAS 1.2 (point format 0) fixture for tests that need a
 * real point cloud file rather than the in-memory streams [LidarRasterizer] otherwise consumes.
 *
 * Points are laid out as a spatially coherent sweep - as an airborne survey's flight lines
 * produce on disk - so a spatial index built over the file has real ground-area chunks to skip,
 * rather than every point sharing one interval.
 */
internal object LasFixture {
    private const val SCALE = 0.01
    private const val HEADER_SIZE = 227
    private const val RECORD_LENGTH = 20

    fun write(file: File, pointCount: Int, extentMeters: Double = 2000.0, seed: Int = 7): File {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("LASF".toByteArray(Charsets.US_ASCII))
        header.position(24); header.put(1); header.put(2) // version 1.2
        header.position(94); header.putShort(HEADER_SIZE.toShort())
        header.position(96); header.putInt(HEADER_SIZE)
        header.position(100); header.putInt(0) // no VLRs
        header.position(104); header.put(0) // point data format 0
        header.position(105); header.putShort(RECORD_LENGTH.toShort())
        header.position(107); header.putInt(pointCount)
        header.position(131)
        header.putDouble(SCALE); header.putDouble(SCALE); header.putDouble(SCALE)
        header.putDouble(0.0); header.putDouble(0.0); header.putDouble(0.0)
        header.position(179)
        header.putDouble(extentMeters); header.putDouble(0.0)
        header.putDouble(extentMeters); header.putDouble(0.0)
        header.putDouble(120.0); header.putDouble(0.0)

        val random = Random(seed)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            val record = ByteBuffer.allocate(RECORD_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until pointCount) {
                val y = i.toDouble() / pointCount * extentMeters
                val x = ((i.toLong() * 7919L) % (extentMeters.toLong() * 10L)) / 10.0
                val z = 40.0 + sin(x * 0.01) * 8 + sin(y * 0.008) * 6 + random.nextDouble() * 0.5
                record.clear()
                record.putInt((x / SCALE).toInt())
                record.putInt((y / SCALE).toInt())
                record.putInt((z / SCALE).toInt())
                record.putShort(1000) // intensity
                record.put(0b00001001.toByte()) // return 1 of 1
                record.put(if (random.nextFloat() < 0.55f) 2 else 5) // classification
                record.put(0) // scan angle
                record.put(0) // user data
                record.putShort(0) // point source ID
                raf.write(record.array())
            }
        }
        return file
    }

    /**
     * Regular ground-classified lattice used to prove DEM coverage: cell size may be finer than
     * spacing, but TIN + bounded fill must still produce a continuous hull.
     */
    fun writeLattice(
        file: File,
        columns: Int,
        rows: Int,
        spacingMeters: Double = 2.0,
        classification: Int = 2,
        originX: Double = 0.0,
        originY: Double = 0.0,
        baseZ: Double = 40.0,
    ): File {
        val pointCount = columns * rows
        val maxX = originX + (columns - 1) * spacingMeters
        val maxY = originY + (rows - 1) * spacingMeters
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put("LASF".toByteArray(Charsets.US_ASCII))
        header.position(24); header.put(1); header.put(2)
        header.position(94); header.putShort(HEADER_SIZE.toShort())
        header.position(96); header.putInt(HEADER_SIZE)
        header.position(100); header.putInt(0)
        header.position(104); header.put(0)
        header.position(105); header.putShort(RECORD_LENGTH.toShort())
        header.position(107); header.putInt(pointCount)
        header.position(131)
        header.putDouble(SCALE); header.putDouble(SCALE); header.putDouble(SCALE)
        header.putDouble(0.0); header.putDouble(0.0); header.putDouble(0.0)
        header.position(179)
        header.putDouble(maxX); header.putDouble(originX)
        header.putDouble(maxY); header.putDouble(originY)
        header.putDouble(baseZ + 20.0); header.putDouble(baseZ - 20.0)

        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            val record = ByteBuffer.allocate(RECORD_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            for (row in 0 until rows) {
                for (col in 0 until columns) {
                    val x = originX + col * spacingMeters
                    val y = originY + row * spacingMeters
                    val z = baseZ + sin(x * 0.05) * 2.0 + sin(y * 0.04) * 1.5
                    record.clear()
                    record.putInt((x / SCALE).toInt())
                    record.putInt((y / SCALE).toInt())
                    record.putInt((z / SCALE).toInt())
                    record.putShort(1000)
                    record.put(0b00001001.toByte())
                    record.put(classification.toByte())
                    record.put(0)
                    record.put(0)
                    record.putShort(0)
                    raf.write(record.array())
                }
            }
        }
        return file
    }
}
