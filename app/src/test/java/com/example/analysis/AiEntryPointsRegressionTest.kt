package com.example.analysis

import com.example.analysis.ml.CandidateFeatures
import com.example.data.ElevationGrid
import com.example.data.GroundSurfaceMode
import com.example.data.LazTerrainReader
import com.example.data.LidarImportOptions
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Regression for the 1000685 AI crash: drives every AI consumer of the derived layers on real
 * LAZ (skipped when absent) and on no-data edge grids. NaN no-data cells reached roundToInt()
 * in MetalDetectingTargetRefiner and the layer renderer.
 */
class AiEntryPointsRegressionTest {
    private var totalTargets = 0
    private fun exercise(label: String, grid: ElevationGrid): List<String> {
        val failures = ArrayList<String>()
        val dir = Files.createTempDirectory("ai").toFile()
        fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure { e ->
                val at = e.stackTrace.firstOrNull { it.className.startsWith("com.example") }
                failures += "$label/$name: $e at $at"
            }
        }
        try {
            var result: TerrainIntelligenceResult? = null
            step("analyze") { result = runBlocking { TerrainIntelligenceEngine(TerrainDerivedLayerCache(dir)).analyze(grid, "harness") } }
            val r = result ?: return failures
            step("homesite") { HomesiteProbabilityMap.compute(r.layers).binned(24) }
            var targets: List<MetalDetectingTarget> = emptyList()
            step("refineTargets") { targets = MetalDetectingTargetRefiner.refine(r) }
            step("refineTargetsMap") { MetalDetectingTargetRefiner.refine(r, historicMapAgreementScore = 0.7f) }
            step("digDepth") { targets.forEach { DigDepthEstimator.estimate(it, r.layers) } }
            step("features") { r.candidates.forEach { CandidateFeatures.extract(it, r.layers) } }
            step("historic") { HistoricFeatureClassifier.refine(r.candidates, r.layers) }
            step("restore") { runBlocking { TerrainIntelligenceEngine(TerrainDerivedLayerCache(dir)).analyze(grid, "harness") } }
            if (label.startsWith("autzen") || label.startsWith("nanBorder")) totalTargets += targets.size
            println("AI $label candidates=${r.candidates.size} targets=${targets.size} rec=${r.recommendation.take(50)}")
        } finally {
            dir.deleteRecursively()
        }
        return failures
    }

    @Test
    fun allEntryPoints() {
        val failures = ArrayList<String>()
        val files = listOf("autzen-classified.laz", "copc.laz", "simple.laz", "1_4_w_evlr.laz", "test1_4.las",
            "USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz")
        for (name in files) {
            val f = File("/workspace/lazdata/$name"); if (!f.isFile) continue
            for (mode in GroundSurfaceMode.values()) {
                val g = LazTerrainReader.read(f, LidarImportOptions(groundMode = mode).sanitized())?.grid ?: continue
                failures += exercise("$name/$mode", g)
            }
        }
        fun synth(w: Int, h: Int, z: (Int, Int) -> Float, valid: (Int, Int) -> Boolean = { _, _ -> true }) =
            ElevationGrid(w, h, FloatArray(w * h) { i -> if (valid(i % w, i / w)) z(i % w, i / w) else Float.NaN },
                FloatArray(w * h), 1f, BooleanArray(w * h) { i -> valid(i % w, i / w) })
        failures += exercise("allNaN", synth(64, 64, { _, _ -> 0f }, { _, _ -> false }))
        failures += exercise("tiny1x1", synth(1, 1, { _, _ -> 5f }))
        failures += exercise("tiny2x3", synth(2, 3, { x, y -> x + y * 1f }))
        failures += exercise("tiny8x8", synth(8, 8, { x, y -> x + y * 1f }))
        failures += exercise("flat", synth(64, 64, { _, _ -> 100f }))
        failures += exercise("nanBorder", synth(80, 80, { x, y -> 100f + kotlin.math.sin(x * 0.3f) * 2f + y * 0.1f }, { x, y -> x in 8..71 && y in 8..71 }))
        failures += exercise("singleValid", synth(40, 40, { _, _ -> 3f }, { x, y -> x == 20 && y == 20 }))
        failures += exercise("checkerNaN", synth(60, 60, { x, y -> (x * y % 7).toFloat() }, { x, y -> (x + y) % 2 == 0 }))
        failures += exercise("big4000x30", synth(4000, 30, { x, y -> kotlin.math.sin(x * 0.05f) * 5f + y }))
        println("AIFAILURES ${failures.size}")
        failures.forEach { println("AIFAIL $it") }
        org.junit.Assert.assertTrue("AI entry points failed:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
