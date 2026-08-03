package com.example.data

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Amount of per-return work required by the optimized LAZ reader. */
internal enum class LidarPointWork {
    SKIP,
    COVERAGE,
    ELEVATION,
}

/** Memory-bounded point-cloud binning shared by the LAS and LAZ readers. */
internal class LidarRasterizer(
    minX: Double,
    maxX: Double,
    minY: Double,
    maxY: Double,
    options: LidarImportOptions,
    declaredPointCount: Long,
    maxBinnedPoints: Double = MAX_BINNED_POINTS,
) {
    private val options = options.sanitized()
    private val sourceRangeX = (maxX - minX).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    private val sourceRangeY = (maxY - minY).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    private val focus = this.options.focusBounds
    private val cropMinX = focus?.let { minX + it.left * sourceRangeX } ?: minX
    private val cropMaxX = focus?.let { minX + it.right * sourceRangeX } ?: maxX
    private val cropMinY = focus?.let { minY + (1.0 - it.bottom) * sourceRangeY } ?: minY
    private val cropMaxY = focus?.let { minY + (1.0 - it.top) * sourceRangeY } ?: maxY
    private val rangeX = (cropMaxX - cropMinX).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    private val rangeY = (cropMaxY - cropMinY).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    private val longSide = this.options.rasterResolution
    val width: Int
    val height: Int

    private val groundMin: FloatArray
    private val groundCount: IntArray
    private val allMin: FloatArray
    private val allMax: FloatArray
    private val allCount: IntArray
    private val coverageCount: IntArray
    private val classHistogram = IntArray(256)
    private val estimatedPointsInFocus = declaredPointCount.coerceAtLeast(1L).toDouble() *
        ((focus?.right ?: 1.0) - (focus?.left ?: 0.0)) *
        ((focus?.bottom ?: 1.0) - (focus?.top ?: 0.0))
    private val sampleStride: Int
    private val coverageStride: Int

    var pointsDecoded: Long = 0
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
        coverageCount = IntArray(width * height)

        // A 512 px overview does not benefit from eight million elevation samples. Target enough
        // returns to populate each output cell several times, with a hard ceiling for large files.
        val usefulSampleBudget = minOf(
            maxBinnedPoints.coerceAtLeast(1.0),
            (width.toDouble() * height.toDouble() * TARGET_SAMPLES_PER_CELL).coerceAtLeast(1.0),
        )
        sampleStride = ceil(estimatedPointsInFocus / usefulSampleBudget).toInt().coerceAtLeast(1)
        // Footprint coverage needs denser sampling than elevation statistics, but processing every
        // return caused multi-minute waits on ordinary 100–200 MiB LAZ files.
        coverageStride = sampleStride.coerceAtMost(MAX_COVERAGE_STRIDE)
    }

    // Rolling counters replace three per-return Long modulo operations. A 200-million-point tile
    // ran 600 million divisions purely to decide which returns to keep.
    private var sampleCountdown = 0
    private var coverageCountdown = 0

    /** Lets the low-level reader avoid coordinate/classification getters for discarded returns. */
    fun nextPointWork(): LidarPointWork = when {
        sampleCountdown == 0 -> LidarPointWork.ELEVATION
        coverageCountdown == 0 -> LidarPointWork.COVERAGE
        else -> LidarPointWork.SKIP
    }

    /** Advances both strides exactly as `pointsDecoded % stride == 0` used to. */
    private fun advance() {
        pointsDecoded++
        if (++sampleCountdown >= sampleStride) sampleCountdown = 0
        if (++coverageCountdown >= coverageStride) coverageCountdown = 0
    }

    fun skipPoint(): Boolean {
        advance()
        return true
    }

    fun addCoveragePoint(x: Double, y: Double): Boolean {
        advance()
        cellIndex(x, y)?.let { coverageCount[it]++ }
        return true
    }

    /**
     * Adds a complete elevation sample. Existing callers may still invoke this for every return;
     * the method retains its own stride guard for compatibility.
     */
    fun addPoint(x: Double, y: Double, z: Float, classification: Int, isKeyPoint: Boolean = false): Boolean {
        val wasSampleReturn = sampleCountdown == 0
        advance()
        if (!z.isFinite()) return true
        val index = cellIndex(x, y) ?: return true

        coverageCount[index]++
        if (!wasSampleReturn) return true

        val normalizedClass = classification.coerceIn(0, 255)
        // Recorded before the noise test so the histogram still describes the file honestly.
        classHistogram[normalizedClass]++

        // Class 7 is Low Point — points the producer identified as sitting below true ground.
        // They are exactly the returns that carve a false pit into a min-Z surface, and that pit
        // then reads as a cellar hole or refuse pit to the detectors. Class 18 is High Noise and
        // does the same to the canopy maximum. The ground path already ignores both by only
        // accepting classes 2 and 8; the all-returns path used for the automatic fallback did not.
        if (isNoise(normalizedClass)) return true

        if (z < allMin[index]) allMin[index] = z
        if (z > allMax[index]) allMax[index] = z
        allCount[index]++
        pointsBinned++

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

    private fun cellIndex(x: Double, y: Double): Int? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (x < cropMinX || x > cropMaxX || y < cropMinY || y > cropMaxY) return null
        val gx = (((x - cropMinX) / rangeX) * (width - 1)).toInt().coerceIn(0, width - 1)
        val gy = ((1.0 - (y - cropMinY) / rangeY) * (height - 1)).toInt().coerceIn(0, height - 1)
        return gy * width + gx
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

        val coverageMask = buildCoverageMask(coverageCount, width, height)
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
        val samplingNote = when {
            sampleStride > 1 ->
                "sampled every ${sampleStride}th elevation return and every ${coverageStride}th footprint return across $pointsDecoded decoded points"
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
        val focusNote = if (focus == null) "complete footprint" else "detailed viewport"
        val smoothingNote = if (options.smoothingRadius == 0) "unsmoothed" else "smoothing radius ${options.smoothingRadius}"
        val classNote = classHistogram.withIndex()
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(5)
            .joinToString(prefix = "classes ", separator = ", ") { "${it.index}:${it.value}" }

        return DemGenerator.LasLoadResult(
            grid = ElevationGrid(width, height, bareEarth, canopy, cellSize, coverageMask),
            totalPointsRead = pointsDecoded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            groundPointsUsed = if (appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) {
                groundPointsBinned
            } else {
                pointsBinned
            },
            usedClassificationFilter = appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED,
            pointFormat = pointFormat,
            note = "$sourceLabel · $focusNote · $modeNote · $classNote · $samplingNote · ${width}×$height $smoothingNote",
            requestedGroundMode = requestedMode,
            appliedGroundMode = appliedMode,
            sampledPoints = pointsBinned,
            wasTruncated = false,
        )
    }

    companion object {
        /**
         * ASPRS noise classes: 7 is Low Point, 18 is High Noise.
         *
         * These are returns the data producer already identified as not being real surface. A
         * low point sits below true ground, so letting one define a cell's minimum carves a
         * false pit into the bare-earth model; a high-noise point inflates the canopy maximum.
         */
        internal fun isNoise(classification: Int): Boolean = classification == 7 || classification == 18

        private const val MIN_SHORT_SIDE = 48
        private const val MIN_CLASSIFIED_POINTS = 100
        private const val MIN_CLASSIFIED_CELLS = 12
        private const val TARGET_SAMPLES_PER_CELL = 8.0
        private const val MAX_COVERAGE_STRIDE = 8
        private const val MAX_BINNED_POINTS = 4_000_000.0
    }
}

