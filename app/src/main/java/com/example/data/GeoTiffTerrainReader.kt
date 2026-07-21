package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import com.example.geospatial.GeoSpatialLibrary.GeographicBounds
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import mil.nga.tiff.TiffReader

/** Reads elevation from classic GeoTIFF files while preserving WGS84/UTM positioning. */
object GeoTiffTerrainReader {
    private const val MAX_TIFF_BYTES = 256 * 1024 * 1024

    data class Result(
        val grid: ElevationGrid,
        val metadata: GeoSpatialMetadata,
        val summary: String,
    )

    fun read(fileName: String, input: InputStream, maxDimension: Int): Result {
        val bytes = readCapped(input)
        require(bytes.size >= 8) { "Invalid GeoTIFF file" }
        val geo = GeoTiffMetadata.parse(bytes)
        val directory = TiffReader.readTiff(bytes).fileDirectories.firstOrNull()
            ?: error("GeoTIFF contains no image directory")
        val sourceWidth = directory.imageWidth.toInt()
        val sourceHeight = directory.imageHeight.toInt()
        require(sourceWidth > 0 && sourceHeight > 0) { "GeoTIFF has invalid dimensions" }
        require(directory.samplesPerPixel >= 1) { "GeoTIFF has no elevation band" }

        val limit = maxDimension.coerceIn(64, 1024)
        val scale = max(sourceWidth.toDouble() / limit, sourceHeight.toDouble() / limit).coerceAtLeast(1.0)
        val width = (sourceWidth / scale).toInt().coerceAtLeast(1)
        val height = (sourceHeight / scale).toInt().coerceAtLeast(1)
        val rasters = directory.readRasters()
        val elevations = FloatArray(width * height)
        for (y in 0 until height) {
            val sourceY = ((y + 0.5) * sourceHeight / height).toInt().coerceIn(0, sourceHeight - 1)
            for (x in 0 until width) {
                val sourceX = ((x + 0.5) * sourceWidth / width).toInt().coerceIn(0, sourceWidth - 1)
                val value = rasters.getPixel(sourceX, sourceY)[0].toDouble()
                elevations[y * width + x] = if (
                    value.isFinite() && (geo.noData == null || kotlin.math.abs(value - geo.noData) > 1e-6)
                ) value.toFloat() else Float.NaN
            }
        }
        fillMissing(elevations, width, height)

        val corners = listOf(
            0.0 to 0.0,
            sourceWidth.toDouble() to 0.0,
            0.0 to sourceHeight.toDouble(),
            sourceWidth.toDouble() to sourceHeight.toDouble(),
        ).map { (x, y) ->
            val model = geo.transform.pixelToModel(x, y)
            geo.projection.toWgs84(model.first, model.second)
        }
        val bounds = GeographicBounds(
            minLat = corners.minOf { it.first },
            maxLat = corners.maxOf { it.first },
            minLon = corners.minOf { it.second },
            maxLon = corners.maxOf { it.second },
        )
        require(bounds.minLat >= -90 && bounds.maxLat <= 90 && bounds.minLon >= -180 && bounds.maxLon <= 180) {
            "GeoTIFF contains invalid geographic bounds"
        }
        val midLat = (bounds.minLat + bounds.maxLat) / 2
        val midLon = (bounds.minLon + bounds.maxLon) / 2
        val horizontalMeters = GeoSpatialLibrary.calculateGeodesicDistance(
            midLat, bounds.minLon, midLat, bounds.maxLon,
        )
        val verticalMeters = GeoSpatialLibrary.calculateGeodesicDistance(
            bounds.minLat, midLon, bounds.maxLat, midLon,
        )
        val cellSize = listOf(horizontalMeters / width, verticalMeters / height)
            .filter { it.isFinite() && it > 0.0 }
            .average()
            .takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val metadata = GeoSpatialMetadata(
            siteName = fileName,
            bounds = bounds,
            crs = geo.projection.label,
            datum = "WGS 84",
            resolutionMeters = cellSize,
            columns = width,
            rows = height,
        )
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = elevations,
            canopySpikes = FloatArray(elevations.size),
            cellSizeMeters = cellSize.toFloat(),
        )
        val downsample = if (width != sourceWidth || height != sourceHeight) {
            "; display sampled from ${sourceWidth}×${sourceHeight}"
        } else ""
        return Result(
            grid = grid,
            metadata = metadata,
            summary = "Loaded georeferenced GeoTIFF → ${width}×${height}, ${"%.2f".format(cellSize)} m/cell$downsample",
        )
    }

    private fun readCapped(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_TIFF_BYTES) { "GeoTIFF exceeds the 256 MiB import limit" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun fillMissing(values: FloatArray, width: Int, height: Int) {
        val valid = values.filter { it.isFinite() }
        require(valid.isNotEmpty()) { "GeoTIFF elevation band contains no usable cells" }
        val fallback = valid.average().toFloat()
        repeat(8) {
            var changed = false
            val previous = values.copyOf()
            for (y in 0 until height) for (x in 0 until width) {
                val index = y * width + x
                if (previous[index].isFinite()) continue
                var sum = 0f
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if ((dx != 0 || dy != 0) && nx in 0 until width && ny in 0 until height) {
                        val neighbor = previous[ny * width + nx]
                        if (neighbor.isFinite()) {
                            sum += neighbor
                            count++
                        }
                    }
                }
                if (count > 0) {
                    values[index] = sum / count
                    changed = true
                }
            }
            if (!changed) return@repeat
        }
        for (index in values.indices) if (!values[index].isFinite()) values[index] = fallback
    }
}

