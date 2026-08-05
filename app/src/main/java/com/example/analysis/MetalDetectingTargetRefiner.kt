package com.example.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class MetalDetectingTargetType(val label: String) {
    FOUNDATION("Foundation / building platform"),
    ROAD_TRAIL("Road or trail corridor"),
    CELLAR_HOLE("Cellar hole"),
    TRASH_PIT("Possible trash / refuse pit"),
    STONE_WALL("Stone wall"),
    OLD_HOMESITE("Old homesite context"),
}

data class MetalDetectingTarget(
    val type: MetalDetectingTargetType,
    val xPercent: Float,
    val yPercent: Float,
    val score: Float,
    val radiusMeters: Float,
    /** Dynamic, per-candidate measurements (e.g. "Flat interior: 82%") - not static boilerplate. */
    val evidence: List<String>,
    /**
     * Rule-based (not ML) reasons this candidate could plausibly be a false positive, computed
     * from the same measured layers as the score itself. Empty when no caution signal triggered.
     */
    val cautionReasons: List<String> = emptyList(),
    /** True when a user-verified field outcome nearby actually influenced this candidate's score. */
    val verifiedNearby: Boolean = false,
    /** Structured per-layer agreement for the evidence panel; empty for legacy callers. */
    val layerEvidence: List<TargetLayerEvidence> = emptyList(),
)

/** Per-layer agreement verdict for the multi-layer evidence panel. */
enum class LayerVerdict(val label: String) {
    SUPPORTS("supports"),
    MIXED("mixed"),
    DISAGREES("disagrees"),
}

/**
 * One measured layer's say about a candidate: what was measured, how strongly, and whether it
 * argues for or against the target. Computed from the same prefix-sum tables as the score, so
 * the panel can never disagree with the ranking.
 */
data class TargetLayerEvidence(
    val layer: String,
    val verdict: LayerVerdict,
    val measurement: String,
)

/**
 * Bundles the prefix-sum tables and radii built once per [MetalDetectingTargetRefiner.refine]
 * call so that [MetalDetectingTargetRefiner]'s dynamic evidence/caution generation can cheaply
 * recompute the same named components at a single candidate's (x,y) after local-maxima
 * extraction, instead of storing every intermediate component as a full-grid array.
 */
private class RefinerContext(
    val flatRect: RectSumTable,
    val smoothRect: RectSumTable,
    val concaveRect: RectSumTable,
    val raisedRect: RectSumTable,
    val loweredRect: RectSumTable,
    val edgeRect: RectSumTable,
    val depressionRect: RectSumTable,
    val ruggedRect: RectSumTable,
    val linearityRect: RectSumTable,
    val linearityDiag: DiagonalSumTable,
    val flatDiag: DiagonalSumTable,
    val smoothDiag: DiagonalSumTable,
    val foundationRect: RectSumTable,
    val cellarRect: RectSumTable,
    val trashRect: RectSumTable,
    val roadRect: RectSumTable,
    val hillCompare: FloatArray,
    val innerRadius: Int,
    val edgeInner: Int,
    val edgeOuter: Int,
    val contextRadius: Int,
    val corridorHalfLength: Int,
    val corridorHalfWidth: Int,
)

/**
 * Re-scores terrain signatures for metal-detecting field work with spatial context.
 *
 * The base terrain engine supplies local-relief, curvature, openness, ruggedness and
 * linearity layers. This pass adds the neighborhood geometry that a single-cell weighted
 * sum cannot represent: flat interiors with edge rings, continuous corridors, compact
 * depressions with raised rims, and shallow irregular pits near occupation features.
 * Scores are screening priorities only; they are not proof of a buried object.
 *
 * Every neighborhood query below (disk/ring means and the four-direction corridor scan) is
 * backed by [RectSumTable] and [DiagonalSumTable] prefix sums built once per source layer.
 * That turns what used to be an O(radius^2) scan per pixel into O(radius) (ring means, via a
 * per-row analytic scan of the circular boundary) or O(1) (axis-aligned corridor sums), which
 * matters a lot here since several radii reach 16-24 cells and are queried at every pixel.
 */
object MetalDetectingTargetRefiner {
    private const val MAX_PER_TYPE = 12
    private const val MAX_TOTAL = 48
    /** Small, capped demotion for detector cautions; field rejection remains stronger. */
    internal const val CAUTION_PENALTY_EACH = 0.06f
    internal const val CAUTION_PENALTY_CAP = 0.18f

    internal fun cautionPenalty(count: Int): Float =
        (count.coerceAtLeast(0) * CAUTION_PENALTY_EACH).coerceAtMost(CAUTION_PENALTY_CAP)

    /**
     * Per-caution demotion, and the most any number of them can remove.
     *
     * Deliberately smaller than the 0.28 a field-verified rejection removes: a caution is the
     * detector saying a natural explanation is available, whereas a rejection is someone having
     * stood on the spot. The cap keeps a heavily-cautioned strong candidate visible and ranked
     * low rather than silently deleted, so it can still be field-checked and fed back.
     */
    internal const val CAUTION_PENALTY_EACH = 0.06f
    internal const val CAUTION_PENALTY_CAP = 0.18f

