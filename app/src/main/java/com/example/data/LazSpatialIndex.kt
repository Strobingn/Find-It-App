package com.example.data

import com.github.mreutegg.laszip4j.laslib.LASreaderLAS
import com.github.mreutegg.laszip4j.laslib.SeekableLaszipReader
import com.github.mreutegg.laszip4j.laszip.LASindex
import com.github.mreutegg.laszip4j.laszip.LASquadtree
import com.github.mreutegg.laszip4j.laszip.LASzip.LASZIP_DECOMPRESS_SELECTIVE_CHANNEL_RETURNS_XY
import com.github.mreutegg.laszip4j.laszip.LasIndexWriter
import java.io.File
import java.util.concurrent.Executors

/**
 * Builds and loads the `.lax` spatial index beside a LAZ/LAS file.
 *
 * Refining to a zoomed viewport asks for a small rectangle out of a file that may hold hundreds of
 * millions of returns. Without an index the decoder has to decompress every point just to discover
 * that almost all of them fall outside the rectangle. The index records which compressed chunks
 * cover which ground area, so the reader can seek straight past the ones that cannot contribute -
 * the same `.lax` sidecar convention LAStools uses, so an index shipped alongside a tile is picked
 * up as-is.
 *
 * Building costs one pass, but that pass decompresses only X and Y, which is far cheaper than a
 * full decode, and it happens once per file rather than once per zoom.
 */
internal object LazSpatialIndex {

    /** Cells this many units across. Finer costs index size; coarser skips fewer chunks. */
    private const val QUADTREE_CELL_SIZE = 50f
    private const val INTERVAL_THRESHOLD = 1000
    private const val MINIMUM_POINTS = 100_000

    // LASindex.complete()'s interval-count cap is disabled (0 skips that branch entirely). Its
    // implementation - LASinterval.merge_intervals() - calls TreeMap.firstKey() on a map that it
    // builds by recording a "gap" only for cells holding more than one interval; when every cell
    // has already collapsed to a single contiguous interval, which is the ordinary case for a
    // single, spatially coherent flight strip, that map is empty and firstKey() throws
    // NoSuchElementException before the method's own size check ever runs. This is a bug in
    // laszip4j 0.21's port of LAStools, reproduced with a synthetic single-strip LAS file, and
    // it cannot be patched from here since LASinterval's state is private to that class. The
    // minimum-points cell-coarsening path above is unrelated and unaffected.
    private const val MAXIMUM_INTERVALS = 0

    fun indexFileFor(source: File): File = File(source.parentFile, "${source.nameWithoutExtension}.lax")

    /** True when a usable index already sits beside [source]. */
    fun exists(source: File): Boolean {
        val index = indexFileFor(source)
        // A stale index would seek to the wrong chunks, so treat one older than the data as absent.
        return index.isFile && index.length() > 0L && index.lastModified() >= source.lastModified()
    }

    /**
     * Builds a `.lax` beside [source] on a background thread when one is missing.
     * Safe to call on every import: existing indexes are no-ops, and failures only cost speed.
     */
    fun ensureBuiltAsync(source: File) {
        if (!source.isFile || exists(source)) return
        indexExecutor.execute {
            runCatching { build(source) }.onFailure { failure ->
                System.err.println("Background LAX index for ${source.name} failed: ${failure.message}")
            }
        }
    }

    /** Loads the sidecar index, or null when there is none or it cannot be parsed. */
    fun load(source: File): LASindex? {
        if (!exists(source)) return null
        return runCatching {
            LASindex().takeIf { it.read(indexFileFor(source).absolutePath) }
        }.getOrNull()
    }

    /**
     * Writes a `.lax` beside [source]. Returns true when an index is in place afterwards,
     * including when one already existed.
     *
     * Failure is never fatal: reads simply fall back to scanning the whole file, so a read-only
     * directory or an unusual LAZ variant costs speed rather than function.
     */
    fun build(
        source: File,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onProgress: (indexedPoints: Long, totalPoints: Long) -> Unit = { _, _ -> },
    ): Boolean {
        if (exists(source)) return true
        if (!source.isFile) return false

        return runCatching {
            SeekableLaszipReader.open(source, LASZIP_DECOMPRESS_SELECTIVE_CHANNEL_RETURNS_XY)?.use { reader ->
                buildFrom(reader, source, shouldContinue, onProgress)
            } ?: false
        }.getOrElse { failure ->
            System.err.println("Could not index ${source.name}: ${failure.message}")
            indexFileFor(source).delete()
            false
        }
    }

    private fun buildFrom(
        reader: LASreaderLAS,
        source: File,
        shouldContinue: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val header = reader.header
        val minX = header.min_x
        val maxX = header.max_x
        val minY = header.min_y
        val maxY = header.max_y
        if (!(minX < maxX) || !(minY < maxY)) return false

        val total = header.extended_number_of_point_records.takeIf { it > 0L }
            ?: (header.number_of_point_records.toLong() and 0xFFFFFFFFL)

        val quadtree = LASquadtree()
        if (!quadtree.setup(minX, maxX, minY, maxY, QUADTREE_CELL_SIZE)) return false

        val index = LASindex()
        index.prepare(quadtree, INTERVAL_THRESHOLD)

        var counted = 0L
        while (reader.read_point()) {
            index.add(reader.get_x(), reader.get_y(), counted.toInt())
            counted++
            if (counted % PROGRESS_INTERVAL == 0L) {
                if (!shouldContinue()) return false
                onProgress(counted, total)
            }
        }
        if (counted == 0L) return false

        // LasIndexWriter.complete also turns off complete()'s hardcoded verbose stderr dump.
        LasIndexWriter.complete(index, MINIMUM_POINTS, MAXIMUM_INTERVALS)
        onProgress(counted, total)

        // Write via a temporary file so an interrupted build cannot leave a half-written index
        // that later reads would trust. LASindex.write(String) cannot be used here - see
        // LasIndexWriter - so the temporary file's exact name is never subject to its filename
        // mangling.
        val destination = indexFileFor(source)
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        temporary.delete()
        if (!runCatching { LasIndexWriter.write(index, temporary) }.getOrDefault(false)) {
            temporary.delete()
            return false
        }
        destination.delete()
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            return false
        }
        return true
    }

    private const val PROGRESS_INTERVAL = 1_000_000L

    private val indexExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "laz-spatial-index").apply { isDaemon = true }
    }
}