private data class GeoTiffMetadata(
    val transform: RasterTransform,
    val projection: GeoProjection,
    val noData: Double?,
) {
    companion object {
        private const val TAG_MODEL_PIXEL_SCALE = 33550
        private const val TAG_MODEL_TIEPOINT = 33922
        private const val TAG_MODEL_TRANSFORMATION = 34264
        private const val TAG_GEO_KEY_DIRECTORY = 34735
        private const val TAG_GDAL_NODATA = 42113
        private const val KEY_GEOGRAPHIC_TYPE = 2048
        private const val KEY_PROJECTED_CS_TYPE = 3072

        fun parse(bytes: ByteArray): GeoTiffMetadata {
            val tags = ClassicTiffTags(bytes)
            val keys = parseGeoKeys(tags.shorts(TAG_GEO_KEY_DIRECTORY))
            val matrix = tags.doubles(TAG_MODEL_TRANSFORMATION)
            val transform = if (matrix.size >= 16) {
                RasterTransform(matrix[0], matrix[1], matrix[3], matrix[4], matrix[5], matrix[7])
            } else {
                val scale = tags.doubles(TAG_MODEL_PIXEL_SCALE)
                val tie = tags.doubles(TAG_MODEL_TIEPOINT)
                require(scale.size >= 2 && tie.size >= 6) { "GeoTIFF is missing georeferencing transform tags" }
                RasterTransform(
                    a = scale[0], b = 0.0, c = tie[3] - tie[0] * scale[0],
                    d = 0.0, e = -scale[1], f = tie[4] + tie[1] * scale[1],
                )
            }
            val projected = keys[KEY_PROJECTED_CS_TYPE]
            val geographic = keys[KEY_GEOGRAPHIC_TYPE]
            val projection = when {
                projected != null -> GeoProjection.fromEpsg(projected)
                geographic == null || geographic == 4326 -> GeographicProjection
                else -> error("Unsupported geographic CRS EPSG:$geographic; use WGS84")
            }
            return GeoTiffMetadata(
                transform = transform,
                projection = projection,
                noData = tags.ascii(TAG_GDAL_NODATA)?.trim()?.trimEnd('\u0000')?.toDoubleOrNull(),
            )
        }

        private fun parseGeoKeys(values: IntArray): Map<Int, Int> {
            if (values.size < 4) return emptyMap()
            return buildMap {
                repeat(values[3]) { index ->
                    val offset = 4 + index * 4
                    if (offset + 3 < values.size && values[offset + 1] == 0 && values[offset + 2] == 1) {
                        put(values[offset], values[offset + 3])
                    }
                }
            }
        }
    }
}

private data class RasterTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun pixelToModel(x: Double, y: Double): Pair<Double, Double> =
        (a * x + b * y + c) to (d * x + e * y + f)
}

private sealed interface GeoProjection {
    val label: String
    fun toWgs84(x: Double, y: Double): Pair<Double, Double>

    companion object {
        fun fromEpsg(epsg: Int): GeoProjection = when (epsg) {
            4326 -> GeographicProjection
            in 32601..32660 -> UtmProjection(epsg, epsg - 32600, true)
            in 32701..32760 -> UtmProjection(epsg, epsg - 32700, false)
            else -> error("Unsupported GeoTIFF CRS EPSG:$epsg; use WGS84 or WGS84 UTM")
        }
    }
}

private object GeographicProjection : GeoProjection {
    override val label = "EPSG:4326 (WGS 84)"
    override fun toWgs84(x: Double, y: Double) = y to x
}

