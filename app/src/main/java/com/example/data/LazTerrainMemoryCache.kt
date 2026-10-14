package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import com.example.gps.crs.GridGeoreference
import java.io.File
import java.util.LinkedHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

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
        /** Class filter changes the raster; without it a filtered reload returned the unfiltered grid. */
        val allowedClasses: List<Int>?,
    )

    private data class CacheEntry(
        val result: DemGenerator.TerrainLoadResult,
        val estimatedBytes: Long,
    )

    private val entries = LinkedHashMap<CacheKey, CacheEntry>(8, 0.75f, true)
    private var currentBytes = 0L

    // Strong reference held here; the budget registry only keeps it weakly.
    private val trimHook: () -> Unit = { clear() }

    init {
        AppMemoryBudget.registerTrimmable(trimHook)
    }

    @Synchronized
    fun get(file: File, options: LidarImportOptions): DemGenerator.TerrainLoadResult? {
        return entries[key(file, options)]?.result
    }

    /**
     * A later viewport that sits inside an already decoded tile, at the same or coarser cell
     * size, is cropped from that tile. A zoom that needs smaller cells still decodes points.
     */
    @Synchronized
    fun findCovering(file: File, options: LidarImportOptions): DemGenerator.TerrainLoadResult? {
        val sanitized = options.sanitized()
        val want = sanitized.focusBounds ?: return null
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        val size = file.length()
        val modified = file.lastModified()
        val classes = sanitized.allowedClasses?.sorted()
        var bestKey: CacheKey? = null
        var bestEntry: CacheEntry? = null
        var bestCell = Double.MAX_VALUE
        for ((cachedKey, entry) in entries) {
            if (cachedKey.canonicalPath != path || cachedKey.fileSize != size || cachedKey.modifiedAt != modified) continue
            if (cachedKey.groundMode != sanitized.groundMode || cachedKey.smoothingRadius != sanitized.smoothingRadius) continue
            if (cachedKey.allowedClasses != classes) continue
            val parent = cachedKey.focusBounds ?: NormalizedRasterBounds.Full
            if (!contains(parent, want)) continue
            val parentSpan = max(parent.right - parent.left, parent.bottom - parent.top)
            val parentLong = max(entry.result.grid.width, entry.result.grid.height).coerceAtLeast(1)
            val parentCell = parentSpan / parentLong
            val wantSpan = max(want.right - want.left, want.bottom - want.top)
            val wantCell = wantSpan / sanitized.rasterResolution.coerceAtLeast(1)
            if (parentCell > wantCell * 1.05) continue
            if (parentCell < bestCell) {
                bestCell = parentCell
                bestKey = cachedKey
                bestEntry = entry
            }
        }
        val chosenKey = bestKey ?: return null
        val chosen = bestEntry ?: return null
        return cropToFocus(
            result = chosen.result,
            parent = chosenKey.focusBounds ?: NormalizedRasterBounds.Full,
            child = want,
        )
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
            allowedClasses = sanitized.allowedClasses?.sorted(),
        )
    }

    private fun estimateBytes(grid: ElevationGrid): Long {
        val cells = grid.width.toLong() * grid.height.toLong()
        // bareEarth float + canopy float + valid-data boolean, plus a small object/array allowance.
        return cells * 9L + 16L * 1024L
    }

    private fun contains(parent: NormalizedRasterBounds, child: NormalizedRasterBounds): Boolean =
        child.left >= parent.left - 1e-9 &&
            child.top >= parent.top - 1e-9 &&
            child.right <= parent.right + 1e-9 &&
            child.bottom <= parent.bottom + 1e-9 &&
            child.right - child.left > 1e-6 &&
            child.bottom - child.top > 1e-6
}