    /** Demotion for a candidate carrying [count] caution reasons. */
    internal fun cautionPenalty(count: Int): Float =
        (count.coerceAtLeast(0) * CAUTION_PENALTY_EACH).coerceAtMost(CAUTION_PENALTY_CAP)

    fun refine(
        result: TerrainIntelligenceResult,
        feedback: List<VerifiedFeedbackPoint> = emptyList(),
        /** Per-type threshold bias from [FeatureTypeCalibration]; see that object for how it's derived. */
        calibration: Map<MetalDetectingTargetType, Float> = emptyMap(),
    ): List<MetalDetectingTarget> {
        val layers = result.layers
        val width = layers.width
        val height = layers.height
        if (width < 3 || height < 3) return emptyList()

        val slope = normalizePositive(requireLayer(layers, TerrainDerivedLayer.SLOPE))
        val curvature = normalizeSigned(requireLayer(layers, TerrainDerivedLayer.CURVATURE))
        val relief = normalizeSigned(requireLayer(layers, TerrainDerivedLayer.LOCAL_RELIEF))
        val depression = normalizePositive(requireLayer(layers, TerrainDerivedLayer.DEPRESSION_DEPTH))
        val rugged = normalizePositive(requireLayer(layers, TerrainDerivedLayer.RUGGEDNESS))
        val linearity = normalizePositive(requireLayer(layers, TerrainDerivedLayer.LINEARITY))
        val hillCompare = normalizePositive(requireLayer(layers, TerrainDerivedLayer.HILLSHADE_COMPARISON))
        val positiveOpen = requireLayer(layers, TerrainDerivedLayer.POSITIVE_OPENNESS)
        val negativeOpen = requireLayer(layers, TerrainDerivedLayer.NEGATIVE_OPENNESS)

        val flat = FloatArray(width * height) { 1f - slope[it] }
        val smooth = FloatArray(width * height) { 1f - rugged[it] }
        val concave = FloatArray(width * height) { max(0f, curvature[it]) }
        val raised = FloatArray(width * height) { max(0f, relief[it]) }
        val lowered = FloatArray(width * height) { max(0f, -relief[it]) }
        val edge = FloatArray(width * height) {
            (abs(curvature[it]) * 0.48f + linearity[it] * 0.34f + hillCompare[it] * 0.18f).coerceIn(0f, 1f)
        }

        val innerRadius = metersToCells(2.5f, layers.cellSizeMeters, 1, 8)
        val edgeInner = metersToCells(2.5f, layers.cellSizeMeters, 1, 8)
        val edgeOuter = metersToCells(7f, layers.cellSizeMeters, edgeInner + 1, 16)
        val contextRadius = metersToCells(12f, layers.cellSizeMeters, 2, 20)
        val corridorHalfLength = metersToCells(14f, layers.cellSizeMeters, 3, 24)
        val corridorHalfWidth = metersToCells(2.5f, layers.cellSizeMeters, 1, 5)

        // Built once per layer, then queried O(width*height) times below at O(radius)/O(1) each.
        val flatRect = RectSumTable(flat, width, height)
        val smoothRect = RectSumTable(smooth, width, height)
        val concaveRect = RectSumTable(concave, width, height)
        val raisedRect = RectSumTable(raised, width, height)
        val loweredRect = RectSumTable(lowered, width, height)
        val edgeRect = RectSumTable(edge, width, height)
        val depressionRect = RectSumTable(depression, width, height)
        val ruggedRect = RectSumTable(rugged, width, height)
        val linearityRect = RectSumTable(linearity, width, height)

        val linearityDiag = DiagonalSumTable(linearity, width, height)
        val flatDiag = DiagonalSumTable(flat, width, height)
        val smoothDiag = DiagonalSumTable(smooth, width, height)

        val foundation = FloatArray(width * height)
        val road = FloatArray(width * height)
        val cellar = FloatArray(width * height)
        val trash = FloatArray(width * height)
        val wall = FloatArray(width * height)
        val homesite = FloatArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val interiorFlat = flatRect.ringMean(x, y, 0, innerRadius)
                val interiorSmooth = smoothRect.ringMean(x, y, 0, innerRadius)
                val boundaryEdge = edgeRect.ringMean(x, y, edgeInner, edgeOuter)
                val rimRaised = raisedRect.ringMean(x, y, edgeInner, edgeOuter)
                val raisedInner = raisedRect.ringMean(x, y, 0, innerRadius)
                val centerDepression = depressionRect.ringMean(x, y, 0, innerRadius)
                val centerConcavity = concaveRect.ringMean(x, y, 0, innerRadius)
                val centerLowered = loweredRect.ringMean(x, y, 0, innerRadius)
                val centerRugged = ruggedRect.ringMean(x, y, 0, innerRadius)
                val directionalLine = directionalContinuity(
                    linearityRect,
                    linearityDiag,
                    x,
                    y,
                    corridorHalfLength,
                    corridorHalfWidth,
                )
                val corridorFlat = directionalContinuity(
                    flatRect,
                    flatDiag,
                    x,
                    y,
                    corridorHalfLength,
                    corridorHalfWidth,
                )
                val corridorSmooth = directionalContinuity(
                    smoothRect,
                    smoothDiag,
                    x,
                    y,
                    corridorHalfLength,
                    corridorHalfWidth,
                )

                // Foundations/platforms: level interior plus a persistent edge/rim and rectilinear continuity.
                foundation[i] = (
                    interiorFlat * 0.25f +
                        interiorSmooth * 0.15f +
                        boundaryEdge * 0.30f +
                        directionalLine * 0.20f +
                        hillCompare[i] * 0.10f
                    ).coerceIn(0f, 1f)

                // Roads/trails: elongated continuity is mandatory; smooth low-gradient corridor is supporting evidence.
                val cutOrCrown = max(centerLowered, raisedInner)
                road[i] = (
                    directionalLine * 0.38f +
                        corridorFlat * 0.24f +
                        corridorSmooth * 0.18f +
                        cutOrCrown * 0.12f +
                        hillCompare[i] * 0.08f
                    ).coerceIn(0f, 1f)

                // Cellar holes: compact deep depression, concave center, and a raised/defined perimeter.
                val compactDepression = (centerDepression - depressionRect.ringMean(x, y, edgeInner, edgeOuter) * 0.55f)
                    .coerceIn(0f, 1f)
                cellar[i] = (
                    compactDepression * 0.38f +
                        centerConcavity * 0.20f +
                        centerLowered * 0.18f +
                        rimRaised * 0.16f +
                        (1f - positiveOpen[i]) * 0.08f
                    ).coerceIn(0f, 1f)

                // Refuse/trash pits are usually shallower and less regular than cellar holes.
                val shallowPreference = triangularPreference(centerDepression, center = 0.46f, halfWidth = 0.38f)
                val irregularEdge = (boundaryEdge * 0.65f + centerRugged * 0.35f).coerceIn(0f, 1f)
                trash[i] = (
                    shallowPreference * 0.28f +
                        centerConcavity * 0.18f +
                        centerLowered * 0.16f +
                        irregularEdge * 0.18f +
                        rimRaised * 0.08f +
                        (1f - abs(positiveOpen[i] - negativeOpen[i])) * 0.12f
                    ).coerceIn(0f, 1f)

                wall[i] = (
                    directionalLine * 0.42f +
                        raisedInner * 0.23f +
                        boundaryEdge * 0.22f +
                        corridorSmooth * 0.13f
                    ).coerceIn(0f, 1f)
            }
        }

        val foundationRect = RectSumTable(foundation, width, height)
        val cellarRect = RectSumTable(cellar, width, height)
        val trashRect = RectSumTable(trash, width, height)
        val roadRect = RectSumTable(road, width, height)

        // Real-world context radius, in grid-percent units, for matching verified feedback points
        // against a pixel while computing the human-activity-context term below.
        val confirmedPoints = feedback.filter { it.confirmed }
        val contextPercentRadius = if (width > 1) contextRadius * 100f / (width - 1) else 100f
        val contextPercentRadiusSq = contextPercentRadius * contextPercentRadius

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val foundationContext = foundationRect.ringMean(x, y, 0, contextRadius)
                val cellarContext = cellarRect.ringMean(x, y, 0, contextRadius)
                val trashContext = trashRect.ringMean(x, y, 0, contextRadius)
                val roadContext = roadRect.ringMean(x, y, 0, contextRadius)
                val xPercent = if (width <= 1) 50f else x * 100f / (width - 1)
                val yPercent = if (height <= 1) 50f else y * 100f / (height - 1)
                val verifiedContext = if (confirmedPoints.isEmpty()) {
                    0f
                } else {
                    val nearestSq = confirmedPoints.minOf { distanceSquared(it.xPercent, it.yPercent, xPercent, yPercent) }
                    (1f - nearestSq / contextPercentRadiusSq).coerceIn(0f, 1f)
                }
                homesite[i] = (
                    foundationContext * 0.34f +
                        cellarContext * 0.20f +
                        trashContext * 0.13f +
                        roadContext * 0.17f +
                        flatRect.ringMean(x, y, 0, contextRadius) * 0.06f +
                        verifiedContext * 0.10f
                    ).coerceIn(0f, 1f)
                // Trash-pit priority rises when a shallow pit is close to occupation evidence.
                trash[i] = (trash[i] * 0.76f + homesite[i] * 0.24f).coerceIn(0f, 1f)
            }
        }

        val ctx = RefinerContext(
            flatRect, smoothRect, concaveRect, raisedRect, loweredRect, edgeRect, depressionRect, ruggedRect,
            linearityRect, linearityDiag, flatDiag, smoothDiag, foundationRect, cellarRect, trashRect, roadRect,
            hillCompare, innerRadius, edgeInner, edgeOuter, contextRadius, corridorHalfLength, corridorHalfWidth,
        )

        fun biasFor(type: MetalDetectingTargetType) = calibration[type] ?: 0f

        val output = ArrayList<MetalDetectingTarget>()
        appendTargets(output, MetalDetectingTargetType.FOUNDATION, foundation, width, height, 0.66f, 8f, feedback, ctx, biasFor(MetalDetectingTargetType.FOUNDATION))
        appendTargets(output, MetalDetectingTargetType.ROAD_TRAIL, road, width, height, 0.67f, 7f, feedback, ctx, biasFor(MetalDetectingTargetType.ROAD_TRAIL))
        appendTargets(output, MetalDetectingTargetType.CELLAR_HOLE, cellar, width, height, 0.68f, 7f, feedback, ctx, biasFor(MetalDetectingTargetType.CELLAR_HOLE))
        appendTargets(output, MetalDetectingTargetType.TRASH_PIT, trash, width, height, 0.65f, 5f, feedback, ctx, biasFor(MetalDetectingTargetType.TRASH_PIT))
        appendTargets(output, MetalDetectingTargetType.STONE_WALL, wall, width, height, 0.68f, 5f, feedback, ctx, biasFor(MetalDetectingTargetType.STONE_WALL))
        appendTargets(output, MetalDetectingTargetType.OLD_HOMESITE, homesite, width, height, 0.66f, 14f, feedback, ctx, biasFor(MetalDetectingTargetType.OLD_HOMESITE))

        return suppressNearbyDuplicates(output)
            .sortedByDescending { it.score }
            .take(MAX_TOTAL)
    }

    private fun appendTargets(
        output: MutableList<MetalDetectingTarget>,
        type: MetalDetectingTargetType,
        score: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        radiusMeters: Float,
        feedback: List<VerifiedFeedbackPoint>,
        ctx: RefinerContext,
        calibrationBias: Float = 0f,
    ) {
        val effectiveThreshold = calibratedThreshold(threshold, calibrationBias)
        localMaxima(score, width, height, effectiveThreshold, MAX_PER_TYPE).forEach { (index, rawValue) ->
            val x = index % width
            val y = index / width
            val xPercent = if (width <= 1) 50f else x * 100f / (width - 1)
            val yPercent = if (height <= 1) 50f else y * 100f / (height - 1)
            val nearestFeedback = feedback.minByOrNull { distanceSquared(it.xPercent, it.yPercent, xPercent, yPercent) }
            val feedbackMatched = nearestFeedback != null &&
                distanceSquared(nearestFeedback.xPercent, nearestFeedback.yPercent, xPercent, yPercent) <= VerifiedFeedback.MATCH_DISTANCE_SQUARED
            val afterFeedback = if (feedbackMatched) {
                (rawValue + if (nearestFeedback!!.confirmed) 0.14f else -0.28f).coerceIn(0f, 1f)
            } else {
                rawValue
            }
            // Caution reasons name the ways a candidate could be a natural feature or a rendering
            // artifact rather than something built. They used to be labels only, so a candidate
            // carrying every caution the detector could raise still outranked a clean one with a
            // slightly lower raw response. They now subtract, which is what makes human features
            // cluster above isolated natural anomalies rather than merely reading differently.
            val cautions = classifyCaution(type, x, y, index, ctx, feedback, xPercent, yPercent)
            val adjusted = (afterFeedback - cautionPenalty(cautions.size)).coerceIn(0f, 1f)
            // Mirrors TerrainIntelligenceEngine's feedback rule: a rejected match can drop an
            // already-qualified candidate, but feedback never resurrects one that never cleared
            // the raw per-pixel threshold in the first place.
            if (adjusted < effectiveThreshold * 0.88f) return@forEach
            output += MetalDetectingTarget(
                type = type,
                xPercent = xPercent,
                yPercent = yPercent,
                score = adjusted,
                radiusMeters = radiusMeters,
                evidence = explainCandidate(type, x, y, index, ctx),
                layerEvidence = measureLayers(x, y, index, ctx),
                cautionReasons = cautions,
                verifiedNearby = feedbackMatched,
            )
        }
    }

    private fun layerVerdict(value: Float): LayerVerdict = when {
        value >= 0.6f -> LayerVerdict.SUPPORTS
        value >= 0.35f -> LayerVerdict.MIXED
        else -> LayerVerdict.DISAGREES
    }

    /**
     * Samples every analysis layer at the candidate cell so the UI can show which layers agree
     * or disagree, not just the winning evidence strings. Roughness is inverted: heavy canopy
     * texture argues against confidence, so high roughness reads as a disagreeing layer.
     */
    private fun measureLayers(x: Int, y: Int, i: Int, ctx: RefinerContext): List<TargetLayerEvidence> {
        fun pct(value: Float) = "${(value.coerceIn(0f, 1f) * 100f).roundToInt()}%"
        val measurements = listOf(
            Triple("Hillshade multi-light", ctx.hillCompare[i], false),
            Triple("Slope edge rim", ctx.edgeRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter), false),
            Triple("Local relief depression", ctx.depressionRect.ringMean(x, y, 0, ctx.innerRadius), false),
            Triple("Raised rim relief", ctx.raisedRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter), false),
            Triple("Curvature concavity", ctx.concaveRect.ringMean(x, y, 0, ctx.innerRadius), false),
            Triple("Flat bench", ctx.flatRect.ringMean(x, y, 0, ctx.innerRadius), false),
            Triple("Surface smoothness", ctx.smoothRect.ringMean(x, y, 0, ctx.innerRadius), false),
            Triple(
                "Linear continuity",
                directionalContinuity(ctx.linearityRect, ctx.linearityDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth),
                false,
            ),
            Triple("Canopy roughness", ctx.ruggedRect.ringMean(x, y, 0, ctx.innerRadius), true),
        )
        return measurements.map { (layer, value, inverted) ->
            val effective = if (inverted) 1f - value.coerceIn(0f, 1f) else value
            TargetLayerEvidence(layer = layer, verdict = layerVerdict(effective), measurement = pct(value))
        }
    }

    /** Dynamic, per-candidate evidence with the actual measured percentages, not static text. */
    private fun explainCandidate(type: MetalDetectingTargetType, x: Int, y: Int, i: Int, ctx: RefinerContext): List<String> {
        fun pct(value: Float) = "${(value * 100f).roundToInt()}%"
        return when (type) {
            MetalDetectingTargetType.FOUNDATION -> listOf(
                "Flat interior: ${pct(ctx.flatRect.ringMean(x, y, 0, ctx.innerRadius))}",
                "Smooth interior: ${pct(ctx.smoothRect.ringMean(x, y, 0, ctx.innerRadius))}",
                "Edge/rim persistence: ${pct(ctx.edgeRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter))}",
                "Multi-direction continuity: ${pct(directionalContinuity(ctx.linearityRect, ctx.linearityDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth))}",
            )
            MetalDetectingTargetType.ROAD_TRAIL -> listOf(
                "Linear directional continuity: ${pct(directionalContinuity(ctx.linearityRect, ctx.linearityDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth))}",
                "Corridor flatness: ${pct(directionalContinuity(ctx.flatRect, ctx.flatDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth))}",
                "Corridor smoothness: ${pct(directionalContinuity(ctx.smoothRect, ctx.smoothDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth))}",
                "Cut or crowned relief: ${pct(max(ctx.loweredRect.ringMean(x, y, 0, ctx.innerRadius), ctx.raisedRect.ringMean(x, y, 0, ctx.innerRadius)))}",
            )
            MetalDetectingTargetType.CELLAR_HOLE -> {
                val centerDepression = ctx.depressionRect.ringMean(x, y, 0, ctx.innerRadius)
                val ringDepression = ctx.depressionRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter)
                listOf(
                    "Compact depression signature: ${pct((centerDepression - ringDepression * 0.55f).coerceIn(0f, 1f))}",
                    "Concave center: ${pct(ctx.concaveRect.ringMean(x, y, 0, ctx.innerRadius))}",
                    "Raised/defined rim: ${pct(ctx.raisedRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter))}",
                )
            }
            MetalDetectingTargetType.TRASH_PIT -> {
                val centerDepression = ctx.depressionRect.ringMean(x, y, 0, ctx.innerRadius)
                val boundaryEdge = ctx.edgeRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter)
                val centerRugged = ctx.ruggedRect.ringMean(x, y, 0, ctx.innerRadius)
                listOf(
                    "Shallow-depth preference: ${pct(triangularPreference(centerDepression, center = 0.46f, halfWidth = 0.38f))}",
                    "Irregular edge signature: ${pct((boundaryEdge * 0.65f + centerRugged * 0.35f).coerceIn(0f, 1f))}",
                )
            }
            MetalDetectingTargetType.STONE_WALL -> listOf(
                "Continuous raised line: ${pct(directionalContinuity(ctx.linearityRect, ctx.linearityDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth))}",
                "Raised interior: ${pct(ctx.raisedRect.ringMean(x, y, 0, ctx.innerRadius))}",
                "Edge persistence: ${pct(ctx.edgeRect.ringMean(x, y, ctx.edgeInner, ctx.edgeOuter))}",
            )
            MetalDetectingTargetType.OLD_HOMESITE -> listOf(
                "Nearby foundation evidence: ${pct(ctx.foundationRect.ringMean(x, y, 0, ctx.contextRadius))}",
                "Nearby cellar evidence: ${pct(ctx.cellarRect.ringMean(x, y, 0, ctx.contextRadius))}",
                "Nearby road-access context: ${pct(ctx.roadRect.ringMean(x, y, 0, ctx.contextRadius))}",
            )
        }
    }

    /**
     * Rule-based (not machine-learned) caution flags: known signatures that can mimic a real
     * feature but usually indicate something else, checked against the same measured layers used
     * to compute the score. Returns an empty list when nothing unusual is flagged.
     */
    private fun classifyCaution(
        type: MetalDetectingTargetType,
        x: Int,
        y: Int,
        i: Int,
        ctx: RefinerContext,
        feedback: List<VerifiedFeedbackPoint>,
        xPercent: Float,
        yPercent: Float,
    ): List<String> {
        val reasons = ArrayList<String>()
        val centerRugged = ctx.ruggedRect.ringMean(x, y, 0, ctx.innerRadius)
        val directionalLine = directionalContinuity(ctx.linearityRect, ctx.linearityDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth)

        when (type) {
            MetalDetectingTargetType.FOUNDATION, MetalDetectingTargetType.STONE_WALL, MetalDetectingTargetType.CELLAR_HOLE -> {
                if (ctx.hillCompare[i] < 0.15f) {
                    reasons += "Edge signal doesn't agree strongly across multiple lighting angles - could be a rendering artifact rather than a real edge."
                }
                if (centerRugged > 0.6f && directionalLine < 0.3f) {
                    reasons += "High surface roughness without directional coherence - could be a natural rock jumble rather than a built feature."
                }
            }
            MetalDetectingTargetType.ROAD_TRAIL -> {
                val corridorFlat = directionalContinuity(ctx.flatRect, ctx.flatDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth)
                val corridorSmooth = directionalContinuity(ctx.smoothRect, ctx.smoothDiag, x, y, ctx.corridorHalfLength, ctx.corridorHalfWidth)
                if (corridorFlat < 0.4f && corridorSmooth < 0.4f) {
                    reasons += "Aligned but not low-gradient or smooth - could be a natural drainage line or animal trail rather than a maintained road."
                }
            }
            MetalDetectingTargetType.TRASH_PIT -> {
                if (centerRugged < 0.15f) {
                    reasons += "Very regular, symmetric depression - may be better explained as a small cellar hole or natural sink rather than a refuse pit."
                }
            }
            MetalDetectingTargetType.OLD_HOMESITE -> {
                val foundationContext = ctx.foundationRect.ringMean(x, y, 0, ctx.contextRadius)
                val cellarContext = ctx.cellarRect.ringMean(x, y, 0, ctx.contextRadius)
                val roadContext = ctx.roadRect.ringMean(x, y, 0, ctx.contextRadius)
                if (foundationContext < 0.3f && cellarContext < 0.3f && roadContext < 0.3f) {
                    reasons += "Limited convergence of foundation, cellar, or road evidence nearby - may be an isolated flat clearing rather than a true homesite."
                }
            }
        }

        val nearRejected = feedback.filter { !it.confirmed }
            .minOfOrNull { distanceSquared(it.xPercent, it.yPercent, xPercent, yPercent) }
        if (nearRejected != null && nearRejected > VerifiedFeedback.MATCH_DISTANCE_SQUARED && nearRejected <= VerifiedFeedback.MATCH_DISTANCE_SQUARED * 4f) {
            reasons += "A nearby location was previously field-checked and rejected as a false positive - worth extra scrutiny."
        }
        return reasons
    }

    private fun suppressNearbyDuplicates(input: List<MetalDetectingTarget>): List<MetalDetectingTarget> {
        val accepted = ArrayList<MetalDetectingTarget>()
        for (candidate in input.sortedByDescending { it.score }) {
            val duplicate = accepted.any {
                it.type == candidate.type &&
                    distanceSquared(it.xPercent, it.yPercent, candidate.xPercent, candidate.yPercent) < 20f
            }
            if (!duplicate) accepted += candidate
        }
        return accepted
    }

    /**
     * Mean response along the strongest of 4 directions (horizontal, vertical, and both
     * diagonals) over a `(2*halfLength+1)` long by `(2*halfWidth+1)` wide corridor centered at
     * (cx,cy). Horizontal/vertical corridors are plain axis-aligned rectangles, answered in O(1)
     * via [RectSumTable]. The two 45-degree corridors are answered in O(halfLength) via
     * [DiagonalSumTable]: for a fixed step along a diagonal direction, sweeping the
     * perpendicular "cross" offset traces a contiguous run on a single x+y (or x-y) diagonal, so
     * each step needs only one O(1) diagonal range lookup instead of `2*halfWidth+1` cell reads.
     */
    private fun directionalContinuity(
        rect: RectSumTable,
        diag: DiagonalSumTable,
        cx: Int,
        cy: Int,
        halfLength: Int,
        halfWidth: Int,
    ): Float {
        var best = 0f

        run {
            val hCount = rect.count(cx - halfLength, cy - halfWidth, cx + halfLength + 1, cy + halfWidth + 1)
            if (hCount > 0) {
                val mean = rect.sum(cx - halfLength, cy - halfWidth, cx + halfLength + 1, cy + halfWidth + 1) / hCount
                best = max(best, mean.toFloat())
            }
            val vCount = rect.count(cx - halfWidth, cy - halfLength, cx + halfWidth + 1, cy + halfLength + 1)
            if (vCount > 0) {
                val mean = rect.sum(cx - halfWidth, cy - halfLength, cx + halfWidth + 1, cy + halfLength + 1) / vCount
                best = max(best, mean.toFloat())
            }
        }

        run {
            var sum = 0.0
            var count = 0
            val uCenter = cx + cy
            for (step in -halfLength..halfLength) {
                val u = uCenter + 2 * step
                val xLow = cx + step - halfWidth
                val xHigh = cx + step + halfWidth
                sum += diag.sumOnSumDiagonal(u, xLow, xHigh)
                count += diag.countOnSumDiagonal(u, xLow, xHigh)
            }
            if (count > 0) best = max(best, (sum / count).toFloat())
        }

        run {
            var sum = 0.0
            var count = 0
            val vCenter = cx - cy
            for (step in -halfLength..halfLength) {
                val v = vCenter + 2 * step
                val yLow = cy - step - halfWidth
                val yHigh = cy - step + halfWidth
                sum += diag.sumOnDiffDiagonal(v, yLow, yHigh)
                count += diag.countOnDiffDiagonal(v, yLow, yHigh)
            }
            if (count > 0) best = max(best, (sum / count).toFloat())
        }

        return best.coerceIn(0f, 1f)
    }

    private fun normalizePositive(values: FloatArray): FloatArray {
        var count = 0
        val finite = FloatArray(values.size)
        for (value in values) if (value.isFinite() && value >= 0f) finite[count++] = value
        if (count == 0) return FloatArray(values.size)
        val sorted = finite.copyOf(count)
        sorted.sort()
        val index = ((count - 1) * 0.96f).roundToInt().coerceIn(0, count - 1)
        val scale = sorted[index].coerceAtLeast(1e-6f)
        return FloatArray(values.size) { (values[it].coerceAtLeast(0f) / scale).coerceIn(0f, 1f) }
    }

    private fun normalizeSigned(values: FloatArray): FloatArray {
        var count = 0
        val finite = FloatArray(values.size)
        for (value in values) if (value.isFinite()) finite[count++] = abs(value)
        if (count == 0) return FloatArray(values.size)
        val sorted = finite.copyOf(count)
        sorted.sort()
        val index = ((count - 1) * 0.96f).roundToInt().coerceIn(0, count - 1)
        val scale = sorted[index].coerceAtLeast(1e-6f)
        return FloatArray(values.size) { (values[it] / scale).coerceIn(-1f, 1f) }
    }

    private fun triangularPreference(value: Float, center: Float, halfWidth: Float): Float =
        (1f - abs(value - center) / halfWidth.coerceAtLeast(1e-6f)).coerceIn(0f, 1f)

    /**
     * Shifts a type's raw threshold by its FeatureTypeCalibration bias: a type the user keeps
     * confirming in the field surfaces more (lower effective threshold), one mostly rejected
     * surfaces less (higher threshold). Zero bias (the default, or too few verified samples) is
     * a no-op. Internal (rather than private) so it's directly unit-testable without needing to
     * hand-tune a synthetic terrain raster to land a score exactly at a threshold boundary.
     */
    internal fun calibratedThreshold(threshold: Float, calibrationBias: Float): Float =
        (threshold - calibrationBias).coerceIn(0.3f, 0.95f)

    private fun metersToCells(meters: Float, cellSize: Float, minimum: Int, maximum: Int): Int =
        (meters / cellSize.coerceAtLeast(0.01f)).roundToInt().coerceIn(minimum, maximum)

    private fun requireLayer(layers: TerrainDerivedLayers, layer: TerrainDerivedLayer): FloatArray =
        requireNotNull(layers.values[layer]) { "Missing derived layer ${layer.name}" }

    private fun distanceSquared(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }

    private fun localMaxima(
        values: FloatArray,
        width: Int,
        height: Int,
        threshold: Float,
        limit: Int,
    ): List<Pair<Int, Float>> {
        val candidates = ArrayList<Pair<Int, Float>>()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val value = values[index]
                if (value < threshold) continue
                var maximum = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        if (values[(y + dy) * width + x + dx] > value) maximum = false
                    }
                }
                if (maximum) candidates += index to value
            }
        }
        return candidates.sortedByDescending { it.second }.take(limit)
    }
}

