package com.example.data.historicmap

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

data class MapFeatureAgreement(
    /** 0 (map feature contradicts terrain evidence) to 1 (strong agreement). */
    val score: Float,
    val featureCells: Int,
    val supportingCells: Int,
    val featureMeanEvidence: Float,
    val backgroundMeanEvidence: Float,
    val note: String,
) {
    val supportFraction: Float
        get() = if (featureCells > 0) supportingCells.toFloat() / featureCells else 0f
}

/**
 * Scores how well a historic-map feature (road, structure, wall, boundary) agrees with terrain
 * evidence on the analysis grid. The score blends support coverage (how much of the feature sits
 * on above-threshold evidence) with contrast (how much stronger the evidence is along the feature
 * than in the background). It feeds ranking only through [rankingAdjustment], which is capped so
 * map agreement informs but never overpowers terrain evidence.
 */
object MapTerrainAgreement {
    const val MAX_RANKING_ADJUSTMENT = 0.1f
    private const val SUPPORT_WEIGHT = 0.6f
    private const val CONTRAST_WEIGHT = 0.4f

    /** Rasterizes a polyline in grid coordinates into cell indices, with optional dilation. */
    fun rasterizePolyline(
        points: List<Pair<Float, Float>>,
        width: Int,
        height: Int,
        halfWidthCells: Int = 0,
    ): Set<Int> {
        if (points.isEmpty() || width <= 0 || height <= 0) return emptySet()
        val cells = HashSet<Int>()
        val radius = halfWidthCells.coerceAtLeast(0)

        fun stamp(centerX: Int, centerY: Int) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val x = centerX + dx
                    val y = centerY + dy
                    if (x in 0 until width && y in 0 until height) {
                        cells.add(y * width + x)
                    }
                }
            }
        }

        if (points.size == 1) {
            stamp(points[0].first.roundToInt(), points[0].second.roundToInt())
            return cells
        }
        for ((from, to) in points.zipWithNext()) {
            val steps = ceil(
                maxOf(abs(to.first - from.first), abs(to.second - from.second)).toDouble() * 2.0,
            ).toInt().coerceAtLeast(1)
            for (step in 0..steps) {
                val fraction = step.toFloat() / steps
                stamp(
                    (from.first + (to.first - from.first) * fraction).roundToInt(),
                    (from.second + (to.second - from.second) * fraction).roundToInt(),
                )
            }
        }
        return cells
    }

    fun score(
        featureCells: Set<Int>,
        evidence: FloatArray,
        validData: BooleanArray,
        supportThreshold: Float,
    ): MapFeatureAgreement {
        val usable = featureCells.filter { index ->
            index in evidence.indices && index in validData.indices &&
                validData[index] && evidence[index].isFinite()
        }
        if (usable.isEmpty()) {
            return MapFeatureAgreement(
                score = 0f,
                featureCells = 0,
                supportingCells = 0,
                featureMeanEvidence = 0f,
                backgroundMeanEvidence = 0f,
                note = "map feature falls outside usable terrain data",
            )
        }

        val featureSet = featureCells.toSet()
        var featureSum = 0f
        var supporting = 0
        for (index in usable) {
            val value = evidence[index]
            featureSum += value
            if (value >= supportThreshold) supporting++
        }
        val featureMean = featureSum / usable.size

        var backgroundSum = 0f
        var backgroundCount = 0
        var globalMax = Float.NEGATIVE_INFINITY
        for (index in evidence.indices) {
            if (index >= validData.size || !validData[index]) continue
            val value = evidence[index]
            if (!value.isFinite()) continue
            if (value > globalMax) globalMax = value
            if (index !in featureSet) {
                backgroundSum += value
                backgroundCount++
            }
        }
        val backgroundMean = if (backgroundCount > 0) backgroundSum / backgroundCount else 0f
        val supportFraction = supporting.toFloat() / usable.size
        val contrast = if (globalMax > backgroundMean) {
            ((featureMean - backgroundMean) / (globalMax - backgroundMean)).coerceIn(0f, 1f)
        } else {
            0f
        }
        val score = (SUPPORT_WEIGHT * supportFraction + CONTRAST_WEIGHT * contrast).coerceIn(0f, 1f)
        return MapFeatureAgreement(
            score = score,
            featureCells = usable.size,
            supportingCells = supporting,
            featureMeanEvidence = featureMean,
            backgroundMeanEvidence = backgroundMean,
            note = "${"%.0f".format(supportFraction * 100f)}% of feature cells on supporting " +
                "evidence (feature mean ${"%.3f".format(featureMean)} vs background " +
                "${"%.3f".format(backgroundMean)})",
        )
    }

    /**
     * Bounded ranking contribution: full agreement adds at most +[MAX_RANKING_ADJUSTMENT],
     * full contradiction subtracts at most the same, and a neutral 0.5 score is a no-op.
     */
    fun rankingAdjustment(score: Float): Float =
        (score.coerceIn(0f, 1f) - 0.5f) * 2f * MAX_RANKING_ADJUSTMENT
}
