package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bins one native chunk of packed records ([LazRsNativeDecoder.RECORD_BYTES]) into
 * [LidarRasterizer] without allocating a per-return object. Calls are made once per chunk, in
 * file order, so the grid matches the laszip4j chunk binner exactly. Coordinates are the exact
 * doubles laszip4j's get_x()/get_y() return (integer × scale + offset), never origin-relative f32.
 */
internal object PackedPointRasterizer {
    /**
     * Adds [pointCount] records. Pass [rect] only when the buffer holds the whole unfiltered chunk
     * (native filtered nothing, e.g. while building the chunk index); it is then applied here
     * with the laszip4j rule: every return feeds [indexChunk] first, then the half-open rect.
     */
    fun addAll(
        rasterizer: LidarRasterizer,
        packed: ByteBuffer,
        pointCount: Int,
        rect: DoubleArray? = null,
        indexChunk: LazChunkIndex.Builder.Chunk? = null,
    ) {
        val b = packed.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        b.clear()
        for (i in 0 until pointCount) {
            val base = i * LazRsNativeDecoder.RECORD_BYTES
            val x = b.getDouble(base)
            val y = b.getDouble(base + 8)
            val cls = b.get(base + 20).toInt() and 0xFF
            indexChunk?.add(x, y, cls == 2)
            if (rect != null && (x < rect[0] || x >= rect[2] || y < rect[1] || y >= rect[3])) continue
            rasterizer.addPoint(
                x = x,
                y = y,
                z = b.getFloat(base + 16),
                classification = cls,
                isKeyPoint = b.get(base + 21).toInt() and 0x01 != 0,
            )
        }
        indexChunk?.commit()
    }
}
