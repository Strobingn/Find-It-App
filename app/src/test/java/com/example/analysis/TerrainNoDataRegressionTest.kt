package com.example.analysis

import com.example.data.ElevationGrid
import com.example.data.GroundSurfaceMode
import com.example.data.LazTerrainReader
import com.example.data.LidarImportOptions
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Regressions: (1) NaN no-data cells outside the data hull poisoned the windowed analysis
 * kernels and crashed buildRecommendation with "Cannot round NaN value"; (2) auto-lowest ground
 * sat above the highest return (DSM) in measured cells after low-noise median replacement.
 */
class TerrainNoDataRegressionTest {
    private fun assertAnalysisFinite(grid: ElevationGrid) = runBlocking {
        val dir = Files.createTempDirectory("nodata").toFile()
        try {
            val result = TerrainIntelligenceEngine(TerrainDerivedLayerCache(dir)).analyze(grid, "test")
            assertTrue(result.candidates.all { it.score.isFinite() && it.xPercent.isFinite() && it.yPercent.isFinite() })
            for (layer in listOf(TerrainDerivedLayer.LOCAL_RELIEF, TerrainDerivedLayer.CURVATURE,
                TerrainDerivedLayer.RUGGEDNESS, TerrainDerivedLayer.MODERN_DISTURBANCE_PENALTY)) {
                val values = result.layers.values.getValue(layer)
                val finite = values.filter { it.isFinite() }
                assertTrue("$layer has no finite cells", finite.isNotEmpty())
                assertTrue("$layer is constant", finite.distinct().size > 1)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun noDataBorderDoesNotPoisonAnalysis() {
        val w = 96
        val h = 96
        val valid = BooleanArray(w * h) { i -> val x = i % w; val y = i / w; x in 10..85 && y in 10..85 }
        val z = FloatArray(w * h) { i ->
            if (!valid[i]) Float.NaN else {
                val x = i % w; val y = i / w
                100f + x * 0.05f + kotlin.math.sin(y * 0.4f) * 0.6f - if ((x - 48) * (x - 48) + (y - 48) * (y - 48) < 25) 1.5f else 0f
            }
        }
        assertAnalysisFinite(ElevationGrid(w, h, z, FloatArray(w * h), 1f, valid))
    }

    @Test
    fun realLazAnalysisAndLowestBelowDsm() {
        val file = File(System.getenv("FINDIT_LARGE_LAZ") ?: "/workspace/lazdata/autzen-classified.laz")
        assumeTrue("LAZ fixture not present", file.isFile)
        val low = LazTerrainReader.read(file, LidarImportOptions(groundMode = GroundSurfaceMode.AUTO_LOWEST).sanitized())!!.grid
        val dsm = LazTerrainReader.read(file, LidarImportOptions(groundMode = GroundSurfaceMode.SURFACE_MODEL).sanitized())!!.grid
        var violations = 0
        for (i in low.bareEarth.indices) {
            val measured = low.validData[i] && dsm.validData[i] && !low.interpolatedData[i] && !dsm.interpolatedData[i]
            if (measured && low.bareEarth[i] > dsm.bareEarth[i] + 1e-3f) violations++
        }
        assertEquals("auto-lowest above DSM in measured cells", 0, violations)
        assertAnalysisFinite(low)
        val classified = LazTerrainReader.read(file, LidarImportOptions().sanitized())!!.grid
        assertAnalysisFinite(classified)
    }
}