/**
 * O(1) axis-aligned rectangle sum/count over a 2D summed-area table, plus an O(radius) circular
 * ring/disk mean (matching the exact `innerSq <= dx*dx+dy*dy <= outerSq` boundary a naive scan
 * would use) built by analytically solving, per row, the 1 or 2 integer x-intervals that satisfy
 * the annulus constraint and summing each with a single rectangle lookup.
 */
internal class RectSumTable(values: FloatArray, private val width: Int, private val height: Int) {
    private val stride = width + 1
    private val prefix = DoubleArray(stride * (height + 1))

    init {
        for (y in 0 until height) {
            var rowSum = 0.0
            val rowBase = y * width
            val prevRow = y * stride
            val curRow = (y + 1) * stride
            for (x in 0 until width) {
                rowSum += values[rowBase + x]
                prefix[curRow + x + 1] = prefix[prevRow + x + 1] + rowSum
            }
        }
    }

    /** Sum over the half-open rectangle [x0,x1) x [y0,y1), clipped to the grid. */
    fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Double {
        val cx0 = x0.coerceIn(0, width)
        val cx1 = x1.coerceIn(0, width)
        val cy0 = y0.coerceIn(0, height)
        val cy1 = y1.coerceIn(0, height)
        if (cx1 <= cx0 || cy1 <= cy0) return 0.0
        return prefix[cy1 * stride + cx1] - prefix[cy0 * stride + cx1] -
            prefix[cy1 * stride + cx0] + prefix[cy0 * stride + cx0]
    }

