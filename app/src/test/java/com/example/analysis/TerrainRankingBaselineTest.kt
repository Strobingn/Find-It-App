package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainRankingBaselineTest {
    @Test
    fun naturalFeatureRiskLowersHumanStructureScore() {
        val clear = TerrainIntelligenceEngine.applyNaturalFeaturePenalty(0.82f, 0.10f, 0.40f)
        val drainage = TerrainIntelligenceEngine.applyNaturalFeaturePenalty(0.82f, 0.90f, 0.40f)

        assertTrue(clear > drainage)
        assertEquals(0.46f, drainage, 1e-4f)
    }

    @Test
    fun penaltyIsBoundedAndDoesNotCreateNegativeScores() {
        assertEquals(0f, TerrainIntelligenceEngine.applyNaturalFeaturePenalty(0.1f, 1f, 2f), 1e-4f)
        assertEquals(1f, TerrainIntelligenceEngine.applyNaturalFeaturePenalty(1.2f, -1f, -2f), 1e-4f)
    }
}
