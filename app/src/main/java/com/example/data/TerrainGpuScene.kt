package com.example.data

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.sqrt

/** Interleaved position/normal vertices and triangle indices for one spatial tile. */
data class TerrainGpuBatch(
    val tile: TerrainSpatialTile,
    val vertices: FloatArray,
    val indices: ShortArray,
    val intIndices: IntArray? = null,
    /**
     * Vertex count when [vertices] is empty. Clipmap scenes upload only the index buffer;
     * the vertex shader reconstructs positions. CPU fallback materializes this many vertices.
     */
    val indexOnlyVertexCount: Int = 0,
) {
    val vertexCount: Int
        get() = if (vertices.isNotEmpty()) vertices.size / FLOATS_PER_VERTEX else indexOnlyVertexCount
    val triangleCount: Int get() = (intIndices?.size ?: indices.size) / 3
    val usesIntIndices: Boolean get() = intIndices != null

    companion object {
        const val FLOATS_PER_VERTEX = 6
    }
}

data class TerrainGpuLevel(
    val reductionFactor: Int,
    val gridWidth: Int,
    val gridHeight: Int,
    val batches: List<TerrainGpuBatch>,
) {
    val triangleCount: Int = batches.sumOf(TerrainGpuBatch::triangleCount)
}

/**
 * Per-LOD resources for the ES 3.0 vertex-texture-fetch renderer (ARM geometry-clipmap
 * pattern): each level owns an R32F heightmap texture plus the elevation window the
 * vertex shader needs to reconstruct positions and normals entirely on the GPU.
 */
data class TerrainClipmapLevel(
    val reductionFactor: Int,
    val heightmap: HeightmapTexture,
    val minElevation: Float,
    val maxElevation: Float,
)

/** Immutable multi-LOD scene ready to upload to OpenGL vertex/index buffers. */
data class TerrainGpuScene(
    val levels: List<TerrainGpuLevel>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val heightmap: HeightmapTexture? = null,
    val cdlodNodes: List<CdlodNode> = emptyList(),
    /** One clipmap per [levels] entry; empty when the scene was built without a heightmap. */
    val clipmaps: List<TerrainClipmapLevel> = emptyList(),
) {
    init {
        require(levels.isNotEmpty())
    }

    fun selectForZoom(zoom: Float): TerrainGpuLevel {
        val safeZoom = zoom.coerceAtLeast(0.1f)
        if (safeZoom >= 1f) return levels.first()
        val desiredFactor = if (safeZoom >= 0.6f) 2 else Int.MAX_VALUE
        return if (desiredFactor == Int.MAX_VALUE) {
            levels.last()
        } else {
            levels.minByOrNull { kotlin.math.abs(it.reductionFactor - desiredFactor) } ?: levels.first()
        }
    }
}

/**
 * Converts a decoded DEM into spatially culled, bounded GPU batches.
 *
 * Each tile stays far below the 65,535-vertex unsigned-short index limit. Empty/no-data tiles are
 * omitted entirely. The finest GPU level follows the caller's cap: decode previews pass 1,024
 * cells on the longest side so the 3D terrain matches the detailed 2D raster, while the LOD
 * pyramid still provides cheap coarse levels for zoomed-out rendering.
 */
object TerrainGpuSceneBuilder {
    /**
     * Max [tileSize] step for [TerrainSpatialGridIndex]. Inclusive tile ends make each full tile
     * (tileSize + 1) vertices wide, so tileSize must stay ≤ 254 for ushort batches (255² ≤ 65,535).
     */
    const val MAX_SAFE_TILE_SIZE = 254
    /** ES 3.0 unsigned-int index tiles. 1025² vertices still fit a 32-bit element buffer. */
    const val MAX_INT_TILE_SIZE = 1_024