    fun count(x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val cx0 = x0.coerceIn(0, width)
        val cx1 = x1.coerceIn(0, width)
        val cy0 = y0.coerceIn(0, height)
        val cy1 = y1.coerceIn(0, height)
        val w = (cx1 - cx0).coerceAtLeast(0)
        val h = (cy1 - cy0).coerceAtLeast(0)
        return w * h
    }

    fun ringMean(cx: Int, cy: Int, innerRadius: Int, outerRadius: Int): Float {
        val innerSq = innerRadius * innerRadius
        val outerSq = outerRadius * outerRadius
        var sum = 0.0
        var count = 0
        for (dy in -outerRadius..outerRadius) {
            val y = cy + dy
            if (y < 0 || y >= height) continue
            val dySq = dy * dy
            val outerRemaining = outerSq - dySq
            if (outerRemaining < 0) continue
            val maxDx = integerSqrtFloor(outerRemaining)
            if (dySq < innerSq) {
                val innerRemaining = innerSq - dySq
                val minDx = integerSqrtCeil(innerRemaining)
                if (minDx > maxDx) continue
                sum += sum(cx - maxDx, y, cx - minDx + 1, y + 1)
                count += count(cx - maxDx, y, cx - minDx + 1, y + 1)
                sum += sum(cx + minDx, y, cx + maxDx + 1, y + 1)
                count += count(cx + minDx, y, cx + maxDx + 1, y + 1)
            } else {
                sum += sum(cx - maxDx, y, cx + maxDx + 1, y + 1)
                count += count(cx - maxDx, y, cx + maxDx + 1, y + 1)
            }
        }
        return if (count == 0) 0f else (sum / count).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        fun integerSqrtFloor(value: Int): Int {
            if (value <= 0) return 0
            var r = sqrt(value.toDouble()).toInt()
            while (r > 0 && r * r > value) r--
            while ((r + 1) * (r + 1) <= value) r++
            return r
        }

        fun integerSqrtCeil(value: Int): Int {
            if (value <= 0) return 0
            val floor = integerSqrtFloor(value)
            return if (floor * floor == value) floor else floor + 1
        }
    }
}

