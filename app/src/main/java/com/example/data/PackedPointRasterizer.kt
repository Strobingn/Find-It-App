package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bins packed xyz/class records into [LidarRasterizer] without allocating a per-return object.
 * Packed XY/Z are origin-relative f32; add [originX]/[originY]/[originZ] back as doubles.
 */
internal object PackedPointRasterizer {
    fun addAll(
        rasterizer: LidarRasterizer,
        packed: ByteBuffer,
        pointCount: Int,
        shouldContinue: () -> Boolean = { true },
        originX: Double = 0.0,
        originY: Double = 0.0,
        originZ: Double = 0.0,
    ): Boolean {
        val buffer = packed.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        if (!rasterizer.acceptsFastBin) {
            repeat(pointCount) { index ->
                if (index and 32_767 == 32_767 && !shouldContinue()) {
                    rasterizer.markTruncated()
                    return false
                }
                val x = buffer.float.toDouble() + originX
                val y = buffer.float.toDouble() + originY
                val z = (buffer.float.toDouble() + originZ).toFloat()
                val classification = buffer.get().toInt() and 0xFF
                val flags = buffer.get().toInt() and 0xFF
                buffer.short
                rasterizer.addPoint(
                    x = x,
                    y = y,
                    z = z,
                    classification = classification,
                    isKeyPoint = flags and 0x01 != 0,
                )
            }
            return true
        }
        val window = 16_384
        val xs = DoubleArray(window)
        val ys = DoubleArray(window)
        val zs = FloatArray(window)
        var filled = 0
        repeat(pointCount) { index ->
            if (index and 32_767 == 32_767 && !shouldContinue()) {
                if (filled > 0) rasterizer.addUnclassifiedSamples(xs, ys, zs, filled)
                rasterizer.markTruncated()
                return false
            }
            xs[filled] = buffer.float.toDouble() + originX
            ys[filled] = buffer.float.toDouble() + originY
            zs[filled] = (buffer.float.toDouble() + originZ).toFloat()
            buffer.short
            buffer.short
            filled++
            if (filled == window) {
                rasterizer.addUnclassifiedSamples(xs, ys, zs, filled)
                filled = 0
            }
        }
        if (filled > 0) rasterizer.addUnclassifiedSamples(xs, ys, zs, filled)
        return true
    }

    fun allocate(pointCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(pointCount.coerceAtLeast(0) * LazRsNativeDecoder.RECORD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
}
