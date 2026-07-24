package com.example.analysis

import com.example.data.ElevationGrid
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Local, offline terrain-analysis engine for LiDAR-derived elevation grids. */
object TerrainAnalysisEngine {

    fun analyze(
        grid: ElevationGrid,
        type: TerrainAnalysisType,
        options: TerrainAnalysisOptions = TerrainAnalysisOptions(),
    ): TerrainAnalysisLayer {
        val safeOptions = options.normalized(grid.cellSizeMeters)
        val z = grid.bareEarth.copyOf()
        val valid = grid.validData.copyOf()
        val values = when (type) {
            TerrainAnalysisType.MULTI_HILLSHADE -> multiHillshade(grid, z, valid)
            TerrainAnalysisType.SKY_VIEW_FACTOR -> skyViewFactor(grid, z, valid, safeOptions)
            TerrainAnalysisType.LOCAL_RELIEF_MODEL -> localRelief(grid, z, valid, safeOptions)
            TerrainAnalysisType.POSITIVE_OPENNESS -> openness(grid, z, valid, safeOptions, inverted = false)
            TerrainAnalysisType.NEGATIVE_OPENNESS -> openness(grid, z, valid, safeOptions, inverted = true)
            TerrainAnalysisType.SLOPE -> slope(grid, z, valid)
            TerrainAnalysisType.CURVATURE -> curvature(grid, z, valid)
            TerrainAnalysisType.ASPECT -> aspect(grid, z, valid)
            TerrainAnalysisType.RUGGEDNESS_INDEX -> ruggedness(grid, z, valid)
            TerrainAnalysisType.FLOW_ACCUMULATION -> {
                val receivers = d8Receivers(grid, z, valid)
                flowAccumulation(z, valid, receivers)
            }
            TerrainAnalysisType.WATERSHED -> {
                val receivers = d8Receivers(grid, z, valid)
                watershedLabels(valid, receivers)
            }
            TerrainAnalysisType.DEPRESSION_DEPTH -> depressionDepth(grid, z, valid)
            TerrainAnalysisType.ANCIENT_STREAM_LIKELIHOOD -> ancientStreamLikelihood(grid, z, valid)
            TerrainAnalysisType.EROSION_SIMULATION -> erosionSimulation(grid, z, valid, safeOptions)
        }
        return buildLayer(grid, type, values, valid)
    }

    private fun slope(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val out = FloatArray(z.size)
        forEachCell(grid) { x, y, i ->
            if (valid[i]) {
                val g = hornGradient(grid, z, valid, x, y)
                out[i] = Math.toDegrees(atan(sqrt(g.dx * g.dx + g.dy * g.dy)).toDouble()).toFloat()
            }
        }
        return out
    }

    private fun aspect(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val out = FloatArray(z.size) { -1f }
        forEachCell(grid) { x, y, i ->
            if (valid[i]) {
                val g = hornGradient(grid, z, valid, x, y)
                out[i] = if (g.dx * g.dx + g.dy * g.dy < 1e-8f) -1f else
                    ((Math.toDegrees(atan2(g.dx, -g.dy).toDouble()).toFloat() + 360f) % 360f)
            }
        }
        return out
    }

    /**
     * Mean curvature approximation using a symmetric 3x3 Laplacian.
     * Cardinal neighbors receive twice the diagonal weight, reducing striping and directional bias.
     * Positive values are concave/depressed; negative values are convex/raised.
     */
    private fun curvature(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val out = FloatArray(z.size)
        val cell2 = grid.cellSizeMeters.coerceAtLeast(0.001f).let { it * it }
        for (y in 1 until grid.height - 1) {
            for (x in 1 until grid.width - 1) {
                val i = y * grid.width + x
                if (!valid[i]) continue
                val c = z[i]
                val n = sample(grid, z, valid, x, y - 1, c)
                val s = sample(grid, z, valid, x, y + 1, c)
                val e = sample(grid, z, valid, x + 1, y, c)
                val w = sample(grid, z, valid, x - 1, y, c)
                val ne = sample(grid, z, valid, x + 1, y - 1, c)
                val nw = sample(grid, z, valid, x - 1, y - 1, c)
                val se = sample(grid, z, valid, x + 1, y + 1, c)
                val sw = sample(grid, z, valid, x - 1, y + 1, c)
                out[i] = (2f * (n + s + e + w) + ne + nw + se + sw - 12f * c) / (6f * cell2)
            }
        }
        return out
    }