    /**
     * Scene the GL surface draws. Clipmap heightmaps plus index buffers; positions and normals
     * are reconstructed in the vertex shader, so this skips the CPU normal pass.
     */
    fun buildDisplayScene(source: ElevationGrid): TerrainGpuScene = build(
        source = source,
        maxFinestDimension = maxOf(source.width, source.height, 64),
        tileSize = TerrainDecodeCoordinator.GPU_PREVIEW_TILE_SIZE,
        useIntIndices = false,
        attachHeightmap = true,
    )

    fun build(
        source: ElevationGrid,
        maxFinestDimension: Int = Int.MAX_VALUE,
        tileSize: Int = 64,
        useIntIndices: Boolean = false,
        attachHeightmap: Boolean = false,
    ): TerrainGpuScene {
        val cap = if (useIntIndices) MAX_INT_TILE_SIZE else MAX_SAFE_TILE_SIZE
        val safeTileSize = tileSize.coerceIn(16, cap)
        val pyramid = TerrainLodPyramid.build(
            source = source,
            maxFinestDimension = maxFinestDimension,
            minDimension = 64,
            // The renderer clamps zoom to [0.75, 8] (GpuTerrainSurface), so selectForZoom can
            // only ever return the finest level (zoom >= 1) or the factor-2 level (zoom in
            // [0.75, 1)). Building factor-4/8 levels was pure waste: extra mesh-build CPU,
            // extra scene RAM, and buffers that were never uploaded or drawn.
            maxLevels = 2,
        )
        // Clipmap scenes never upload positions. Building them (and a normal per vertex) dominated
        // mesh time on 1,024²+ DEMs and was discarded by the vertex-texture-fetch renderer.
        val indexOnly = attachHeightmap
        val built = pyramid.levels.map { level ->
            buildLevel(level, safeTileSize, useIntIndices, indexOnly)
        }.filter { it.level.batches.isNotEmpty() }

        val levels = built.map { it.level }
        require(levels.isNotEmpty()) { "Terrain contains no renderable cells" }
        val clipmapPacks = if (attachHeightmap) built.map { HeightmapCdlod.pack(it.grid) } else null
        val heightmap = when {
            clipmapPacks == null -> null
            // Finest LOD is the source grid itself: one pack feeds both the scene and that clipmap.
            built.first().grid === source -> clipmapPacks.first()
            else -> HeightmapCdlod.pack(source)
        }
        val nodes = if (heightmap != null) {
            HeightmapCdlod.select(
                width = heightmap.width,
                height = heightmap.height,
                rangeInCells = HeightmapCdlod.rangeInCellsForZoom(1.4f, maxOf(heightmap.width, heightmap.height)),
            )
        } else {
            emptyList()
        }
        // One heightmap per LOD level for the ES 3.0 vertex-texture-fetch renderer. The ARM
        // geometry-clipmap pattern backs each level with its own texture; the vertex shader
        // reconstructs positions and normals from it, so no per-vertex buffers are uploaded.
        val clipmaps = if (clipmapPacks != null) {
            built.zip(clipmapPacks).map { (entry, pack) ->
                TerrainClipmapLevel(
                    reductionFactor = entry.level.reductionFactor,
                    heightmap = pack,
                    minElevation = entry.minElevation,
                    maxElevation = entry.maxElevation,
                )
            }
        } else {
            emptyList()
        }
        return TerrainGpuScene(
            levels = levels,
            sourceWidth = source.width,
            sourceHeight = source.height,
            heightmap = heightmap,
            cdlodNodes = nodes,
            clipmaps = clipmaps,
        )
    }

    /** Builder-internal pairing of a GPU level with the grid it was built from. */
    private data class BuiltLevel(
        val level: TerrainGpuLevel,
        val grid: ElevationGrid,
        val minElevation: Float,
        val maxElevation: Float,
    )

    internal fun buildBatch(grid: ElevationGrid, tile: TerrainSpatialTile): TerrainGpuBatch? {
        val bounds = elevationBounds(grid)
        return buildBatch(grid, tile, bounds.first, bounds.second, useIntIndices = false, indexOnly = false)
    }

