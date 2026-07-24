package com.example.data

import kotlin.math.max
import kotlin.math.sqrt

/** Interleaved position/normal vertices and unsigned-short triangle indices for one spatial tile. */
data class TerrainGpuBatch(
    val tile: TerrainSpatialTile,
    val vertices: FloatArray,
    val indices: ShortArray,
) {
    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
    val triangleCount: Int get() = indices.size / 3

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

/** Immutable multi-LOD scene ready to upload to OpenGL vertex/index buffers. */
data class TerrainGpuScene(
    val levels: List<TerrainGpuLevel>,
    val sourceWidth: Int,
    val sourceHeight: Int,
) {
    init {
        require(levels.isNotEmpty())
    }

    fun selectForZoom(zoom: Float): TerrainGpuLevel {
        val safeZoom = zoom.coerceAtLeast(1f)
        val desiredFactor = when {
            safeZoom >= 5f -> 1
            safeZoom >= 2.5f -> 2
            safeZoom >= 1.5f -> 4
            else -> Int.MAX_VALUE
        }
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
 * omitted entirely. The finest GPU level is capped at 512 cells on its longest side while the CPU
 * analysis grid can remain at 1024².
 */
object TerrainGpuSceneBuilder {
    fun build(
        source: ElevationGrid,
        maxFinestDimension: Int = 512,
        tileSize: Int = 64,
    ): TerrainGpuScene {
        val pyramid = TerrainLodPyramid.build(
            source = source,
            maxFinestDimension = maxFinestDimension,
            minDimension = 64,
            maxLevels = 4,
        )
        val levels = pyramid.levels.map { level ->
            val index = TerrainSpatialGridIndex.build(level.grid, tileSize)
            val elevationBounds = elevationBounds(level.grid)
            TerrainGpuLevel(
                reductionFactor = level.reductionFactor,
                gridWidth = level.grid.width,
                gridHeight = level.grid.height,
                batches = index.nonEmptyTiles().mapNotNull { tile ->
                    buildBatch(level.grid, tile, elevationBounds.first, elevationBounds.second)
                },
            )
        }.filter { it.batches.isNotEmpty() }

        require(levels.isNotEmpty()) { "Terrain contains no renderable cells" }
        return TerrainGpuScene(levels, source.width, source.height)
    }

    internal fun buildBatch(grid: ElevationGrid, tile: TerrainSpatialTile): TerrainGpuBatch? {
        val bounds = elevationBounds(grid)
        return buildBatch(grid, tile, bounds.first, bounds.second)
    }

    private fun buildBatch(
        grid: ElevationGrid,
        tile: TerrainSpatialTile,
        minElevation: Float,
        maxElevation: Float,
    ): TerrainGpuBatch? {
        if (tile.isEmpty) return null
        val localWidth = tile.endXInclusive - tile.startX + 1
        val localHeight = tile.endYInclusive - tile.startY + 1
        if (localWidth < 2 || localHeight < 2) return null
        require(localWidth.toLong() * localHeight <= 65_535L)
        val elevationRange = (maxElevation - minElevation).takeIf { it > 0f } ?: 1f

        val vertices = FloatArray(localWidth * localHeight * TerrainGpuBatch.FLOATS_PER_VERTEX)
        for (localY in 0 until localHeight) {
            for (localX in 0 until localWidth) {
                val x = tile.startX + localX
                val y = tile.startY + localY
                val sourceIndex = y * grid.width + x
                val vertexIndex = (localY * localWidth + localX) * TerrainGpuBatch.FLOATS_PER_VERTEX
                val valid = grid.validData[sourceIndex] && grid.bareEarth[sourceIndex].isFinite()
                val elevation = if (valid) grid.bareEarth[sourceIndex] else minElevation

                vertices[vertexIndex] = if (grid.width > 1) x.toFloat() / (grid.width - 1) * 2f - 1f else 0f
                vertices[vertexIndex + 1] = if (grid.height > 1) 1f - y.toFloat() / (grid.height - 1) * 2f else 0f
                vertices[vertexIndex + 2] = ((elevation - minElevation) / elevationRange - 0.5f) * 0.75f
                writeNormal(grid, x, y, valid, vertices, vertexIndex + 3)
            }
        }

        val indices = ShortArray((localWidth - 1) * (localHeight - 1) * 6)
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
                indices[indexCount++] = v00.toShort()
                indices[indexCount++] = v01.toShort()
                indices[indexCount++] = v10.toShort()
                indices[indexCount++] = v10.toShort()
                indices[indexCount++] = v01.toShort()
                indices[indexCount++] = v11.toShort()
            }
        }
        if (indexCount == 0) return null
        return TerrainGpuBatch(tile, vertices, indices.copyOf(indexCount))
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

    private fun writeNormal(
        grid: ElevationGrid,
        x: Int,
        y: Int,
        valid: Boolean,
        target: FloatArray,
        offset: Int,
    ) {
        if (!valid) {
            target[offset] = 0f
            target[offset + 1] = 0f
            target[offset + 2] = 0f
            return
        }

        fun elevationAt(sampleX: Int, sampleY: Int): Float {
            val sx = sampleX.coerceIn(0, grid.width - 1)
            val sy = sampleY.coerceIn(0, grid.height - 1)
            val index = sy * grid.width + sx
            return if (grid.validData[index] && grid.bareEarth[index].isFinite()) {
                grid.bareEarth[index]
            } else {
                grid.bareEarth[y * grid.width + x].takeIf { it.isFinite() } ?: 0f
            }
        }

        val dx = (elevationAt(x + 1, y) - elevationAt(x - 1, y)) /
            max(2f * grid.cellSizeMeters, 0.001f)
        val dy = (elevationAt(x, y + 1) - elevationAt(x, y - 1)) /
            max(2f * grid.cellSizeMeters, 0.001f)
        var nx = -dx
        var ny = dy
        var nz = 1f
        val length = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(0.0001f)
        nx /= length
        ny /= length
        nz /= length
        target[offset] = nx
        target[offset + 1] = ny
        target[offset + 2] = nz
    }
}
