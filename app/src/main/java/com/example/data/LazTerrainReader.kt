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
        indexedFocusPlan(file, sanitized)?.let { plan ->
            // Sized once from the persisted density histogram: one pass over only the chunks
            // that touch the viewport, never a corrective second decode.
            val sized = sanitized.copy(
                rasterResolution = focusResolutionForPointSpacing(
                    focusX, focusY, plan.groundPoints, sanitized.rasterResolution,
                ),
            ).sanitized()
            return readParallelChunks(file, sized, shouldContinue, onProgress, pointStride = plan.stride)
                ?: readUnclamped(file, sized, shouldContinue, onProgress)
        }
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

    internal data class FocusPlan(val groundPoints: Long, val allPoints: Long, val stride: Int)

    /**
     * Ground/all points expected in a focused refine (from the chunk index histogram) and the
     * point stride that keeps one refine under [refinePointBudget] returns. Null when the file
     * has no index yet or a class filter makes the histogram unrepresentative.
     */
    internal fun indexedFocusPlan(file: File, options: LidarImportOptions): FocusPlan? {
        val focus = options.focusBounds ?: return null
        if (options.allowedClasses != null) return null
        val (header, chunkSize) = openHeader(file) ?: return null
        val index = LazChunkIndex.load(file, header.pointCount, chunkSize) ?: return null
        val rangeX = header.maxX - header.minX
        val rangeY = header.maxY - header.minY
        val x0 = header.minX + focus.left * rangeX
        val x1 = header.minX + focus.right * rangeX
        val y0 = header.minY + (1.0 - focus.bottom) * rangeY
        val y1 = header.minY + (1.0 - focus.top) * rangeY
        val all = index.estimate(x0, y0, x1, y1, ground = false)
        val stride = kotlin.math.ceil(all / refinePointBudget).toInt().coerceAtLeast(1)
        val groundTotal = index.groundHist.sum()
        val ground = if (options.groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED && groundTotal > 0) {
            index.estimate(x0, y0, x1, y1, ground = true)
        } else {
            val r = FOCUS_CELL_TO_GROUND_SPACING / DEFAULT_CELL_TO_SPACING
            all * r * r
        }
        return FocusPlan((ground / stride).toLong(), all.toLong(), stride)
    }

    /** Max returns decoded for one refined screen; denser viewports are thinned uniformly. */
    @Volatile internal var refinePointBudget: Long = 6_000_000L

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
    ): DemGenerator.LasLoadResult? {
        val parallel = runCatching {
            readParallelChunks(file, clampedOptions, shouldContinue, onProgress)
        }.onFailure { exception ->
            System.err.println("Parallel LAZ decode failed for ${file.name}: ${exception.message}")
        }.getOrNull()
        if (parallel != null) return parallel

        val optimized = runCatching {
            SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS)?.use { reader ->
                readOpenedLowLevel(reader, clampedOptions, shouldContinue, onProgress, indexSource = file)
            }
        }.onFailure { exception ->
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
                            withheld = reader.point.get_withheld_flag().toInt() != 0,
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
                        withheld = reader.point.get_withheld_flag().toInt() != 0,
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
        val estimatedPoints = estimatedPoints(header.pointCount, focus)
        val rasterizer = newRasterizer(header, densityLimitedOptions(header, options), estimatedPoints)
        if (focus != null) {
            // Attach the spatial index before the rectangle: with one, the reader seeks past whole
            // compressed chunks that cannot intersect the viewport instead of decompressing every
            // point to test it. Without one this is unchanged, just slower.
            indexSource?.let { file ->
                LazSpatialIndex.load(file)?.let(reader::set_index)
            }
            applyFocus(reader, header, focus, rasterizer.padSourceUnitsX(), rasterizer.padSourceUnitsY())
        }
        return rasterizeLowLevel(
            reader = reader,
            header = header,
            options = densityLimitedOptions(header, options),
            progressTotal = estimatedPoints,
            shouldContinue = shouldContinue,
            onProgress = onProgress,
            prepared = rasterizer,
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
        prepared: LidarRasterizer? = null,
    ): DemGenerator.LasLoadResult? {
        val rasterizer = prepared ?: newRasterizer(header, options, progressTotal)
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
                    withheld = reader.point.get_withheld_flag().toInt() != 0,
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
     * One seekable reader per core. Workers claim compressed chunks, decode them into compact
     * buffers, and a single binner adds chunks strictly in file order (output identical to the
     * sequential decoder).
     *
     * Focused refines use the persisted [LazChunkIndex]: only chunks whose XY bounds touch the
     * viewport (plus the rasterizer's pad ring) are decoded, filtered with laslib's own
     * inside_rectangle predicate (min ≤ x < max), so the grid matches the seekable
     * inside_rectangle path bit for bit while reading a fraction of the file. Any pass that
     * reads every chunk records the index for later refines.
     */
    private fun readParallelChunks(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
        pointStride: Int = 1,
    ): DemGenerator.LasLoadResult? {
        val opened = openHeader(file) ?: return null
        val header = opened.first
        val chunkSize = opened.second
        val pointCount = header.pointCount
        if (pointCount <= 0L) return null
        val limited = densityLimitedOptions(header, options)
        val focus = limited.focusBounds
        if (!parallelDecodeEnabled || pointCount < chunkSize.toLong() * 2L) return null
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val useNative = LazDecoderSettings.resolve() == LazDecoderBackend.NATIVE_LAZ_RS
        if (workers == 1 && !useNative) return null

        val totalChunks = ((pointCount + chunkSize - 1L) / chunkSize).toInt().coerceAtLeast(1)
        val existingIndex = LazChunkIndex.load(file, pointCount, chunkSize)
        val declaredPoints = if (focus != null) estimatedPoints(header.pointCount, focus) else pointCount
        var rasterizer = newRasterizer(header, limited, declaredPoints)
        val rect: DoubleArray? = focus?.let {
            val rangeX = header.maxX - header.minX
            val rangeY = header.maxY - header.minY
            val padX = rasterizer.padSourceUnitsX()
            val padY = rasterizer.padSourceUnitsY()
            doubleArrayOf(
                header.minX + it.left * rangeX - padX,
                header.minY + (1.0 - it.bottom) * rangeY - padY,
                header.minX + it.right * rangeX + padX,
                header.minY + (1.0 - it.top) * rangeY + padY,
            )
        }
        val selected: IntArray = if (rect != null && existingIndex != null) {
            existingIndex.chunksOverlapping(rect[0], rect[1], rect[2], rect[3])
        } else {
            IntArray(totalChunks) { it }
        }
        fun newBuilder() = if (existingIndex == null && selected.size == totalChunks) {
            LazChunkIndex.Builder(pointCount, chunkSize, header.minX, header.maxX, header.minY, header.maxY, totalChunks)
        } else {
            null
        }
        val groundFlags = intArrayOf(header.versionMajor, header.versionMinor, header.pointFormat)
        if (useNative) {
            val native = runCatching {
                readNativeChunks(
                    file, header, chunkSize, selected, totalChunks, rect, pointStride, rasterizer,
                    newBuilder(), groundFlags, workers, shouldContinue, onProgress,
                )
            }.onFailure { exception ->
                System.err.println("laz-rs decode failed for ${file.name}, using laszip4j: ${exception.message}")
            }.getOrNull()
            if (native != null) return native
            // The native attempt may have binned part of the file: start laszip4j from a clean grid.
            rasterizer = newRasterizer(header, limited, declaredPoints)
            if (workers == 1) return null
        }
        val builder = newBuilder()
        val results = Array(selected.size) { java.util.concurrent.CompletableFuture<DecodedChunk>() }
        val window = java.util.concurrent.Semaphore(workers * 2)
        val nextChunk = java.util.concurrent.atomic.AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(workers) { task ->
            Thread(task, "laz-chunk").apply { isDaemon = true }
        }
        val selectedPoints = selected.sumOf { min(chunkSize.toLong(), pointCount - it.toLong() * chunkSize) }
        return try {
            repeat(workers) {
                pool.execute {
                    val reader = runCatching { SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS) }.getOrNull()
                    try {
                        while (!cancelled.get()) {
                            window.acquire()
                            val slot = nextChunk.getAndIncrement()
                            if (slot >= selected.size) {
                                window.release()
                                break
                            }
                            val chunk = selected[slot]
                            val future = results[slot]
                            try {
                                checkNotNull(reader) { "Could not open ${file.name}" }
                                val start = chunk.toLong() * chunkSize
                                val count = min(chunkSize.toLong(), pointCount - start).toInt()
                                check(reader.seek(start)) { "Seek to point $start failed" }
                                future.complete(
                                    DecodedChunk.decode(reader, count, start, rect, pointStride, builder?.Chunk(chunk), groundFlags),
                                )
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
            var complete = true
            TerrainStageProfiler.stage("decode_parallel", file.name) {
                var decoded = 0L
                for (slot in selected.indices) {
                    if (!shouldContinue()) {
                        cancelled.set(true)
                        rasterizer.markTruncated()
                        complete = false
                        break
                    }
                    val points = try {
                        results[slot].get()
                    } catch (error: java.util.concurrent.ExecutionException) {
                        throw error.cause ?: error
                    }
                    results[slot] = DecodedChunk.CONSUMED
                    points.addTo(rasterizer)
                    window.release()
                    decoded += points.rawCount
                    onProgress?.invoke(decoded, selectedPoints)
                    val chunk = selected[slot]
                    if (points.rawCount < min(chunkSize.toLong(), pointCount - chunk.toLong() * chunkSize)) {
                        // Short chunk = reader hit EOF early; the sequential path would stop here too.
                        rasterizer.markTruncated()
                        complete = false
                        break
                    }
                }
            }
            if (complete && builder != null) builder.build()?.let { runCatching { LazChunkIndex.store(file, it) } }
            val label = if (rect != null) {
                "chunk-index refine (${selected.size}/$totalChunks chunks, $workers cores" +
                    (if (pointStride > 1) ", 1/$pointStride point budget" else "") + ")"
            } else {
                "parallel chunk decode ($workers cores)"
            }
            finish(rasterizer, header, label)
        } finally {
            cancelled.set(true)
            pool.shutdownNow()
        }
    }

    /**
     * Streaming laz-rs path over the same chunk selection as the laszip4j binner. Native workers
     * decode chunks (seek via chunk table, reusable scratch, same rect/stride rule) into pooled
     * buffers; this thread bins them strictly in slot order. Returns null on any native failure
     * so the caller falls back to laszip4j; cancellation returns the truncated grid, exactly
     * like the laszip4j path.
     */
    private fun readNativeChunks(
        file: File,
        header: Header,
        chunkSize: Int,
        selected: IntArray,
        totalChunks: Int,
        rect: DoubleArray?,
        pointStride: Int,
        rasterizer: LidarRasterizer,
        builder: LazChunkIndex.Builder?,
        groundFlags: IntArray,
        workers: Int,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val pointCount = header.pointCount
        val session = LazRsNativeDecoder.open(file) ?: return null
        session.use {
            // Must agree with the chunk index geometry, or chunk numbers mean different points.
            if (it.pointCount != pointCount || it.chunkSize != chunkSize.toLong()) return null
            // The index needs every return of every chunk, so filter on this side when building one.
            val nativeRect = if (builder == null) rect else null
            val nativeStride = if (builder == null) pointStride else 1
            if (!it.start(selected, nativeRect, nativeStride, workers)) return null
            val selectedPoints = selected.sumOf { c -> min(chunkSize.toLong(), pointCount - c.toLong() * chunkSize) }
            var complete = true
            var decoded = 0L
            onProgress?.invoke(0L, selectedPoints)
            val ok = TerrainStageProfiler.stage("decode_lazrs", file.name) {
                for (slot in selected.indices) {
                    val buffer = try {
                        it.next(slot, shouldContinue)
                    } catch (_: LazRsNativeDecoder.CancellationSignal) {
                        it.cancel()
                        rasterizer.markTruncated()
                        complete = false
                        break
                    } ?: return@stage false
                    val chunk = selected[slot]
                    val first = chunk.toLong() * chunkSize
                    if (builder != null) {
                        PackedPointRasterizer.addAll(
                            rasterizer, buffer, it.lastKept, first, rect, pointStride, builder.Chunk(chunk), groundFlags,
                        )
                    } else {
                        PackedPointRasterizer.addAll(rasterizer, buffer, it.lastKept)
                    }
                    val raw = it.lastRaw
                    it.release(slot)
                    decoded += raw
                    onProgress?.invoke(decoded, selectedPoints)
                    if (raw < min(chunkSize.toLong(), pointCount - first)) return@stage false
                }
                true
            }
            if (!ok) return null
            if (complete && builder != null) builder.build()?.let { b -> runCatching { LazChunkIndex.store(file, b) } }
            val label = if (rect != null) {
                "laz-rs chunk-index refine (${selected.size}/$totalChunks chunks, $workers cores" +
                    (if (pointStride > 1) ", 1/$pointStride point budget" else "") + ")"
            } else {
                "laz-rs streaming parallel chunk decode ($workers cores)"
            }
            return finish(rasterizer, header, label)
        }
    }

    private fun openHeader(file: File): Pair<Header, Int>? =
        SeekableLaszipReader.open(file, TERRAIN_DECOMPRESS_FIELDS)?.use { reader ->
            val sourceHeader = reader.header
            val pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
                ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL)
            Header(
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
            ) to (sourceHeader.laszip?.chunk_size?.takeIf { it in 1..5_000_000 } ?: DEFAULT_CHUNK_POINTS)
        }

    /** One decoded compressed chunk, kept compact until the binner adds it in file order. */
    private class DecodedChunk(
        val count: Int,
        /** Points read from the file for this chunk, before the viewport filter. */
        val rawCount: Int,
        private val xs: DoubleArray,
        private val ys: DoubleArray,
        private val zs: FloatArray,
        private val classes: ByteArray,
        private val keyPoints: BooleanArray,
        private val withheld: BooleanArray,
    ) {
        fun addTo(rasterizer: LidarRasterizer) {
            for (i in 0 until count) {
                rasterizer.addPoint(
                    x = xs[i],
                    y = ys[i],
                    z = zs[i],
                    classification = classes[i].toInt() and 0xFF,
                    isKeyPoint = keyPoints[i],
                    withheld = withheld[i],
                )
            }
        }

        companion object {
            val CONSUMED = java.util.concurrent.CompletableFuture.completedFuture(
                DecodedChunk(0, 0, DoubleArray(0), DoubleArray(0), FloatArray(0), ByteArray(0), BooleanArray(0), BooleanArray(0)),
            )

            fun decode(
                reader: LASreaderLAS,
                wanted: Int,
                firstPoint: Long,
                rect: DoubleArray?,
                stride: Int,
                index: LazChunkIndex.Builder.Chunk?,
                groundFlags: IntArray,
            ): DecodedChunk {
                // Filtered chunks keep only the viewport share; size buffers lazily for those.
                var capacity = if (rect == null) wanted else min(wanted, 4_096)
                var xs = DoubleArray(capacity)
                var ys = DoubleArray(capacity)
                var zs = FloatArray(capacity)
                var classes = ByteArray(capacity)
                var keys = BooleanArray(capacity)
                var held = BooleanArray(capacity)
                var n = 0
                var raw = 0
                while (raw < wanted && reader.read_point()) {
                    val x = reader.get_x()
                    val y = reader.get_y()
                    val cls = reader.point.get_classification().toInt() and 0xFF
                    val key = reader.point.get_keypoint_flag().toInt() != 0
                    index?.add(x, y, isSourceGround(cls, key, groundFlags[0], groundFlags[1], groundFlags[2]))
                    val global = firstPoint + raw
                    raw++
                    if (rect != null && (x < rect[0] || x >= rect[2] || y < rect[1] || y >= rect[3])) continue
                    if (stride > 1 && global % stride != 0L) continue
                    if (n == capacity) {
                        capacity = min(wanted, capacity * 2)
                        xs = xs.copyOf(capacity); ys = ys.copyOf(capacity); zs = zs.copyOf(capacity)
                        classes = classes.copyOf(capacity); keys = keys.copyOf(capacity); held = held.copyOf(capacity)
                    }
                    xs[n] = x
                    ys[n] = y
                    zs[n] = reader.get_z().toFloat()
                    classes[n] = cls.toByte()
                    keys[n] = key
                    held[n] = reader.point.get_withheld_flag().toInt() != 0
                    n++
                }
                index?.commit()
                return DecodedChunk(n, raw, xs, ys, zs, classes, keys, held)
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

    private fun applyFocus(
        reader: LASreaderLAS,
        header: Header,
        focus: NormalizedRasterBounds,
        padX: Double = 0.0,
        padY: Double = 0.0,
    ) {
        val rangeX = header.maxX - header.minX
        val rangeY = header.maxY - header.minY
        reader.inside_rectangle(
            header.minX + focus.left * rangeX - padX,
            header.minY + (1.0 - focus.bottom) * rangeY - padY,
            header.minX + focus.right * rangeX + padX,
            header.minY + (1.0 - focus.top) * rangeY + padY,
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

