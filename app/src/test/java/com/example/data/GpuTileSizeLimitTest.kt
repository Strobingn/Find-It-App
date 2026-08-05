package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuTileSizeLimitTest {
    @Test
    fun fastTileSizeStaysWithinUnsignedShortVertexLimit() {
        val tile = TerrainDecodeCoordinator.GPU_FAST_TILE_SIZE
        assertTrue(
            "GPU_FAST_TILE_SIZE=$tile must be ≤ ${TerrainGpuSceneBuilder.MAX_SAFE_TILE_SIZE}",
            tile <= TerrainGpuSceneBuilder.MAX_SAFE_TILE_SIZE,
        )
        assertTrue(tile.toLong() * tile <= 65_535L)
    }

    @Test
    fun buildAcceptsPreviouslyBroken256TileRequestByClamping() {
        // Before the fix, tileSize=256 crashed with bare "Failed requirement" on 256×256 batches.
        val width = 512
        val height = 384
        val size = width * height
        val grid = ElevationGrid(
            width = width,
            height = height,
            bareEarth = FloatArray(size) { index ->
                val x = index % width
                val y = index / width
                (x + y).toFloat()
            },
            canopySpikes = FloatArray(size),
            validData = BooleanArray(size) { true },
        )
        val scene = TerrainGpuSceneBuilder.build(
            source = grid,
            maxFinestDimension = 512,
            tileSize = 256, // request the unsafe size; builder must clamp
        )
        assertTrue(scene.levels.isNotEmpty())
        assertTrue(scene.levels.flatMap { it.batches }.all { it.vertexCount <= 65_535 })
        assertEquals(512, scene.sourceWidth)
    }
}
