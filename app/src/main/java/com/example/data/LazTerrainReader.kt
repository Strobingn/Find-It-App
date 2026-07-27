package com.example.data

import com.github.mreutegg.laszip4j.LASPoint
import com.github.mreutegg.laszip4j.LASReader
import com.github.mreutegg.laszip4j.laslib.LASreadOpener
import com.github.mreutegg.laszip4j.laslib.LASreader
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    private const val DECODE_BATCH_POINTS = 65_536

    /**
     * Fast file-backed path. Uses laszip4j's low-level reader directly so the same mutable LASpoint
     * is reused for every return. The convenience Iterable allocates a new LASPoint wrapper per
     * return, which creates severe GC pressure on multi-million-point terrain tiles.
     */
    fun read(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? {
        return try {
            val lowLevelReader = LASreadOpener().open(file.absolutePath) ?: return null
            lowLevelReader.use { reader ->
                val sourceHeader = reader.header
                val header = Header(
                    versionMajor = sourceHeader.version_major.toInt() and 0xFF,
                    versionMinor = sourceHeader.version_minor.toInt() and 0xFF,
                    pointFormat = sourceHeader.point_data_format.toInt() and 0x3F,
                    pointCount = sourceHeader.extended_number_of_point_records.takeIf { it > 0L }
                        ?: (sourceHeader.number_of_point_records.toLong() and 0xFFFFFFFFL),
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
                rasterizeLowLevel(
                    reader = reader,
                    header = header,
                    options = options,
                    progressTotal = estimatedPoints,
                    shouldContinue = shouldContinue,
                    onProgress = onProgress,
                )
            }
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
    }

    /** Stream fallback for content providers that cannot be copied to a local file. */
    fun read(
        inputStream: InputStream,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? {
        return try {
            val buffered = if (inputStream is BufferedInputStream) {
                inputStream
            } else {
                BufferedInputStream(inputStream, 256 * 1024)
            }
            val header = readHeader(buffered) ?: return null
            rasterizeIterable(
                points = LASReader.getPoints(buffered),
                header = header,
                options = options,
                progressTotal = header.pointCount,
                shouldContinue = shouldContinue,
                onProgress = onProgress,
            )
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
    }

    private fun rasterizeLowLevel(
        reader: LASreader,
        header: Header,
        options: LidarImportOptions,
        progressTotal: Long,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val rasterizer = createRasterizer(header, options)
        var pointsInBatch = 0
        onProgress?.invoke(0L, progressTotal)

        while (reader.read_point()) {
            val point = reader.point
            rasterizer.shouldBinNextPoint()
            val x = point.getX() * header.scaleX + header.offsetX
            val y = point.getY() * header.scaleY + header.offsetY
            val z = (point.getZ() * header.scaleZ + header.offsetZ).toFloat()
            if (!rasterizer.addSampledPoint(
                    x = x,
                    y = y,
                    z = z,
                    classification = point.getClassification().toInt(),
                    isKeyPoint = point.getKeypoint_flag().toInt() == 1,
                )
            ) {
                break
            }

            pointsInBatch++
            if (pointsInBatch >= DECODE_BATCH_POINTS) {
                pointsInBatch = 0
                onProgress?.invoke(rasterizer.pointsDecoded, progressTotal)
                if (!shouldContinue()) return null
            }
        }
        return finishRasterizer(rasterizer, header, progressTotal, onProgress)
    }

    private fun rasterizeIterable(
        points: Iterable<LASPoint>,
        header: Header,
        options: LidarImportOptions,
        progressTotal: Long,
        shouldContinue: () -> Boolean,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        val rasterizer = createRasterizer(header, options)
        var pointsInBatch = 0
        onProgress?.invoke(0L, progressTotal)
        for (point in points) {
            rasterizer.shouldBinNextPoint()
            val x = point.getX() * header.scaleX + header.offsetX
            val y = point.getY() * header.scaleY + header.offsetY
            val z = (point.getZ() * header.scaleZ + header.offsetZ).toFloat()
            if (!rasterizer.addSampledPoint(
                    x = x,
                    y = y,
                    z = z,
                    classification = point.getClassification().toInt(),
                    isKeyPoint = point.isKeyPoint(),
                )
            ) {
                break
            }

            pointsInBatch++
            if (pointsInBatch >= DECODE_BATCH_POINTS) {
                pointsInBatch = 0
                onProgress?.invoke(rasterizer.pointsDecoded, progressTotal)
                if (!shouldContinue()) return null
            }
        }
        return finishRasterizer(rasterizer, header, progressTotal, onProgress)
    }

    private fun createRasterizer(header: Header, options: LidarImportOptions) = LidarRasterizer(
        minX = header.minX,
        maxX = header.maxX,
        minY = header.minY,
        maxY = header.maxY,
        options = options,
        declaredPointCount = header.pointCount,
    )

    private fun finishRasterizer(
        rasterizer: LidarRasterizer,
        header: Header,
        progressTotal: Long,
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)?,
    ): DemGenerator.LasLoadResult? {
        onProgress?.invoke(progressTotal, progressTotal)
        return rasterizer.finish(
            pointFormat = header.pointFormat,
            sourceLabel = "LAS/LAZ ${header.versionMajor}.${header.versionMinor} format ${header.pointFormat}",
        )
    }

    private fun readHeader(input: BufferedInputStream): Header? {
        input.mark(4_096)
        val bytes = ByteArray(375)
        var count = 0
        while (count < bytes.size) {
            val read = input.read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read > 0) count += read
        }
        input.reset()
        if (count < 227 || !bytes.copyOfRange(0, 4).contentEquals("LASF".toByteArray())) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val versionMajor = bytes[24].toInt() and 0xFF
        val versionMinor = bytes[25].toInt() and 0xFF
        val rawPointFormat = bytes[104].toInt() and 0xFF
        val pointFormat = rawPointFormat and 0x3F
        var pointCount = buffer.getInt(107).toLong() and 0xFFFFFFFFL
        if (versionMajor == 1 && versionMinor >= 4 && count >= 255) {
            buffer.getLong(247).takeIf { it > 0 }?.let { pointCount = it }
        }
        val header = Header(
            versionMajor = versionMajor,
            versionMinor = versionMinor,
            pointFormat = pointFormat,
            pointCount = pointCount,
            scaleX = buffer.getDouble(131),
            scaleY = buffer.getDouble(139),
            scaleZ = buffer.getDouble(147),
            offsetX = buffer.getDouble(155),
            offsetY = buffer.getDouble(163),
            offsetZ = buffer.getDouble(171),
            maxX = buffer.getDouble(179),
            minX = buffer.getDouble(187),
            maxY = buffer.getDouble(195),
            minY = buffer.getDouble(203),
        )
        return header.takeIf {
            listOf(
                it.scaleX,
                it.scaleY,
                it.scaleZ,
                it.offsetX,
                it.offsetY,
                it.offsetZ,
                it.maxX,
                it.minX,
                it.maxY,
                it.minY,
            ).all { value -> value.isFinite() } && it.maxX > it.minX && it.maxY > it.minY
        }
    }

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
