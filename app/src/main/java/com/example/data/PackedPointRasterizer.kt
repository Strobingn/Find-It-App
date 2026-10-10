package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bins packed xyz/class records into [LidarRasterizer] without allocating a per-return object.
 * Used by the laz-rs JNI path and by the mapped uncompressed-LAS reader.
 */
internal object PackedPointRasterizer {
    fun addAll(
        rasterizer: LidarRasterizer,
        packed: ByteBuffer,
        pointCount: Int,
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        val buffer = packed.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        buffer.clear()
        repeat(pointCount) { index ->
            if (index and 32_767 == 32_767 && !shouldContinue()) {
                rasterizer.markTruncated()
                return false
            }
            val x = buffer.float.toDouble()
            val y = buffer.float.toDouble()
            val z = buffer.float
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

    fun allocate(pointCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(pointCount.coerceAtLeast(0) * LazRsNativeDecoder.RECORD_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
}
