package com.example.data

import com.github.mreutegg.laszip4j.LASPoint
import com.github.mreutegg.laszip4j.LASReader
import com.github.mreutegg.laszip4j.laslib.LASreaderLAS
import com.github.mreutegg.laszip4j.laslib.SeekableLaszipReader
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_CLASSIFICATION
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_FLAGS
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_Z
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    private const val DECODE_BATCH_POINTS = 32_768
    private const val FILE_BUFFER_BYTES = 1024 * 1024
    private val TERRAIN_DECOMPRESS_FIELDS =
        LASZIP_DECOMPRESS_SELECTIVE_Z or
            LASZIP_DECOMPRESS_SELECTIVE_CLASSIFICATION or
            LASZIP_DECOMPRESS_SELECTIVE_FLAGS

    /**
     * Opens local LAZ files through laszip4j's seekable RandomAccessFile path. Compressed chunk
     * tables and embedded indexes may require seeking; the previous FileInputStream implementation
     * compiled successfully but returned no points for valid files on Android.
     *
     * A compatibility fallback retains the public high-level LASReader(File) path for unusual LAZ
     * variants while keeping the bounded overview sampling introduced by the performance work.
     */
    fun read(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? {
        val optimized = runCatching {
            SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS)?.use { reader ->
                readOpenedLowLevel(reader, options, shouldContinue, onProgress)
            }
        }.onFailure { exception ->
            System.err.println("Seekable selective LAZ decode failed for ${file.name}: ${exception.message}")
        }.getOrNull()
        if (optimized != null) return optimized

        return readHighLevelFile(file, options, shouldContinue, onProgress)
    }

    /** Stream compatibility path used only where a local seekable file is unavailable. */
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
        val reader = LASreaderLAS()
        if (!reader.open(buffered, TERRAIN_DECOMPRESS_FIELDS)) return@runCatching null
        reader.use { readOpenedLowLevel(it, options, shouldContinue, onProgress) }
    }.onFailure { it.printStackTrace() }.getOrNull()

    private fun readOpenedLowLevel(
        reader: LASreaderLAS,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val sourceHeader = reader.header
        val pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
            ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL)
        val header = Header(
            versionMajor = sourceHeader.version_major.toInt() and 0xFF,
            versionMinor = sourceHeader.version_minor.toInt() and 0xFF,
            pointFormat = sourceHeader.point_data_format.toInt() and 0x3F,
            pointCount = pointCount,
            scaleX = sourceHeader.x_scale_factor,
            scaleY = sourceHeader.y_scale_factor,
            scaleZ = sourceHeader.z_scale_factor,
            offsetX = sourceHeader.x_offset,
            offsetY = sourceHeader.y_offset,
            offsetZ = sourceHeader.z_offset,
            maxX = sourceHeader.max_x,
            minX = sourceHeader.min_x,
            maxY = sourceHeader.max_y,
            minY = sourceHeader.min_y,
        )
        val focus = options.sanitized().focusBounds
        if (focus != null) applyFocus(reader, header, focus)
        val estimatedPoints = estimatedPoints(header.pointCount, focus)
        return rasterizeLowLevel(
            reader = reader,
            header = header,
            options = options,
            progressTotal = estimatedPoints,
            shouldContinue = shouldContinue,
            onProgress = onProgress,
        )
    }

    private fun readHighLevelFile(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? = runCatching {
        val reader = LASReader(file)
        val sourceHeader = reader.header
        val header = Header(
            versionMajor = sourceHeader.versionMajor.toInt() and 0xFF,
            versionMinor = sourceHeader.versionMinor.toInt() and 0xFF,
            pointFormat = sourceHeader.pointDataRecordFormat.toInt() and 0x3F,
            pointCount = sourceHeader.numberOfPointRecords.takeIf { it > 0L }
                ?: (sourceHeader.legacyNumberOfPointRecords.toLong() and 0xFFFFFFFFL),
            scaleX = sourceHeader.xScaleFactor,
            scaleY = sourceHeader.yScaleFactor,
            scaleZ = sourceHeader.zScaleFactor,
            offsetX = sourceHeader.xOffset,
            offsetY = sourceHeader.yOffset,
            offsetZ = sourceHeader.zOffset,
            maxX = sourceHeader.maxX,
            minX = sourceHeader.minX,
            maxY = sourceHeader.maxY,
            minY = sourceHeader.minY,
        )
        val focus = options.sanitized().focusBounds
        val constrained = focus?.let {
            val rangeX = header.maxX - header.minX
            val rangeY = header.maxY - header.minY
            reader.insideRectangle(
                header.minX + it.left * rangeX,
                header.minY + (1.0 - it.bottom) * rangeY,
                header.minX + it.right * rangeX,
                header.minY + (1.0 - it.top) * rangeY,
            )
        } ?: reader
        constrained.getCloseablePoints().use { points ->
            rasterizeHighLevel(
                points = points,
                header = header,
                options = options,
                progressTotal = estimatedPoints(header.pointCount, focus),
                shouldContinue = shouldContinue,
                onProgress = onProgress,
            )
        }
    }.onFailure { exception ->
        System.err.println("Compatible LAZ decode failed for ${file.name}: ${exception.message}")
        exception.printStackTrace()
    }.getOrNull()

    private fun rasterizeLowLevel(
        reader: LASreaderLAS,
        header: Header,
        options: LidarImportOptions,
        progressTotal: Long,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val rasterizer = newRasterizer(header, options, progressTotal)
        var pointsInBatch = 0
        onProgress?.invoke(0L, progressTotal)
        while (reader.read_point()) {
            when (rasterizer.nextPointWork()) {
                LidarPointWork.SKIP -> rasterizer.skipPoint()
                LidarPointWork.COVERAGE -> rasterizer.addCoveragePoint(reader.get_x(), reader.get_y())
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
        return finish(rasterizer, header, "seekable selective decode")
    }

    private fun rasterizeHighLevel(
        points: Iterable<LASPoint>,
        header: Header,
        options: LidarImportOptions,
        progressTotal: Long,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val rasterizer = newRasterizer(header, options, progressTotal)
        var pointsInBatch = 0
        onProgress?.invoke(0L, progressTotal)
        for (point in points) {
            when (rasterizer.nextPointWork()) {
                LidarPointWork.SKIP -> rasterizer.skipPoint()
                LidarPointWork.COVERAGE -> rasterizer.addCoveragePoint(
                    x = point.getX() * header.scaleX + header.offsetX,
                    y = point.getY() * header.scaleY + header.offsetY,
                )
                LidarPointWork.ELEVATION -> rasterizer.addPoint(
                    x = point.getX() * header.scaleX + header.offsetX,
                    y = point.getY() * header.scaleY + header.offsetY,
                    z = (point.getZ() * header.scaleZ + header.offsetZ).toFloat(),
                    classification = point.getClassification().toInt(),
                    isKeyPoint = point.isKeyPoint(),
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
        return finish(rasterizer, header, "seekable compatibility decode")
    }

    private fun newRasterizer(
        header: Header,
        options: LidarImportOptions,
        progressTotal: Long,
    ) = LidarRasterizer(
        minX = header.minX,
        maxX = header.maxX,
        minY = header.minY,
        maxY = header.maxY,
        options = options,
        declaredPointCount = progressTotal,
    )

    private fun finish(
        rasterizer: LidarRasterizer,
        header: Header,
        decoderLabel: String,
    ): DemGenerator.LasLoadResult? = rasterizer.finish(
        pointFormat = header.pointFormat,
        sourceLabel = "LAZ ${header.versionMajor}.${header.versionMinor} format ${header.pointFormat} $decoderLabel",
    )

    private fun applyFocus(reader: LASreaderLAS, header: Header, focus: NormalizedRasterBounds) {
        val rangeX = header.maxX - header.minX
        val rangeY = header.maxY - header.minY
        reader.inside_rectangle(
            header.minX + focus.left * rangeX,
            header.minY + (1.0 - focus.bottom) * rangeY,
            header.minX + focus.right * rangeX,
            header.minY + (1.0 - focus.top) * rangeY,
        )
    }

    private fun estimatedPoints(pointCount: Long, focus: NormalizedRasterBounds?): Long = focus?.let {
        (pointCount * (it.right - it.left) * (it.bottom - it.top)).toLong().coerceAtLeast(1L)
    } ?: pointCount.coerceAtLeast(1L)

    private data class Header(
        val versionMajor: Int,
        val versionMinor: Int,
        val pointFormat: Int,
        val pointCount: Long,
        val scaleX: Double,
        val scaleY: Double,
        val scaleZ: Double,
        val offsetX: Double,
        val offsetY: Double,
        val offsetZ: Double,
        val maxX: Double,
        val minX: Double,
        val maxY: Double,
        val minY: Double,
    )
}
