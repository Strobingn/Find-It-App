package com.example.data

import com.github.mreutegg.laszip4j.LASReader
import com.github.mreutegg.laszip4j.LASPoint
import java.io.File
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    private const val DECODE_BATCH_POINTS = 65_536

    fun read(
        file: File,
        options: LidarImportOptions,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: ((decodedPoints: Long, totalPoints: Long) -> Unit)? = null,
    ): DemGenerator.LasLoadResult? {
        return try {
            val reader = LASReader(file)
            val sourceHeader = reader.header
            val header = Header(
                versionMajor = sourceHeader.versionMajor.toInt() and 0xFF,
                versionMinor = sourceHeader.versionMinor.toInt() and 0xFF,
                pointFormat = sourceHeader.pointDataRecordFormat.toInt() and 0x3F,
                pointCount = sourceHeader.numberOfPointRecords.takeIf { it > 0 }
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
            val estimatedPoints = focus?.let {
                (header.pointCount * (it.right - it.left) * (it.bottom - it.top))
                    .toLong()
                    .coerceAtLeast(1L)
            } ?: header.pointCount
            constrained.getCloseablePoints().use { points ->
                rasterize(
                    points = points,
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
            rasterize(
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

    private fun rasterize(
        points: Iterable<LASPoint>,
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
            declaredPointCount = header.pointCount,
        )
        var pointsInBatch = 0
        onProgress?.invoke(0L, progressTotal)
        for (point in points) {
            val shouldBin = rasterizer.shouldBinNextPoint()
            pointsInBatch++

            if (shouldBin) {
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
            }

            if (pointsInBatch >= DECODE_BATCH_POINTS) {
                pointsInBatch = 0
                onProgress?.invoke(rasterizer.pointsDecoded, progressTotal)
                if (!shouldContinue()) return null
            }
        }
        onProgress?.invoke(progressTotal, progressTotal)
        return rasterizer.finish(
            pointFormat = header.pointFormat,
            sourceLabel = "LAZ ${header.versionMajor}.${header.versionMinor} format ${header.pointFormat}",
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
