package com.example.analysis.epoch

import com.example.analysis.TerrainFeatureCandidate
import kotlin.math.hypot

/**
 * Compares terrain candidates between two epochs (A = older/reference, B = newer).
 * Spec: match same type within 3% of map diagonal; score-changed if |Δscore| ≥ 0.1.
 */
object CandidateDelta {

    const val CANDIDATE_MATCH_PERCENT: Float = 3f
    const val SCORE_CHANGE_MIN: Float = 0.1f

    enum class Kind { APPEARED, DISAPPEARED, SCORE_CHANGED }

    data class Item(
        val kind: Kind,
        val typeLabel: String,
        val xPercent: Float,
        val yPercent: Float,
        val scoreA: Float?,
        val scoreB: Float?,
        val note: String,
    )

    data class Result(
        val items: List<Item>,
        val appeared: Int,
        val disappeared: Int,
        val scoreChanged: Int,
        val note: String,
        val honestyLine: String =
            "Candidate delta is terrain morphology only — not metal identity.",
    )

    fun compare(
        epochA: List<TerrainFeatureCandidate>,
        epochB: List<TerrainFeatureCandidate>,
        matchPercent: Float = CANDIDATE_MATCH_PERCENT,
        scoreChangeMin: Float = SCORE_CHANGE_MIN,
    ): Result {
        val matchDist = matchPercent.coerceAtLeast(0.5f)
        val matchedB = BooleanArray(epochB.size)
        val items = ArrayList<Item>()

        for (a in epochA) {
            var bestIdx = -1
            var bestD = Float.MAX_VALUE
            epochB.forEachIndexed { idx, b ->
                if (matchedB[idx]) return@forEachIndexed
                if (a.type != b.type) return@forEachIndexed
                val d = hypot(a.xPercent - b.xPercent, a.yPercent - b.yPercent)
                if (d < bestD) {
                    bestD = d
                    bestIdx = idx
                }
            }
            if (bestIdx >= 0 && bestD <= matchDist) {
                matchedB[bestIdx] = true
                val b = epochB[bestIdx]
                val ds = b.score - a.score
                if (kotlin.math.abs(ds) >= scoreChangeMin) {
                    items += Item(
                        kind = Kind.SCORE_CHANGED,
                        typeLabel = a.type.label,
                        xPercent = b.xPercent,
                        yPercent = b.yPercent,
                        scoreA = a.score,
                        scoreB = b.score,
                        note = "Δscore ${"%+.2f".format(ds)} · match ${"%.1f".format(bestD)}%",
                    )
                }
            } else {
                items += Item(
                    kind = Kind.DISAPPEARED,
                    typeLabel = a.type.label,
                    xPercent = a.xPercent,
                    yPercent = a.yPercent,
                    scoreA = a.score,
                    scoreB = null,
                    note = "Present in A only",
                )
            }
        }
        epochB.forEachIndexed { idx, b ->
            if (!matchedB[idx]) {
                items += Item(
                    kind = Kind.APPEARED,
                    typeLabel = b.type.label,
                    xPercent = b.xPercent,
                    yPercent = b.yPercent,
                    scoreA = null,
                    scoreB = b.score,
                    note = "Present in B only",
                )
            }
        }
        val appeared = items.count { it.kind == Kind.APPEARED }
        val disappeared = items.count { it.kind == Kind.DISAPPEARED }
        val changed = items.count { it.kind == Kind.SCORE_CHANGED }
        return Result(
            items = items.sortedWith(
                compareBy<Item> { it.kind.ordinal }
                    .thenByDescending { (it.scoreB ?: it.scoreA ?: 0f) },
            ),
            appeared = appeared,
            disappeared = disappeared,
            scoreChanged = changed,
            note = "Appeared $appeared · disappeared $disappeared · score-changed $changed " +
                "(match ≤${matchPercent}% diagonal, Δscore ≥$scoreChangeMin)",
        )
    }
}