/**
 * O(1) range sums along the two 45-degree diagonal families of a grid: cells where `x+y` is
 * constant ("sum diagonal") and cells where `x-y` is constant ("diff diagonal"). Used to answer
 * the diagonal-direction corridor sums in [MetalDetectingTargetRefiner]'s directional-continuity
 * scan without an O(halfWidth) inner loop per step.
 */
internal class DiagonalSumTable(values: FloatArray, width: Int, height: Int) {
    private val uCount = width + height - 1
    private val uStart = IntArray(uCount)
    private val uPrefix = arrayOfNulls<DoubleArray>(uCount)

    private val vOffset = height - 1
    private val vCount = width + height - 1
    private val vStart = IntArray(vCount)
    private val vPrefix = arrayOfNulls<DoubleArray>(vCount)

    init {
        for (u in 0 until uCount) {
            val xLo = maxOf(0, u - height + 1)
            val xHi = minOf(width - 1, u)
            uStart[u] = xLo
            if (xHi >= xLo) {
                val len = xHi - xLo + 1
                val prefix = DoubleArray(len + 1)
                for (idx in 0 until len) {
                    val x = xLo + idx
                    val y = u - x
                    prefix[idx + 1] = prefix[idx] + values[y * width + x]
                }
                uPrefix[u] = prefix
            }
        }
        for (vIdx in 0 until vCount) {
            val v = vIdx - vOffset
            val yLo = maxOf(0, -v)
            val yHi = minOf(height - 1, width - 1 - v)
            vStart[vIdx] = yLo
            if (yHi >= yLo) {
                val len = yHi - yLo + 1
                val prefix = DoubleArray(len + 1)
                for (idx in 0 until len) {
                    val y = yLo + idx
                    val x = y + v
                    prefix[idx + 1] = prefix[idx] + values[y * width + x]
                }
                vPrefix[vIdx] = prefix
            }
        }
    }