private fun cropToFocus(
    result: DemGenerator.TerrainLoadResult,
    parent: NormalizedRasterBounds,
    child: NormalizedRasterBounds,
): DemGenerator.TerrainLoadResult? {
    val grid = result.grid
    val parentWidth = (parent.right - parent.left).coerceAtLeast(1e-9)
    val parentHeight = (parent.bottom - parent.top).coerceAtLeast(1e-9)
    val localLeft = ((child.left - parent.left) / parentWidth).coerceIn(0.0, 1.0)
    val localRight = ((child.right - parent.left) / parentWidth).coerceIn(0.0, 1.0)
    val localTop = ((child.top - parent.top) / parentHeight).coerceIn(0.0, 1.0)
    val localBottom = ((child.bottom - parent.top) / parentHeight).coerceIn(0.0, 1.0)
    val x0 = floor(localLeft * (grid.width - 1)).toInt().coerceIn(0, grid.width - 1)
    val x1 = ceil(localRight * (grid.width - 1)).toInt().coerceIn(x0, grid.width - 1)
    val y0 = floor(localTop * (grid.height - 1)).toInt().coerceIn(0, grid.height - 1)
    val y1 = ceil(localBottom * (grid.height - 1)).toInt().coerceIn(y0, grid.height - 1)
    val width = x1 - x0 + 1
    val height = y1 - y0 + 1
    if (width < 2 || height < 2) return null
    val cells = width * height
    val bare = FloatArray(cells)
    val canopy = FloatArray(cells)
    val valid = BooleanArray(cells)
    val interpolated = BooleanArray(cells)
    for (y in 0 until height) {
        val sourceRow = (y0 + y) * grid.width + x0
        val destRow = y * width
        for (x in 0 until width) {
            bare[destRow + x] = grid.bareEarth[sourceRow + x]
            canopy[destRow + x] = grid.canopySpikes[sourceRow + x]
            valid[destRow + x] = grid.validData[sourceRow + x]
            interpolated[destRow + x] = grid.interpolatedData[sourceRow + x]
        }
    }
    val croppedGrid = ElevationGrid(width, height, bare, canopy, grid.cellSizeMeters, valid, interpolated)
    return result.copy(
        grid = croppedGrid,
        summary = result.summary.take(12_000) + " · cropped from a decoded tile",
        geoMetadata = cropGeo(result.geoMetadata, grid, x0, y0, x1, y1, width, height),
    )
}

private fun cropGeo(
    metadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    grid: ElevationGrid,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    width: Int,
    height: Int,
): GeoSpatialLibrary.GeoSpatialMetadata? {
    if (metadata == null) return null
    val projected = metadata.projected
    if (projected != null) {
        val nx0 = x0.toDouble() / (grid.width - 1).coerceAtLeast(1)
        val nx1 = x1.toDouble() / (grid.width - 1).coerceAtLeast(1)
        val ny0 = y0.toDouble() / (grid.height - 1).coerceAtLeast(1)
        val ny1 = y1.toDouble() / (grid.height - 1).coerceAtLeast(1)
        val rangeX = projected.maxX - projected.minX
        val rangeY = projected.maxY - projected.minY
        val cropped = GridGeoreference(
            crs = projected.crs,
            minX = projected.minX + nx0 * rangeX,
            maxX = projected.minX + nx1 * rangeX,
            minY = projected.maxY - ny1 * rangeY,
            maxY = projected.maxY - ny0 * rangeY,
            width = width,
            height = height,
        )
        val envelope = cropped.geographicEnvelope()
        return metadata.copy(
            bounds = GeoSpatialLibrary.GeographicBounds(envelope[0], envelope[1], envelope[2], envelope[3]),
            resolutionMeters = cropped.cellMeters,
            columns = width,
            rows = height,
            projected = cropped,
        )
    }
    val bounds = metadata.bounds ?: return metadata.copy(columns = width, rows = height)
    val nx0 = x0.toDouble() / (grid.width - 1).coerceAtLeast(1)
    val nx1 = x1.toDouble() / (grid.width - 1).coerceAtLeast(1)
    val ny0 = y0.toDouble() / (grid.height - 1).coerceAtLeast(1)
    val ny1 = y1.toDouble() / (grid.height - 1).coerceAtLeast(1)
    return metadata.copy(
        bounds = GeoSpatialLibrary.GeographicBounds(
            minLat = bounds.maxLat - ny1 * (bounds.maxLat - bounds.minLat),
            maxLat = bounds.maxLat - ny0 * (bounds.maxLat - bounds.minLat),
            minLon = bounds.minLon + nx0 * (bounds.maxLon - bounds.minLon),
            maxLon = bounds.minLon + nx1 * (bounds.maxLon - bounds.minLon),
        ),
        columns = width,
        rows = height,
    )
}
