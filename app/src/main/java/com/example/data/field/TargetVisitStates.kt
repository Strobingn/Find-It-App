package com.example.data.field

import com.example.analysis.ReviewedVerdict
import com.example.data.VerificationOutcome

/**
 * Target-state rules for field verification. A field check is an auditable event: targets may be
 * corrected between checked states as evidence improves, but a checked target never returns to
 * UNVERIFIED, and a no-op "transition" is rejected so the log only records real changes.
 */
object TargetVisitStates {
    val checkedOutcomes: List<VerificationOutcome> = listOf(
        VerificationOutcome.CONFIRMED_FEATURE,
        VerificationOutcome.REJECTED_FALSE_POSITIVE,
        VerificationOutcome.INCONCLUSIVE,
    )

    fun isChecked(outcome: VerificationOutcome): Boolean = outcome != VerificationOutcome.UNVERIFIED

    fun canTransition(from: VerificationOutcome, to: VerificationOutcome): Boolean {
        if (from == to) return false
        // A completed field check stays on record; it can be corrected, never erased.
        if (to == VerificationOutcome.UNVERIFIED) return false
        return true
    }

    /** Maps a field outcome to the verdict retained in the reviewed-example training store. */
    fun toReviewedVerdict(outcome: VerificationOutcome): ReviewedVerdict? = when (outcome) {
        VerificationOutcome.CONFIRMED_FEATURE -> ReviewedVerdict.PRODUCTIVE
        VerificationOutcome.REJECTED_FALSE_POSITIVE -> ReviewedVerdict.REJECTED
        VerificationOutcome.INCONCLUSIVE -> ReviewedVerdict.AMBIGUOUS
        VerificationOutcome.UNVERIFIED -> null
    }
}