private class UtmProjection(
    epsg: Int,
    private val zone: Int,
    private val northern: Boolean,
) : GeoProjection {
    override val label = "EPSG:$epsg (WGS 84 / UTM zone $zone${if (northern) "N" else "S"})"

    override fun toWgs84(x: Double, y: Double): Pair<Double, Double> {
        val adjustedX = x - 500000.0
        val adjustedY = if (northern) y else y - 10_000_000.0
        val m = adjustedY / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2.pow(2) / 64 - 5 * E2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A / sqrt(1 - E2 * sin(phi1).pow(2))
        val t1 = tan(phi1).pow(2)
        val c1 = EP2 * cos(phi1).pow(2)
        val r1 = A * (1 - E2) / (1 - E2 * sin(phi1).pow(2)).pow(1.5)
        val d = adjustedX / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d.pow(2) / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1.pow(2) - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1.pow(2) - 252 * EP2 - 3 * c1.pow(2)) * d.pow(6) / 720
            )
        val origin = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val lon = origin + (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1.pow(2) + 8 * EP2 + 24 * t1.pow(2)) * d.pow(5) / 120
            ) / cos(phi1)
        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    companion object {
        private const val A = 6378137.0
        private const val E2 = 0.00669437999014
        private const val K0 = 0.9996
        private const val EP2 = E2 / (1.0 - E2)
    }
}

/** Minimal classic-TIFF tag reader for GeoTIFF metadata. */
private class ClassicTiffTags(bytes: ByteArray) {
    private val data = ByteBuffer.wrap(bytes)
    private val entries = mutableMapOf<Int, Entry>()

    init {
        data.order(when {
            bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> ByteOrder.BIG_ENDIAN
            else -> error("Invalid TIFF byte order")
        })
        require(unsignedShort(2) == 42) { "BigTIFF is not supported; use classic GeoTIFF" }
        val ifdOffset = unsignedInt(4).toInt()
        requireRange(ifdOffset, 2)
        val count = unsignedShort(ifdOffset)
        repeat(count) { index ->
            val offset = ifdOffset + 2 + index * 12
            requireRange(offset, 12)
            val type = unsignedShort(offset + 2)
            val valueCount = unsignedInt(offset + 4).toInt()
            val typeSize = TYPE_SIZES[type] ?: return@repeat
            val byteCount = valueCount.toLong() * typeSize
            val valueOffset = if (byteCount <= 4) offset + 8 else unsignedInt(offset + 8).toInt()
            if (valueCount >= 0 && byteCount <= Int.MAX_VALUE && valueOffset >= 0 && valueOffset.toLong() + byteCount <= bytes.size) {
                entries[unsignedShort(offset)] = Entry(type, valueCount, valueOffset)
            }
        }
    }

    fun doubles(tag: Int): DoubleArray = entries[tag]?.let { entry ->
        DoubleArray(entry.count) { number(entry, it) }
    } ?: doubleArrayOf()

    fun shorts(tag: Int): IntArray = entries[tag]?.let { entry ->
        IntArray(entry.count) { number(entry, it).toInt() }
    } ?: intArrayOf()

    fun ascii(tag: Int): String? = entries[tag]?.takeIf { it.type == 2 }?.let { entry ->
        val output = ByteArray(entry.count)
        data.duplicate().apply { position(entry.offset); get(output) }
        output.toString(Charsets.US_ASCII)
    }

    private fun number(entry: Entry, index: Int): Double {
        val offset = entry.offset + index * (TYPE_SIZES[entry.type] ?: 1)
        return when (entry.type) {
            1 -> data.get(offset).toInt().and(0xFF).toDouble()
            3 -> unsignedShort(offset).toDouble()
            4 -> unsignedInt(offset).toDouble()
            5 -> unsignedInt(offset).toDouble() / unsignedInt(offset + 4).toDouble()
            8 -> data.getShort(offset).toDouble()
            9 -> data.getInt(offset).toDouble()
            10 -> data.getInt(offset).toDouble() / data.getInt(offset + 4).toDouble()
            11 -> data.getFloat(offset).toDouble()
            12 -> data.getDouble(offset)
            else -> Double.NaN
        }
    }

    private fun unsignedShort(offset: Int) = data.getShort(offset).toInt().and(0xFFFF)
    private fun unsignedInt(offset: Int) = data.getInt(offset).toLong().and(0xFFFF_FFFFL)
    private fun requireRange(offset: Int, length: Int) =
        require(offset >= 0 && offset + length <= data.capacity()) { "Corrupt TIFF directory" }

    private data class Entry(val type: Int, val count: Int, val offset: Int)

    companion object {
        private val TYPE_SIZES = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8, 11 to 4, 12 to 8)
    }
}