    /**
     * Rebuilds the CPU position/normal buffer from a clipmap heightmap. Used only when the ES 3.0
     * clipmap shader failed to compile and the renderer must fall back to vertex buffers.
     * NaN texels match [HeightmapCdlod.pack]: they are not a drawn corner.
     */
    fun cpuVerticesFromHeightmap(
        batch: TerrainGpuBatch,
        gridWidth: Int,
        gridHeight: Int,
        heights: FloatArray,
        cellSizeMeters: Float,
        minElevation: Float,
        maxElevation: Float,
    ): FloatArray {
        if (batch.vertices.isNotEmpty()) return batch.vertices
        val localWidth = batch.tile.endXInclusive - batch.tile.startX + 1
        val localHeight = batch.tile.endYInclusive - batch.tile.startY + 1
        val vertices = FloatArray(localWidth * localHeight * TerrainGpuBatch.FLOATS_PER_VERTEX)
        val range = (maxElevation - minElevation).takeIf { it > 0f } ?: 1f
        writeVertices(
            vertices = vertices,
            tile = batch.tile,
            bare = heights,
            valid = null,
            gridWidth = gridWidth,
            gridHeight = gridHeight,
            cellSizeMeters = cellSizeMeters,
            minElevation = minElevation,
            elevationRange = range,
        )
        return vertices
    }

    private fun buildLevel(
        level: TerrainLodLevel,
        tileSize: Int,
        useIntIndices: Boolean,
        indexOnly: Boolean,
    ): BuiltLevel {
        val index = TerrainSpatialGridIndex.build(level.grid, tileSize)
        val bounds = elevationBounds(level.grid)
        val slots = arrayOfNulls<TerrainGpuBatch>(index.tiles.size)
        val parallel = index.tiles.size >= 4 && level.grid.width * level.grid.height >= 65_536
        if (!parallel) {
            for (i in index.tiles.indices) {
                slots[i] = batchForTile(level.grid, index.tiles[i], bounds, useIntIndices, indexOnly)
            }
        } else {
            val tasks = index.tiles.indices.map { i ->
                meshPool.submit {
                    slots[i] = batchForTile(level.grid, index.tiles[i], bounds, useIntIndices, indexOnly)
                }
            }
            var failure: Throwable? = null
            for (task in tasks) {
                try {
                    task.get()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                }
            }
            if (failure != null) {
                val cause = (failure as? ExecutionException)?.cause ?: failure
                throw cause
            }
        }
        var rendered = 0
        var skippedEmpty = 0
        var skippedNoQuads = 0
        val batches = ArrayList<TerrainGpuBatch>(slots.size)
        for (i in slots.indices) {
            val batch = slots[i]
            if (index.tiles[i].isEmpty) {
                skippedEmpty++
            } else if (batch == null) {
                skippedNoQuads++
            } else {
                rendered++
                batches += batch
            }
        }
        TerrainCoverageLog.emit(
            "FindItTerrain",
            "GPU level ${level.grid.width}×${level.grid.height} tiles " +
                "render=$rendered empty=$skippedEmpty noQuads=$skippedNoQuads" +
                if (indexOnly) " index-only" else "",
        )
        return BuiltLevel(
            level = TerrainGpuLevel(
                reductionFactor = level.reductionFactor,
                gridWidth = level.grid.width,
                gridHeight = level.grid.height,
                batches = batches,
            ),
            grid = level.grid,
            minElevation = bounds.first,
            maxElevation = bounds.second,
        )
    }

    private fun batchForTile(
        grid: ElevationGrid,
        tile: TerrainSpatialTile,
        elevationBounds: Pair<Float, Float>,
        useIntIndices: Boolean,
        indexOnly: Boolean,
    ): TerrainGpuBatch? {
        if (tile.isEmpty) return null
        return buildBatch(
            grid,
            tile,
            elevationBounds.first,
            elevationBounds.second,
            useIntIndices,
            indexOnly,
        )
    }