    private fun directionalHorizons(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        options: TerrainAnalysisOptions,
        inverted: Boolean,
    ): FloatArray {
        val directions = options.directionCount
        val radius = (options.horizonRadiusMeters / grid.cellSizeMeters.coerceAtLeast(0.001f))
            .roundToInt().coerceIn(2, max(2, min(grid.width, grid.height) / 2))
        val out = FloatArray(z.size * directions)

        for (direction in 0 until directions) {
            val angle = direction * 2.0 * PI / directions
            val rayX = cos(angle)
            val rayY = sin(angle)
            val base = direction * z.size
            forEachCell(grid) { x, y, i ->
                if (!valid[i]) return@forEachCell
                var highest = 0f
                var lastX = Int.MIN_VALUE
                var lastY = Int.MIN_VALUE
                for (step in 1..radius) {
                    val nx = (x + rayX * step).roundToInt()
                    val ny = (y + rayY * step).roundToInt()
                    if (nx == lastX && ny == lastY) continue
                    lastX = nx
                    lastY = ny
                    if (nx !in 0 until grid.width || ny !in 0 until grid.height) break
                    val ni = ny * grid.width + nx
                    if (!valid[ni]) continue
                    val distance = sqrt(((nx - x) * (nx - x) + (ny - y) * (ny - y)).toDouble()) *
                        grid.cellSizeMeters
                    if (distance <= 0.0) continue
                    val rise = if (inverted) z[i] - z[ni] else z[ni] - z[i]
                    val candidate = atan2(rise.toDouble(), distance).toFloat().coerceAtLeast(0f)
                    if (candidate > highest) highest = candidate
                }
                out[base + i] = highest.coerceAtMost((PI / 2.0).toFloat())
            }
        }
        return out
    }

    private fun skyViewFactor(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        options: TerrainAnalysisOptions,
    ): FloatArray {
        val horizons = directionalHorizons(grid, z, valid, options, inverted = false)
        val out = FloatArray(z.size)
        for (i in out.indices) {
            if (!valid[i]) continue
            var total = 0.0
            for (d in 0 until options.directionCount) {
                val horizon = horizons[d * z.size + i].toDouble()
                val c = cos(horizon)
                total += c * c
            }
            out[i] = (total / options.directionCount).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    private fun openness(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        options: TerrainAnalysisOptions,
        inverted: Boolean,
    ): FloatArray {
        val horizons = directionalHorizons(grid, z, valid, options, inverted)
        val out = FloatArray(z.size)
        for (i in out.indices) {
            if (!valid[i]) continue
            var total = 0.0
            for (d in 0 until options.directionCount) {
                total += 90.0 - Math.toDegrees(horizons[d * z.size + i].toDouble())
            }
            out[i] = (total / options.directionCount).toFloat().coerceIn(0f, 90f)
        }
        return out
    }

    private fun multiHillshade(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val out = FloatArray(z.size)
        forEachCell(grid) { x, y, i ->
            if (valid[i]) {
                val g = hornGradient(grid, z, valid, x, y)
                out[i] = 0.4f * shade(g, 315f) + 0.25f * shade(g, 45f) +
                    0.15f * shade(g, 135f) + 0.2f * shade(g, 225f)
            }
        }
        return out
    }

    private fun localRelief(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        options: TerrainAnalysisOptions,
    ): FloatArray {
        val radius = (options.localRadiusMeters / grid.cellSizeMeters.coerceAtLeast(0.001f))
            .roundToInt().coerceIn(1, max(1, min(grid.width, grid.height) / 3))
        val out = FloatArray(z.size)
        forEachCell(grid) { x, y, i ->
            if (!valid[i]) return@forEachCell
            var sum = 0.0
            var count = 0
            for (yy in max(0, y - radius)..min(grid.height - 1, y + radius)) {
                for (xx in max(0, x - radius)..min(grid.width - 1, x + radius)) {
                    val j = yy * grid.width + xx
                    if (valid[j]) { sum += z[j]; count++ }
                }
            }
            if (count > 0) out[i] = z[i] - (sum / count).toFloat()
        }
        return out
    }

    private fun ruggedness(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val out = FloatArray(z.size)
        forEachCell(grid) { x, y, i ->
            if (!valid[i]) return@forEachCell
            var sum = 0.0
            var count = 0
            for ((dx, dy) in NEIGHBORS) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until grid.width || ny !in 0 until grid.height) continue
                val j = ny * grid.width + nx
                if (!valid[j]) continue
                val delta = z[j] - z[i]
                sum += delta * delta
                count++
            }
            if (count > 0) out[i] = sqrt(sum / count).toFloat()
        }
        return out
    }

