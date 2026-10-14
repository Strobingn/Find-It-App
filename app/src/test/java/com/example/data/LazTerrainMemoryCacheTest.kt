package com.example.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LazTerrainMemoryCacheTest {
    @Test
    fun reusesOnlyMatchingFileAndImportOptions() {
        val file = File.createTempFile("find-it-cache-", ".laz")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val options = LidarImportOptions(
                groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                rasterResolution = 256,
                smoothingRadius = 0,
            )
            val result = DemGenerator.TerrainLoadResult(
                grid = ElevationGrid(
                    width = 2,
                    height = 2,
                    bareEarth = floatArrayOf(1f, 2f, 3f, 4f),
                    canopySpikes = FloatArray(4),
                ),
                summary = "cached",
                isBareEarth = true,
            )
            val cache = LazTerrainMemoryCache(maxBytes = 1024 * 1024)

            cache.put(file, options, result)

            assertSame(result, cache.get(file, options))
            assertNull(cache.get(file, options.copy(rasterResolution = 512)))

            file.appendBytes(byteArrayOf(4))
            assertNull(cache.get(file, options))
        } finally {
            file.delete()
        }
    }

    @Test
    fun panInsideADecodedTileReusesItUntilTheCellsWouldGetCoarser() {
        val file = File.createTempFile("find-it-cover-", ".laz")
        try {
            file.writeBytes(byteArrayOf(1))
            val side = 256
            val elevations = FloatArray(side * side) { it.toFloat() }
            val cache = LazTerrainMemoryCache(maxBytes = 16 * 1024 * 1024)
            cache.put(
                file,
                LidarImportOptions(rasterResolution = side),
                DemGenerator.TerrainLoadResult(
                    grid = ElevationGrid(side, side, elevations, FloatArray(elevations.size)),
                    summary = "overview",
                    isBareEarth = true,
                ),
            )
            val inside = cache.findCovering(
                file,
                LidarImportOptions(
                    rasterResolution = 128,
                    focusBounds = NormalizedRasterBounds(0.0, 0.0, 0.9, 0.9),
                ),
            )
            requireNotNull(inside)
            assertEquals(0f, inside.grid.bareEarth[0])
            assertEquals(1f, inside.grid.bareEarth[1])

            assertNull(
                cache.findCovering(
                    file,
                    LidarImportOptions(
                        rasterResolution = 2_048,
                        focusBounds = NormalizedRasterBounds(0.40, 0.40, 0.50, 0.50),
                    ),
                ),
            )
        } finally {
            file.delete()
        }
    }
}
