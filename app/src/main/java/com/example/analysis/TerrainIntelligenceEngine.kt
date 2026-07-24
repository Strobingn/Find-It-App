package com.example.analysis

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.ElevationGrid
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.EnumMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class TerrainDerivedLayer(val label: String, val description: String) {
    SLOPE("Slope", "Surface steepness in degrees"),
    ASPECT("Aspect", "Downslope direction from north"),
    CURVATURE("Curvature", "Concave and convex terrain bending"),
    LOCAL_RELIEF("Local Relief Model", "Small features after broad terrain is removed"),
    HILLSHADE_COMPARISON("Hillshade comparison", "Directional illumination disagreement across eight azimuths"),
    POSITIVE_OPENNESS("Positive openness", "Exposure of the upper horizon"),
    NEGATIVE_OPENNESS("Negative openness", "Enclosure below the local horizon"),
    SKY_VIEW_FACTOR("Sky-view factor", "Approximate visible-sky fraction"),
    DEPRESSION_DEPTH("Depression depth", "Local bowl and sink depth"),
    RUGGEDNESS("Ruggedness Index", "Local elevation variability"),
    LINEARITY("Linear feature response", "Narrow aligned ridges, cuts, walls, roads, and trails"),
    ANCIENT_STREAM("Ancient-stream likelihood", "Low, concave, gently graded drainage traces"),
    ARTIFACT_HOTSPOT("Artifact hotspot prediction", "Combined terrain-context priority score"),
}

enum class TerrainFeatureType(val label: String) {
    DEPRESSION("Depression"),
    STONE_WALL("Stone wall"),
    FOUNDATION("Foundation"),
    CELLAR_HOLE("Cellar hole"),
    ROAD_TRAIL("Road or trail"),
    OLD_HOMESITE("Old homesite"),
    ARTIFACT_HOTSPOT("Artifact hotspot"),
    DIG_RECOMMENDATION("AI dig recommendation"),
    CHARCOAL_PIT("Charcoal pit"),
    MINE_QUARRY("Mine or quarry"),
    MILITARY_CAMP("Military-camp probability"),
    ANCIENT_STREAM("Ancient stream"),
}

enum class TerrainFeedbackRating { CONFIRMED, REJECTED, UNSURE }

data class TerrainFeedbackRecord(
    val datasetKey: String,
    val candidateId: String,
    val featureType: TerrainFeatureType,
    val xPercent: Float,
    val yPercent: Float,
    val rating: TerrainFeedbackRating,
    val note: String,
    val updatedAtMillis: Long,
)

data class TerrainFeatureCandidate(
    val id: String,
    val type: TerrainFeatureType,
    val xPercent: Float,
    val yPercent: Float,
    val score: Float,
    val radiusMeters: Float,
    val evidence: List<String>,
    val feedback: TerrainFeedbackRating? = null,
    val note: String = "",
)

data class TerrainDerivedLayers(
    val width: Int,
    val height: Int,
    val cellSizeMeters: Float,
    val values: Map<TerrainDerivedLayer, FloatArray>,
)

data class TerrainIntelligenceResult(
    val datasetKey: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val layers: TerrainDerivedLayers,
    val candidates: List<TerrainFeatureCandidate>,
    val recommendation: String,
    val cacheHit: TerrainDerivedLayerCache.Hit,
)

