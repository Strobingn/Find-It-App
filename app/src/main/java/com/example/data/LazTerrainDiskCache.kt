package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Persistent byte-bounded cache for decoded LAZ/LAS rasters.
 *
 * Prefer a directory under [android.content.Context.getFilesDir] so Android does not purge entries
 * under storage pressure the way it does with [android.content.Context.getCacheDir]. Cache keys
 * include source path, source size/timestamp, and every import option. Writes use a temporary file
 * followed by atomic promotion. Corrupt or stale entries are deleted on read.
 */
class LazTerrainDiskCache(
    private val directory: File,
    private val maxBytes: Long = 1_024L * 1024L * 1024L,
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
                val magic = input.readUTF()
                require(magic == MAGIC_V3 || magic == MAGIC_V2) { "Unsupported terrain cache entry" }
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
                val geoMetadata = if (magic == MAGIC_V3) readGeoMetadata(input, width, height, cellSize) else null
                DemGenerator.TerrainLoadResult(
                    grid = ElevationGrid(width, height, bare, canopy, cellSize, valid),
                    summary = summary,
                    isBareEarth = isBareEarth,
                    geoMetadata = geoMetadata,
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
                output.writeUTF(MAGIC_V3)
                output.writeInt(result.grid.width)
                output.writeInt(result.grid.height)
                output.writeFloat(result.grid.cellSizeMeters)
                output.writeBoolean(result.isBareEarth)
                output.writeUTF(result.summary.take(MAX_SUMMARY_CHARS))
                result.grid.bareEarth.forEach(output::writeFloat)
                result.grid.canopySpikes.forEach(output::writeFloat)
                result.grid.validData.forEach(output::writeBoolean)
                writeGeoMetadata(output, result.geoMetadata)
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

    private fun writeGeoMetadata(
        output: DataOutputStream,
        metadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    ) {
        if (metadata == null) {
            output.writeBoolean(false)
            return
        }
        output.writeBoolean(true)
        output.writeUTF(metadata.siteName.take(512))
        output.writeUTF(metadata.crs.take(256))
        output.writeUTF(metadata.datum.take(128))
        output.writeDouble(metadata.resolutionMeters)
        val bounds = metadata.bounds
        if (bounds == null) {
            output.writeBoolean(false)
        } else {
            output.writeBoolean(true)
            output.writeDouble(bounds.minLat)
            output.writeDouble(bounds.maxLat)
            output.writeDouble(bounds.minLon)
            output.writeDouble(bounds.maxLon)
        }
    }

    private fun readGeoMetadata(
        input: DataInputStream,
        width: Int,
        height: Int,
        cellSize: Float,
    ): GeoSpatialLibrary.GeoSpatialMetadata? {
        if (!input.readBoolean()) return null
        val siteName = input.readUTF()
        val crs = input.readUTF()
        val datum = input.readUTF()
        val resolution = input.readDouble().takeIf { it.isFinite() && it > 0.0 }
            ?: cellSize.toDouble()
        val bounds = if (input.readBoolean()) {
            GeoSpatialLibrary.GeographicBounds(
                minLat = input.readDouble(),
                maxLat = input.readDouble(),
                minLon = input.readDouble(),
                maxLon = input.readDouble(),
            )
        } else {
            null
        }
        return GeoSpatialLibrary.GeoSpatialMetadata(
            siteName = siteName,
            bounds = bounds,
            crs = crs,
            datum = datum,
            resolutionMeters = resolution,
            columns = width,
            rows = height,
        )
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
        // V2: validData mask from full raster. V3: same + optional georeference metadata.
        private const val MAGIC_V2 = "FINDIT_DEM_CACHE_V2"
        private const val MAGIC_V3 = "FINDIT_DEM_CACHE_V3"
        private const val CACHE_EXTENSION = "fitdem"
        private const val BUFFER_BYTES = 256 * 1024
        private const val MAX_CELLS = 2_000_000L
        private const val MAX_SUMMARY_CHARS = 16_000
    }
}

/**
 * Two-tier decoded terrain cache: a synchronous memory LRU backed by an asynchronously persisted,
 * byte-bounded disk cache. First render no longer waits for several megabytes of float-array I/O.
 */
class LazTerrainCache(
    private val memory: LazTerrainMemoryCache,
    private val disk: LazTerrainDiskCache,
) {
    enum class Hit { MEMORY, DISK, MISS }

    data class Lookup(
        val result: DemGenerator.TerrainLoadResult?,
        val hit: Hit,
    )

    private val diskWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diskWriteGeneration = AtomicLong(0L)

    fun get(file: File, options: LidarImportOptions): Lookup {
        memory.get(file, options)?.let { return Lookup(it, Hit.MEMORY) }
        disk.get(file, options)?.let {
            memory.put(file, options, it)
            return Lookup(it, Hit.DISK)
        }
        return Lookup(null, Hit.MISS)
    }

    /** Immediate memory put only — never blocks on disk I/O. */
    fun putMemory(file: File, options: LidarImportOptions, result: DemGenerator.TerrainLoadResult) {
        memory.put(file, options, result)
    }

    /** Queued durable write; generation-gated so clear/remove drops stale writers. */
    fun putDisk(file: File, options: LidarImportOptions, result: DemGenerator.TerrainLoadResult) {
        val generation = diskWriteGeneration.get()
        diskWriteScope.launch {
            if (generation == diskWriteGeneration.get()) disk.put(file, options, result)
        }
    }

    fun put(file: File, options: LidarImportOptions, result: DemGenerator.TerrainLoadResult) {
        putMemory(file, options, result)
        putDisk(file, options, result)
    }

    fun remove(file: File) {
        diskWriteGeneration.incrementAndGet()
        disk.remove(file)
        // Memory keys include source metadata and are naturally invalidated by deletion/replacement.
    }

    fun clear() {
        diskWriteGeneration.incrementAndGet()
        memory.clear()
        disk.clear()
    }

    fun diskSizeBytes(): Long = disk.sizeBytes()
}
