package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The modern-disturbance penalty exists to push recent grading, cut-and-fill, and vehicle
 * scarring below pre-industrial features in the ranking. These pin its two signatures: sharp
 * straight cut/fill edges must read high, machine-smoothed benches must read moderate, and the
 * historic features it protects - linear-but-gentle wagon roads, rough-but-flat cellar rims -
 * must stay low.
 */
class ModernDisturbancePenaltyTest {

    @Test
    fun aSharpStraightCutFillEdgeReadsHigh() {
        val penalty = TerrainIntelligenceEngine.combineModernDisturbance(
            slopeNorm = 0.9f,
            linearityNorm = 0.9f,
            ruggedNorm = 0.4f,
            hillCompareNorm = 0.5f,
        )

        assertTrue("steep + strongly linear must read as likely modern", penalty > 0.5f)
    }

    @Test
    fun aMachineSmoothedBenchReadsModerate() {
        val penalty = TerrainIntelligenceEngine.combineModernDisturbance(
            slopeNorm = 0.05f,
            linearityNorm = 0.2f,
            ruggedNorm = 0.05f,
            hillCompareNorm = 0.9f,
        )

        assertTrue(penalty > 0.2f)
        assertTrue("a bench alone is weaker than a cut/fill edge", penalty < 0.5f)
    }

    /** Wagon roads are linear but gentle: linearity alone must not trip the penalty. */
    @Test
    fun aLinearButGentleHistoricRoadStaysLow() {
        val penalty = TerrainIntelligenceEngine.combineModernDisturbance(
            slopeNorm = 0.08f,
            linearityNorm = 0.85f,
            ruggedNorm = 0.3f,
            hillCompareNorm = 0.3f,
        )

        assertTrue(penalty < 0.15f)
    }

    /** A flat but rough historic site is the opposite mismatch and must also stay low. */
    @Test
    fun aFlatButRoughHistoricRimStaysLow() {
        val penalty = TerrainIntelligenceEngine.combineModernDisturbance(
            slopeNorm = 0.1f,
            linearityNorm = 0.2f,
            ruggedNorm = 0.85f,
            hillCompareNorm = 0.4f,
        )

        assertTrue(penalty < 0.1f)
    }

    @Test
    fun featurelessGroundReadsZero() {
        assertEquals(
            0f,
            TerrainIntelligenceEngine.combineModernDisturbance(0f, 0f, 1f, 0f),
            1e-4f,
        )
    }

    @Test
    fun outputIsBoundedForOutOfRangeInputs() {
        val high = TerrainIntelligenceEngine.combineModernDisturbance(2f, 2f, -1f, 3f)
        val low = TerrainIntelligenceEngine.combineModernDisturbance(-2f, -2f, 3f, -3f)

        assertTrue(high in 0f..1f)
        assertEquals(0f, low, 1e-4f)
    }

    /** The penalty must discount, never erase: even maximal evidence keeps the score non-negative. */
    @Test
    fun scoringStaysBoundedWhenBothPenaltiesApply() {
        val afterNatural = TerrainIntelligenceEngine.applyNaturalFeaturePenalty(0.9f, 0.8f, 0.40f)
        val afterModern = TerrainIntelligenceEngine.applyNaturalFeaturePenalty(afterNatural, 0.9f, 0.30f)

        assertTrue(afterModern >= 0f)
        assertTrue(afterModern < afterNatural)
        assertEquals(0.31f, afterModern, 1e-4f)
    }
}