    /** Sum of cells with x+y == u and x in [xLow, xHigh] (inclusive). */
    fun sumOnSumDiagonal(u: Int, xLow: Int, xHigh: Int): Double {
        if (u < 0 || u >= uCount) return 0.0
        val prefix = uPrefix[u] ?: return 0.0
        val len = prefix.size - 1
        val start = uStart[u]
        val lo = (xLow - start).coerceIn(0, len)
        val hi = (xHigh + 1 - start).coerceIn(0, len)
        if (hi <= lo) return 0.0
        return prefix[hi] - prefix[lo]
    }

    fun countOnSumDiagonal(u: Int, xLow: Int, xHigh: Int): Int {
        if (u < 0 || u >= uCount) return 0
        val prefix = uPrefix[u] ?: return 0
        val len = prefix.size - 1
        val start = uStart[u]
        val lo = (xLow - start).coerceIn(0, len)
        val hi = (xHigh + 1 - start).coerceIn(0, len)
        return (hi - lo).coerceAtLeast(0)
    }

    /** Sum of cells with x-y == v and y in [yLow, yHigh] (inclusive). */
    fun sumOnDiffDiagonal(v: Int, yLow: Int, yHigh: Int): Double {
        val vIdx = v + vOffset
        if (vIdx < 0 || vIdx >= vCount) return 0.0
        val prefix = vPrefix[vIdx] ?: return 0.0
        val len = prefix.size - 1
        val start = vStart[vIdx]
        val lo = (yLow - start).coerceIn(0, len)
        val hi = (yHigh + 1 - start).coerceIn(0, len)
        if (hi <= lo) return 0.0
        return prefix[hi] - prefix[lo]
    }

    fun countOnDiffDiagonal(v: Int, yLow: Int, yHigh: Int): Int {
        val vIdx = v + vOffset
        if (vIdx < 0 || vIdx >= vCount) return 0
        val prefix = vPrefix[vIdx] ?: return 0
        val len = prefix.size - 1
        val start = vStart[vIdx]
        val lo = (yLow - start).coerceIn(0, len)
        val hi = (yHigh + 1 - start).coerceIn(0, len)
        return (hi - lo).coerceAtLeast(0)
    }
}
