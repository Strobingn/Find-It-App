package com.example.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Post-process geometric detector hits: require multi-visualization agreement, reject game
 * trails / burrow mounds / modern OSM, and split remaining finds into auto-show vs maybe.
 *
 * [autoShowThreshold] is the precision-calibrated cutoff from [FeatureDetectionEval]
 * (target ≈ 95% precision on held-out public historic inventories).
 */
object HistoricFeatureClassifier {
    /** Measured on the bundled hold-out areas; see docs/FEATURE_DETECTION_EVAL.md. */
    const val DEFAULT_AUTO_SHOW_THRESHOLD = 0.86f
    const val MIN_VISUALIZATION_HITS = 2

    fun refine(
        candidates: List<TerrainFeatureCandidate>,
        layers: TerrainDerivedLayers,
        modern: OsmModernContext = OsmModernContext.Empty,
        autoShowThreshold: Float = DEFAULT_AUTO_SHOW_THRESHOLD,
    ): List<TerrainFeatureCandidate> {
        val width = layers.width
        val height = layers.height
        val relief = layers.values[TerrainDerivedLayer.LOCAL_RELIEF]
        val sky = layers.values[TerrainDerivedLayer.SKY_VIEW_FACTOR]
        val slope = layers.values[TerrainDerivedLayer.SLOPE]
        val hill = layers.values[TerrainDerivedLayer.HILLSHADE_COMPARISON]
        val linearity = layers.values[TerrainDerivedLayer.LINEARITY]
        val depression = layers.values[TerrainDerivedLayer.DEPRESSION_DEPTH]
        return candidates.mapNotNull { candidate ->
            val x = ((candidate.xPercent / 100f) * (width - 1)).roundToInt().coerceIn(0, width - 1)
            val y = ((candidate.yPercent / 100f) * (height - 1)).roundToInt().coerceIn(0, height - 1)
            val index = y * width + x
            val hits = visualizationHits(
                type = candidate.type,
                index = index,
                relief = relief,
                sky = sky,
                slope = slope,
                hill = hill,
                linearity = linearity,
                depression = depression,
            )
            val rejection = rejectionReason(
                candidate = candidate,
                width = width,
                height = height,
                cellSize = layers.cellSizeMeters,
                x = x,
                y = y,
                relief = relief,
                slope = slope,
                linearity = linearity,
                modern = modern,
            )
            if (rejection != null && candidate.feedback != TerrainFeedbackRating.CONFIRMED) {
                return@mapNotNull null
            }
            val score = (candidate.score + 0.04f * (hits - 1).coerceAtLeast(0)).coerceIn(0f, 1f)
            val bucket = when {
                candidate.feedback == TerrainFeedbackRating.CONFIRMED -> FeatureDisplayBucket.AUTO
                candidate.feedback == TerrainFeedbackRating.REJECTED -> FeatureDisplayBucket.HIDDEN
                hits < MIN_VISUALIZATION_HITS -> FeatureDisplayBucket.HIDDEN
                score >= autoShowThreshold -> FeatureDisplayBucket.AUTO
                else -> FeatureDisplayBucket.MAYBE
            }
            if (bucket == FeatureDisplayBucket.HIDDEN) null
            else candidate.copy(
                score = score,
                visualizationHits = hits,
                displayBucket = bucket,
                evidence = candidate.evidence + "$hits visualization hit(s)",
            )
        }.sortedByDescending { it.score }
    }

    internal fun visualizationHits(
        type: TerrainFeatureType,
        index: Int,
        relief: FloatArray?,
        sky: FloatArray?,
        slope: FloatArray?,
        hill: FloatArray?,
        linearity: FloatArray?,
        depression: FloatArray?,
    ): Int {
        var hits = 0
        fun present(values: FloatArray?, predicate: (Float) -> Boolean): Boolean {
            val value = values?.getOrNull(index) ?: return false
            return value.isFinite() && predicate(value)
        }
        when (type) {
            TerrainFeatureType.FOUNDATION, TerrainFeatureType.OLD_HOMESITE -> {
                if (present(relief) { abs(it) >= 0.12f }) hits++
                if (present(hill) { it >= 0.08f }) hits++
                if (present(linearity) { it >= 0.05f }) hits++
                if (present(slope) { it in 0f..18f }) hits++
            }
            TerrainFeatureType.CELLAR_HOLE, TerrainFeatureType.DEPRESSION -> {
                if (present(depression) { it >= 0.08f }) hits++
                if (present(relief) { it <= -0.08f }) hits++
                if (present(sky) { it <= 0.92f }) hits++
                if (present(hill) { it >= 0.05f }) hits++
            }
            TerrainFeatureType.ROAD_TRAIL, TerrainFeatureType.STONE_WALL -> {
                if (present(linearity) { it >= 0.08f }) hits++
                if (present(hill) { it >= 0.06f }) hits++
                if (present(slope) { it in 1f..22f }) hits++
                if (present(relief) { abs(it) in 0.04f..0.55f }) hits++
            }
            else -> {
                if (present(relief) { abs(it) >= 0.08f }) hits++
                if (present(hill) { it >= 0.05f }) hits++
                if (present(sky) { true }) hits++
            }
        }
        return hits
    }