class TerrainIntelligenceEngine(
    private val cache: TerrainDerivedLayerCache,
) {
    suspend fun analyze(
        grid: ElevationGrid,
        terrainSummary: String,
        feedback: List<TerrainFeedbackRecord> = emptyList(),
        onStage: suspend (String) -> Unit = {},
    ): TerrainIntelligenceResult {
        val datasetKey = terrainSignature(grid)
        onStage("Checking the derived-layer cache…")
        val lookup = cache.get(datasetKey)
        val layers = lookup.layers ?: withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            onStage("Extracting slope, aspect, curvature, relief, openness, sky view, and ruggedness…")
            computeDerivedLayers(grid)
        }.also {
            onStage("Saving derived layers for fast reopening…")
            cache.put(datasetKey, it)
        }

        onStage("Running terrain-feature detectors…")
        val candidates = withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            detectFeatures(datasetKey, layers, feedback)
        }
        val recommendation = buildRecommendation(candidates, terrainSummary)
        return TerrainIntelligenceResult(
            datasetKey = datasetKey,
            sourceWidth = grid.width,
            sourceHeight = grid.height,
            layers = layers,
            candidates = candidates,
            recommendation = recommendation,
            cacheHit = lookup.hit,
        )
    }

    private fun computeDerivedLayers(grid: ElevationGrid): TerrainDerivedLayers {
        val sampled = downsample(grid, MAX_ANALYSIS_SIDE)
        val width = sampled.width
        val height = sampled.height
        val cell = sampled.cellSizeMeters.coerceAtLeast(0.01f)
        val elevation = sampled.elevation
        val size = width * height
        val slope = FloatArray(size)
        val aspect = FloatArray(size)
        val curvature = FloatArray(size)
        val hillshadeRange = FloatArray(size)
        val linearityRaw = FloatArray(size)

        fun index(x: Int, y: Int): Int = y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)
        fun at(x: Int, y: Int): Float = elevation[index(x, y)]

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val z00 = at(x - 1, y - 1)
                val z01 = at(x, y - 1)
                val z02 = at(x + 1, y - 1)
                val z10 = at(x - 1, y)
                val z12 = at(x + 1, y)
                val z20 = at(x - 1, y + 1)
                val z21 = at(x, y + 1)
                val z22 = at(x + 1, y + 1)
                val dx = ((z02 + 2f * z12 + z22) - (z00 + 2f * z10 + z20)) / (8f * cell)
                val dy = ((z20 + 2f * z21 + z22) - (z00 + 2f * z01 + z02)) / (8f * cell)
                slope[i] = Math.toDegrees(atan(sqrt(dx * dx + dy * dy)).toDouble()).toFloat()
                aspect[i] = ((Math.toDegrees(atan2(dx, -dy).toDouble()).toFloat() + 360f) % 360f)
                curvature[i] = (at(x - 1, y) + at(x + 1, y) + at(x, y - 1) + at(x, y + 1) - 4f * at(x, y)) / (cell * cell)

                var minShade = 1f
                var maxShade = 0f
                for (azimuth in 0 until 360 step 45) {
                    val shade = terrainShade(dx, dy, azimuth.toFloat(), 35f)
                    minShade = min(minShade, shade)
                    maxShade = max(maxShade, shade)
                }
                hillshadeRange[i] = maxShade - minShade

                val dxx = abs(at(x - 1, y) - 2f * at(x, y) + at(x + 1, y))
                val dyy = abs(at(x, y - 1) - 2f * at(x, y) + at(x, y + 1))
                val d45 = abs(at(x - 1, y - 1) - 2f * at(x, y) + at(x + 1, y + 1))
                val d135 = abs(at(x + 1, y - 1) - 2f * at(x, y) + at(x - 1, y + 1))
                val strongest = max(max(dxx, dyy), max(d45, d135))
                val second = listOf(dxx, dyy, d45, d135).sortedDescending().getOrElse(1) { 0f }
                linearityRaw[i] = (strongest - second * 0.45f).coerceAtLeast(0f)
            }
        }

        val reliefRadius = (8f / cell).roundToInt().coerceIn(2, 18)
        val reliefMean = boxMean(elevation, width, height, reliefRadius)
        val localRelief = FloatArray(size) { elevation[it] - reliefMean[it] }
        val ruggedness = boxStandardDeviation(elevation, width, height, (2f / cell).roundToInt().coerceIn(1, 4))
        val depressionDepth = FloatArray(size) { max(0f, -localRelief[it]) * 0.7f + max(0f, curvature[it]) * cell * cell * 0.3f }

        val positiveOpenness = FloatArray(size)
        val negativeOpenness = FloatArray(size)
        val skyView = FloatArray(size)
        val horizonRadius = (24f / cell).roundToInt().coerceIn(5, 18)
        val directions = arrayOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to 0, 1 to 0,
            -1 to 1, 0 to 1, 1 to 1,
        )
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val center = elevation[i]
                var positiveAngleSum = 0.0
                var negativeAngleSum = 0.0
                var skySum = 0.0
                for ((stepX, stepY) in directions) {
                    var highest = 0.0
                    var lowest = 0.0
                    for (step in 1..horizonRadius) {
                        val px = x + stepX * step
                        val py = y + stepY * step
                        if (px !in 0 until width || py !in 0 until height) break
                        val horizontal = cell * step * if (stepX != 0 && stepY != 0) 1.41421356f else 1f
                        val angle = atan(((elevation[py * width + px] - center) / horizontal).toDouble())
                        highest = max(highest, angle)
                        lowest = min(lowest, angle)
                    }
                    positiveAngleSum += highest
                    negativeAngleSum += -lowest
                    skySum += cos(highest).let { it * it }
                }
                val count = directions.size.toDouble()
                positiveOpenness[i] = (1.0 - positiveAngleSum / count / (PI / 2.0)).toFloat().coerceIn(0f, 1f)
                negativeOpenness[i] = (1.0 - negativeAngleSum / count / (PI / 2.0)).toFloat().coerceIn(0f, 1f)
                skyView[i] = (skySum / count).toFloat().coerceIn(0f, 1f)
            }
        }

        val slopeNorm = normalizePositive(slope)
        val curvatureNorm = normalizeSigned(curvature)
        val reliefNorm = normalizeSigned(localRelief)
        val ruggedNorm = normalizePositive(ruggedness)
        val depressionNorm = normalizePositive(depressionDepth)
        val linearity = normalizePositive(linearityRaw)
        val ancientStream = FloatArray(size) { i ->
            val concavity = max(0f, curvatureNorm[i])
            val lowRelief = max(0f, -reliefNorm[i])
            val gentle = 1f - slopeNorm[i]
            val enclosed = 1f - positiveOpenness[i]
            (concavity * 0.34f + lowRelief * 0.30f + gentle * 0.23f + enclosed * 0.13f).coerceIn(0f, 1f)
        }

        val layers = EnumMap<TerrainDerivedLayer, FloatArray>(TerrainDerivedLayer::class.java).apply {
            put(TerrainDerivedLayer.SLOPE, slope)
            put(TerrainDerivedLayer.ASPECT, aspect)
            put(TerrainDerivedLayer.CURVATURE, curvature)
            put(TerrainDerivedLayer.LOCAL_RELIEF, localRelief)
            put(TerrainDerivedLayer.HILLSHADE_COMPARISON, hillshadeRange)
            put(TerrainDerivedLayer.POSITIVE_OPENNESS, positiveOpenness)
            put(TerrainDerivedLayer.NEGATIVE_OPENNESS, negativeOpenness)
            put(TerrainDerivedLayer.SKY_VIEW_FACTOR, skyView)
            put(TerrainDerivedLayer.DEPRESSION_DEPTH, depressionDepth)
            put(TerrainDerivedLayer.RUGGEDNESS, ruggedness)
            put(TerrainDerivedLayer.LINEARITY, linearityRaw)
            put(TerrainDerivedLayer.ANCIENT_STREAM, ancientStream)
        }
        return TerrainDerivedLayers(width, height, cell, layers)
    }

    private fun detectFeatures(
        datasetKey: String,
        layers: TerrainDerivedLayers,
        feedback: List<TerrainFeedbackRecord>,
    ): List<TerrainFeatureCandidate> {
        val width = layers.width
        val height = layers.height
        val size = width * height
        val slope = normalizePositive(requireLayer(layers, TerrainDerivedLayer.SLOPE))
        val curvature = normalizeSigned(requireLayer(layers, TerrainDerivedLayer.CURVATURE))
        val relief = normalizeSigned(requireLayer(layers, TerrainDerivedLayer.LOCAL_RELIEF))
        val rugged = normalizePositive(requireLayer(layers, TerrainDerivedLayer.RUGGEDNESS))
        val depression = normalizePositive(requireLayer(layers, TerrainDerivedLayer.DEPRESSION_DEPTH))
        val linearity = normalizePositive(requireLayer(layers, TerrainDerivedLayer.LINEARITY))
        val positiveOpen = requireLayer(layers, TerrainDerivedLayer.POSITIVE_OPENNESS)
        val negativeOpen = requireLayer(layers, TerrainDerivedLayer.NEGATIVE_OPENNESS)
        val skyView = requireLayer(layers, TerrainDerivedLayer.SKY_VIEW_FACTOR)
        val hillCompare = normalizePositive(requireLayer(layers, TerrainDerivedLayer.HILLSHADE_COMPARISON))
        val stream = requireLayer(layers, TerrainDerivedLayer.ANCIENT_STREAM)

        val flat = FloatArray(size) { 1f - slope[it] }
        val smooth = FloatArray(size) { 1f - rugged[it] }
        val nearNeutralRelief = FloatArray(size) { 1f - abs(relief[it]).coerceIn(0f, 1f) }
        val convex = FloatArray(size) { max(0f, -curvature[it]) }
        val concave = FloatArray(size) { max(0f, curvature[it]) }
        val raised = FloatArray(size) { max(0f, relief[it]) }
        val lowered = FloatArray(size) { max(0f, -relief[it]) }

        val road = FloatArray(size) { i ->
            (flat[i] * 0.28f + smooth[i] * 0.22f + linearity[i] * 0.30f + nearNeutralRelief[i] * 0.20f).coerceIn(0f, 1f)
        }
        val foundation = FloatArray(size) { i ->
            (flat[i] * 0.25f + smooth[i] * 0.18f + linearity[i] * 0.31f + hillCompare[i] * 0.16f + raised[i] * 0.10f).coerceIn(0f, 1f)
        }
        val cellar = FloatArray(size) { i ->
            (depression[i] * 0.48f + concave[i] * 0.22f + lowered[i] * 0.17f + smooth[i] * 0.08f + (1f - positiveOpen[i]) * 0.05f).coerceIn(0f, 1f)
        }
        val wall = FloatArray(size) { i ->
            (linearity[i] * 0.48f + raised[i] * 0.20f + abs(curvature[i]) * 0.17f + smooth[i] * 0.15f).coerceIn(0f, 1f)
        }
        val charcoal = FloatArray(size) { i ->
            (flat[i] * 0.33f + smooth[i] * 0.24f + nearNeutralRelief[i] * 0.18f + hillCompare[i] * 0.13f + (1f - abs(positiveOpen[i] - negativeOpen[i])) * 0.12f).coerceIn(0f, 1f)
        }
        val quarry = FloatArray(size) { i ->
            (depression[i] * 0.27f + rugged[i] * 0.30f + slope[i] * 0.20f + concave[i] * 0.13f + (1f - skyView[i]) * 0.10f).coerceIn(0f, 1f)
        }
        val homesite = FloatArray(size) { i ->
            (foundation[i] * 0.37f + cellar[i] * 0.25f + road[i] * 0.20f + wall[i] * 0.10f + flat[i] * 0.08f).coerceIn(0f, 1f)
        }
        val camp = FloatArray(size) { i ->
            (flat[i] * 0.25f + road[i] * 0.22f + homesite[i] * 0.20f + hillCompare[i] * 0.13f + smooth[i] * 0.10f + wall[i] * 0.10f).coerceIn(0f, 1f)
        }
        val hotspot = FloatArray(size) { i ->
            (homesite[i] * 0.34f + road[i] * 0.18f + camp[i] * 0.17f + foundation[i] * 0.12f + cellar[i] * 0.11f + flat[i] * 0.08f).coerceIn(0f, 1f)
        }
        val dig = FloatArray(size) { i ->
            (hotspot[i] * 0.55f + homesite[i] * 0.18f + road[i] * 0.10f + stream[i] * 0.07f + smooth[i] * 0.10f).coerceIn(0f, 1f)
        }

        val definitions = listOf(
            Detector(TerrainFeatureType.DEPRESSION, depression, 0.62f, 5f, listOf("local bowl depth", "concave curvature", "negative local relief")),
            Detector(TerrainFeatureType.STONE_WALL, wall, 0.64f, 4f, listOf("linear raised response", "narrow curvature edge", "low cross-line roughness")),
            Detector(TerrainFeatureType.FOUNDATION, foundation, 0.66f, 7f, listOf("rectilinear edge response", "flat interior tendency", "multi-hillshade persistence")),
            Detector(TerrainFeatureType.CELLAR_HOLE, cellar, 0.67f, 6f, listOf("compact depression", "concave center", "enclosed local horizon")),
            Detector(TerrainFeatureType.ROAD_TRAIL, road, 0.65f, 5f, listOf("elongated low-gradient corridor", "smooth local surface", "linear response")),
            Detector(TerrainFeatureType.OLD_HOMESITE, homesite, 0.68f, 12f, listOf("foundation/cellar combination", "road access context", "locally usable ground")),
            Detector(TerrainFeatureType.ARTIFACT_HOTSPOT, hotspot, 0.69f, 10f, listOf("homesite context", "access corridor proximity", "disturbance evidence")),
            Detector(TerrainFeatureType.DIG_RECOMMENDATION, dig, 0.72f, 8f, listOf("ensemble terrain score", "field-access context", "user feedback weighting")),
            Detector(TerrainFeatureType.CHARCOAL_PIT, charcoal, 0.70f, 9f, listOf("level circular-platform tendency", "subtle relief rim", "balanced openness")),
            Detector(TerrainFeatureType.MINE_QUARRY, quarry, 0.70f, 16f, listOf("large concavity", "steep irregular margins", "high ruggedness")),
            Detector(TerrainFeatureType.MILITARY_CAMP, camp, 0.72f, 18f, listOf("clustered level ground", "road/trail context", "repeated disturbance pattern")),
            Detector(TerrainFeatureType.ANCIENT_STREAM, stream, 0.66f, 10f, listOf("concave low-relief corridor", "gentle continuous grade", "restricted sky view")),
        )

        val output = ArrayList<TerrainFeatureCandidate>()
        for (definition in definitions) {
            val maxima = localMaxima(definition.score, width, height, definition.threshold, perTypeLimit = 14)
            for ((index, rawScore) in maxima) {
                val x = index % width
                val y = index / width
                val xPercent = if (width <= 1) 50f else x * 100f / (width - 1)
                val yPercent = if (height <= 1) 50f else y * 100f / (height - 1)
                val matchingFeedback = feedback
                    .filter { it.datasetKey == datasetKey && it.featureType == definition.type }
                    .minByOrNull { distanceSquared(it.xPercent, it.yPercent, xPercent, yPercent) }
                    ?.takeIf { distanceSquared(it.xPercent, it.yPercent, xPercent, yPercent) <= 64f }
                val adjusted = when (matchingFeedback?.rating) {
                    TerrainFeedbackRating.CONFIRMED -> rawScore + 0.14f
                    TerrainFeedbackRating.REJECTED -> rawScore - 0.28f
                    TerrainFeedbackRating.UNSURE -> rawScore - 0.03f
                    null -> rawScore
                }.coerceIn(0f, 1f)
                if (adjusted < definition.threshold * 0.88f) continue
                output += TerrainFeatureCandidate(
                    id = stableCandidateId(datasetKey, definition.type, xPercent, yPercent),
                    type = definition.type,
                    xPercent = xPercent,
                    yPercent = yPercent,
                    score = adjusted,
                    radiusMeters = definition.radiusMeters,
                    evidence = definition.evidence,
                    feedback = matchingFeedback?.rating,
                    note = matchingFeedback?.note.orEmpty(),
                )
            }
        }

        val sorted = output.sortedByDescending { it.score }.take(MAX_TOTAL_CANDIDATES)
        val hotspotLayer = FloatArray(size)
        for (candidate in sorted.filter { it.type == TerrainFeatureType.ARTIFACT_HOTSPOT || it.type == TerrainFeatureType.DIG_RECOMMENDATION }) {
            val cx = (candidate.xPercent / 100f * (width - 1)).roundToInt()
            val cy = (candidate.yPercent / 100f * (height - 1)).roundToInt()
            val radius = (candidate.radiusMeters / layers.cellSizeMeters).roundToInt().coerceIn(2, 18)
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = cx + dx
                    val py = cy + dy
                    if (px !in 0 until width || py !in 0 until height) continue
                    val distance = sqrt((dx * dx + dy * dy).toFloat()) / radius
                    if (distance > 1f) continue
                    val influence = candidate.score * (1f - distance)
                    val i = py * width + px
                    hotspotLayer[i] = max(hotspotLayer[i], influence)
                }
            }
        }
        (layers.values as? MutableMap<TerrainDerivedLayer, FloatArray>)?.put(TerrainDerivedLayer.ARTIFACT_HOTSPOT, hotspotLayer)
        return sorted
    }

    private fun buildRecommendation(candidates: List<TerrainFeatureCandidate>, summary: String): String {
        val top = candidates.filter { it.type == TerrainFeatureType.DIG_RECOMMENDATION }.take(3)
        if (top.isEmpty()) {
            return "No high-confidence dig-priority zone cleared the current screening threshold. Review local-relief, openness, ruggedness, and ancient-stream layers manually before lowering thresholds."
        }
        return buildString {
            append("Terrain ensemble recommends field-checking ")
            append(top.size)
            append(if (top.size == 1) " zone" else " zones")
            append(". Highest score: ")
            append((top.first().score * 100f).roundToInt())
            append("% at grid ")
            append(top.first().xPercent.roundToInt())
            append(", ")
            append(top.first().yPercent.roundToInt())
            append(". Verify access, ownership, safety, and surface evidence before digging. Source context: ")
            append(summary.take(180))
        }
    }

    companion object {
        private const val MAX_ANALYSIS_SIDE = 384
        private const val MAX_TOTAL_CANDIDATES = 120

        fun terrainSignature(grid: ElevationGrid): String {
            var hash = -3750763034362895579L
            fun mix(value: Int) {
                hash = (hash xor value.toLong()) * 1099511628211L
            }
            mix(grid.width)
            mix(grid.height)
            mix(grid.cellSizeMeters.toBits())
            val step = max(1, grid.bareEarth.size / 4096)
            var index = 0
            while (index < grid.bareEarth.size) {
                mix(grid.bareEarth[index].toBits())
                mix(grid.canopySpikes[index].toBits())
                index += step
            }
            return java.lang.Long.toUnsignedString(hash, 16)
        }

        private fun terrainShade(dx: Float, dy: Float, azimuthDegrees: Float, altitudeDegrees: Float): Float {
            val azimuth = Math.toRadians(azimuthDegrees.toDouble())
            val altitude = Math.toRadians(altitudeDegrees.toDouble())
            val normalLength = sqrt(dx * dx + dy * dy + 1f)
            val nx = -dx / normalLength
            val ny = -dy / normalLength
            val nz = 1f / normalLength
            val horizontal = cos(altitude).toFloat()
            val lx = (sin(azimuth) * horizontal).toFloat()
            val ly = (-cos(azimuth) * horizontal).toFloat()
            val lz = sin(altitude).toFloat()
            return (nx * lx + ny * ly + nz * lz).coerceIn(0f, 1f)
        }

        private fun downsample(grid: ElevationGrid, maxSide: Int): SampledGrid {
            val scale = max(1.0, max(grid.width, grid.height).toDouble() / maxSide.toDouble())
            val width = ceil(grid.width / scale).toInt().coerceAtLeast(1)
            val height = ceil(grid.height / scale).toInt().coerceAtLeast(1)
            val output = FloatArray(width * height)
            for (y in 0 until height) {
                val y0 = floor(y * scale).toInt().coerceIn(0, grid.height - 1)
                val y1 = ceil((y + 1) * scale).toInt().coerceIn(y0 + 1, grid.height)
                for (x in 0 until width) {
                    val x0 = floor(x * scale).toInt().coerceIn(0, grid.width - 1)
                    val x1 = ceil((x + 1) * scale).toInt().coerceIn(x0 + 1, grid.width)
                    var sum = 0.0
                    var count = 0
                    for (sy in y0 until y1) {
                        for (sx in x0 until x1) {
                            val i = sy * grid.width + sx
                            if (!grid.validData[i]) continue
                            sum += grid.bareEarth[i]
                            count++
                        }
                    }
                    output[y * width + x] = if (count > 0) (sum / count).toFloat() else grid.bareEarth[y0 * grid.width + x0]
                }
            }
            return SampledGrid(width, height, grid.cellSizeMeters * scale.toFloat(), output)
        }

        private fun boxMean(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
            val stride = width + 1
            val integral = DoubleArray((width + 1) * (height + 1))
            for (y in 0 until height) {
                var row = 0.0
                for (x in 0 until width) {
                    row += values[y * width + x]
                    integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
                }
            }
            val output = FloatArray(values.size)
            for (y in 0 until height) {
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius).coerceAtMost(height - 1)
                for (x in 0 until width) {
                    val x0 = (x - radius).coerceAtLeast(0)
                    val x1 = (x + radius).coerceAtMost(width - 1)
                    val sum = rectangleSum(integral, stride, x0, y0, x1, y1)
                    output[y * width + x] = (sum / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
                }
            }
            return output
        }

        private fun boxStandardDeviation(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
            val stride = width + 1
            val sum = DoubleArray((width + 1) * (height + 1))
            val square = DoubleArray(sum.size)
            for (y in 0 until height) {
                var row = 0.0
                var rowSquare = 0.0
                for (x in 0 until width) {
                    val value = values[y * width + x].toDouble()
                    row += value
                    rowSquare += value * value
                    sum[(y + 1) * stride + x + 1] = sum[y * stride + x + 1] + row
                    square[(y + 1) * stride + x + 1] = square[y * stride + x + 1] + rowSquare
                }
            }
            val output = FloatArray(values.size)
            for (y in 0 until height) {
                val y0 = (y - radius).coerceAtLeast(0)
                val y1 = (y + radius).coerceAtMost(height - 1)
                for (x in 0 until width) {
                    val x0 = (x - radius).coerceAtLeast(0)
                    val x1 = (x + radius).coerceAtMost(width - 1)
                    val count = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()
                    val localSum = rectangleSum(sum, stride, x0, y0, x1, y1)
                    val localSquare = rectangleSum(square, stride, x0, y0, x1, y1)
                    val mean = localSum / count
                    output[y * width + x] = sqrt(max(0.0, localSquare / count - mean * mean)).toFloat()
                }
            }
            return output
        }

        private fun normalizePositive(values: FloatArray): FloatArray {
            val finite = values.filter { it.isFinite() && it >= 0f }.sorted()
            val scale = finite.getOrElse((finite.size * 0.96).toInt().coerceIn(0, max(0, finite.lastIndex))) { 1f }.coerceAtLeast(1e-6f)
            return FloatArray(values.size) { (values[it].coerceAtLeast(0f) / scale).coerceIn(0f, 1f) }
        }

        private fun normalizeSigned(values: FloatArray): FloatArray {
            val magnitudes = values.map { abs(it) }.filter(Float::isFinite).sorted()
            val scale = magnitudes.getOrElse((magnitudes.size * 0.96).toInt().coerceIn(0, max(0, magnitudes.lastIndex))) { 1f }.coerceAtLeast(1e-6f)
            return FloatArray(values.size) { (values[it] / scale).coerceIn(-1f, 1f) }
        }

        private fun localMaxima(
            score: FloatArray,
            width: Int,
            height: Int,
            threshold: Float,
            perTypeLimit: Int,
        ): List<Pair<Int, Float>> {
            val candidates = ArrayList<Pair<Int, Float>>()
            val radius = 4
            for (y in radius until height - radius step 2) {
                for (x in radius until width - radius step 2) {
                    val i = y * width + x
                    val value = score[i]
                    if (value < threshold) continue
                    var isMaximum = true
                    loop@ for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            if (dx == 0 && dy == 0) continue
                            if (score[(y + dy) * width + x + dx] > value) {
                                isMaximum = false
                                break@loop
                            }
                        }
                    }
                    if (isMaximum) candidates += i to value
                }
            }
            return candidates.sortedByDescending { it.second }.take(perTypeLimit)
        }

        private fun requireLayer(layers: TerrainDerivedLayers, type: TerrainDerivedLayer): FloatArray =
            requireNotNull(layers.values[type]) { "Missing derived layer ${type.name}" }

        private fun stableCandidateId(key: String, type: TerrainFeatureType, x: Float, y: Float): String =
            "$key-${type.name.lowercase(Locale.US)}-${(x * 10).roundToInt()}-${(y * 10).roundToInt()}"

        private fun distanceSquared(x1: Float, y1: Float, x2: Float, y2: Float): Float {
            val dx = x1 - x2
            val dy = y1 - y2
            return dx * dx + dy * dy
        }

        private fun rectangleSum(integral: DoubleArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double =
            integral[(y1 + 1) * stride + x1 + 1] - integral[y0 * stride + x1 + 1] -
                integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]
    }

    private data class SampledGrid(val width: Int, val height: Int, val cellSizeMeters: Float, val elevation: FloatArray)
    private data class Detector(
        val type: TerrainFeatureType,
        val score: FloatArray,
        val threshold: Float,
        val radiusMeters: Float,
        val evidence: List<String>,
    )
}

