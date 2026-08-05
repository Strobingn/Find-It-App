package com.example.data.field

import com.example.analysis.ReviewedVerdict
import com.example.data.VerificationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetVisitStatesTest {
    @Test
    fun unverifiedTargetsCanMoveToAnyCheckedState() {
        for (outcome in TargetVisitStates.checkedOutcomes) {
            assertTrue(
                "UNVERIFIED -> $outcome should be allowed",
                TargetVisitStates.canTransition(VerificationOutcome.UNVERIFIED, outcome),
            )
        }
    }

    @Test
    fun checkedTargetsCanBeCorrectedButNeverErased() {
        assertTrue(
            TargetVisitStates.canTransition(
                VerificationOutcome.CONFIRMED_FEATURE,
                VerificationOutcome.REJECTED_FALSE_POSITIVE,
            ),
        )
        assertTrue(
            TargetVisitStates.canTransition(
                VerificationOutcome.INCONCLUSIVE,
                VerificationOutcome.CONFIRMED_FEATURE,
            ),
        )
        for (outcome in VerificationOutcome.entries) {
            assertFalse(
                "$outcome -> UNVERIFIED must not erase a field check",
                TargetVisitStates.canTransition(outcome, VerificationOutcome.UNVERIFIED),
            )
        }
    }

    @Test
    fun noOpTransitionsAreRejected() {
        for (outcome in VerificationOutcome.entries) {
            assertFalse(TargetVisitStates.canTransition(outcome, outcome))
        }
    }

    @Test
    fun outcomesMapToReviewedExampleVerdicts() {
        assertEquals(
            ReviewedVerdict.PRODUCTIVE,
            TargetVisitStates.toReviewedVerdict(VerificationOutcome.CONFIRMED_FEATURE),
        )
        assertEquals(
            ReviewedVerdict.REJECTED,
            TargetVisitStates.toReviewedVerdict(VerificationOutcome.REJECTED_FALSE_POSITIVE),
        )
        assertEquals(
            ReviewedVerdict.AMBIGUOUS,
            TargetVisitStates.toReviewedVerdict(VerificationOutcome.INCONCLUSIVE),
        )
        assertNull(TargetVisitStates.toReviewedVerdict(VerificationOutcome.UNVERIFIED))
    }
}