    internal fun rejectionReason(
        candidate: TerrainFeatureCandidate,
        width: Int,
        height: Int,
        cellSize: Float,
        x: Int,
        y: Int,
        relief: FloatArray?,
        slope: FloatArray?,
        linearity: FloatArray?,
        modern: OsmModernContext,
    ): String? {
        if (modern.hitsModern(candidate.xPercent, candidate.yPercent, candidate.radiusMeters)) {
            return "rejected: modern OSM road or building"
        }
        if (candidate.type == TerrainFeatureType.ROAD_TRAIL &&
            looksLikeGameTrail(width, height, cellSize, x, y, relief, slope, linearity)
        ) {
            return "rejected: narrow wandering contour-following trail"
        }
        if ((candidate.type == TerrainFeatureType.FOUNDATION ||
                candidate.type == TerrainFeatureType.CELLAR_HOLE ||
                candidate.type == TerrainFeatureType.DEPRESSION) &&
            candidate.radiusMeters <= 3.2f &&
            looksLikeBurrowMound(width, height, cellSize, x, y, relief)
        ) {
            return "rejected: compact burrow-scale mound"
        }
        return null
    }

    /**
     * Game trails are narrow, change heading often, and hug a single contour instead of
     * holding a steady grade across slope.
     */
    internal fun looksLikeGameTrail(
        width: Int,
        height: Int,
        cellSize: Float,
        x: Int,
        y: Int,
        relief: FloatArray?,
        slope: FloatArray?,
        linearity: FloatArray?,
    ): Boolean {
        if (relief == null || slope == null || linearity == null) return false
        val radius = (3.5f / cellSize.coerceAtLeast(0.25f)).roundToInt().coerceIn(2, 8)
        var linearSum = 0f
        var slopeVar = 0f
        var slopeMean = 0f
        var count = 0
        var headingChanges = 0
        var lastStrongDx = 0
        var lastStrongDy = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val px = x + dx
                val py = y + dy
                if (px !in 0 until width || py !in 0 until height) continue
                val i = py * width + px
                val lin = linearity.getOrNull(i) ?: continue
                val sl = slope.getOrNull(i) ?: continue
                linearSum += lin
                slopeMean += sl
                count++
                if (lin >= 0.12f) {
                    if (lastStrongDx != 0 || lastStrongDy != 0) {
                        val turn = abs(dx * lastStrongDy - dy * lastStrongDx)
                        if (turn >= 2) headingChanges++
                    }
                    lastStrongDx = dx
                    lastStrongDy = dy
                }
            }
        }
        if (count == 0) return false
        slopeMean /= count
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val px = x + dx
                val py = y + dy
                if (px !in 0 until width || py !in 0 until height) continue
                val sl = slope[py * width + px]
                slopeVar += (sl - slopeMean) * (sl - slopeMean)
            }
        }
        slopeVar /= count
        val meanLinear = linearSum / count
        val narrow = meanLinear < 0.18f
        val wandering = headingChanges >= 4
        val contourHugging = slopeVar < 4f && slopeMean in 4f..16f
        return narrow && wandering && contourHugging
    }

    /** Isolated rises under ~2.5 m across are typical rodent/animal spoil, not cellars. */
    internal fun looksLikeBurrowMound(
        width: Int,
        height: Int,
        cellSize: Float,
        x: Int,
        y: Int,
        relief: FloatArray?,
    ): Boolean {
        if (relief == null) return false
        val radius = (2.2f / cellSize.coerceAtLeast(0.25f)).roundToInt().coerceIn(1, 5)
        var peak = 0f
        var support = 0
        var cells = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val px = (x + dx).coerceIn(0, width - 1)
                val py = (y + dy).coerceIn(0, height - 1)
                val value = relief[py * width + px]
                if (!value.isFinite()) continue
                cells++
                if (value > peak) peak = value
                if (value >= 0.08f) support++
            }
        }
        val footprintMeters = sqrt(support.toFloat().coerceAtLeast(1f)) * cellSize
        return peak in 0.08f..0.45f && support <= max(3, cells / 8) && footprintMeters <= 2.6f
    }
}

/** Modern roads and buildings used to reject detector hits. Coordinates are normalized 0–100. */
data class OsmModernFeature(
    val xPercent: Float,
    val yPercent: Float,
    val radiusMeters: Float,
    val kind: Kind,
) {
    enum class Kind { ROAD, BUILDING }
}

data class OsmModernContext(
    val features: List<OsmModernFeature>,
) {
    fun hitsModern(xPercent: Float, yPercent: Float, radiusMeters: Float): Boolean {
        return features.any { feature ->
            val dx = feature.xPercent - xPercent
            val dy = feature.yPercent - yPercent
            val distance = sqrt(dx * dx + dy * dy)
            val threshold = 6f + (feature.radiusMeters + radiusMeters) * 0.15f
            distance <= threshold
        }
    }

    companion object {
        val Empty = OsmModernContext(emptyList())
    }
}
