package com.example.data.export

import com.example.data.ElevationGrid
import java.io.ByteArrayOutputStream

/**
 * Minimal single-band Float32 GeoTIFF writer (little-endian, uncompressed, WGS-84 geographic).
 * Writes the exact tags QGIS and other GIS tools need: dimensions, sample format, model pixel
 * scale, one tiepoint at the north-west corner, and a GeoKey directory marking the raster as
 * EPSG:4326 pixel-is-area geographic data.
 */
object GeoTiffWriter {
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_SAMPLE_FORMAT = 339
    private const val TAG_MODEL_PIXEL_SCALE = 33550
    private const val TAG_MODEL_TIEPOINT = 33922
    private const val TAG_GEO_KEY_DIRECTORY = 34735

    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_DOUBLE = 12

    fun writeElevation(
        grid: ElevationGrid,
        westLongitude: Double,
        northLatitude: Double,
        cellWidthDegrees: Double,
        cellHeightDegrees: Double,
    ): ByteArray = writeElevation(
        elevations = grid.bareEarth,
        width = grid.width,
        height = grid.height,
        westLongitude = westLongitude,
        northLatitude = northLatitude,
        cellWidthDegrees = cellWidthDegrees,
        cellHeightDegrees = cellHeightDegrees,
    )

    fun writeElevation(
        elevations: FloatArray,
        width: Int,
        height: Int,
        westLongitude: Double,
        northLatitude: Double,
        cellWidthDegrees: Double,
        cellHeightDegrees: Double,
    ): ByteArray {
        require(elevations.size == width * height) { "elevations must be width * height" }
        require(width > 0 && height > 0) { "dimensions must be positive" }

        val geoKeys = shortArrayOf(
            1, 1, 0, 3, // header: version 1, revision 1.0, 3 keys
            1024, 0, 1, 2, // GTModelTypeGeoKey = ModelTypeGeographic
            1025, 0, 1, 1, // GTRasterTypeGeoKey = RasterPixelIsArea
            2048, 0, 1, 4326, // GeographicTypeGeoKey = EPSG:4326 (WGS-84)
        )
        val pixelScale = doubleArrayOf(cellWidthDegrees, cellHeightDegrees, 0.0)
        val tiepoint = doubleArrayOf(0.0, 0.0, 0.0, westLongitude, northLatitude, 0.0)

        val entryCount = 13
        val ifdOffset = 8
        val extrasOffset = ifdOffset + 2 + entryCount * 12 + 4
        val pixelScaleOffset = extrasOffset
        val tiepointOffset = pixelScaleOffset + pixelScale.size * 8
        val geoKeysOffset = tiepointOffset + tiepoint.size * 8
        val pixelDataOffset = geoKeysOffset + geoKeys.size * 2

        val output = ByteArrayOutputStream(pixelDataOffset + elevations.size * 4)
        // Header: little-endian, magic 42, first IFD at offset 8.
        output.write('I'.code)
        output.write('I'.code)
        output.writeShortLe(output, 42)
        output.writeIntLe(output, ifdOffset)

        output.writeShortLe(output, entryCount)
        writeIfdEntry(output, TAG_IMAGE_WIDTH, TYPE_LONG, 1, width)
        writeIfdEntry(output, TAG_IMAGE_LENGTH, TYPE_LONG, 1, height)
        writeIfdEntry(output, TAG_BITS_PER_SAMPLE, TYPE_SHORT, 1, 32)
        writeIfdEntry(output, TAG_COMPRESSION, TYPE_SHORT, 1, 1)
        writeIfdEntry(output, TAG_PHOTOMETRIC, TYPE_SHORT, 1, 1)
        writeIfdEntry(output, TAG_STRIP_OFFSETS, TYPE_LONG, 1, pixelDataOffset)
        writeIfdEntry(output, TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, 1)
        writeIfdEntry(output, TAG_ROWS_PER_STRIP, TYPE_LONG, 1, height)
        writeIfdEntry(output, TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, elevations.size * 4)
        writeIfdEntry(output, TAG_SAMPLE_FORMAT, TYPE_SHORT, 1, 3)
        writeIfdEntry(output, TAG_MODEL_PIXEL_SCALE, TYPE_DOUBLE, 3, pixelScaleOffset)
        writeIfdEntry(output, TAG_MODEL_TIEPOINT, TYPE_DOUBLE, 6, tiepointOffset)
        writeIfdEntry(output, TAG_GEO_KEY_DIRECTORY, TYPE_SHORT, geoKeys.size, geoKeysOffset)
        output.writeIntLe(output, 0) // no further IFD

        for (value in pixelScale) output.writeDoubleLe(output, value)
        for (value in tiepoint) output.writeDoubleLe(output, value)
        for (value in geoKeys) output.writeShortLe(output, value.toInt())
        // Row 0 is the northern edge: the grid's row order already matches TIFF's top-down layout.
        for (value in elevations) output.writeFloatLe(output, value)

        return output.toByteArray()
    }

    private fun writeIfdEntry(
        output: ByteArrayOutputStream,
        tag: Int,
        type: Int,
        count: Int,
        valueOrOffset: Int,
    ) {
        output.writeShortLe(output, tag)
        output.writeShortLe(output, type)
        output.writeIntLe(output, count)
        if (type == TYPE_SHORT && count == 1) {
            output.writeShortLe(output, valueOrOffset)
            output.writeShortLe(output, 0)
        } else {
            output.writeIntLe(output, valueOrOffset)
        }
    }

    private fun ByteArrayOutputStream.writeShortLe(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(stream: ByteArrayOutputStream, value: Int) {
        stream.write(value and 0xFF)
        stream.write((value ushr 8) and 0xFF)
        stream.write((value ushr 16) and 0xFF)
        stream.write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeDoubleLe(stream: ByteArrayOutputStream, value: Double) {
        val bits = value.toBits()
        for (shift in 0 until 64 step 8) stream.write(((bits ushr shift) and 0xFF).toInt())
    }

    private fun ByteArrayOutputStream.writeFloatLe(stream: ByteArrayOutputStream, value: Float) {
        writeIntLe(stream, value.toBits())
    }
}