    private fun buildBatch(
        grid: ElevationGrid,
        tile: TerrainSpatialTile,
        minElevation: Float,
        maxElevation: Float,
        useIntIndices: Boolean = false,
        indexOnly: Boolean = false,
    ): TerrainGpuBatch? {
        if (tile.isEmpty) return null
        val localWidth = tile.endXInclusive - tile.startX + 1
        val localHeight = tile.endYInclusive - tile.startY + 1
        if (localWidth < 2 || localHeight < 2) return null
        val vertexLimit = if (useIntIndices) 2_000_000L else 65_535L
        require(localWidth.toLong() * localHeight <= vertexLimit) {
            "GPU tile too large (${localWidth}×${localHeight}); vertices must stay ≤ $vertexLimit"
        }
        val elevationRange = (maxElevation - minElevation).takeIf { it > 0f } ?: 1f

        val vertices = if (indexOnly) {
            FloatArray(0)
        } else {
            val filled = FloatArray(localWidth * localHeight * TerrainGpuBatch.FLOATS_PER_VERTEX)
            writeVertices(
                vertices = filled,
                tile = tile,
                bare = grid.bareEarth,
                valid = grid.validData,
                gridWidth = grid.width,
                gridHeight = grid.height,
                cellSizeMeters = grid.cellSizeMeters,
                minElevation = minElevation,
                elevationRange = elevationRange,
            )
            filled
        }

        val quadCapacity = (localWidth - 1) * (localHeight - 1) * 6
        val shortIndices = if (useIntIndices) ShortArray(0) else ShortArray(quadCapacity)
        val intIndices = if (useIntIndices) IntArray(quadCapacity) else null
        var indexCount = 0
        for (localY in 0 until localHeight - 1) {
            for (localX in 0 until localWidth - 1) {
                val x = tile.startX + localX
                val y = tile.startY + localY
                val i00 = y * grid.width + x
                val i10 = i00 + 1
                val i01 = i00 + grid.width
                val i11 = i01 + 1
                if (!grid.validData[i00] || !grid.validData[i10] || !grid.validData[i01] || !grid.validData[i11]) {
                    continue
                }
                val v00 = localY * localWidth + localX
                val v10 = v00 + 1
                val v01 = v00 + localWidth
                val v11 = v01 + 1
                if (intIndices != null) {
                    intIndices[indexCount++] = v00
                    intIndices[indexCount++] = v01
                    intIndices[indexCount++] = v10
                    intIndices[indexCount++] = v10
                    intIndices[indexCount++] = v01
                    intIndices[indexCount++] = v11
                } else {
                    shortIndices[indexCount++] = v00.toShort()
                    shortIndices[indexCount++] = v01.toShort()
                    shortIndices[indexCount++] = v10.toShort()
                    shortIndices[indexCount++] = v10.toShort()
                    shortIndices[indexCount++] = v01.toShort()
                    shortIndices[indexCount++] = v11.toShort()
                }
            }
        }
        if (indexCount == 0) return null
        return TerrainGpuBatch(
            tile = tile,
            vertices = vertices,
            indices = if (intIndices != null) ShortArray(0) else shortIndices.copyOf(indexCount),
            intIndices = intIndices?.copyOf(indexCount),
            indexOnlyVertexCount = if (indexOnly) localWidth * localHeight else 0,
        )
    }

    private fun elevationBounds(grid: ElevationGrid): Pair<Float, Float> {
        var minElevation = Float.MAX_VALUE
        var maxElevation = -Float.MAX_VALUE
        for (index in grid.bareEarth.indices) {
            if (!grid.validData[index]) continue
            val value = grid.bareEarth[index]
            if (!value.isFinite()) continue
            if (value < minElevation) minElevation = value
            if (value > maxElevation) maxElevation = value
        }
        require(minElevation != Float.MAX_VALUE) { "Terrain contains no valid elevations" }
        return minElevation to maxElevation
    }

