package com.github.mreutegg.laszip4j.laslib

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.ArrayDeque
import com.github.mreutegg.laszip4j.laszip.ByteStreamInDataInput
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small package-level bridge to laszip4j's seekable RandomAccessFile decoder.
 *
 * laszip4j exposes the stream opener publicly, but its seekable overload is package-private.
 * LAZ chunk tables and internal spatial indexes may require seeking, so opening a compressed file
 * through a plain InputStream can fail at runtime even though the same file is valid.
 */
object SeekableLaszipReader {
    @JvmStatic
    fun open(file: File, selectiveFields: Int): LASreaderLAS? {
        val randomAccessFile = RandomAccessFile(file, "r")
        val reader = LASreaderLAS()
        return if (reader.open(randomAccessFile, false, selectiveFields)) {
            reader
        } else {
            runCatching { randomAccessFile.close() }
            null
        }
    }

    /**
     * Opens a remote COPC through an on-demand, persistent HTTP byte-range cache and resolves only
     * the octree chunks that intersect [focus]. The returned point offsets are LAS point indexes;
     * laszip4j uses the file's variable LAZ chunk table to seek to each independently compressed
     * COPC node.
     */
    @JvmStatic
    fun openHttpCopc(
        url: String,
        cacheFile: File,
        selectiveFields: Int,
        focus: NormalizedCopcBounds,
        client: OkHttpClient = OkHttpClient(),
    ): CopcSelection? {
        val randomAccessFile = HttpRangeRandomAccessFile(url, cacheFile, client)
        val ranges = try {
            CopcHierarchyReader(randomAccessFile).selectedPointRanges(focus)
        } catch (exception: Throwable) {
            runCatching { randomAccessFile.close() }
            throw exception
        }
        if (ranges.isEmpty()) {
            randomAccessFile.close()
            return null
        }
        randomAccessFile.seek(0L)
        val stream = HttpRangeByteStream(randomAccessFile)
        val reader = LASreaderLAS()
        return if (reader.open(stream, selectiveFields)) {
            CopcSelection(reader, ranges)
        } else {
            runCatching { stream.close() }
            null
        }
    }
}

data class NormalizedCopcBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0 && left < right)
        require(top in 0.0..1.0 && bottom in 0.0..1.0 && top < bottom)
    }
}

data class CopcPointRange(val firstPoint: Long, val pointCount: Int)

class CopcSelection internal constructor(
    val reader: LASreaderLAS,
    val pointRanges: List<CopcPointRange>,
) : AutoCloseable {
    val selectedPointCount: Long = pointRanges.sumOf { it.pointCount.toLong() }

    override fun close() = reader.close()
}

private data class CopcInfo(
    val centerX: Double,
    val centerY: Double,
    val halfSize: Double,
    val rootHierarchyOffset: Long,
    val rootHierarchySize: Int,
)

private data class CopcEntry(
    val level: Int,
    val x: Int,
    val y: Int,
    val z: Int,
    val offset: Long,
    val byteSize: Int,
    val pointCount: Int,
)

internal class CopcHierarchyReader(private val source: RandomAccessFile) {
    fun selectedPointRanges(focus: NormalizedCopcBounds): List<CopcPointRange> {
        val info = readInfo()
        val entries = readAllEntries(info)
        var firstPoint = 0L
        return buildList {
            entries.asSequence()
                .filter { it.pointCount > 0 }
                .sortedBy(CopcEntry::offset)
                .forEach { entry ->
                    if (intersects(entry, info, focus)) {
                        add(CopcPointRange(firstPoint, entry.pointCount))
                    }
                    firstPoint += entry.pointCount.toLong()
                }
        }
    }

    private fun readInfo(): CopcInfo {
        source.seek(COPC_VLR_OFFSET)
        val header = ByteArray(COPC_VLR_HEADER_BYTES)
        source.readFully(header)
        val userId = header.copyOfRange(2, 18).toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
        val recordId = header.uint16Le(18)
        val recordLength = header.uint16Le(20)
        if (userId != COPC_USER_ID || recordId != COPC_INFO_RECORD_ID || recordLength != COPC_INFO_BYTES) {
            throw IOException("Remote point cloud is not a supported COPC 1.0 file")
        }
        val payload = ByteArray(COPC_INFO_BYTES)
        source.readFully(payload)
        val centerX = payload.doubleLe(0)
        val centerY = payload.doubleLe(8)
        val halfSize = payload.doubleLe(24)
        val hierarchyOffset = payload.int64Le(40)
        val hierarchySize = payload.int64Le(48)
        if (!centerX.isFinite() || !centerY.isFinite() || !halfSize.isFinite() || halfSize <= 0.0 ||
            hierarchyOffset <= 0L || hierarchySize <= 0L || hierarchySize > MAX_HIERARCHY_PAGE_BYTES
        ) {
            throw IOException("COPC info VLR contains invalid hierarchy metadata")
        }
        return CopcInfo(centerX, centerY, halfSize, hierarchyOffset, hierarchySize.toInt())
    }

