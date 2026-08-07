package com.example.data.mosaic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MosaicStressSuiteTest {

    @Test
    fun run_passesWithHealthyHeapAndCancel() {
        val report = MosaicStressSuite.run(
            mosaicTileCount = 4,
            cancelRequested = true,
            cacheHit = true,
            priorTerrainPreserved = true,
            dualGridHeldBytes = 1_000_000L,
            maxHeapBytes = 512L * 1024 * 1024,
            reservedHeadroomBytes = 100L * 1024 * 1024,
        )
        assertTrue(report.passCount >= 4)
        assertTrue(report.format().contains("Mosaic stress QA"))
        assertTrue(report.format().contains("LiDAR", ignoreCase = true) || report.honestyLine.contains("metal"))
    }

    @Test
    fun cancelWithoutPrior_failsCancelScenario() {
        val report = MosaicStressSuite.run(
            cancelRequested = true,
            priorTerrainPreserved = false,
            maxHeapBytes = 512L * 1024 * 1024,
            reservedHeadroomBytes = 100L * 1024 * 1024,
        )
        val cancel = report.results.first { it.id == MosaicStressSuite.ScenarioId.CANCEL_COOPERATIVE }
        assertFalse(cancel.passed)
    }

    @Test
    fun hugeTileCount_failsScale() {
        val report = MosaicStressSuite.run(
            mosaicTileCount = 40,
            maxHeapBytes = 512L * 1024 * 1024,
            reservedHeadroomBytes = 100L * 1024 * 1024,
        )
        val tiles = report.results.first { it.id == MosaicStressSuite.ScenarioId.LARGE_TILE_COUNT }
        assertFalse(tiles.passed)
    }
}
