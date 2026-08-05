package com.example.data.export

import java.io.ByteArrayOutputStream

enum class ShapefileFieldType(val dbfCode: Char) {
    CHARACTER('C'),
    NUMBER('N'),
}

data class ShapefileField(
    val name: String,
    val type: ShapefileFieldType,
    val length: Int,
    val decimals: Int = 0,
)

data class ShapefilePoint(
    val longitude: Double,
    val latitude: Double,
    /** Attribute values in the same order as the field list passed to the writer. */
    val attributes: List<String>,
)

data class ShapefileBundle(
    val shp: ByteArray,
    val shx: ByteArray,
    val dbf: ByteArray,
)

/**
 * Minimal ESRI shapefile writer for point layers: the .shp geometry, .shx index, and dBASE III
 * .dbf attributes, with WGS-84 lon/lat coordinates. Field names are truncated to the dBASE
 * 10-byte limit; character values are truncated to their field length rather than failing.
 */
object ShapefileWriter {
    private const val FILE_CODE = 9994
    private const val VERSION = 1000
    private const val SHAPE_TYPE_POINT = 1
    private const val HEADER_BYTES = 100
    private const val POINT_CONTENT_BYTES = 20

    fun writePoints(fields: List<ShapefileField>, points: List<ShapefilePoint>): ShapefileBundle {
        require(fields.isNotEmpty()) { "at least one attribute field is required" }
        val shp = ByteArrayOutputStream()
        val shx = ByteArrayOutputStream()

        var minX = Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (point in points) {
            minX = minOf(minX, point.longitude)
            maxX = maxOf(maxX, point.longitude)
            minY = minOf(minY, point.latitude)
            maxY = maxOf(maxY, point.latitude)
        }
        if (points.isEmpty()) {
            minX = 0.0; minY = 0.0; maxX = 0.0; maxY = 0.0
        }

        val shpLengthWords = (HEADER_BYTES + points.size * (8 + POINT_CONTENT_BYTES)) / 2
        val shxLengthWords = (HEADER_BYTES + points.size * 8) / 2
        writeMainHeader(shp, shpLengthWords, minX, minY, maxX, maxY)
        writeMainHeader(shx, shxLengthWords, minX, minY, maxX, maxY)

        var recordOffsetWords = HEADER_BYTES / 2
        points.forEachIndexed { index, point ->
            shp.writeIntBe(index + 1)
            shp.writeIntBe(POINT_CONTENT_BYTES / 2)
            shp.writeIntLe(SHAPE_TYPE_POINT)
            shp.writeDoubleLe(point.longitude)
            shp.writeDoubleLe(point.latitude)

            shx.writeIntBe(recordOffsetWords)
            shx.writeIntBe(POINT_CONTENT_BYTES / 2)
            recordOffsetWords += (8 + POINT_CONTENT_BYTES) / 2
        }

        return ShapefileBundle(
            shp = shp.toByteArray(),
            shx = shx.toByteArray(),
            dbf = writeDbf(fields, points),
        )
    }

    private fun writeMainHeader(
        output: ByteArrayOutputStream,
        fileLengthWords: Int,
        minX: Double,
        minY: Double,
        maxX: Double,
        maxY: Double,
    ) {
        output.writeIntBe(FILE_CODE)
        repeat(5) { output.writeIntBe(0) }
        output.writeIntBe(fileLengthWords)
        output.writeIntLe(VERSION)
        output.writeIntLe(SHAPE_TYPE_POINT)
        output.writeDoubleLe(minX)
        output.writeDoubleLe(minY)
        output.writeDoubleLe(maxX)
        output.writeDoubleLe(maxY)
        repeat(4) { output.writeDoubleLe(0.0) } // Z and M ranges unused
    }

    private fun writeDbf(fields: List<ShapefileField>, points: List<ShapefilePoint>): ByteArray {
        val recordLength = 1 + fields.sumOf { it.length }
        val headerLength = 32 + fields.size * 32 + 1
        val output = ByteArrayOutputStream(headerLength + points.size * recordLength + 1)

        output.write(0x03) // dBASE III without memo
        output.write(126) // year 2026 (year - 1900)
        output.write(1)
        output.write(1)
        output.writeIntLe(points.size)
        output.writeShortLe(headerLength)
        output.writeShortLe(recordLength)
        repeat(20) { output.write(0) }

        for (field in fields) {
            val nameBytes = field.name.take(10).toByteArray(Charsets.US_ASCII)
            output.write(nameBytes)
            repeat(11 - nameBytes.size) { output.write(0) }
            output.write(field.type.dbfCode.code)
            repeat(4) { output.write(0) }
            output.write(field.length.coerceIn(1, 254))
            output.write(field.decimals.coerceIn(0, 15))
            repeat(14) { output.write(0) }
        }
        output.write(0x0D)

        for (point in points) {
            output.write(0x20) // not deleted
            fields.forEachIndexed { index, field ->
                val raw = point.attributes.getOrElse(index) { "" }
                val text = when (field.type) {
                    ShapefileFieldType.CHARACTER -> raw.take(field.length).padEnd(field.length, ' ')
                    ShapefileFieldType.NUMBER -> raw.take(field.length).padStart(field.length, ' ')
                }
                output.write(text.toByteArray(Charsets.US_ASCII))
            }
        }
        output.write(0x1A)
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntBe(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeDoubleLe(value: Double) {
        val bits = value.toBits()
        for (shift in 0 until 64 step 8) write(((bits ushr shift) and 0xFF).toInt())
    }
}