class TerrainDerivedLayerCache(
    private val directory: File,
    private val maxDiskBytes: Long = 384L * 1024L * 1024L,
) {
    enum class Hit { MEMORY, DISK, MISS }
    data class Lookup(val layers: TerrainDerivedLayers?, val hit: Hit)

    private val memory = object : LinkedHashMap<String, TerrainDerivedLayers>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TerrainDerivedLayers>?): Boolean = size > 3
    }

    init {
        directory.mkdirs()
    }

    suspend fun get(key: String): Lookup = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[key] }?.let { return@withContext Lookup(it, Hit.MEMORY) }
        val file = File(directory, "$key.tic.gz")
        val loaded = runCatching { read(file) }.getOrNull()
        if (loaded != null) {
            synchronized(memory) { memory[key] = loaded }
            file.setLastModified(System.currentTimeMillis())
            Lookup(loaded, Hit.DISK)
        } else {
            file.delete()
            Lookup(null, Hit.MISS)
        }
    }

    suspend fun put(key: String, layers: TerrainDerivedLayers) = withContext(Dispatchers.IO) {
        synchronized(memory) { memory[key] = layers }
        directory.mkdirs()
        val target = File(directory, "$key.tic.gz")
        val temp = File(directory, "$key.tic.gz.part")
        runCatching {
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(FileOutputStream(temp)))).use { output ->
                output.writeInt(CACHE_MAGIC)
                output.writeInt(CACHE_VERSION)
                output.writeInt(layers.width)
                output.writeInt(layers.height)
                output.writeFloat(layers.cellSizeMeters)
                val entries = TerrainDerivedLayer.entries.filter { layers.values[it] != null }
                output.writeInt(entries.size)
                for (type in entries) {
                    val values = requireNotNull(layers.values[type])
                    output.writeInt(type.ordinal)
                    output.writeInt(values.size)
                    for (value in values) output.writeFloat(value)
                }
            }
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "Could not promote terrain intelligence cache" }
            prune()
        }.onFailure { temp.delete() }
    }

    fun clear() {
        synchronized(memory) { memory.clear() }
        directory.listFiles()?.forEach(File::delete)
    }

    private fun read(file: File): TerrainDerivedLayers? {
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(GZIPInputStream(FileInputStream(file)))).use { input ->
            if (input.readInt() != CACHE_MAGIC || input.readInt() != CACHE_VERSION) return null
            val width = input.readInt()
            val height = input.readInt()
            val cellSize = input.readFloat()
            if (width !in 1..4096 || height !in 1..4096 || !cellSize.isFinite() || cellSize <= 0f) return null
            val count = input.readInt().coerceIn(0, TerrainDerivedLayer.entries.size)
            val map = EnumMap<TerrainDerivedLayer, FloatArray>(TerrainDerivedLayer::class.java)
            repeat(count) {
                val type = TerrainDerivedLayer.entries.getOrNull(input.readInt()) ?: return null
                val size = input.readInt()
                if (size != width * height) return null
                map[type] = FloatArray(size) { input.readFloat() }
            }
            return TerrainDerivedLayers(width, height, cellSize, map)
        }
    }

    private fun prune() {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".tic.gz") }?.sortedByDescending(File::lastModified).orEmpty()
        var retained = 0L
        for (file in files) {
            retained += file.length()
            if (retained > maxDiskBytes) file.delete()
        }
    }

    companion object {
        private const val CACHE_MAGIC = 0x54494E54
        private const val CACHE_VERSION = 1
    }
}

