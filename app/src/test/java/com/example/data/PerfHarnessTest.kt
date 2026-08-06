package com.example.data

import org.junit.Assert.assertTrue
import org.junit.Test

class PerfHarnessTest {

    @Test
    fun runSyntheticTerrainBenchmark_producesReport() {
        PerfHarness.clear()
        val report = PerfHarness.runSyntheticTerrainBenchmark(gridSide = 48, repeats = 1)
        assertTrue(report.contains("Perf harness", ignoreCase = true))
        assertTrue(
            report.contains("synthetic_grid_build") ||
                report.contains("grid_scan") ||
                report.contains("hillshade"),
        )
        assertTrue(PerfHarness.allSamples().isNotEmpty())
        val summary = PerfHarness.summarize()
        assertTrue(summary.any { it.count >= 1 })
    }

    @Test
    fun time_recordsSample() {
        PerfHarness.clear()
        val v = PerfHarness.time("unit_test_op", "detail") { 42 }
        assertTrue(v == 42)
        assertTrue(PerfHarness.allSamples().any { it.name == "unit_test_op" })
    }
}
