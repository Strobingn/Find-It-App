package com.example.data

import com.github.mreutegg.laszip4j.laslib.SeekableLaszipReader
import com.github.mreutegg.laszip4j.laszip.LASzip
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import java.util.zip.CheckedOutputStream

/** Conservative compressed-chunk bounds, collected during import; never samples returns. */
internal class LazChunkIndex private constructor(
    val pointCount: Long, val chunkSize: Int,
    val minX: Double, val maxX: Double, val minY: Double, val maxY: Double,
    private val bounds: DoubleArray,
    private val all: LongArray, private val ground: LongArray,
) {
    val chunkCount: Int get() = bounds.size / 4

    /** Inclusive node-grid edges: touching chunks must also be decoded. */
    fun chunksOverlapping(x0: Double, y0: Double, x1: Double, y1: Double): IntArray =
        (0 until chunkCount).filter { c ->
            val i = c * 4
            bounds[i + 1] >= x0 && bounds[i] <= x1 && bounds[i + 3] >= y0 && bounds[i + 2] <= y1
        }.toIntArray()

    fun estimate(x0: Double, y0: Double, x1: Double, y1: Double, classified: Boolean): Double {
        val hist = if (classified) ground else all
        val cw = (maxX - minX) / HIST
        val ch = (maxY - minY) / HIST
        var sum = 0.0
        for (y in ((y0 - minY) / ch).toInt().coerceIn(0, HIST - 1)..((y1 - minY) / ch).toInt().coerceIn(0, HIST - 1)) {
            val fy = ((minOf(y1, minY + (y + 1) * ch) - maxOf(y0, minY + y * ch)) / ch).coerceIn(0.0, 1.0)
            for (x in ((x0 - minX) / cw).toInt().coerceIn(0, HIST - 1)..((x1 - minX) / cw).toInt().coerceIn(0, HIST - 1)) {
                val fx = ((minOf(x1, minX + (x + 1) * cw) - maxOf(x0, minX + x * cw)) / cw).coerceIn(0.0, 1.0)
                sum += hist[y * HIST + x] * fx * fy
            }
        }
        return sum
    }

    /** Thread confined to the ordered binner, so no per-point locks or staging arrays. */
    class Builder(
        private val points: Long, private val chunkSize: Int,
        private val minX: Double, private val maxX: Double,
        private val minY: Double, private val maxY: Double,
        private val pointFormat: Int,
    ) {
        private val chunks = ((points - 1) / chunkSize + 1).toInt()
        private val bounds = DoubleArray(chunks * 4) { if (it % 2 == 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY }
        private val all = LongArray(HIST * HIST)
        private val ground = LongArray(HIST * HIST)
        private var recorded = 0L

        fun add(x: Double, y: Double, classification: Int, withheld: Boolean) {
            val i = (recorded / chunkSize).toInt() * 4
            recorded++
            if (!x.isFinite() || !y.isFinite()) return
            bounds[i] = minOf(bounds[i], x); bounds[i + 1] = maxOf(bounds[i + 1], x)
            bounds[i + 2] = minOf(bounds[i + 2], y); bounds[i + 3] = maxOf(bounds[i + 3], y)
            if (withheld || classification == 7 || classification == 18) return
            val gx = ((x - minX) * HIST / (maxX - minX)).toInt().coerceIn(0, HIST - 1)
            val gy = ((y - minY) * HIST / (maxY - minY)).toInt().coerceIn(0, HIST - 1)
            val cell = gy * HIST + gx
            all[cell]++
            if (isSourceGround(classification, pointFormat = pointFormat)) ground[cell]++
        }

        fun finish(): LazChunkIndex? = if (recorded != points) null else
            LazChunkIndex(points, chunkSize, minX, maxX, minY, maxY, bounds, all, ground)
    }

    companion object {
        private const val HIST = 256
        private const val MAGIC = 0x46494348
        private const val VERSION = 2
        private const val MAX_CHUNKS = 100_000 // <=3.2 MB bounds, plus 1 MB density histogram
        private val memory = object : LinkedHashMap<String, LazChunkIndex>(4, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LazChunkIndex>?) = size > 4
        }
        private val locks = Array(16) { ReentrantLock() }
        private val pending = ConcurrentHashMap.newKeySet<String>()
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "laz-chunk-index").apply { isDaemon = true } }
        @Volatile var fallbackDir: File? = null
        private fun key(file: File) = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        private fun sidecars(file: File) = listOfNotNull(File(file.path + ".fichunks"), fallbackDir?.let {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key(file).toByteArray()).joinToString("") { b -> "%02x".format(b) }
            File(it, "$hash.fichunks")
        })
        fun supported(points: Long, chunkSize: Int, minX: Double, maxX: Double, minY: Double, maxY: Double) =
            points > 0 && chunkSize in 1..5_000_000 && (points - 1) / chunkSize < MAX_CHUNKS &&
                minX.isFinite() && maxX.isFinite() && minY.isFinite() && maxY.isFinite() && maxX > minX && maxY > minY

        fun load(file: File, points: Long, chunkSize: Int): LazChunkIndex? {
            val key = key(file)
            synchronized(memory) { memory[key]?.takeIf { it.pointCount == points && it.chunkSize == chunkSize }?.let { return it } }
            for (side in sidecars(file)) {
                val index = runCatching { read(side, file, points, chunkSize) }.getOrNull() ?: continue
                synchronized(memory) { memory[key] = index }
                return index
            }
            return null
        }

        fun store(file: File, index: LazChunkIndex) {
            synchronized(memory) { memory[key(file)] = index }
            for (side in sidecars(file)) {
                var temp: File? = null
                val success = runCatching {
                    side.parentFile?.mkdirs()
                    temp = File.createTempFile("fichunks-", ".tmp", side.parentFile)
                    val crc = CRC32()
                    DataOutputStream(CheckedOutputStream(temp!!.outputStream().buffered(), crc)).use { out ->
                        out.writeInt(MAGIC); out.writeInt(VERSION)
                        out.writeLong(file.length()); out.writeLong(file.lastModified())
                        out.writeLong(index.pointCount); out.writeInt(index.chunkSize)
                        out.writeDouble(index.minX); out.writeDouble(index.maxX)
                        out.writeDouble(index.minY); out.writeDouble(index.maxY)
                        out.writeInt(index.chunkCount)
                        index.bounds.forEach(out::writeDouble)
                        index.all.forEach(out::writeLong); index.ground.forEach(out::writeLong)
                        val checksum = crc.value
                        out.writeLong(checksum)
                    }
                    temp.renameTo(side)
                }.getOrDefault(false)
                temp?.delete()
                if (success) return
            }
        }
        internal fun clearMemory() = synchronized(memory) { memory.clear() }
        private fun read(side: File, source: File, expectedPoints: Long, expectedChunkSize: Int): LazChunkIndex? {
            if (!side.isFile) return null
            val crc = CRC32()
            DataInputStream(CheckedInputStream(side.inputStream().buffered(), crc)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return null
                if (input.readLong() != source.length() || input.readLong() != source.lastModified()) return null
                val points = input.readLong(); val chunk = input.readInt()
                if (points != expectedPoints || chunk != expectedChunkSize) return null
                val x0 = input.readDouble(); val x1 = input.readDouble(); val y0 = input.readDouble(); val y1 = input.readDouble()
                if (!supported(points, chunk, x0, x1, y0, y1)) return null
                val n = input.readInt()
                if (n.toLong() != (points - 1) / chunk + 1 || side.length() != 80L + n * 32L + HIST * HIST * 16L) return null
                val bounds = DoubleArray(n * 4) { input.readDouble() }
                for (i in 0 until n) {
                    val j = i * 4
                    val empty = bounds[j] == Double.POSITIVE_INFINITY && bounds[j + 1] == Double.NEGATIVE_INFINITY &&
                        bounds[j + 2] == Double.POSITIVE_INFINITY && bounds[j + 3] == Double.NEGATIVE_INFINITY
                    if (!empty && ((j..j + 3).any { !bounds[it].isFinite() } || bounds[j] > bounds[j + 1] || bounds[j + 2] > bounds[j + 3])) return null
                }
                val all = LongArray(HIST * HIST) { input.readLong() }
                val ground = LongArray(HIST * HIST) { input.readLong() }
                if (all.indices.any { all[it] < 0 || all[it] > points || ground[it] < 0 || ground[it] > all[it] }) return null
                val checksum = crc.value
                if (input.readLong() != checksum || input.read() != -1) return null
                return LazChunkIndex(points, chunk, x0, x1, y0, y1, bounds, all, ground)
            }
        }

        fun ensureBuiltAsync(file: File) {
            if (!file.isFile || !pending.add(file.absolutePath)) return
            executor.execute {
                try { runCatching { ensure(file) } } finally { pending.remove(file.absolutePath) }
            }
        }

        /** Old cached imports need one lightweight pass; complete new imports already have it. */
        fun ensure(file: File, shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
                   onProgress: (Long, Long) -> Unit = { _, _ -> }): LazChunkIndex? {
            val lock = locks[(file.absolutePath.hashCode() and Int.MAX_VALUE) % locks.size]
            while (shouldContinue()) {
                if (!lock.tryLock(100, TimeUnit.MILLISECONDS)) continue
                try {
                    val fields = LASzip.LASZIP_DECOMPRESS_SELECTIVE_CLASSIFICATION or LASzip.LASZIP_DECOMPRESS_SELECTIVE_FLAGS
                    return SeekableLaszipReader.open(file, fields)?.use { reader ->
                        val h = reader.header
                        val points = h.extended_number_of_point_records.takeIf { it > 0 } ?: (h.number_of_point_records.toLong() and 0xffffffffL)
                        val chunk = h.laszip?.chunk_size?.takeIf { it in 1..5_000_000 } ?: 50_000
                        load(file, points, chunk)?.let { return@use it }
                        if (!supported(points, chunk, h.min_x, h.max_x, h.min_y, h.max_y)) return@use null
                        val identity = key(file)
                        val builder = Builder(points, chunk, h.min_x, h.max_x, h.min_y, h.max_y, h.point_data_format.toInt() and 0x3f)
                        var n = 0L
                        onProgress(0, points)
                        while (n < points) {
                            if (n % 8192 == 0L && !shouldContinue()) return@use null
                            if (!reader.read_point()) return@use null
                            builder.add(reader.get_x(), reader.get_y(), reader.point.get_classification().toInt() and 0xff, reader.point.get_withheld_flag().toInt() != 0)
                            n++
                            if (n % chunk == 0L) onProgress(n, points)
                        }
                        if (!shouldContinue() || identity != key(file)) return@use null
                        builder.finish()?.also { store(file, it); onProgress(points, points) }
                    }
                } finally { lock.unlock() }
            }
            return null
        }
    }
}