    private fun readAllEntries(info: CopcInfo): List<CopcEntry> {
        val pages = ArrayDeque<Pair<Long, Int>>()
        val visited = mutableSetOf<Pair<Long, Int>>()
        val result = mutableListOf<CopcEntry>()
        pages.add(info.rootHierarchyOffset to info.rootHierarchySize)
        var hierarchyBytes = 0L
        while (pages.isNotEmpty()) {
            val page = pages.removeFirst()
            if (!visited.add(page)) continue
            val (offset, size) = page
            if (offset <= 0L || size <= 0 || size % COPC_ENTRY_BYTES != 0 ||
                size > MAX_HIERARCHY_PAGE_BYTES || hierarchyBytes + size > MAX_TOTAL_HIERARCHY_BYTES
            ) {
                throw IOException("COPC hierarchy page is invalid or unreasonably large")
            }
            hierarchyBytes += size
            source.seek(offset)
            val bytes = ByteArray(size)
            source.readFully(bytes)
            for (index in bytes.indices step COPC_ENTRY_BYTES) {
                val entry = CopcEntry(
                    level = bytes.int32Le(index),
                    x = bytes.int32Le(index + 4),
                    y = bytes.int32Le(index + 8),
                    z = bytes.int32Le(index + 12),
                    offset = bytes.int64Le(index + 16),
                    byteSize = bytes.int32Le(index + 24),
                    pointCount = bytes.int32Le(index + 28),
                )
                if (entry.level < 0 || entry.x < 0 || entry.y < 0 || entry.z < 0) continue
                when {
                    entry.pointCount > 0 && entry.offset > 0L && entry.byteSize > 0 -> result += entry
                    entry.pointCount == -1 -> pages.add(entry.offset to entry.byteSize)
                    entry.pointCount < -1 -> throw IOException("COPC hierarchy has an invalid point count")
                }
            }
        }
        return result
    }

    private fun intersects(
        entry: CopcEntry,
        info: CopcInfo,
        focus: NormalizedCopcBounds,
    ): Boolean {
        if (entry.level > MAX_OCTREE_LEVEL) return false
        val rootMinX = info.centerX - info.halfSize
        val rootMinY = info.centerY - info.halfSize
        val rootSize = info.halfSize * 2.0
        val divisions = 1L shl entry.level
        if (entry.x.toLong() >= divisions || entry.y.toLong() >= divisions) return false
        val nodeSize = rootSize / divisions.toDouble()
        val nodeMinX = rootMinX + entry.x * nodeSize
        val nodeMaxX = nodeMinX + nodeSize
        val nodeMinY = rootMinY + entry.y * nodeSize
        val nodeMaxY = nodeMinY + nodeSize
        val cropMinX = rootMinX + focus.left * rootSize
        val cropMaxX = rootMinX + focus.right * rootSize
        val cropMinY = rootMinY + (1.0 - focus.bottom) * rootSize
        val cropMaxY = rootMinY + (1.0 - focus.top) * rootSize
        return nodeMaxX >= cropMinX && nodeMinX <= cropMaxX &&
            nodeMaxY >= cropMinY && nodeMinY <= cropMaxY
    }

    companion object {
        private const val COPC_VLR_OFFSET = 375L
        private const val COPC_VLR_HEADER_BYTES = 54
        private const val COPC_INFO_BYTES = 160
        private const val COPC_ENTRY_BYTES = 32
        private const val COPC_USER_ID = "copc"
        private const val COPC_INFO_RECORD_ID = 1
        private const val MAX_OCTREE_LEVEL = 30
        private const val MAX_HIERARCHY_PAGE_BYTES = 64L * 1024L * 1024L
        private const val MAX_TOTAL_HIERARCHY_BYTES = 256L * 1024L * 1024L
    }
}

