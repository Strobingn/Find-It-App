package com.example.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Low-memory profile (fewer decode workers, smaller caches) must not change the decoded grid. */
class LowMemoryDecodeIdentityTest {
    @After fun reset() { AppMemoryBudget.lowMemoryOverride = null }

    private fun decode(file: File, opts: LidarImportOptions, low: Boolean): ElevationGrid {
        AppMemoryBudget.lowMemoryOverride = low
        return LazTerrainReader.read(file, opts.sanitized())!!.grid
    }

    private fun assertIdentical(file: File, opts: LidarImportOptions) {
        val a = decode(file, opts, low = false)
        val b = decode(file, opts, low = true)
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertEquals(a.cellSizeMeters, b.cellSizeMeters, 0f)
        assertArrayEquals(a.bareEarth.map { it.toRawBits() }.toIntArray(), b.bareEarth.map { it.toRawBits() }.toIntArray())
        assertArrayEquals(a.canopySpikes.map { it.toRawBits() }.toIntArray(), b.canopySpikes.map { it.toRawBits() }.toIntArray())
        assertArrayEquals(a.validData, b.validData)
        assertArrayEquals(a.interpolatedData, b.interpolatedData)
    }

    private fun check(path: String) {
        val f = File(path); assumeTrue("missing $path", f.isFile)
        assertIdentical(f, LidarImportOptions(rasterResolution = 1024))
        assertIdentical(f, LidarImportOptions(rasterResolution = 2048, focusBounds = NormalizedRasterBounds(0.45, 0.45, 0.55, 0.55)))
    }

    @Test fun lowMemoryProfileShrinksBudget() {
        AppMemoryBudget.lowMemoryOverride = false
        val normal = AppMemoryBudget.terrainMemoryCacheBytes()
        AppMemoryBudget.lowMemoryOverride = true
        assert(AppMemoryBudget.terrainMemoryCacheBytes() <= normal)
        assert(AppMemoryBudget.decodeWorkers() <= 2)
    }

    @Test fun autzenIdentical() = check("/workspace/lazdata/autzen-classified.laz")
    @Test fun nyIdentical() = check("/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz")
}
