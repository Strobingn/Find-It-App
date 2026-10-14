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
        require(pointCount >= 0 && pointCount.toLong() * LazRsNativeDecoder.RECORD_BYTES <= packed.capacity())
        if (!shouldContinue()) return false
        val buffer = packed.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
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
                isWithheld = flags and 0x02 != 0,
            )
        }
        return true
    }

    fun allocate(pointCount: Int): ByteBuffer {
        require(pointCount in 0..(Int.MAX_VALUE / LazRsNativeDecoder.RECORD_BYTES)) { "Packed point buffer exceeds JVM capacity" }
        return ByteBuffer.allocateDirect(pointCount * LazRsNativeDecoder.RECORD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
    }
}
