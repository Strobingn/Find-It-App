package com.example.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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
    val evidence: List<String>,
)

/**
 * Re-scores terrain signatures for metal-detecting field work with spatial context.
 *
 * The base terrain engine supplies local-relief, curvature, openness, ruggedness and
 * linearity layers. This pass adds the neighborhood geometry that a single-cell weighted
 * sum cannot represent: flat interiors with edge rings, continuous corridors, compact
 * depressions with raised rims, and shallow irregular pits near occupation features.
 * Scores are screening priorities only; they are not proof of a buried object.
 */
object MetalDetectingTargetRefiner {
    private const val MAX_PER_TYPE = 12
    private const val MAX_TOTAL = 48

    fun refine(result: TerrainIntelligenceResult): List<MetalDetectingTarget> {
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

        val foundation = FloatArray(width * height)
        val road = FloatArray(width * height)
        val cellar = FloatArray(width * height)
        val trash = FloatArray(width * height)
        val wall = FloatArray(width * height)
        val homesite = FloatArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val interiorFlat = diskMean(flat, width, height, x, y, innerRadius)
                val interiorSmooth = diskMean(smooth, width, height, x, y, innerRadius)
                val boundaryEdge = ringMean(edge, width, height, x, y, edgeInner, edgeOuter)
                val rimRaised = ringMean(raised, width, height, x, y, edgeInner, edgeOuter)
                val centerDepression = diskMean(depression, width, height, x, y, innerRadius)
                val centerConcavity = diskMean(concave, width, height, x, y, innerRadius)
                val centerLowered = diskMean(lowered, width, height, x, y, innerRadius)
                val centerRugged = diskMean(rugged, width, height, x, y, innerRadius)
                val directionalLine = directionalContinuity(
                    linearity,
                    width,
                    height,
                    x,
                    y,
                    corridorHalfLength,
                    corridorHalfWidth,
                )
                val corridorFlat = directionalContinuity(
                    flat,
                    width,
                    height,
                    x,
                    y,
                    corridorHalfLength,
                    corridorHalfWidth,
                )
                val corridorSmooth = directionalContinuity(
                    smooth,
                    width,
                    height,
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
                val cutOrCrown = max(centerLowered, diskMean(raised, width, height, x, y, innerRadius))
                road[i] = (
                    directionalLine * 0.38f +
                        corridorFlat * 0.24f +
                        corridorSmooth * 0.18f +
                        cutOrCrown * 0.12f +
                        hillCompare[i] * 0.08f
                    ).coerceIn(0f, 1f)

                // Cellar holes: compact deep depression, concave center, and a raised/defined perimeter.
                val compactDepression = (centerDepression - ringMean(depression, width, height, x, y, edgeInner, edgeOuter) * 0.55f)
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
                val irregularEdge = (ringMean(edge, width, height, x, y, edgeInner, edgeOuter) * 0.65f + centerRugged * 0.35f)
                    .coerceIn(0f, 1f)
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
                        ringMean(raised, width, height, x, y, 0, innerRadius) * 0.23f +
                        boundaryEdge * 0.22f +
                        corridorSmooth * 0.13f
                    ).coerceIn(0f, 1f)
            }
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val foundationContext = diskMean(foundation, width, height, x, y, contextRadius)
                val cellarContext = diskMean(cellar, width, height, x, y, contextRadius)
                val trashContext = diskMean(trash, width, height, x, y, contextRadius)
                val roadContext = diskMean(road, width, height, x, y, contextRadius)
                homesite[i] = (
                    foundationContext * 0.36f +
                        cellarContext * 0.22f +
                        trashContext * 0.14f +
                        roadContext * 0.18f +
                        diskMean(flat, width, height, x, y, contextRadius) * 0.10f
                    ).coerceIn(0f, 1f)
                // Trash-pit priority rises when a shallow pit is close to occupation evidence.
                trash[i] = (trash[i] * 0.76f + homesite[i] * 0.24f).coerceIn(0f, 1f)
            }
        }

        val output = ArrayList<MetalDetectingTarget>()
        appendTargets(output, MetalDetectingTargetType.FOUNDATION, foundation, width, height, 0.66f, 8f,
            listOf("flat interior neighborhood", "rectilinear edge ring", "multi-direction persistence"))
        appendTargets(output, MetalDetectingTargetType.ROAD_TRAIL, road, width, height, 0.67f, 7f,
            listOf("continuous linear corridor", "low-gradient smooth surface", "cut or crowned relief"))
        appendTargets(output, MetalDetectingTargetType.CELLAR_HOLE, cellar, width, height, 0.68f, 7f,
            listOf("compact deep depression", "concave center", "raised or defined perimeter"))
        appendTargets(output, MetalDetectingTargetType.TRASH_PIT, trash, width, height, 0.65f, 5f,
            listOf("shallow irregular depression", "occupation-context proximity", "possible refuse-pit morphology"))
        appendTargets(output, MetalDetectingTargetType.STONE_WALL, wall, width, height, 0.68f, 5f,
            listOf("continuous raised line", "edge persistence", "low cross-line roughness"))
        appendTargets(output, MetalDetectingTargetType.OLD_HOMESITE, homesite, width, height, 0.66f, 14f,
            listOf("foundation/cellar/pit cluster", "road access context", "locally usable ground"))

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
        evidence: List<String>,
    ) {
        localMaxima(score, width, height, threshold, MAX_PER_TYPE).forEach { (index, value) ->
            val x = index % width
            val y = index / width
            output += MetalDetectingTarget(
                type = type,
                xPercent = if (width <= 1) 50f else x * 100f / (width - 1),
                yPercent = if (height <= 1) 50f else y * 100f / (height - 1),
                score = value,
                radiusMeters = radiusMeters,
                evidence = evidence,
            )
        }
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

    private fun directionalContinuity(
        values: FloatArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        halfLength: Int,
        halfWidth: Int,
    ): Float {
        val directions = arrayOf(1 to 0, 0 to 1, 1 to 1, 1 to -1)
        var best = 0f
        for ((dx, dy) in directions) {
            var sum = 0f
            var count = 0
            for (step in -halfLength..halfLength) {
                for (cross in -halfWidth..halfWidth) {
                    val px = cx + dx * step - dy * cross
                    val py = cy + dy * step + dx * cross
                    if (px !in 0 until width || py !in 0 until height) continue
                    sum += values[py * width + px]
                    count++
                }
            }
            if (count > 0) best = max(best, sum / count)
        }
        return best.coerceIn(0f, 1f)
    }

    private fun diskMean(
        values: FloatArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        radius: Int,
    ): Float = ringMean(values, width, height, cx, cy, 0, radius)

    private fun ringMean(
        values: FloatArray,
        width: Int,
        height: Int,
        cx: Int,
        cy: Int,
        innerRadius: Int,
        outerRadius: Int,
    ): Float {
        val innerSquared = innerRadius * innerRadius
        val outerSquared = outerRadius * outerRadius
        var sum = 0f
        var count = 0
        for (dy in -outerRadius..outerRadius) {
            for (dx in -outerRadius..outerRadius) {
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared < innerSquared || distanceSquared > outerSquared) continue
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until width || y !in 0 until height) continue
                sum += values[y * width + x]
                count++
            }
        }
        return if (count == 0) 0f else (sum / count).coerceIn(0f, 1f)
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

    private fun normalizePositive(values: FloatArray): FloatArray {
        val finite = values.filter { it.isFinite() && it >= 0f }.sorted()
        if (finite.isEmpty()) return FloatArray(values.size)
        val scale = finite[(finite.lastIndex * 0.96f).roundToInt().coerceIn(0, finite.lastIndex)].coerceAtLeast(1e-6f)
        return FloatArray(values.size) { (values[it].coerceAtLeast(0f) / scale).coerceIn(0f, 1f) }
    }

    private fun normalizeSigned(values: FloatArray): FloatArray {
        val finite = values.filter(Float::isFinite).map(::abs).sorted()
        if (finite.isEmpty()) return FloatArray(values.size)
        val scale = finite[(finite.lastIndex * 0.96f).roundToInt().coerceIn(0, finite.lastIndex)].coerceAtLeast(1e-6f)
        return FloatArray(values.size) { (values[it] / scale).coerceIn(-1f, 1f) }
    }

    private fun triangularPreference(value: Float, center: Float, halfWidth: Float): Float =
        (1f - abs(value - center) / halfWidth.coerceAtLeast(1e-6f)).coerceIn(0f, 1f)

    private fun metersToCells(meters: Float, cellSize: Float, minimum: Int, maximum: Int): Int =
        (meters / cellSize.coerceAtLeast(0.01f)).roundToInt().coerceIn(minimum, maximum)

    private fun requireLayer(layers: TerrainDerivedLayers, layer: TerrainDerivedLayer): FloatArray =
        requireNotNull(layers.values[layer]) { "Missing derived layer ${layer.name}" }

    private fun distanceSquared(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return dx * dx + dy * dy
    }
}
