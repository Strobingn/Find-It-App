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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    /** Test hook: false forces the sequential decoder so parallel output can be compared to it. */
    @Volatile internal var parallelDecodeEnabled = true

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
        val sanitized = options.sanitized()
        val focus = sanitized.focusBounds
        val memoKey = groundDensityKey(file, sanitized)
        val extent = runCatching { headerExtent(file) }.getOrNull()
        if (focus == null || extent == null) {
            val result = readUnclamped(file, sanitized, shouldContinue, onProgress)
            if (result != null && focus == null && extent != null) {
                // Valid (hull) cells, not the bbox: empty corners would understate density.
                val g = result.grid
                val validCells = g.validData.count { it }.toDouble()
                val cellX = extent.first / (g.width - 1).coerceAtLeast(1)
                val cellY = extent.second / (g.height - 1).coerceAtLeast(1)
                GroundDensityMemo.record(memoKey, effectiveGroundPoints(result), validCells * cellX * cellY)
            }
            return result
        }
        // A refined viewport must not use cells finer than the real ground point spacing: the TIN
        // then fills most cells from a few points and the hillshade shows facets/stair-steps.
        val focusX = extent.first * (focus.right - focus.left)
        val focusY = extent.second * (focus.bottom - focus.top)
        val known = GroundDensityMemo.pointsPerArea(memoKey)
        val first = if (known != null) {
            sanitized.copy(
                rasterResolution = focusResolutionForPointSpacing(
                    focusX, focusY, (known * focusX * focusY).toLong(), sanitized.rasterResolution,
                ),
            ).sanitized()
        } else {
            sanitized
        }
        val result = readUnclamped(file, first, shouldContinue, onProgress) ?: return null
        // Verify against the exact ground count inside the crop; re-bin once if still too fine.
        val ideal = focusResolutionForPointSpacing(
            focusX, focusY, effectiveGroundPoints(result), sanitized.rasterResolution,
        )
        val off = ideal.toDouble() / first.rasterResolution
        if ((off in (1.0 / FOCUS_REDECODE_TOLERANCE)..FOCUS_REDECODE_TOLERANCE) || !shouldContinue()) return result
        return readUnclamped(file, first.copy(rasterResolution = ideal).sanitized(), shouldContinue, onProgress)
            ?: result
    }

    /**
     * Classified ground uses 1.25× its spacing; unclassified modes count every return, so scale
     * them to keep the overview's 1.75× all-return rule with the same clamp function.
     */
    private fun effectiveGroundPoints(result: DemGenerator.LasLoadResult): Long {
        val n = result.groundPointsUsed.toLong()
        if (result.appliedGroundMode == GroundSurfaceMode.SOURCE_CLASSIFIED) return n
        val r = FOCUS_CELL_TO_GROUND_SPACING / DEFAULT_CELL_TO_SPACING
        return (n * r * r).toLong()
    }

    /** Source footprint (x, y extent in source units) from the LAS header, or null. */
    private fun headerExtent(file: File): Pair<Double, Double>? =
        SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS)?.use { reader ->
            val h = reader.header
            val x = h.max_x - h.min_x
            val y = h.max_y - h.min_y
            if (x > 0.0 && y > 0.0 && x.isFinite() && y.isFinite()) x to y else null
        }

    private fun groundDensityKey(file: File, options: LidarImportOptions): String =
        "${runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)}|${file.length()}|" +
            "${file.lastModified()}|${options.groundMode}|${options.allowedClasses?.sorted()}"

    private fun readUnclamped(
        file: File,
        clampedOptions: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? = try {
        readUnclampedOnce(file, clampedOptions, shouldContinue, onProgress, rethrowOom = true)
    } catch (oom: OutOfMemoryError) {
        // Drop caches, switch to the low-memory profile (fewer workers), retry once.
        AppMemoryBudget.onOutOfMemory()
        System.err.println("LAZ decode ran out of memory for ${file.name}; retrying with caches cleared")
        readUnclampedOnce(file, clampedOptions, shouldContinue, onProgress, rethrowOom = false)
    }

    private fun readUnclampedOnce(
        file: File,
        clampedOptions: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
        rethrowOom: Boolean,
    ): DemGenerator.LasLoadResult? {
        if (LazDecoderSettings.resolve() == LazDecoderBackend.NATIVE_LAZ_RS) {
            val native = runCatching {
                readNativePacked(file, clampedOptions, shouldContinue)
            }.onFailure { exception ->
                System.err.println("laz-rs decode failed for ${file.name}: ${exception.message}")
            }.getOrNull()
            if (native != null) return native
        }

        val parallel = runCatching {
            readParallelChunks(file, clampedOptions, shouldContinue, onProgress)
        }.onFailure { exception ->
            if (rethrowOom && exception is OutOfMemoryError) throw exception
            System.err.println("Parallel LAZ decode failed for ${file.name}: ${exception.message}")
        }.getOrNull()
        if (parallel != null) return parallel

        val optimized = runCatching {
            SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS)?.use { reader ->
                readOpenedLowLevel(reader, clampedOptions, shouldContinue, onProgress, indexSource = file)
            }
        }.onFailure { exception ->
            if (rethrowOom && exception is OutOfMemoryError) throw exception
            System.err.println("Seekable selective LAZ decode failed for ${file.name}: ${exception.message}")
        }.getOrNull()
        if (optimized != null) return optimized

        return readHighLevelFile(file, clampedOptions, shouldContinue, onProgress)
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
                val previewOptions = densityLimitedOptions(
                    header,
                    safeOptions.copy(smoothingRadius = 0),
                )
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
        val limited = densityLimitedOptions(header, options)
        val rasterizer = LidarRasterizer(
            minX = header.minX,
            maxX = header.maxX,
            minY = header.minY,
            maxY = header.maxY,
            options = limited,
            declaredPointCount = estimatedPoints(header.pointCount, options.sanitized().focusBounds),
            lasVersionMajor = header.versionMajor,
            lasVersionMinor = header.versionMinor,
            pointFormat = header.pointFormat,
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
                    if (!shouldContinue()) {
                        rasterizer.markTruncated()
                        return null
                    }
                }
            }
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
    ): DemGenerator.LasLoadResult? {
        // laszip4j's non-seekable stream reader stops at the first chunk boundary of chunked LAZ
        // (it read 50,000 of 10.6M points on autzen), so spool to a temp file and use the seekable
        // file path. The stream decoder remains the fallback when spooling is not possible.
        val spooled = runCatching {
            File.createTempFile("laz-stream-", ".laz").also { temp ->
                temp.outputStream().buffered(FILE_BUFFER_BYTES).use { out -> inputStream.copyTo(out, FILE_BUFFER_BYTES) }
            }
        }.getOrNull()
        if (spooled != null) {
            try {
                return read(spooled, options, shouldContinue, onProgress)
            } finally {
                spooled.delete()
            }
        }
        return readStreamDirect(inputStream, options, shouldContinue, onProgress)
    }

    private fun readStreamDirect(
        inputStream: InputStream,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
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
            options = densityLimitedOptions(header, options),
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
                options = densityLimitedOptions(header, options),
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
                if (!shouldContinue()) {
                    rasterizer.markTruncated()
                    break
                }
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
                if (!shouldContinue()) {
                    rasterizer.markTruncated()
                    break
                }
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
        lasVersionMajor = header.versionMajor,
        lasVersionMinor = header.versionMinor,
        pointFormat = header.pointFormat,
    )

    /**
     * One seekable reader per core, each owning a disjoint compressed-chunk range.
     * Workers fill private rasters that are merged on the calling thread.
     */
    private fun readParallelChunks(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val probe = SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS) ?: return null
        val header: Header
        val chunkSize: Int
        val pointCount: Long
        probe.use { reader ->
            val sourceHeader = reader.header
            pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
                ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL)
            header = Header(
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
            chunkSize = sourceHeader.laszip?.chunk_size?.takeIf { it in 1..5_000_000 } ?: DEFAULT_CHUNK_POINTS
        }
        if (pointCount <= 0L) return null
        val limited = densityLimitedOptions(header, options)
        val focus = limited.focusBounds
        if (focus != null) return null
        if (!parallelDecodeEnabled || pointCount < chunkSize.toLong() * 2L) return null
        val workers = AppMemoryBudget.decodeWorkers()
        if (workers == 1) return null

        // One shared raster; workers only decode. Each claims the next compressed chunk, decodes
        // it into a compact buffer (~17 B/point), and a single binner adds chunks strictly in
        // file order. Memory is bounded by the in-flight window (2 chunks per worker, ~14 MB at
        // 8 workers) instead of a full private raster per worker (75 MB each at 1536²), so all
        // cores can be used, and the result is identical to the sequential decoder.
        val rasterizer = newRasterizer(header, limited, pointCount)
        val totalChunks = ((pointCount + chunkSize - 1L) / chunkSize).toInt().coerceAtLeast(1)
        val results = Array(totalChunks) { java.util.concurrent.CompletableFuture<DecodedChunk>() }
        val window = java.util.concurrent.Semaphore(workers * 2)
        val nextChunk = java.util.concurrent.atomic.AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(workers) { task ->
            Thread(task, "laz-chunk").apply { isDaemon = true }
        }
        return try {
            repeat(workers) {
                pool.execute {
                    val reader = runCatching { SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS) }.getOrNull()
                    try {
                        while (!cancelled.get()) {
                            window.acquire()
                            val chunk = nextChunk.getAndIncrement()
                            if (chunk >= totalChunks) {
                                window.release()
                                break
                            }
                            val future = results[chunk]
                            try {
                                checkNotNull(reader) { "Could not open ${file.name}" }
                                val start = chunk.toLong() * chunkSize
                                val count = min(chunkSize.toLong(), pointCount - start).toInt()
                                check(reader.seek(start)) { "Seek to point $start failed" }
                                future.complete(DecodedChunk.decode(reader, count))
                            } catch (error: Throwable) {
                                future.completeExceptionally(error)
                            }
                        }
                    } catch (_: InterruptedException) {
                        // Pool shut down: the binner already stopped waiting.
                    } finally {
                        reader?.close()
                    }
                }
            }
            TerrainStageProfiler.stage("decode_parallel", file.name) {
                var decoded = 0L
                for (chunk in 0 until totalChunks) {
                    if (!shouldContinue()) {
                        cancelled.set(true)
                        rasterizer.markTruncated()
                        break
                    }
                    val points = try {
                        results[chunk].get()
                    } catch (error: java.util.concurrent.ExecutionException) {
                        throw error.cause ?: error
                    }
                    results[chunk] = DecodedChunk.CONSUMED
                    points.addTo(rasterizer)
                    window.release()
                    decoded += points.count
                    onProgress?.invoke(decoded, pointCount)
                    if (points.count < min(chunkSize.toLong(), pointCount - chunk.toLong() * chunkSize)) {
                        // Short chunk = reader hit EOF early; the sequential path would stop here too.
                        rasterizer.markTruncated()
                        break
                    }
                }
            }
            finish(rasterizer, header, "parallel chunk decode ($workers cores)")
        } finally {
            cancelled.set(true)
            pool.shutdownNow()
        }
    }

    /** One decoded compressed chunk, kept compact until the binner adds it in file order. */
    private class DecodedChunk(
        val count: Int,
        private val xs: DoubleArray,
        private val ys: DoubleArray,
        private val zs: FloatArray,
        private val classes: ByteArray,
        private val keyPoints: BooleanArray,
    ) {
        fun addTo(rasterizer: LidarRasterizer) {
            if (rasterizer.acceptsFastBin) {
                rasterizer.addUnclassifiedSamples(xs, ys, zs, count)
                return
            }
            for (i in 0 until count) {
                rasterizer.addPoint(
                    x = xs[i],
                    y = ys[i],
                    z = zs[i],
                    classification = classes[i].toInt() and 0xFF,
                    isKeyPoint = keyPoints[i],
                )
            }
        }

        companion object {
            val CONSUMED = java.util.concurrent.CompletableFuture.completedFuture(
                DecodedChunk(0, DoubleArray(0), DoubleArray(0), FloatArray(0), ByteArray(0), BooleanArray(0)),
            )

            fun decode(reader: LASreaderLAS, wanted: Int): DecodedChunk {
                val xs = DoubleArray(wanted)
                val ys = DoubleArray(wanted)
                val zs = FloatArray(wanted)
                val classes = ByteArray(wanted)
                val keys = BooleanArray(wanted)
                var n = 0
                while (n < wanted && reader.read_point()) {
                    xs[n] = reader.get_x()
                    ys[n] = reader.get_y()
                    zs[n] = reader.get_z().toFloat()
                    classes[n] = reader.point.get_classification().toByte()
                    keys[n] = reader.point.get_keypoint_flag().toInt() != 0
                    n++
                }
                return DecodedChunk(n, xs, ys, zs, classes, keys)
            }
        }
    }

    private fun finish(
        rasterizer: LidarRasterizer,
        header: Header,
        decoderLabel: String,
    ): DemGenerator.LasLoadResult? = TerrainStageProfiler.stage("grid", decoderLabel) {
        rasterizer.finish(
            pointFormat = header.pointFormat,
            sourceLabel = "LAZ ${header.versionMajor}.${header.versionMinor} format ${header.pointFormat} $decoderLabel",
        )
    }

    /**
     * laz-rs JNI path: one packed buffer, then the same [LidarRasterizer] hot path.
     * Falls back to laszip4j when the .so is missing or the decode returns no points.
     */
    private fun readNativePacked(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
    ): DemGenerator.LasLoadResult? {
        if (!LazRsNativeDecoder.isAvailable) return null
        val probe = SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS) ?: return null
        val header: Header
        val pointCount: Long
        probe.use { reader ->
            val sourceHeader = reader.header
            pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
                ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL)
            header = Header(
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
        }
        if (pointCount <= 0L || pointCount > Int.MAX_VALUE.toLong()) return null
        val packed = PackedPointRasterizer.allocate(pointCount.toInt())
        val origins = DoubleArray(3)
        val decoded = TerrainStageProfiler.stage("decode_lazrs", file.name) {
            LazRsNativeDecoder.decodePacked(file, packed, origins, shouldContinue)
        }
        if (decoded <= 0) return null
        val rasterizer = newRasterizer(header, options, pointCount)
        PackedPointRasterizer.addAll(
            rasterizer,
            packed,
            decoded,
            shouldContinue,
            originX = origins[0],
            originY = origins[1],
            originZ = origins[2],
        )
        return finish(rasterizer, header, "laz-rs packed JNI")
    }

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

    /**
     * Apply the 1.5–2× ground-spacing cell cap once from the **full** header. Chunk workers must
     * not recompute this from their own point range or merged rasters will disagree in size.
     * Focused refine skips the cap so the zoomed view can use a finer cell than the overview.
     */
    private fun densityLimitedOptions(header: Header, options: LidarImportOptions): LidarImportOptions {
        val sanitized = options.sanitized()
        val focus = sanitized.focusBounds
        val rangeX = ((header.maxX - header.minX) * ((focus?.right ?: 1.0) - (focus?.left ?: 0.0)))
            .takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val rangeY = ((header.maxY - header.minY) * ((focus?.bottom ?: 1.0) - (focus?.top ?: 0.0)))
            .takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return sanitized.forTerrainDecode(
            rangeX = rangeX,
            rangeY = rangeY,
            pointCount = estimatedPoints(header.pointCount, focus),
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


/**
 * Largest refine resolution whose cell is at least [cellToSpacing]× the point spacing of
 * [pointsInFocus] spread over the viewport; never above the requested resolution.
 */
internal fun focusResolutionForPointSpacing(
    rangeX: Double,
    rangeY: Double,
    pointsInFocus: Long,
    requestedResolution: Int,
    cellToSpacing: Double = FOCUS_CELL_TO_GROUND_SPACING,
): Int {
    val cap = recommendedRasterResolution(rangeX, rangeY, pointsInFocus.coerceAtLeast(1L), cellToSpacing)
        ?: return requestedResolution
    return min(requestedResolution, cap).coerceAtLeast(LidarImportOptions.MIN_RASTER_RESOLUTION)
}

/** Refine cell vs measured ground spacing: ~1.25× keeps detail without per-point terracing. */
internal const val FOCUS_CELL_TO_GROUND_SPACING = 1.25
/** Re-decode a refine only when its cell is meaningfully (>15%) finer than the clamp. */
private const val FOCUS_REDECODE_TOLERANCE = 1.15

/** Ground points per source area unit, learned from full-footprint decodes this session. */
internal object GroundDensityMemo {
    private val density = java.util.concurrent.ConcurrentHashMap<String, Double>()

    fun record(key: String, groundPoints: Long, area: Double) {
        if (groundPoints > 0L && area > 0.0 && area.isFinite()) density[key] = groundPoints / area
    }

    fun pointsPerArea(key: String): Double? = density[key]
}

