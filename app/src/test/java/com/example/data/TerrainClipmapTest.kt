package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the per-LOD heightmap resources the ES 3.0 vertex-texture-fetch renderer consumes:
 * one clipmap per built level, NaN where the grid has no renderable data, and the absence
 * contract when the scene is built without a heightmap (CPU-batch fallback scenes).
 */
class TerrainClipmapTest {

    @Test
    fun clipmapsFollowBuiltLevelsWhenAttached() {
        val scene = TerrainGpuSceneBuilder.build(
            source = sampleGrid(200, 120),
            maxFinestDimension = 100,
            tileSize = 32,
            attachHeightmap = true,
        )

        assertEquals(scene.levels.size, scene.clipmaps.size)
        scene.levels.zip(scene.clipmaps).forEach { (level, clipmap) ->
            assertEquals(level.reductionFactor, clipmap.reductionFactor)
            assertEquals(level.gridWidth, clipmap.heightmap.width)
            assertEquals(level.gridHeight, clipmap.heightmap.height)
            // Cell size doubles with each reduction: the vertex shader normal math depends on it.
            assertEquals(0.5f * level.reductionFactor, clipmap.heightmap.cellSizeMeters)
            // Every finite height must sit inside the elevation window passed to the shader.
            clipmap.heightmap.floats.filter { it.isFinite() }.forEach { z ->
                assertTrue(z >= clipmap.minElevation)
                assertTrue(z <= clipmap.maxElevation)
            }
        }
    }

    @Test
    fun clipmapsAbsentWithoutHeightmap() {
        val scene = TerrainGpuSceneBuilder.build(
            source = sampleGrid(128, 64),
            maxFinestDimension = 128,
            tileSize = 32,
            attachHeightmap = false,
        )

        assertTrue(scene.heightmap == null)
        assertTrue(scene.clipmaps.isEmpty())
        assertTrue(scene.cdlodNodes.isEmpty())
        assertTrue(scene.levels.first().batches.isNotEmpty())
    }

    @Test
    fun finestClipmapMatchesSourceGridWhenUncapped() {
        val width = 128
        val height = 64
        val bare = FloatArray(width * height) { index -> (index % width) * 0.25f }
        val valid = BooleanArray(width * height) { true }
        // A no-data block and one valid-but-non-finite cell must both surface as NaN,
        // matching the HeightmapCdlod.pack contract with the vertex shader.
        for (y in 10..14) {
            for (x in 20..25) valid[y * width + x] = false
        }
        bare[40 * width + 30] = Float.NaN
        val source = ElevationGrid(
            width,
            height,
            bare,
            FloatArray(width * height),
            cellSizeMeters = 0.5f,
            validData = valid,
        )

        val scene = TerrainGpuSceneBuilder.build(
            source = source,
            tileSize = 32,
            attachHeightmap = true,
        )

        assertEquals(1, scene.levels.first().reductionFactor)
        val clipmap = scene.clipmaps.first()
        assertEquals(width, clipmap.heightmap.width)
        assertEquals(height, clipmap.heightmap.height)
        for (index in clipmap.heightmap.floats.indices) {
            val renderable = valid[index] && bare[index].isFinite()
            if (renderable) {
                assertEquals(bare[index], clipmap.heightmap.floats[index], 0f)
            } else {
                assertTrue(clipmap.heightmap.floats[index].isNaN())
            }
        }
    }

    private fun sampleGrid(width: Int, height: Int): ElevationGrid {
        val bare = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (x * 0.05f) + (y * 0.03f)
        }
        val canopy = FloatArray(width * height)
        val valid = BooleanArray(width * height) { true }
        return ElevationGrid(width, height, bare, canopy, cellSizeMeters = 0.5f, validData = valid)
    }
}
