package com.example.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** Memory-bounded point-cloud binning shared by the LAS and LAZ readers. */
internal class LidarRasterizer(
    private val minX: Double,
    private val maxX: Double,
    private val minY: Double,
    private val maxY: Double,
    options: LidarImportOptions,
    declaredPointCount: Long,
) {
    private val options = options.sanitized()
    private val rangeX = (maxX - minX).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    private val rangeY = (maxY - minY).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    private val longSide = this.options.rasterResolution
    val width: Int
    val height: Int

    private val groundMin: FloatArray
    private val groundCount: IntArray
    private val allMin: FloatArray
    private val allMax: FloatArray
    private val allCount: IntArray
    private val classHistogram = IntArray(256)
    private val sampleStride = ceil(
        declaredPointCount.coerceAtLeast(1L).toDouble() / MAX_BINNED_POINTS,
    ).toInt().coerceAtLeast(1)

    var pointsDecoded: Int = 0
        private set
    var pointsBinned: Int = 0
        private set
    private var groundPointsBinned: Int = 0

    init {
        if (rangeX >= rangeY) {
            width = longSide
            height = (longSide * rangeY / rangeX).roundToInt().coerceIn(MIN_SHORT_SIDE, longSide)
        } else {
            height = longSide
            width = (longSide * rangeX / rangeY).roundToInt().coerceIn(MIN_SHORT_SIDE, longSide)
        }
        groundMin = FloatArray(width * height) { Float.MAX_VALUE }
        groundCount = IntArray(width * height)
        allMin = FloatArray(width * height) { Float.MAX_VALUE }
        allMax = FloatArray(width * height) { -Float.MAX_VALUE }
        allCount = IntArray(width * height)
    }

    /** Returns false after the mobile safety ceiling has been reached. */
    fun addPoint(x: Double, y: Double, z: Float, classification: Int, isKeyPoint: Boolean = false): Boolean {
        if (pointsDecoded >= MAX_DECODED_POINTS) return false
        val pointIndex = pointsDecoded++
        if (pointIndex % sampleStride != 0) return true
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return true

        val gx = (((x - minX) / rangeX) * (width - 1)).toInt().coerceIn(0, width - 1)
        val gy = ((1.0 - (y - minY) / rangeY) * (height - 1)).toInt().coerceIn(0, height - 1)
        val index = gy * width + gx
        if (z < allMin[index]) allMin[index] = z
        if (z > allMax[index]) allMax[index] = z
        allCount[index]++
        pointsBinned++

        val normalizedClass = classification.coerceIn(0, 255)
        classHistogram[normalizedClass]++
        // Class 2 is Ground. Class 8 was historically Model Key-Point; modern files use the key-point flag.
        val isSourceGround = normalizedClass == 2 || normalizedClass == 8 ||
            (isKeyPoint && normalizedClass == 2)
        if (isSourceGround) {
            if (z < groundMin[index]) groundMin[index] = z
            groundCount[index]++
            groundPointsBinned++
        }
        return true
    }

    fun finish(pointFormat: Int, sourceLabel: String): DemGenerator.LasLoadResult? {
        if (pointsBinned == 0 || allCount.none { it > 0 }) return null

        val populatedCells = allCount.count { it > 0 }
        val classifiedCells = groundCount.count { it > 0 }
        val classifiedCoverageIsUsable =
            groundPointsBinned >= MIN_CLASSIFIED_POINTS &&
                classifiedCells >= max(MIN_CLASSIFIED_CELLS, (populatedCells * 0.08f).roundToInt())

        val requestedMode = options.groundMode
        val appliedMode = when {
            requestedMode == GroundSurfaceMode.SOURCE_CLASSIFIED && classifiedCoverageIsUsable ->
                GroundSurfaceMode.SOURCE_CLASSIFIED
            requestedMode == GroundSurfaceMode.SOURCE_CLASSIFIED -> GroundSurfaceMode.AUTO_LOWEST
            else -> requestedMode
        }
        val source = when (appliedMode) {
            GroundSurfaceMode.SOURCE_CLASSIFIED -> groundMin
            GroundSurfaceMode.AUTO_LOWEST -> allMin
            GroundSurfaceMode.SURFACE_MODEL -> allMax
        }
        val sourceCounts = when (appliedMode) {
            GroundSurfaceMode.SOURCE_CLASSIFIED -> groundCount
            else -> allCount
        }

        val surface = FloatArray(width * height)
        for (index in surface.indices) {
            surface[index] = if (sourceCounts[index] > 0) source[index] else Float.NaN
        }
        fillMissingNearest(surface, width, height)

        val cleaned = if (appliedMode == GroundSurfaceMode.SURFACE_MODEL) {
            surface
        } else {
            suppressIsolatedLowNoise(surface, width, height)
        }
        val bareEarth = if (options.smoothingRadius > 0) {
            boxSmooth(cleaned, width, height, options.smoothingRadius)
        } else {
            cleaned
        }
        val canopy = FloatArray(surface.size)
        if (appliedMode != GroundSurfaceMode.SURFACE_MODEL) {
            for (index in canopy.indices) {
                if (allCount[index] > 0) {
                    canopy[index] = (allMax[index] - bareEarth[index]).coerceAtLeast(0f)
                }
            }
        }

        val cellSize = max(rangeX / (width - 1), rangeY / (height - 1))
            .takeIf { it.isFinite() && it in 0.001..100_000.0 }
            ?.toFloat() ?: 1f
        val capped = pointsDecoded >= MAX_DECODED_POINTS
        val samplingNote = when {
            capped -> "mobile safety limit reached at $pointsDecoded decoded points"
            sampleStride > 1 -> "binned every ${sampleStride}th return across $pointsDecoded decoded points"
            else -> "$pointsDecoded points decoded"
        }
        val modeNote = when (appliedMode) {
            GroundSurfaceMode.SOURCE_CLASSIFIED ->
                "ASPRS ground classes: $groundPointsBinned / $pointsBinned sampled returns"
            GroundSurfaceMode.AUTO_LOWEST -> if (requestedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) {
                "classified ground coverage was sparse; used automatic lowest-return ground estimate"
            } else {
                "automatic lowest-return ground estimate"
            }
            GroundSurfaceMode.SURFACE_MODEL -> "highest-return surface model (vegetation and structures included)"
        }
        val smoothingNote = if (options.smoothingRadius == 0) "unsmoothed" else "smoothing radius ${options.smoothingRadius}"
        val classNote = classHistogram.withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(prefix = "classes ", separator = ", ") { "${it.index}:${it.value}" }

        return DemGenerator.LasLoadResult(
            grid = ElevationGrid(width, height, bareEarth, canopy, cellSize),
            totalPointsRead = pointsDecoded,
            groundPointsUsed = if (appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) {
                groundPointsBinned
            } else {
                pointsBinned
            },
            usedClassificationFilter = appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED,
            pointFormat = pointFormat,
            note = "$sourceLabel · $modeNote · $classNote · $samplingNote · ${width}×$height $smoothingNote",
            requestedGroundMode = requestedMode,
            appliedGroundMode = appliedMode,
            sampledPoints = pointsBinned,
            wasTruncated = capped,
        )
    }

    companion object {
        private const val MIN_SHORT_SIDE = 48
        private const val MIN_CLASSIFIED_POINTS = 100
        private const val MIN_CLASSIFIED_CELLS = 12
        private const val MAX_BINNED_POINTS = 8_000_000.0
        private const val MAX_DECODED_POINTS = 30_000_000
    }
}

