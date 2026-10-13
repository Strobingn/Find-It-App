package com.example.data

import com.example.ui.DEFAULT_VEGETATION_FILTER
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerrainFixAllRegressionTest {
    /** The default hillshade must be pure bare earth: blending 20% canopy caused "pebble" noise. */
    @Test
    fun defaultHillshadeIsBareEarth() {
        assertEquals(1f, DEFAULT_VEGETATION_FILTER, 0f)
        val grid = ElevationGrid(2, 1, floatArrayOf(10f, 11f), floatArrayOf(25f, 0f))
        assertEquals(10f, grid.getElevationAt(0, 0, DEFAULT_VEGETATION_FILTER), 0f)
    }

    @Test
    fun memoryCacheKeyIncludesClassFilter() {
        val file = File.createTempFile("find-it-cache-", ".laz")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val options = LidarImportOptions(rasterResolution = 256)
            val filtered = options.copy(allowedClasses = setOf(2, 6))
            val result = DemGenerator.TerrainLoadResult(
                grid = ElevationGrid(2, 2, floatArrayOf(1f, 2f, 3f, 4f), FloatArray(4)),
                summary = "filtered",
                isBareEarth = true,
            )
            val cache = LazTerrainMemoryCache(maxBytes = 1024 * 1024)
            cache.put(file, filtered, result)
            assertNull(cache.get(file, options))
            assertSame(result, cache.get(file, options.copy(allowedClasses = setOf(6, 2))))
        } finally {
            file.delete()
        }
    }

    /** read(InputStream) used to stop at the first LAZ chunk (50,000 of 10.6M points on autzen). */
    @Test
    fun streamReadDecodesEveryChunk() {
        val file = File(System.getenv("FINDIT_LARGE_LAZ") ?: "/workspace/lazdata/autzen-classified.laz")
        assumeTrue("LAZ fixture not present", file.isFile)
        val options = LidarImportOptions().sanitized()
        val fromFile = LazTerrainReader.read(file, options)!!
        val fromStream = file.inputStream().use { LazTerrainReader.read(it, options) }!!
        assertEquals(fromFile.totalPointsRead, fromStream.totalPointsRead)
        assertEquals(fromFile.grid.width, fromStream.grid.width)
        org.junit.Assert.assertArrayEquals(fromFile.grid.bareEarth, fromStream.grid.bareEarth, 1e-4f)
    }
}