object TerrainIntelligenceRenderer {
    fun renderLayer(result: TerrainIntelligenceResult, type: TerrainDerivedLayer): Bitmap {
        val layers = result.layers
        val values = requireNotNull(layers.values[type]) { "Layer ${type.label} is not available" }
        val bitmap = Bitmap.createBitmap(layers.width, layers.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(values.size)
        val normalizedPositive = normalizeForRendering(values, signed = false)
        val normalizedSigned = normalizeForRendering(values, signed = true)
        for (i in values.indices) {
            pixels[i] = when (type) {
                TerrainDerivedLayer.ASPECT -> {
                    val hue = ((values[i] % 360f) + 360f) % 360f
                    Color.HSVToColor(floatArrayOf(hue, 0.82f, 0.95f))
                }
                TerrainDerivedLayer.CURVATURE,
                TerrainDerivedLayer.LOCAL_RELIEF -> diverging(normalizedSigned[i])
                TerrainDerivedLayer.POSITIVE_OPENNESS,
                TerrainDerivedLayer.NEGATIVE_OPENNESS,
                TerrainDerivedLayer.SKY_VIEW_FACTOR -> gradient(normalizedPositive[i], Color.rgb(18, 35, 60), Color.rgb(244, 226, 151))
                TerrainDerivedLayer.ANCIENT_STREAM -> gradient(normalizedPositive[i], Color.rgb(42, 38, 32), Color.rgb(30, 150, 210))
                TerrainDerivedLayer.ARTIFACT_HOTSPOT -> heat(normalizedPositive[i])
                TerrainDerivedLayer.SLOPE -> gradient(normalizedPositive[i], Color.rgb(42, 70, 45), Color.rgb(235, 75, 36))
                TerrainDerivedLayer.DEPRESSION_DEPTH -> gradient(normalizedPositive[i], Color.rgb(235, 232, 208), Color.rgb(30, 84, 180))
                TerrainDerivedLayer.RUGGEDNESS,
                TerrainDerivedLayer.LINEARITY,
                TerrainDerivedLayer.HILLSHADE_COMPARISON -> heat(normalizedPositive[i])
            }
        }
        bitmap.setPixels(pixels, 0, layers.width, 0, 0, layers.width, layers.height)
        return bitmap
    }

    private fun normalizeForRendering(values: FloatArray, signed: Boolean): FloatArray {
        val scaleValues = if (signed) values.map { abs(it) } else values.map { max(0f, it) }
        val sorted = scaleValues.filter(Float::isFinite).sorted()
        val scale = sorted.getOrElse((sorted.size * 0.97).toInt().coerceIn(0, max(0, sorted.lastIndex))) { 1f }.coerceAtLeast(1e-6f)
        return FloatArray(values.size) {
            if (signed) (values[it] / scale).coerceIn(-1f, 1f) else (values[it] / scale).coerceIn(0f, 1f)
        }
    }

    private fun diverging(value: Float): Int = if (value < 0f) {
        gradient(-value, Color.rgb(224, 222, 202), Color.rgb(31, 107, 201))
    } else {
        gradient(value, Color.rgb(224, 222, 202), Color.rgb(220, 58, 35))
    }

    private fun heat(value: Float): Int = when {
        value < 0.45f -> gradient(value / 0.45f, Color.rgb(28, 40, 54), Color.rgb(42, 126, 166))
        value < 0.75f -> gradient((value - 0.45f) / 0.30f, Color.rgb(42, 126, 166), Color.rgb(245, 196, 55))
        else -> gradient((value - 0.75f) / 0.25f, Color.rgb(245, 196, 55), Color.rgb(224, 45, 35))
    }

    private fun gradient(amount: Float, start: Int, end: Int): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(start) * (1f - t) + Color.red(end) * t).roundToInt(),
            (Color.green(start) * (1f - t) + Color.green(end) * t).roundToInt(),
            (Color.blue(start) * (1f - t) + Color.blue(end) * t).roundToInt(),
        )
    }
}
