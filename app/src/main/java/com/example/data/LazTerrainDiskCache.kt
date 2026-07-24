package com.example.data

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Persistent byte-bounded cache for decoded LAZ/LAS rasters.
 *
 * Cache keys include source path, source size/timestamp, and every import option. Writes use a
 * temporary file followed by atomic promotion. Corrupt or stale entries are deleted on read.
 */
class LazTerrainDiskCache(
    private val directory: File,
    private val maxBytes: Long = 512L * 1024L * 1024L,
) {
    init {
        directory.mkdirs()
    }

    @Synchronized
    fun get(file: File, options: LidarImportOptions): DemGenerator.TerrainLoadResult? {
        val cacheFile = cacheFile(file, options)
        if (!cacheFile.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(cacheFile), BUFFER_BYTES)).use { input ->
                require(input.readUTF() == MAGIC) { "Unsupported terrain cache entry" }
                val width = input.readInt()
                val height = input.readInt()
                val cells = width.toLong() * height.toLong()
                require(width > 0 && height > 0 && cells <= MAX_CELLS) { "Invalid cached grid size" }
                val cellSize = input.readFloat()
                val isBareEarth = input.readBoolean()
                val summary = input.readUTF()
                val bare = FloatArray(cells.toInt()) { input.readFloat() }
                val canopy = FloatArray(cells.toInt()) { input.readFloat() }
                val valid = BooleanArray(cells.toInt()) { input.readBoolean() }
                DemGenerator.TerrainLoadResult(
                    grid = ElevationGrid(width, height, bare, canopy, cellSize, valid),
                    summary = summary,
                    isBareEarth = isBareEarth,
                )
            }
        }.onSuccess {
            cacheFile.setLastModified(System.currentTimeMillis())
        }.onFailure {
            cacheFile.delete()
        }.getOrNull()
    }

    @Synchronized
    fun put(file: File, options: LidarImportOptions, result: DemGenerator.TerrainLoadResult) {
        directory.mkdirs()
        if (!directory.isDirectory) return
        val target = cacheFile(file, options)
        val partial = File(directory, ".${target.name}.part")
        partial.delete()
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(partial), BUFFER_BYTES)).use { output ->
                output.writeUTF(MAGIC)
                output.writeInt(result.grid.width)
                output.writeInt(result.grid.height)
                output.writeFloat(result.grid.cellSizeMeters)
                output.writeBoolean(result.isBareEarth)
                output.writeUTF(result.summary.take(MAX_SUMMARY_CHARS))
                result.grid.bareEarth.forEach(output::writeFloat)
                result.grid.canopySpikes.forEach(output::writeFloat)
                result.grid.validData.forEach(output::writeBoolean)
                output.flush()
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            trimToLimit()
        }.onFailure {
            partial.delete()
        }
    }

    @Synchronized
    fun remove(file: File) {
        val sourcePrefix = sourceIdentityPrefix(file)
        directory.listFiles()?.forEach { candidate ->
            if (candidate.name.startsWith(sourcePrefix)) candidate.delete()
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    @Synchronized
    fun sizeBytes(): Long = directory.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L

    private fun trimToLimit() {
        val entries = directory.listFiles()
            ?.filter { it.isFile && it.extension == CACHE_EXTENSION }
            ?.sortedBy(File::lastModified)
            ?.toMutableList()
            ?: return
        var bytes = entries.sumOf(File::length)
        val iterator = entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val file = iterator.next()
            bytes -= file.length()
            file.delete()
        }
    }

    private fun cacheFile(file: File, options: LidarImportOptions): File {
        val sanitized = options.sanitized()
        val key = buildString {
            append(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
            append('|').append(file.length())
            append('|').append(file.lastModified())
            append('|').append(sanitized.groundMode.name)
            append('|').append(sanitized.rasterResolution)
            append('|').append(sanitized.smoothingRadius)
            append('|').append(sanitized.focusBounds)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(directory, "${sourceIdentityPrefix(file)}-$digest.$CACHE_EXTENSION")
    }

    private fun sourceIdentityPrefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath).toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { byte -> "%02x".format(byte) }
        return "terrain-$digest"
    }

    companion object {
        private const val MAGIC = "FINDIT_DEM_CACHE_V1"
        private const val CACHE_EXTENSION = "fitdem"
        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_CELLS = 2_000_000L
        private const val MAX_SUMMARY_CHARS = 16_000
    }
}

/** Two-tier decoded terrain cache: fast memory LRU backed by a bounded persistent cache. */
class LazTerrainCache(
    private val memory: LazTerrainMemoryCache,
    private val disk: LazTerrainDiskCache,
) {
    enum class Hit { MEMORY, DISK, MISS }

    data class Lookup(
        val result: DemGenerator.TerrainLoadResult?,
        val hit: Hit,
    )

    fun get(file: File, options: LidarImportOptions): Lookup {
        memory.get(file, options)?.let { return Lookup(it, Hit.MEMORY) }
        disk.get(file, options)?.let {
            memory.put(file, options, it)
            return Lookup(it, Hit.DISK)
        }
        return Lookup(null, Hit.MISS)
    }

    fun put(file: File, options: LidarImportOptions, result: DemGenerator.TerrainLoadResult) {
        memory.put(file, options, result)
        disk.put(file, options, result)
    }

    fun remove(file: File) {
        disk.remove(file)
        // Memory keys include source metadata and are naturally invalidated by deletion/replacement.
    }

    fun clear() {
        memory.clear()
        disk.clear()
    }

    fun diskSizeBytes(): Long = disk.sizeBytes()
}
