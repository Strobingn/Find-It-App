package com.example.data

import java.io.File
import java.util.LinkedHashMap

/**
 * Byte-bounded LRU cache for decoded LAZ/LAS rasters.
 *
 * Reopening a saved dataset with the same import options can reuse the already decoded DEM instead
 * of scanning the full point cloud again. The source file remains authoritative: size or timestamp
 * changes produce a different key. The default limit scales from the actual large-heap ceiling
 * granted by Android on the current device.
 */
class LazTerrainMemoryCache(
    private val maxBytes: Long = AppMemoryBudget.terrainMemoryCacheBytes(),
) {
    private data class CacheKey(
        val canonicalPath: String,
        val fileSize: Long,
        val modifiedAt: Long,
        val groundMode: GroundSurfaceMode,
        val rasterResolution: Int,
        val smoothingRadius: Int,
        val focusBounds: NormalizedRasterBounds?,
    )

    private data class CacheEntry(
        val result: DemGenerator.TerrainLoadResult,
        val estimatedBytes: Long,
    )

    private val entries = LinkedHashMap<CacheKey, CacheEntry>(8, 0.75f, true)
    private var currentBytes = 0L

    @Synchronized
    fun get(file: File, options: LidarImportOptions): DemGenerator.TerrainLoadResult? {
        return entries[key(file, options)]?.result
    }

    @Synchronized
    fun put(file: File, options: LidarImportOptions, result: DemGenerator.TerrainLoadResult) {
        val key = key(file, options)
        entries.remove(key)?.let { currentBytes -= it.estimatedBytes }

        val estimatedBytes = estimateBytes(result.grid)
        if (estimatedBytes > maxBytes) return

        entries[key] = CacheEntry(result, estimatedBytes)
        currentBytes += estimatedBytes
        trimToLimit()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0L
    }

    @Synchronized
    fun sizeBytes(): Long = currentBytes

    fun limitBytes(): Long = maxBytes

    private fun trimToLimit() {
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            currentBytes -= iterator.next().value.estimatedBytes
            iterator.remove()
        }
    }

    private fun key(file: File, options: LidarImportOptions): CacheKey {
        val sanitized = options.sanitized()
        return CacheKey(
            canonicalPath = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath),
            fileSize = file.length(),
            modifiedAt = file.lastModified(),
            groundMode = sanitized.groundMode,
            rasterResolution = sanitized.rasterResolution,
            smoothingRadius = sanitized.smoothingRadius,
            focusBounds = sanitized.focusBounds,
        )
    }

    private fun estimateBytes(grid: ElevationGrid): Long {
        val cells = grid.width.toLong() * grid.height.toLong()
        // bareEarth float + canopy float + valid-data boolean, plus a small object/array allowance.
        return cells * 9L + 16L * 1024L
    }
}
