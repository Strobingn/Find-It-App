package com.example.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainPhase2PerformanceTest {
    @Test
    fun lodPyramidCapsFinestDimensionAndSelectsCoarserLevelsAtLowZoom() {
        val grid = sampleGrid(width = 256, height = 128)
        val pyramid = TerrainLodPyramid.build(
            source = grid,
            maxFinestDimension = 128,
            minDimension = 32,
            maxLevels = 4,
        )

        assertEquals(128, pyramid.finest.grid.width)
        assertEquals(2, pyramid.finest.reductionFactor)
        assertTrue(pyramid.levels.size >= 2)
        assertEquals(pyramid.coarsest, pyramid.selectForZoom(1f))
        assertEquals(pyramid.finest, pyramid.selectForZoom(8f))
    }

    @Test
    fun spatialIndexSkipsNoDataTiles() {
        val width = 128
        val height = 128
        val valid = BooleanArray(width * height) { index ->
            val x = index % width
            val y = index / width
            x < 64 && y < 64
        }
        val grid = ElevationGrid(
            width,
            height,
            FloatArray(width * height) { it.toFloat() },
            FloatArray(width * height),
            validData = valid,
        )

        val index = TerrainSpatialGridIndex.build(grid, tileSize = 64)

        assertEquals(4, index.tiles.size)
        assertEquals(1, index.nonEmptyTiles().size)
        assertEquals(1, index.query(NormalizedRasterBounds.Full).size)
    }

    @Test
    fun gpuSceneUsesBoundedUnsignedShortBatchesAndMultipleLodLevels() {
        val scene = TerrainGpuSceneBuilder.build(
            source = sampleGrid(320, 240),
            maxFinestDimension = 256,
            tileSize = 64,
        )

        assertTrue(scene.levels.size >= 2)
        assertTrue(scene.levels.all { level -> level.batches.isNotEmpty() })
        assertTrue(scene.levels.flatMap { it.batches }.all { batch -> batch.vertexCount <= 65_535 })
        assertTrue(scene.levels.flatMap { it.batches }.all { batch -> batch.indices.isNotEmpty() })
    }

    @Test
    fun persistentCacheRoundTripsTerrainAndInvalidatesWhenSourceChanges() {
        val root = Files.createTempDirectory("findit-phase2-cache").toFile()
        try {
            val source = File(root, "source.laz").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val cache = LazTerrainDiskCache(File(root, "cache"), maxBytes = 8L * 1024L * 1024L)
            val options = LidarImportOptions(rasterResolution = 128)
            val expected = DemGenerator.TerrainLoadResult(
                grid = sampleGrid(64, 32),
                summary = "test terrain",
                isBareEarth = true,
            )

            cache.put(source, options, expected)
            val restored = cache.get(source, options)

            assertNotNull(restored)
            assertEquals(expected.grid.width, restored!!.grid.width)
            assertEquals(expected.grid.height, restored.grid.height)
            assertEquals(expected.summary, restored.summary)
            assertTrue(cache.sizeBytes() > 0L)

            source.appendBytes(byteArrayOf(4))
            assertEquals(null, cache.get(source, options))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sampleGrid(width: Int, height: Int): ElevationGrid {
        val bare = FloatArray(width * height) { index ->
            val x = index % width
            val y = index / width
            (x * 0.05f) + (y * 0.03f)
        }
        val canopy = FloatArray(width * height) { index -> if (index % 19 == 0) 2f else 0f }
        val valid = BooleanArray(width * height) { true }
        assertFalse(bare.isEmpty())
        return ElevationGrid(width, height, bare, canopy, cellSizeMeters = 0.5f, validData = valid)
    }
}