    private fun d8Receivers(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): IntArray {
        val receivers = IntArray(z.size) { -1 }
        forEachCell(grid) { x, y, i ->
            if (!valid[i]) return@forEachCell
            var bestDrop = 0f
            for ((dx, dy) in NEIGHBORS) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until grid.width || ny !in 0 until grid.height) continue
                val j = ny * grid.width + nx
                if (!valid[j]) continue
                val distance = if (dx != 0 && dy != 0) SQRT_TWO else 1f
                val drop = (z[i] - z[j]) / distance
                if (drop > bestDrop) { bestDrop = drop; receivers[i] = j }
            }
        }
        return receivers
    }

    private fun flowAccumulation(z: FloatArray, valid: BooleanArray, receivers: IntArray): FloatArray {
        val out = FloatArray(z.size) { if (valid[it]) 1f else 0f }
        val indices = valid.indices.filter { valid[it] }.sortedByDescending { z[it] }
        for (i in indices) receivers[i].takeIf { it >= 0 }?.let { out[it] += out[i] }
        return out
    }

    private fun watershedLabels(valid: BooleanArray, receivers: IntArray): FloatArray {
        val labels = IntArray(valid.size) { -1 }
        var next = 0
        fun resolve(start: Int): Int {
            var current = start
            val path = ArrayList<Int>()
            val seen = HashSet<Int>()
            while (current >= 0 && labels[current] < 0 && seen.add(current)) {
                path += current
                current = receivers[current]
            }
            val label = when {
                current >= 0 && labels[current] >= 0 -> labels[current]
                else -> next++
            }
            path.forEach { labels[it] = label }
            return label
        }
        for (i in valid.indices) if (valid[i] && labels[i] < 0) resolve(i)
        return FloatArray(valid.size) { if (valid[it]) labels[it].toFloat() else 0f }
    }

    private fun depressionDepth(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val filled = z.copyOf()
        val visited = BooleanArray(z.size)
        val queue = PriorityQueue<FloodCell>(compareBy { it.elevation })
        fun seed(x: Int, y: Int) {
            val i = y * grid.width + x
            if (valid[i] && !visited[i]) { visited[i] = true; queue += FloodCell(i, z[i]) }
        }
        for (x in 0 until grid.width) { seed(x, 0); seed(x, grid.height - 1) }
        for (y in 0 until grid.height) { seed(0, y); seed(grid.width - 1, y) }
        while (queue.isNotEmpty()) {
            val cell = queue.remove()
            val x = cell.index % grid.width; val y = cell.index / grid.width
            for ((dx, dy) in NEIGHBORS) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until grid.width || ny !in 0 until grid.height) continue
                val j = ny * grid.width + nx
                if (!valid[j] || visited[j]) continue
                visited[j] = true
                filled[j] = max(z[j], cell.elevation)
                queue += FloodCell(j, filled[j])
            }
        }
        return FloatArray(z.size) { if (valid[it]) (filled[it] - z[it]).coerceAtLeast(0f) else 0f }
    }

    private fun ancientStreamLikelihood(grid: ElevationGrid, z: FloatArray, valid: BooleanArray): FloatArray {
        val flow = flowAccumulation(z, valid, d8Receivers(grid, z, valid))
        val slopes = slope(grid, z, valid)
        val curves = curvature(grid, z, valid)
        val maxFlow = flow.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return FloatArray(z.size) { i ->
            if (!valid[i]) 0f else (
                ln1p(flow[i].toDouble()) / ln1p(maxFlow.toDouble()) *
                    (1.0 / (1.0 + slopes[i] / 12.0)) *
                    (1.0 + curves[i].coerceAtLeast(0f))
                ).toFloat()
        }
    }

    private fun erosionSimulation(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        options: TerrainAnalysisOptions,
    ): FloatArray {
        val flow = flowAccumulation(z, valid, d8Receivers(grid, z, valid))
        val slopes = slope(grid, z, valid)
        val maxFlow = flow.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        return FloatArray(z.size) { i ->
            if (!valid[i]) 0f else
                (-options.rainfallFactor * sqrt(flow[i] / maxFlow) * slopes[i] / 90f * 0.1f)
        }
    }

    private fun buildLayer(
        grid: ElevationGrid,
        type: TerrainAnalysisType,
        values: FloatArray,
        valid: BooleanArray,
    ): TerrainAnalysisLayer {
        val stats = statistics(values, valid, type == TerrainAnalysisType.ASPECT)
        return TerrainAnalysisLayer(
            type = type,
            width = grid.width,
            height = grid.height,
            values = values,
            validData = valid,
            cellSizeMeters = grid.cellSizeMeters,
            minimum = stats.minimum,
            maximum = stats.maximum,
            mean = stats.mean,
            percentile95 = stats.percentile95,
            summary = when (type) {
                TerrainAnalysisType.SKY_VIEW_FACTOR -> "Sky visibility now uses obstruction angles from every direction; low values mark enclosed terrain."
                TerrainAnalysisType.POSITIVE_OPENNESS -> "High values mark exposed ridges and raised earthworks; low values mark surrounding obstruction."
                TerrainAnalysisType.NEGATIVE_OPENNESS -> "Low values identify enclosed depressions, ditches and pits."
                TerrainAnalysisType.CURVATURE -> "Positive values indicate concave terrain; negative values indicate convex terrain."
                else -> type.description
            },
        )
    }

    private fun statistics(values: FloatArray, valid: BooleanArray, ignoreNegative: Boolean): Statistics {
        val selected = ArrayList<Float>()
        var sum = 0.0
        for (i in values.indices) {
            if (!valid[i] || !values[i].isFinite() || ignoreNegative && values[i] < 0f) continue
            selected += values[i]; sum += values[i]
        }
        if (selected.isEmpty()) return Statistics(0f, 0f, 0f, 0f)
        selected.sort()
        return Statistics(
            selected.first(), selected.last(), (sum / selected.size).toFloat(),
            selected[(selected.lastIndex * 0.95f).roundToInt()],
        )
    }

    private fun hornGradient(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        x: Int,
        y: Int,
    ): Gradient {
        val c = z[y * grid.width + x]
        fun at(px: Int, py: Int) = sample(grid, z, valid, px, py, c)
        val z00 = at(x - 1, y - 1); val z01 = at(x, y - 1); val z02 = at(x + 1, y - 1)
        val z10 = at(x - 1, y); val z12 = at(x + 1, y)
        val z20 = at(x - 1, y + 1); val z21 = at(x, y + 1); val z22 = at(x + 1, y + 1)
        val d = grid.cellSizeMeters.coerceAtLeast(0.001f)
        return Gradient(
            ((z02 + 2f * z12 + z22) - (z00 + 2f * z10 + z20)) / (8f * d),
            ((z20 + 2f * z21 + z22) - (z00 + 2f * z01 + z02)) / (8f * d),
        )
    }

    private fun sample(
        grid: ElevationGrid,
        z: FloatArray,
        valid: BooleanArray,
        x: Int,
        y: Int,
        fallback: Float,
    ): Float {
        val px = x.coerceIn(0, grid.width - 1); val py = y.coerceIn(0, grid.height - 1)
        val i = py * grid.width + px
        return if (valid[i]) z[i] else fallback
    }

    private fun shade(g: Gradient, azimuthDegrees: Float): Float {
        val az = Math.toRadians(azimuthDegrees.toDouble())
        val alt = Math.toRadians(35.0)
        val length = sqrt(g.dx * g.dx + g.dy * g.dy + 1f)
        val nx = -g.dx / length; val ny = -g.dy / length; val nz = 1f / length
        val horizontal = cos(alt).toFloat()
        val lx = (sin(az) * horizontal).toFloat()
        val ly = (-cos(az) * horizontal).toFloat()
        val lz = sin(alt).toFloat()
        return (nx * lx + ny * ly + nz * lz).coerceIn(0f, 1f)
    }

    private inline fun forEachCell(grid: ElevationGrid, action: (Int, Int, Int) -> Unit) {
        for (y in 0 until grid.height) for (x in 0 until grid.width) action(x, y, y * grid.width + x)
    }

    private data class Gradient(val dx: Float, val dy: Float)
    private data class FloodCell(val index: Int, val elevation: Float)
    private data class Statistics(
        val minimum: Float,
        val maximum: Float,
        val mean: Float,
        val percentile95: Float,
    )

    private val NEIGHBORS = arrayOf(
        -1 to -1, 0 to -1, 1 to -1,
        -1 to 0, 1 to 0,
        -1 to 1, 0 to 1, 1 to 1,
    )
    private const val SQRT_TWO = 1.41421356f
}
