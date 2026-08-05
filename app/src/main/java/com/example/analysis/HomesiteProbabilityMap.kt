package com.example.analysis

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Continuous 0..1 probability surface answering "how likely is a historic homesite here?" for
 * every terrain cell, as opposed to [MetalDetectingTargetRefiner]'s discrete ranked points.
 *
 * The score is built from explainable components, all derived from [TerrainDerivedLayers]:
 *  - bench: a broad flat, smooth pad (building sites were leveled, not perched on slopes)
 *  - structure context: nearby depression x edge evidence (cellar holes, foundation outlines)
 *  - wall context: nearby linear x raised evidence (stone walls, field boundaries)
 *  - road context: nearby linear x flat evidence (wagon roads, cart paths)
 *  - anomaly clustering: human activity concentrates; isolated single-cell blips score lower
 *  - wet veto: cells sitting in ancient stream / drainage corridors are penalized
 *
 * Everything is normalized per dataset (96th percentile, mirroring the refiner) so the map is
 * comparable across tiles, and every blur is a [RectSumTable] box mean so cost stays O(cells).
 */
data class HomesiteProbabilityGrid(
    val width: Int,
    val height: Int,
    val values: FloatArray,
) {
    /**
     * Average-pools the full-resolution surface into [bins] x [bins] cells for map overlays.
     * Bin (row, col) covers the matching fraction of the source grid, so the overlay stays
     * aligned with the rendered terrain image without knowing real-world extents.
     */
    fun binned(bins: Int): FloatArray {
        require(bins >= 1) { "bins must be >= 1" }
        val out = FloatArray(bins * bins)
        for (binRow in 0 until bins) {
            val y0 = binRow * height / bins
            val y1 = ((binRow + 1) * height / bins).coerceAtLeast(y0 + 1).coerceAtMost(height)
            for (binCol in 0 until bins) {
                val x0 = binCol * width / bins
                val x1 = ((binCol + 1) * width / bins).coerceAtLeast(x0 + 1).coerceAtMost(width)
                var sum = 0f
                var count = 0
                for (y in y0 until y1) {
                    val rowOffset = y * width
                    for (x in x0 until x1) {
                        sum += values[rowOffset + x]
                        count++
                    }
                }
                out[binRow * bins + binCol] = if (count > 0) sum / count else 0f
            }
        }
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HomesiteProbabilityGrid) return false
        return width == other.width && height == other.height && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = 31 * (31 * width + height) + values.contentHashCode()
}

object HomesiteProbabilityMap {
    // Component weights; they sum to 1.0 so the pre-veto score stays interpretable.
    private const val WEIGHT_BENCH = 0.30f
    private const val WEIGHT_STRUCTURE = 0.20f
    private const val WEIGHT_ROAD = 0.18f
    private const val WEIGHT_WALL = 0.14f
    private const val WEIGHT_CLUSTER = 0.18f

    /** Strongest stream evidence removes this fraction of the score. */
    private const val WET_VETO_STRENGTH = 0.75f

    fun compute(layers: TerrainDerivedLayers): HomesiteProbabilityGrid {
        val width = layers.width
        val height = layers.height
        require(width >= 3 && height >= 3) { "Grid too small for homesite mapping" }

        val slope = normalizePositive(requireLayer(layers, TerrainDerivedLayer.SLOPE))
        val curvature = normalizeSigned(requireLayer(layers, TerrainDerivedLayer.CURVATURE))
        val relief = normalizeSigned(requireLayer(layers, TerrainDerivedLayer.LOCAL_RELIEF))
        val depression = normalizePositive(requireLayer(layers, TerrainDerivedLayer.DEPRESSION_DEPTH))
        val linearity = normalizePositive(requireLayer(layers, TerrainDerivedLayer.LINEARITY))
        // Tolerate older cached analyses that predate the stream layer instead of crashing.
        val stream = layers.values[TerrainDerivedLayer.ANCIENT_STREAM]
            ?.let(::normalizePositive)
            ?: FloatArray(width * height)

        val size = width * height
        val flat = FloatArray(size) { 1f - slope[it] }
        val raised = FloatArray(size) { relief[it].coerceAtLeast(0f) }
        val edge = FloatArray(size) { (abs(curvature[it]) * 0.5f + linearity[it] * 0.5f).coerceIn(0f, 1f) }

        // Evidence fields that get context-blurred: the question is not "is this exact cell a
        // cellar" but "does this cell sit among cellar / wall / road evidence".
        val structureEvidence = FloatArray(size) { depression[it] * (0.4f + 0.6f * edge[it]) }
        val wallEvidence = FloatArray(size) { linearity[it] * raised[it] }
        val roadEvidence = FloatArray(size) { linearity[it] * flat[it] }
        val anomalyEvidence = FloatArray(size) {
            (depression[it] * edge[it] + linearity[it] * raised[it] + depression[it] * flat[it] * 0.5f)
                .coerceIn(0f, 1f)
        }

        val benchRadius = metersToCells(6f, layers.cellSizeMeters, 1, 10)
        val structureRadius = metersToCells(14f, layers.cellSizeMeters, 2, 24)
        val roadRadius = metersToCells(18f, layers.cellSizeMeters, 2, 30)
        val wallRadius = metersToCells(16f, layers.cellSizeMeters, 2, 28)
        val clusterRadius = metersToCells(30f, layers.cellSizeMeters, 3, 48)
        val wetRadius = metersToCells(10f, layers.cellSizeMeters, 1, 18)

        val flatRect = RectSumTable(flat, width, height)
        val structureRect = RectSumTable(structureEvidence, width, height)
        val wallRect = RectSumTable(wallEvidence, width, height)
        val roadRect = RectSumTable(roadEvidence, width, height)
        val anomalyRect = RectSumTable(anomalyEvidence, width, height)
        val streamRect = RectSumTable(stream, width, height)

        val out = FloatArray(size)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val i = rowOffset + x
                val bench = flatRect.ringMean(x, y, 0, benchRadius)
                val structureCtx = structureRect.ringMean(x, y, 0, structureRadius)
                val roadCtx = roadRect.ringMean(x, y, 0, roadRadius)
                val wallCtx = wallRect.ringMean(x, y, 0, wallRadius)
                // Renormalize the big-window blur toward its own 96th percentile would be ideal;
                // the clamp below keeps the cheaper absolute version bounded.
                val cluster = (anomalyRect.ringMean(x, y, 0, clusterRadius) * 2.2f).coerceIn(0f, 1f)
                val wet = (streamRect.ringMean(x, y, 0, wetRadius) * 1.6f).coerceIn(0f, 1f)

                val score = WEIGHT_BENCH * bench +
                    WEIGHT_STRUCTURE * structureCtx +
                    WEIGHT_ROAD * roadCtx +
                    WEIGHT_WALL * wallCtx +
                    WEIGHT_CLUSTER * cluster
                out[i] = (score * (1f - WET_VETO_STRENGTH * wet)).coerceIn(0f, 1f)
            }
        }
        return HomesiteProbabilityGrid(width, height, out)
    }

    private fun metersToCells(meters: Float, cellSize: Float, minimum: Int, maximum: Int): Int =
        (meters / cellSize.coerceAtLeast(0.01f)).roundToInt().coerceIn(minimum, maximum)

    private fun requireLayer(layers: TerrainDerivedLayers, layer: TerrainDerivedLayer): FloatArray =
        requireNotNull(layers.values[layer]) { "Missing derived layer ${layer.name}" }

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
}
