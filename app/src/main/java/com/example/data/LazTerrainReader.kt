package com.example.data

import com.github.mreutegg.laszip4j.LASReader
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pure-Java LAZ decoding backed by laszip4j; all rasterization remains memory bounded. */
internal object LazTerrainReader {
    fun read(
        inputStream: InputStream,
        options: LidarImportOptions,
    ): DemGenerator.LasLoadResult? {
        return try {
            val buffered = if (inputStream is BufferedInputStream) {
                inputStream
            } else {
                BufferedInputStream(inputStream, 512 * 1024)
            }
            val header = readHeader(buffered) ?: return null
            val safeOptions = options.sanitized()
            val rasterizer = LidarRasterizer(
                minX = header.minX,
                maxX = header.maxX,
                minY = header.minY,
                maxY = header.maxY,
                options = safeOptions,
                declaredPointCount = header.pointCount,
            )

            // LAZ is a sequential compressed stream, so every point still has to be decompressed.
            // For a detailed zoomed viewport, however, most returns are outside the requested crop.
            // Reject those returns after reading only X/Y and avoid the more expensive Z,
            // classification and key-point extraction for the overwhelming majority of points.
            val focus = safeOptions.focusBounds
            val sourceRangeX = header.maxX - header.minX
            val sourceRangeY = header.maxY - header.minY
            val cropMinX = focus?.let { header.minX + it.left * sourceRangeX } ?: header.minX
            val cropMaxX = focus?.let { header.minX + it.right * sourceRangeX } ?: header.maxX
            val cropMinY = focus?.let { header.minY + (1.0 - it.bottom) * sourceRangeY } ?: header.minY
            val cropMaxY = focus?.let { header.minY + (1.0 - it.top) * sourceRangeY } ?: header.maxY
            val focusedRead = focus != null

            for (point in LASReader.getPoints(buffered)) {
                val x = point.getX() * header.scaleX + header.offsetX
                val y = point.getY() * header.scaleY + header.offsetY

                if (focusedRead && (x < cropMinX || x > cropMaxX || y < cropMinY || y > cropMaxY)) {
                    // addPoint records the decoded return, then exits immediately on the crop test.
                    // A finite placeholder Z avoids touching compressed attributes we do not need.
                    rasterizer.addPoint(
                        x = x,
                        y = y,
                        z = 0f,
                        classification = 0,
                        isKeyPoint = false,
                    )
                    continue
                }

                val z = (point.getZ() * header.scaleZ + header.offsetZ).toFloat()
                if (!rasterizer.addPoint(
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
            rasterizer.finish(
                pointFormat = header.pointFormat,
                sourceLabel = "LAZ ${header.versionMajor}.${header.versionMinor} format ${header.pointFormat}",
            )
        } catch (exception: Exception) {
            exception.printStackTrace()
            null
        }
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
