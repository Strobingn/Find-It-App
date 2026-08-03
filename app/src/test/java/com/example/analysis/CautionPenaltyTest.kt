package com.example.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Caution reasons name the ways a candidate could be a natural feature or a rendering artifact
 * rather than something built. They were labels only, so a candidate carrying every caution the
 * detector could raise still outranked a clean one with a slightly lower raw response.
 */
class CautionPenaltyTest {
    @Test
    fun aCleanCandidateIsNotDemoted() {
        assertEquals(0f, MetalDetectingTargetRefiner.cautionPenalty(0), 1e-6f)
    }

    @Test
    fun eachCautionAddsToTheDemotion() {
        assertEquals(
            MetalDetectingTargetRefiner.CAUTION_PENALTY_EACH,
            MetalDetectingTargetRefiner.cautionPenalty(1),
            1e-6f,
        )
        assertEquals(
            MetalDetectingTargetRefiner.CAUTION_PENALTY_EACH * 2f,
            MetalDetectingTargetRefiner.cautionPenalty(2),
            1e-6f,
        )
    }

    /** The cap is what keeps a heavily-cautioned candidate ranked low rather than deleted. */
    @Test
    fun theDemotionIsCapped() {
        assertEquals(
            MetalDetectingTargetRefiner.CAUTION_PENALTY_CAP,
            MetalDetectingTargetRefiner.cautionPenalty(50),
            1e-6f,
        )
    }

    @Test
    fun theCapIsReachedAndNeverExceeded() {
        (0..20).forEach { count ->
            val penalty = MetalDetectingTargetRefiner.cautionPenalty(count)
            assertTrue(
                "penalty for $count cautions must stay within the cap",
                penalty <= MetalDetectingTargetRefiner.CAUTION_PENALTY_CAP + 1e-6f,
            )
            assertTrue("penalty must never be negative", penalty >= 0f)
        }
    }

    /**
     * A caution is the detector offering a natural explanation; a rejection is someone having
     * stood on the spot. The weaker evidence must not outweigh the stronger.
     */
    @Test
    fun cautionsWeighLessThanAFieldVerifiedRejection() {
        val verifiedRejectionPenalty = 0.28f

        assertTrue(
            MetalDetectingTargetRefiner.CAUTION_PENALTY_CAP < verifiedRejectionPenalty,
        )
    }

    @Test
    fun aNegativeCountIsTreatedAsNone() {
        assertEquals(0f, MetalDetectingTargetRefiner.cautionPenalty(-3), 1e-6f)
    }

    /** Ranking is the point: a cautioned candidate must fall below a clean one it used to tie. */
    @Test
    fun aCautionedCandidateRanksBelowACleanOneOfEqualRawScore() {
        val raw = 0.80f
        val clean = raw - MetalDetectingTargetRefiner.cautionPenalty(0)
        val cautioned = raw - MetalDetectingTargetRefiner.cautionPenalty(2)

        assertTrue(cautioned < clean)
    }

    /** A clean but weaker candidate should now be able to overtake a stronger cautioned one. */
    @Test
    fun aCleanWeakerCandidateCanOvertakeACautionedStrongerOne() {
        val cautionedStrong = 0.80f - MetalDetectingTargetRefiner.cautionPenalty(3)
        val cleanWeaker = 0.70f - MetalDetectingTargetRefiner.cautionPenalty(0)

        assertTrue(cleanWeaker > cautionedStrong)
    }
}
