package com.example.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-chunk XY bounds of a local LAZ/LAS file plus a coarse point-density histogram, recorded
 * once while the full file is decoded and persisted next to it (or in the cache dir when that
 * folder is read-only). A zoomed refine then decodes only the chunks whose bounds touch the
 * viewport and sizes its grid from the histogram up front, instead of re-reading the whole
 * file (and then re-reading it again at a corrected resolution).
 *
 * Memory: 32 B per chunk (630 chunks on the 31.5M-point NY tile) + 2 × 256² ints = ~0.5 MB.
 */
internal class LazChunkIndex(
    val pointCount: Long,
    val chunkSize: Int,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val chunkMinX: DoubleArray,
    val chunkMaxX: DoubleArray,
    val chunkMinY: DoubleArray,
    val chunkMaxY: DoubleArray,
    /** Row-major [HIST]×[HIST] counts over the header bbox, row 0 = minY. */
    val allHist: IntArray,
    val groundHist: IntArray,
) {
    val chunkCount: Int get() = chunkMinX.size

    /** Chunks whose bounds intersect [x0,x1)×[y0,y1). */
    fun chunksOverlapping(x0: Double, y0: Double, x1: Double, y1: Double): IntArray {
        val out = IntArray(chunkCount)
        var n = 0
        for (c in 0 until chunkCount) {
            if (chunkMaxX[c] >= x0 && chunkMinX[c] < x1 && chunkMaxY[c] >= y0 && chunkMinY[c] < y1) out[n++] = c
        }
        return out.copyOf(n)
    }

    /** Estimated points in a rectangle from the histogram (area-weighted partial cells). */
    fun estimate(x0: Double, y0: Double, x1: Double, y1: Double, ground: Boolean): Double {
        val hist = if (ground) groundHist else allHist
        val cw = (maxX - minX) / HIST
        val ch = (maxY - minY) / HIST
        if (!(cw > 0.0) || !(ch > 0.0)) return 0.0
        val gx0 = ((x0 - minX) / cw).toInt().coerceIn(0, HIST - 1)
        val gx1 = ((x1 - minX) / cw).toInt().coerceIn(0, HIST - 1)
        val gy0 = ((y0 - minY) / ch).toInt().coerceIn(0, HIST - 1)
        val gy1 = ((y1 - minY) / ch).toInt().coerceIn(0, HIST - 1)
        var sum = 0.0
        for (gy in gy0..gy1) {
            val cy0 = minY + gy * ch
            val fy = ((minOf(y1, cy0 + ch) - maxOf(y0, cy0)) / ch).coerceIn(0.0, 1.0)
            if (fy == 0.0) continue
            for (gx in gx0..gx1) {
                val cx0 = minX + gx * cw
                val fx = ((minOf(x1, cx0 + cw) - maxOf(x0, cx0)) / cw).coerceIn(0.0, 1.0)
                sum += hist[gy * HIST + gx] * fx * fy
            }
        }
        return sum
    }

    /** Accumulates bounds while chunks are decoded (any thread, each chunk written once). */
    class Builder(
        private val pointCount: Long,
        private val chunkSize: Int,
        private val minX: Double,
        private val maxX: Double,
        private val minY: Double,
        private val maxY: Double,
        chunks: Int,
    ) {
        private val cMinX = DoubleArray(chunks) { Double.POSITIVE_INFINITY }
        private val cMaxX = DoubleArray(chunks) { Double.NEGATIVE_INFINITY }
        private val cMinY = DoubleArray(chunks) { Double.POSITIVE_INFINITY }
        private val cMaxY = DoubleArray(chunks) { Double.NEGATIVE_INFINITY }
        private val done = BooleanArray(chunks)
        private val all = IntArray(HIST * HIST)
        private val ground = IntArray(HIST * HIST)
        private val sx = HIST / (maxX - minX).coerceAtLeast(1e-9)
        private val sy = HIST / (maxY - minY).coerceAtLeast(1e-9)

        /** Thread-confined per-chunk accumulator; [commit] merges it. */
        inner class Chunk(private val chunk: Int) {
            var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY
            var y0 = Double.POSITIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
            private val cells = IntArray(chunkSize.coerceAtMost(1 shl 22))
            private val isGround = BooleanArray(cells.size)
            private var n = 0
            fun add(x: Double, y: Double, sourceGround: Boolean) {
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                if (n < cells.size && x.isFinite() && y.isFinite()) {
                    val gx = ((x - minX) * sx).toInt().coerceIn(0, HIST - 1)
                    val gy = ((y - minY) * sy).toInt().coerceIn(0, HIST - 1)
                    cells[n] = gy * HIST + gx; isGround[n] = sourceGround; n++
                }
            }
            fun commit() = synchronized(this@Builder) {
                cMinX[chunk] = x0; cMaxX[chunk] = x1; cMinY[chunk] = y0; cMaxY[chunk] = y1
                done[chunk] = true
                for (i in 0 until n) { all[cells[i]]++; if (isGround[i]) ground[cells[i]]++ }
            }
        }

        /** Null unless every chunk was recorded (a cancelled decode must not persist holes). */
        fun build(): LazChunkIndex? = synchronized(this) {
            if (!done.all { it }) return null
            LazChunkIndex(pointCount, chunkSize, minX, maxX, minY, maxY, cMinX, cMaxX, cMinY, cMaxY, all, ground)
        }
    }

    companion object {
        const val HIST = 256
        private const val MAGIC = 0x46494348 // "FICH"
        private const val VERSION = 1
        private val memory = ConcurrentHashMap<String, LazChunkIndex>()

        /** Fallback directory when the LAZ folder is not writable (set by the app at start-up). */
        @Volatile var fallbackDir: File? = null

        private fun key(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        private fun sidecars(file: File): List<File> = listOfNotNull(
            File(file.path + ".fichunks"),
            fallbackDir?.let { File(it, "${file.name}.${key(file).hashCode().toUInt().toString(16)}.fichunks") },
        )

        fun load(file: File, pointCount: Long, chunkSize: Int): LazChunkIndex? {
            memory[key(file)]?.takeIf { it.pointCount == pointCount && it.chunkSize == chunkSize }?.let { return it }
            for (side in sidecars(file)) {
                val loaded = runCatching { read(side, file) }.getOrNull() ?: continue
                if (loaded.pointCount != pointCount || loaded.chunkSize != chunkSize) continue
                memory[key(file)] = loaded
                return loaded
            }
            return null
        }

        fun store(file: File, index: LazChunkIndex) {
            memory[key(file)] = index
            for (side in sidecars(file)) {
                val ok = runCatching {
                    val tmp = File(side.path + ".tmp")
                    DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { out ->
                        out.writeInt(MAGIC); out.writeInt(VERSION)
                        out.writeLong(file.length()); out.writeLong(file.lastModified())
                        out.writeLong(index.pointCount); out.writeInt(index.chunkSize)
                        out.writeDouble(index.minX); out.writeDouble(index.maxX)
                        out.writeDouble(index.minY); out.writeDouble(index.maxY)
                        out.writeInt(index.chunkCount)
                        for (c in 0 until index.chunkCount) {
                            out.writeDouble(index.chunkMinX[c]); out.writeDouble(index.chunkMaxX[c])
                            out.writeDouble(index.chunkMinY[c]); out.writeDouble(index.chunkMaxY[c])
                        }
                        for (v in index.allHist) out.writeInt(v)
                        for (v in index.groundHist) out.writeInt(v)
                    }
                    tmp.renameTo(side) || run { tmp.delete(); false }
                }.getOrDefault(false)
                if (ok) return
            }
        }

        /** Test hook: forget in-memory copies (sidecars stay). */
        internal fun clearMemory() = memory.clear()

        private fun read(side: File, source: File): LazChunkIndex? {
            if (!side.isFile) return null
            DataInputStream(side.inputStream().buffered(1 shl 16)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                if (input.readLong() != source.length() || input.readLong() != source.lastModified()) return null
                val points = input.readLong(); val chunkSize = input.readInt()
                val minX = input.readDouble(); val maxX = input.readDouble()
                val minY = input.readDouble(); val maxY = input.readDouble()
                val n = input.readInt()
                if (n !in 1..10_000_000) return null
                val a = DoubleArray(n); val b = DoubleArray(n); val c = DoubleArray(n); val d = DoubleArray(n)
                for (i in 0 until n) { a[i] = input.readDouble(); b[i] = input.readDouble(); c[i] = input.readDouble(); d[i] = input.readDouble() }
                val all = IntArray(HIST * HIST) { input.readInt() }
                val ground = IntArray(HIST * HIST) { input.readInt() }
                return LazChunkIndex(points, chunkSize, minX, maxX, minY, maxY, a, b, c, d, all, ground)
            }
        }
    }
}
