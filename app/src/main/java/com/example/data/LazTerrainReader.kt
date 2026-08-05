package com.example.data

import com.github.mreutegg.laszip4j.LASPoint
import com.github.mreutegg.laszip4j.LASReader
import com.github.mreutegg.laszip4j.laslib.CopcSelection
import com.github.mreutegg.laszip4j.laslib.LASreaderLAS
import com.github.mreutegg.laszip4j.laslib.NormalizedCopcBounds
import com.github.mreutegg.laszip4j.laslib.SeekableLaszipReader
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_CLASSIFICATION
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_FLAGS
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_Z
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import kotlin.math.min

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    private const val DECODE_BATCH_POINTS = 32_768
    private const val FILE_BUFFER_BYTES = 1024 * 1024
    /** Sparse preview is disabled at the coordinator; keep denser knobs if re-enabled later. */
    private const val PREVIEW_MIN_SOURCE_POINTS = 3_000_000L
    private const val PREVIEW_TARGET_POINTS = 5_000_000
    private const val PREVIEW_MAX_CHUNKS = 1_024
    private const val DEFAULT_CHUNK_POINTS = 50_000
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
                readOpenedLowLevel(reader, options, shouldContinue, onProgress, indexSource = file)
            }
        }.onFailure { exception ->
            System.err.println("Seekable selective LAZ decode failed for ${file.name}: ${exception.message}")
        }.getOrNull()
        if (optimized != null) return optimized

        return readHighLevelFile(file, options, shouldContinue, onProgress)
    }

    /**
     * Full-resolution sparse preview for large full-footprint LAZ files.
     *
     * Seeks across compressed chunks and bins a uniform subset of returns at the **requested**
     * raster resolution (quality floor ≥ 1,024 — never downsamples the product grid). Exact
     * [read] should follow in the background; this is only for first paint latency.
     */
    fun readSparsePreview(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((sampledPoints: Long, targetPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? {
        val safeOptions = options.sanitized()
        if (safeOptions.focusBounds != null) return null
        return runCatching {
            SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS)?.use { reader ->
                val sourceHeader = reader.header
                val pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
                    ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL)
                if (pointCount < PREVIEW_MIN_SOURCE_POINTS || pointCount > Int.MAX_VALUE.toLong()) {
                    return@use null
                }
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
                val sourceChunkSize = sourceHeader.laszip?.chunk_size
                    ?.takeIf { it in 1..5_000_000 }
                    ?: DEFAULT_CHUNK_POINTS
                val totalChunks = ((pointCount + sourceChunkSize - 1L) / sourceChunkSize)
                    .coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                val chunksToSample = min(totalChunks, PREVIEW_MAX_CHUNKS).coerceAtLeast(1)
                val pointsPerChunk = (PREVIEW_TARGET_POINTS / chunksToSample)
                    .coerceIn(1, sourceChunkSize)
                // Keep full raster resolution; only thin the point sample budget.
                val previewOptions = safeOptions.copy(smoothingRadius = 0).sanitized()
                val rasterizer = LidarRasterizer(
                    minX = header.minX,
                    maxX = header.maxX,
                    minY = header.minY,
                    maxY = header.maxY,
                    options = previewOptions,
                    // Declared budget ≈ samples we will feed so internal stride stays ~1.
                    declaredPointCount = PREVIEW_TARGET_POINTS.toLong(),
                )
                val targetSamples = chunksToSample.toLong() * pointsPerChunk.toLong()
                var sampled = 0L
                onProgress?.invoke(0L, targetSamples)

                for (sampleIndex in 0 until chunksToSample) {
                    if (!shouldContinue()) return@use null
                    val chunkIndex = when {
                        chunksToSample == 1 -> 0
                        chunksToSample == totalChunks -> sampleIndex
                        else -> ((sampleIndex.toLong() * (totalChunks - 1L)) / (chunksToSample - 1L)).toInt()
                    }
                    val startPoint = chunkIndex.toLong() * sourceChunkSize.toLong()
                    if (!reader.seek(startPoint)) continue

                    var readInChunk = 0
                    while (readInChunk < pointsPerChunk && reader.read_point()) {
                        rasterizer.addPoint(
                            x = reader.get_x(),
                            y = reader.get_y(),
                            z = reader.get_z().toFloat(),
                            classification = reader.point.get_classification().toInt(),
                            isKeyPoint = reader.point.get_keypoint_flag().toInt() != 0,
                        )
                        readInChunk++
                        sampled++
                    }
                    onProgress?.invoke(sampled, targetSamples)
                }

                val finished = rasterizer.finish(
                    pointFormat = header.pointFormat,
                    sourceLabel = "Sparse full-res chunk preview of LAZ " +
                        "${header.versionMajor}.${header.versionMinor} format ${header.pointFormat}",
                ) ?: return@use null
                finished.copy(
                    note = "${finished.note} · exact terrain still processing",
                    wasTruncated = true,
                )
            }
        }.onFailure { exception ->
            System.err.println("Sparse LAZ preview failed for ${file.name}: ${exception.message}")
        }.getOrNull()
    }

    /** Range-backed COPC/LAZ decode; only blocks touched by the seekable decoder reach disk. */
    fun readRemote(
        url: String,
        rangeCacheFile: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? {
        val focus = options.sanitized().focusBounds
            ?: throw java.io.IOException("COPC streaming requires a selected area")
        return try {
            SeekableLaszipReader.openHttpCopc(
                url = url,
                cacheFile = rangeCacheFile,
                selectiveFields = TERRAIN_DECOMPRESS_FIELDS,
                focus = NormalizedCopcBounds(focus.left, focus.top, focus.right, focus.bottom),
            )?.use { selection ->
                readOpenedCopc(selection, options, shouldContinue, onProgress)
            }
        } catch (exception: Throwable) {
            System.err.println("HTTP range COPC decode failed: ${exception.message}")
            throw java.io.IOException(
                "COPC range request/decode failed: ${exception.message}",
                exception,
            )
        }
    }

    private fun readOpenedCopc(
        selection: CopcSelection,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val reader = selection.reader
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
        val selectedPointCount = selection.selectedPointCount.coerceAtLeast(1L)
        val rasterizer = LidarRasterizer(
            minX = header.minX,
            maxX = header.maxX,
            minY = header.minY,
            maxY = header.maxY,
            options = options,
            declaredPointCount = estimatedPoints(header.pointCount, options.sanitized().focusBounds),
        )
        var decoded = 0L
        var pointsInBatch = 0
        onProgress?.invoke(0L, selectedPointCount)
        for (range in selection.pointRanges) {
            if (!shouldContinue()) return null
            if (!reader.seek(range.firstPoint)) {
                throw java.io.IOException("Could not seek to COPC point ${range.firstPoint}")
            }
            repeat(range.pointCount) {
                if (!reader.read_point()) {
                    throw java.io.IOException("COPC chunk ended before its declared point count")
                }
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
                decoded++
                pointsInBatch++
                if (pointsInBatch >= DECODE_BATCH_POINTS) {
                    pointsInBatch = 0
                    onProgress?.invoke(decoded, selectedPointCount)
                    if (!shouldContinue()) return null
                    if (rasterizer.shouldStopDecoding()) break
                }
            }
            if (rasterizer.shouldStopDecoding()) break
        }
        onProgress?.invoke(decoded, selectedPointCount)
        return finish(rasterizer, header, "COPC hierarchy range decode")
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
        reader.use { readOpenedLowLevel(it, options, shouldContinue, onProgress, indexSource = null) }
    }.onFailure { it.printStackTrace() }.getOrNull()

    private fun readOpenedLowLevel(
        reader: LASreaderLAS,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
        /** The on-disk LAZ, when there is one, so its .lax sidecar can be used. */
        indexSource: File?,
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
        if (focus != null) {
            // Attach the spatial index before the rectangle: with one, the reader seeks past whole
            // compressed chunks that cannot intersect the viewport instead of decompressing every
            // point to test it. Without one this is unchanged, just slower.
            indexSource?.let { file ->
                LazSpatialIndex.load(file)?.let(reader::set_index)
            }
            applyFocus(reader, header, focus)
        }
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
                if (rasterizer.shouldStopDecoding()) break
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
                if (rasterizer.shouldStopDecoding()) break
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