    private fun writeVertices(
        vertices: FloatArray,
        tile: TerrainSpatialTile,
        bare: FloatArray,
        valid: BooleanArray?,
        gridWidth: Int,
        gridHeight: Int,
        cellSizeMeters: Float,
        minElevation: Float,
        elevationRange: Float,
    ) {
        val localWidth = tile.endXInclusive - tile.startX + 1
        val localHeight = tile.endYInclusive - tile.startY + 1
        val xDenom = if (gridWidth > 1) (gridWidth - 1).toFloat() else 1f
        val yDenom = if (gridHeight > 1) (gridHeight - 1).toFloat() else 1f
        val span = max(2f * cellSizeMeters, 0.001f)
        val lastX = gridWidth - 1
        val lastY = gridHeight - 1
        for (localY in 0 until localHeight) {
            val y = tile.startY + localY
            val row = y * gridWidth
            val interiorY = y > 0 && y < lastY
            for (localX in 0 until localWidth) {
                val x = tile.startX + localX
                val sourceIndex = row + x
                val vertexIndex = (localY * localWidth + localX) * TerrainGpuBatch.FLOATS_PER_VERTEX
                val center = bare[sourceIndex]
                val isValid = if (valid != null) valid[sourceIndex] && center.isFinite() else center.isFinite()
                val elevation = if (isValid) center else minElevation
                vertices[vertexIndex] = if (gridWidth > 1) x.toFloat() / xDenom * 2f - 1f else 0f
                vertices[vertexIndex + 1] = if (gridHeight > 1) 1f - y.toFloat() / yDenom * 2f else 0f
                vertices[vertexIndex + 2] = ((elevation - minElevation) / elevationRange - 0.5f) * 0.75f
                if (!isValid) {
                    vertices[vertexIndex + 3] = 0f
                    vertices[vertexIndex + 4] = 0f
                    vertices[vertexIndex + 5] = 0f
                    continue
                }
                val left: Float
                val right: Float
                val up: Float
                val down: Float
                if (interiorY && x > 0 && x < lastX) {
                    left = sampleOrCenter(bare, valid, sourceIndex - 1, center)
                    right = sampleOrCenter(bare, valid, sourceIndex + 1, center)
                    up = sampleOrCenter(bare, valid, sourceIndex - gridWidth, center)
                    down = sampleOrCenter(bare, valid, sourceIndex + gridWidth, center)
                } else {
                    left = sampleClamped(bare, valid, x - 1, y, gridWidth, gridHeight, center)
                    right = sampleClamped(bare, valid, x + 1, y, gridWidth, gridHeight, center)
                    up = sampleClamped(bare, valid, x, y - 1, gridWidth, gridHeight, center)
                    down = sampleClamped(bare, valid, x, y + 1, gridWidth, gridHeight, center)
                }
                val dx = (right - left) / span
                val dy = (down - up) / span
                val nx = -dx
                val ny = dy
                val nz = 1f
                val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
                vertices[vertexIndex + 3] = nx / length
                vertices[vertexIndex + 4] = ny / length
                vertices[vertexIndex + 5] = nz / length
            }
        }
    }

    private fun sampleOrCenter(
        bare: FloatArray,
        valid: BooleanArray?,
        index: Int,
        center: Float,
    ): Float {
        val value = bare[index]
        val usable = if (valid != null) valid[index] && value.isFinite() else value.isFinite()
        return if (usable) value else center
    }

    private fun sampleClamped(
        bare: FloatArray,
        valid: BooleanArray?,
        x: Int,
        y: Int,
        gridWidth: Int,
        gridHeight: Int,
        center: Float,
    ): Float {
        val sx = when {
            x < 0 -> 0
            x >= gridWidth -> gridWidth - 1
            else -> x
        }
        val sy = when {
            y < 0 -> 0
            y >= gridHeight -> gridHeight - 1
            else -> y
        }
        return sampleOrCenter(bare, valid, sy * gridWidth + sx, center)
    }

    private val meshPool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    ) { task -> Thread(task, "terrain-mesh").apply { isDaemon = true } }
}
