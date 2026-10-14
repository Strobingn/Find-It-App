package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bins one native chunk of packed records ([LazRsNativeDecoder.RECORD_BYTES]) into
 * [LidarRasterizer] without allocating a per-return object. Calls are made once per chunk,
 * in file order, so the grid matches the laszip4j chunk binner exactly.
 */
internal object PackedPointRasterizer {
    /**
     * Adds [pointCount] records. Pass [rect]/[stride] only when the buffer holds the whole
     * unfiltered chunk (native filtered nothing); they are then applied here with the laszip4j
     * rule: every return feeds [indexChunk] first, then half-open rect, then global stride.
     */
    fun addAll(
        rasterizer: LidarRasterizer,
        packed: ByteBuffer,
        pointCount: Int,
        firstPoint: Long = 0L,
        rect: DoubleArray? = null,
        stride: Int = 1,
        indexChunk: LazChunkIndex.Builder.Chunk? = null,
        groundFlags: IntArray? = null,
    ) {
        val b = packed.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.clear()
        for (i in 0 until pointCount) {
            val base = i * LazRsNativeDecoder.RECORD_BYTES
            val x = b.getDouble(base)
            val y = b.getDouble(base + 8)
            val cls = b.get(base + 20).toInt() and 0xFF
            val flags = b.get(base + 21).toInt()
            val key = flags and 0x01 != 0
            if (indexChunk != null) {
                val g = groundFlags ?: intArrayOf(1, 2, 0)
                indexChunk.add(x, y, isSourceGround(cls, key, g[0], g[1], g[2]))
            }
            if (rect != null && (x < rect[0] || x >= rect[2] || y < rect[1] || y >= rect[3])) continue
            if (stride > 1 && (firstPoint + i) % stride != 0L) continue
            rasterizer.addPoint(
                x = x,
                y = y,
                z = b.getFloat(base + 16),
                classification = cls,
                isKeyPoint = key,
                withheld = flags and 0x02 != 0,
            )
        }
        indexChunk?.commit()
    }
}