/** RandomAccessFile facade that fetches missing fixed-size blocks with HTTP Range requests. */
private class HttpRangeRandomAccessFile(
    private val url: String,
    cacheFile: File,
    private val client: OkHttpClient,
) : RandomAccessFile(File(rangeCacheParent(cacheFile), ".${cacheFile.name}.handle"), "rw") {
    private val cache = RandomAccessFile(cacheFile, "rw")
    private val blockMapFile = File(rangeCacheParent(cacheFile), "${cacheFile.name}.blocks")
    private val present = BitSet.valueOf(
        blockMapFile.takeIf(File::isFile)?.readBytes() ?: byteArrayOf(),
    )
    private val remoteLength = discoverLength()
    private var position = 0L

    init {
        if (cache.length() != remoteLength) {
            cache.setLength(remoteLength)
            present.clear()
            blockMapFile.delete()
        }
    }

    override fun length(): Long = remoteLength

    override fun getFilePointer(): Long = position

    override fun seek(pos: Long) {
        require(pos >= 0L) { "Negative seek" }
        position = pos
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0
        if (position >= remoteLength) return -1
        val count = minOf(length.toLong(), remoteLength - position).toInt()
        ensureCached(position, count)
        cache.seek(position)
        val read = cache.read(buffer, offset, count)
        if (read > 0) position += read
        return read
    }

    @Synchronized
    private fun ensureCached(start: Long, byteCount: Int) {
        val first = (start / BLOCK_BYTES).toInt()
        val last = ((start + byteCount - 1L) / BLOCK_BYTES).toInt()
        for (block in first..last) {
            if (!present[block]) fetchBlock(block)
        }
    }

    private fun fetchBlock(block: Int) {
        val start = block.toLong() * BLOCK_BYTES
        val end = minOf(remoteLength - 1L, start + BLOCK_BYTES - 1L)
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code != 206) {
                throw IOException("COPC server did not honor byte range $start-$end")
            }
            val returnedRange = response.header("Content-Range")?.substringBefore('/')
            if (returnedRange != "bytes $start-$end") {
                throw IOException("COPC server returned the wrong byte range")
            }
            val body = response.body ?: throw IOException("Empty COPC range response")
            cache.seek(start)
            body.byteStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                var remaining = end - start + 1L
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Short COPC range response")
                    cache.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        present.set(block)
        blockMapFile.writeBytes(present.toByteArray())
    }

    private fun discoverLength(): Long {
        val head = Request.Builder().url(url).head().build()
        client.newCall(head).execute().use { response ->
            response.header("Content-Length")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        val range = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        client.newCall(range).execute().use { response ->
            return response.header("Content-Range")
                ?.substringAfter('/')
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: throw IOException("COPC server did not report file size")
        }
    }

    override fun close() {
        runCatching { cache.close() }
        runCatching { super.close() }
    }

    companion object {
        private const val BLOCK_BYTES = 1024L * 1024L
    }
}

/** Avoids laszip4j's memory-mapped RandomAccessFile adapter, which cannot map a remote facade. */
private class HttpRangeByteStream(
    private val source: HttpRangeRandomAccessFile,
) : ByteStreamInDataInput(source) {
    override fun isSeekable(): Boolean = true

    override fun tell(): Long = source.filePointer

    override fun seek(position: Long): Boolean = runCatching {
        source.seek(position)
        true
    }.getOrDefault(false)

    override fun seekEnd(distance: Long): Boolean {
        if (distance < 0L || distance > source.length()) return false
        return seek(source.length() - distance)
    }

    override fun close() = source.close()
}

private fun rangeCacheParent(cacheFile: File): File =
    requireNotNull(cacheFile.absoluteFile.parentFile) { "COPC range cache needs a parent directory" }
        .apply { mkdirs() }

private fun ByteArray.uint16Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.int32Le(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        (this[offset + 3].toInt() shl 24)

private fun ByteArray.int64Le(offset: Int): Long =
    (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24) or
        ((this[offset + 4].toLong() and 0xFFL) shl 32) or
        ((this[offset + 5].toLong() and 0xFFL) shl 40) or
        ((this[offset + 6].toLong() and 0xFFL) shl 48) or
        (this[offset + 7].toLong() shl 56)

private fun ByteArray.doubleLe(offset: Int): Double =
    Double.fromBits(int64Le(offset))
