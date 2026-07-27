package com.example.data

import com.github.mreutegg.laszip4j.laslib.LASreaderLAS
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_CLASSIFICATION
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_FLAGS
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_Z
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    private const val DECODE_BATCH_POINTS = 32_768
    private const val FILE_BUFFER_BYTES = 1024 * 1024
    private val TERRAIN_DECOMPRESS_FIELDS =
        LASZIP_DECOMPRESS_SELECTIVE_Z or
            LASZIP_DECOMPRESS_SELECTIVE_CLASSIFICATION or
            LASZIP_DECOMPRESS_SELECTIVE_FLAGS

    fun read(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? = runCatching {
        FileInputStream(file).buffered(FILE_BUFFER_BYTES).use { input ->
            readLowLevel(input, options, shouldContinue, onProgress)
        }
    }.onFailure { it.printStackTrace() }.getOrNull()

    fun read(
        inputStream: InputStream,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? = runCatching {
        val buffered = if (inputStream is BufferedInputStream) {
            inputStream
        } else {
            BufferedInputStream(inputStream, FILE_BUFFER_BYTES)
        }
        readLowLevel(buffered, options, shouldContinue, onProgress)
    }.onFailure { it.printStackTrace() }.getOrNull()

    /**
     * Uses laszip4j's reusable low-level point object and selective LAS 1.4 decompression. The old
     * high-level Iterable path allocated a LASPoint wrapper and decompressed RGB/GPS/extra-byte
     * payloads for every return even though terrain generation only needs XYZ, class, and flags.
     */
    private fun readLowLevel(
        input: InputStream,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val reader = LASreaderLAS()
        if (!reader.open(input, TERRAIN_DECOMPRESS_FIELDS)) return null
        return reader.use {
            val sourceHeader = reader.header
            val pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
                ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL)
            val header = Header(
                versionMajor = sourceHeader.version_major.toInt() and 0xFF,
                versionMinor = sourceHeader.version_minor.toInt() and 0xFF,
                pointFormat = sourceHeader.point_data_format.toInt() and 0x3F,
                pointCount = pointCount,
                maxX = sourceHeader.max_x,
                minX = sourceHeader.min_x,
                maxY = sourceHeader.max_y,
                minY = sourceHeader.min_y,
            )
            val focus = options.sanitized().focusBounds
            if (focus != null) {
                val rangeX = header.maxX - header.minX
                val rangeY = header.maxY - header.minY
                reader.inside_rectangle(
                    header.minX + focus.left * rangeX,
                    header.minY + (1.0 - focus.bottom) * rangeY,
                    header.minX + focus.right * rangeX,
                    header.minY + (1.0 - focus.top) * rangeY,
                )
            }
            val estimatedPoints = focus?.let {
                (header.pointCount * (it.right - it.left) * (it.bottom - it.top))
                    .toLong()
                    .coerceAtLeast(1L)
            } ?: header.pointCount
            rasterize(
                reader = reader,
                header = header,
                options = options,
                progressTotal = estimatedPoints,
                shouldContinue = shouldContinue,
                onProgress = onProgress,
            )
        }
    }

    private fun rasterize(
        reader: LASreaderLAS,
        header: Header,
        options: LidarImportOptions,
        progressTotal: Long,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val rasterizer = LidarRasterizer(
            minX = header.minX,
            maxX = header.maxX,
            minY = header.minY,
            maxY = header.maxY,
            options = options,
            declaredPointCount = progressTotal,
        )
        var pointsInBatch = 0
        onProgress?.invoke(0L, progressTotal)
        while (reader.read_point()) {
            when (rasterizer.nextPointWork()) {
                LidarPointWork.SKIP -> rasterizer.skipPoint()
                LidarPointWork.COVERAGE -> rasterizer.addCoveragePoint(
                    x = reader.get_x(),
                    y = reader.get_y(),
                )
                LidarPointWork.ELEVATION -> rasterizer.addPoint(
                    x = reader.get_x(),
                    y = reader.get_y(),
                    z = reader.get_z().toFloat(),
                    classification = reader.point.get_classification().toInt(),
                    isKeyPoint = reader.point.get_keypoint_flag().toInt() != 0,
                )
            }
            pointsInBatch++
            if (pointsInBatch >= DECODE_BATCH_POINTS) {
                pointsInBatch = 0
                onProgress?.invoke(rasterizer.pointsDecoded, progressTotal)
                if (!shouldContinue()) return null
            }
        }
        onProgress?.invoke(rasterizer.pointsDecoded, progressTotal)
        return rasterizer.finish(
            pointFormat = header.pointFormat,
            sourceLabel = "LAZ ${header.versionMajor}.${header.versionMinor} format ${header.pointFormat} selective decode",
        )
    }

    private data class Header(
        val versionMajor: Int,
        val versionMinor: Int,
        val pointFormat: Int,
        val pointCount: Long,
        val maxX: Double,
        val minX: Double,
        val maxY: Double,
        val minY: Double,
    )
}