internal fun fillMissingNearest(grid: FloatArray, width: Int, height: Int) {
    val queue = IntArray(grid.size)
    var head = 0
    var tail = 0
    for (index in grid.indices) {
        if (grid[index].isFinite()) queue[tail++] = index
    }
    if (tail == 0) {
        grid.fill(0f)
        return
    }

    // Multi-source propagation grows from every measured cell at once. Unlike directional scan
    // filling, this cannot smear the first value in a row across large parts of the raster.
    while (head < tail) {
        val index = queue[head++]
        val x = index % width
        val y = index / width
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val neighbor = ny * width + nx
                if (grid[neighbor].isFinite()) continue
                grid[neighbor] = grid[index]
                queue[tail++] = neighbor
            }
        }
    }
}

internal fun buildCoverageMask(counts: IntArray, width: Int, height: Int): BooleanArray {
    require(counts.size == width * height)
    val populated = counts.count { it > 0 }
    if (populated == 0) return BooleanArray(counts.size)

    // Bridge ordinary raster-bin gaps, but keep large holes and space outside irregular flight
    // footprints transparent. Radius adapts to decoded point density and remains tightly bounded.
    val averageSpacing = sqrt(counts.size.toDouble() / populated)
    val radius = (ceil(averageSpacing * 2.0).toInt()).coerceIn(2, 8)
    val distance = IntArray(counts.size) { Int.MAX_VALUE }
    val queue = IntArray(counts.size)
    var head = 0
    var tail = 0
    for (index in counts.indices) {
        if (counts[index] > 0) {
            distance[index] = 0
            queue[tail++] = index
        }
    }
    while (head < tail) {
        val index = queue[head++]
        val nextDistance = distance[index] + 1
        if (nextDistance > radius) continue
        val x = index % width
        val y = index / width
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val neighbor = ny * width + nx
                if (nextDistance >= distance[neighbor]) continue
                distance[neighbor] = nextDistance
                queue[tail++] = neighbor
            }
        }
    }
    return BooleanArray(counts.size) { distance[it] <= radius }
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
