package com.example.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regression: the first terrain painted for a large multi-chunk LAZ was a sparse chunk preview
 * (diagonal "brick" strips with black gaps), and the Library open path never replaced it with the
 * exact decode. The first outcome handed to onPreview must be the exact, gap-free product.
 *
 * Needs a real multi-chunk LAZ >= 32 MB / >= 3M points (e.g. PDAL autzen_trim.laz). Set
 * FINDIT_LARGE_LAZ to its path; skipped when absent.
 */
class FirstRenderMatchesExactDecodeTest {
    @Test
    fun firstRenderIsExactAndGapFree() = runBlocking {
        val path = System.getenv("FINDIT_LARGE_LAZ") ?: "/workspace/lazdata/autzen-classified.laz"
        val file = File(path)
        assumeTrue("large LAZ fixture not present: $path", file.isFile && file.length() >= 32L shl 20)

        val dir = Files.createTempDirectory("first-render").toFile()
        try {
            val coordinator = TerrainDecodeCoordinator(
                LazTerrainCache(LazTerrainMemoryCache(maxBytes = 512L shl 20), LazTerrainDiskCache(dir)),
            )
            val options = LidarImportOptions()
            val painted = ArrayList<TerrainDecodeOutcome>()
            val outcome = coordinator.decode(file, file.name, options, onPreview = { painted += it })

            val first = painted.firstOrNull() ?: outcome
            assertFalse("first render must not be the sparse chunk preview", first.isPreview)
            val empty = emptyShareInsideHull(first.terrain.grid)
            println("first-render ${first.terrain.grid.width}x${first.terrain.grid.height} emptyInsideHull=$empty")
            assertTrue("empty share inside hull $empty >= 5%", empty < 0.05f)

            val exact = LazTerrainReader.read(file, options.sanitized())!!.grid
            val g = first.terrain.grid
            assertEquals(exact.width, g.width)
            assertEquals(exact.height, g.height)
            assertArrayEquals(exact.bareEarth, g.bareEarth, 1e-4f)
        } finally {
            dir.deleteRecursively()
        }
    }
}