internal fun fillMissingNearest(grid: FloatArray, width: Int, height: Int) {
    if (grid.none { it.isFinite() }) {
        grid.fill(0f)
        return
    }
    // Four directional propagation passes fill large holes without a radius-dependent O(n*r²) search.
    repeat(2) { pass ->
        val yRange = if (pass == 0) 0 until height else height - 1 downTo 0
        val xRange = if (pass == 0) 0 until width else width - 1 downTo 0
        for (y in yRange) {
            for (x in xRange) {
                val index = y * width + x
                if (grid[index].isFinite()) continue
                val neighbors = intArrayOf(
                    if (x > 0) index - 1 else -1,
                    if (x + 1 < width) index + 1 else -1,
                    if (y > 0) index - width else -1,
                    if (y + 1 < height) index + width else -1,
                )
                for (neighbor in neighbors) {
                    if (neighbor >= 0 && grid[neighbor].isFinite()) {
                        grid[index] = grid[neighbor]
                        break
                    }
                }
            }
        }
    }
    // A pathological isolated hole can survive the passes only if no data existed, handled above.
    val fallback = grid.firstOrNull { it.isFinite() } ?: 0f
    for (index in grid.indices) if (!grid[index].isFinite()) grid[index] = fallback
}

private fun suppressIsolatedLowNoise(source: FloatArray, width: Int, height: Int): FloatArray {
    val output = source.copyOf()
    val neighbors = FloatArray(8)
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    neighbors[count++] = source[(y + dy) * width + x + dx]
                }
            }
            neighbors.sort(0, count)
            val median = neighbors[count / 2]
            val index = y * width + x
            // Remove only extreme low outliers; shallow cellars, ditches and tracks remain untouched.
            if (source[index] < median - LOW_NOISE_THRESHOLD_METERS) output[index] = median
        }
    }
    return output
}

internal fun boxSmooth(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
    if (radius <= 0) return source.copyOf()
    val integral = DoubleArray((width + 1) * (height + 1))
    for (y in 0 until height) {
        var rowSum = 0.0
        for (x in 0 until width) {
            rowSum += source[y * width + x]
            integral[(y + 1) * (width + 1) + x + 1] =
                integral[y * (width + 1) + x + 1] + rowSum
        }
    }
    val output = FloatArray(source.size)
    for (y in 0 until height) {
        val y0 = (y - radius).coerceAtLeast(0)
        val y1 = (y + radius).coerceAtMost(height - 1)
        for (x in 0 until width) {
            val x0 = (x - radius).coerceAtLeast(0)
            val x1 = (x + radius).coerceAtMost(width - 1)
            val sum = rectangleSum(integral, width + 1, x0, y0, x1, y1)
            output[y * width + x] = (sum / ((x1 - x0 + 1) * (y1 - y0 + 1))).toFloat()
        }
    }
    return output
}

private fun rectangleSum(
    integral: DoubleArray,
    stride: Int,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
): Double = integral[(y1 + 1) * stride + x1 + 1] - integral[y0 * stride + x1 + 1] -
    integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]

private const val LOW_NOISE_THRESHOLD_METERS = 3f
