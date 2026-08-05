package com.example.data

import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Amount of per-return work required by the optimized LAZ reader. */
internal enum class LidarPointWork {
    SKIP,
    COVERAGE,
    ELEVATION,
}

/** Confidence bucket for the produced ground surface, reported with every import. */
enum class GroundSurfaceQuality {
    /** Vendor/classified ground with dense per-cell coverage. */
    CLASSIFIED_DENSE,

    /** Classified ground was usable but thin; gap-filled areas are less trustworthy. */
    CLASSIFIED_SPARSE,

    /** Automatic lowest-return estimate with healthy sampling and spike rejection. */
    ESTIMATED_ROBUST,

    /** Automatic estimate from sparse returns; treat subtle relief with caution. */
    ESTIMATED_FRAGILE,

    /** Highest-return surface model; no ground separation was attempted. */
    SURFACE_MODEL,
}

/** Structured ground-filtering outcome attached to every raster build. */
data class GroundSurfaceReport(
    val quality: GroundSurfaceQuality,
    /** Fraction of raster cells with measured ground before gap filling. */
    val groundCellFraction: Float,
    /** Average sampled ground returns per populated cell. */
    val groundSamplesPerCell: Float,
    /** Isolated below-ground returns removed from the automatic estimate. */
    val lowSpikesRejected: Int,
)

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
    private val isOverview = this.options.focusBounds == null
    /** Classification / ground-class tracking is only needed for source-classified ground mode. */
    private val tracksSourceClasses = this.options.groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED
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
    /** Precomputed cell scales: multiplies replace two divisions on every return (hot path). */
    private val xToGrid: Double
    private val yToGrid: Double

    private val groundMin: FloatArray
    private val groundCount: IntArray
    private val allMin: FloatArray
    private val allSecondMin: FloatArray
    private val allLowBandCount: IntArray
    private val allMax: FloatArray
    private val allCount: IntArray
    private val coverageCount: IntArray
    private val classHistogram = IntArray(256)
    private val estimatedPointsInFocus = declaredPointCount.coerceAtLeast(1L).toDouble() *
        ((focus?.right ?: 1.0) - (focus?.left ?: 0.0)) *
        ((focus?.bottom ?: 1.0) - (focus?.top ?: 0.0))
    private val usefulSampleBudget: Double
    private val sampleStride: Int
    private val coverageStride: Int
    /** Hard cap on decoded returns for full-footprint overview opens of huge tiles. */
    private val maxDecodedPoints: Long
    private val targetElevationCells: Int

    var pointsDecoded: Long = 0
        private set
    var pointsBinned: Int = 0
        private set
    private var groundPointsBinned: Int = 0
    private var elevationCellsPopulated: Int = 0

    init {
        if (rangeX >= rangeY) {
            width = longSide
            height = (longSide * rangeY / rangeX).roundToInt().coerceIn(MIN_SHORT_SIDE, longSide)
        } else {
            height = longSide
            width = (longSide * rangeX / rangeY).roundToInt().coerceIn(MIN_SHORT_SIDE, longSide)
        }
        xToGrid = (width - 1).toDouble() / rangeX
        yToGrid = (height - 1).toDouble() / rangeY
        groundMin = FloatArray(width * height) { Float.MAX_VALUE }
        groundCount = IntArray(width * height)
        allMin = FloatArray(width * height) { Float.MAX_VALUE }
        allSecondMin = FloatArray(width * height) { Float.MAX_VALUE }
        allLowBandCount = IntArray(width * height)
        allMax = FloatArray(width * height) { -Float.MAX_VALUE }
        allCount = IntArray(width * height)
        coverageCount = IntArray(width * height)

        // First-paint overview is a detailed product (1,024+). Use the same per-cell sample budget
        // as cropped refine so full-footprint opens are not soft/sparse compared with zoom-in.
        usefulSampleBudget = minOf(
            maxBinnedPoints.coerceAtLeast(1.0),
            (width.toDouble() * height.toDouble() * TARGET_SAMPLES_PER_CELL).coerceAtLeast(1.0),
        )
        sampleStride = ceil(estimatedPointsInFocus / usefulSampleBudget).toInt().coerceAtLeast(1)
        // Overview: every return at least marks coverage so first paint has no skip-holes.
        // Focused refine keeps a capped coverage stride for speed on huge crops.
        coverageStride = if (isOverview) {
            1
        } else {
            sampleStride.coerceAtMost(MAX_COVERAGE_STRIDE)
        }
        // Only bail early on absurd multi-hundred-million-point tiles after a dense scan budget.
        maxDecodedPoints = if (isOverview) {
            minOf(
                estimatedPointsInFocus.toLong().coerceAtLeast(1L),
                maxOf(
                    (usefulSampleBudget * OVERVIEW_SCAN_MULTIPLIER).toLong(),
                    width.toLong() * height * OVERVIEW_MIN_RETURNS_PER_CELL,
                ),
            )
        } else {
            Long.MAX_VALUE
        }
        targetElevationCells = max(1, (width * height * OVERVIEW_CELL_FILL_TARGET).roundToInt())
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
        // Only maintained when source classes are tracked; on huge tiles the histogram write is
        // measurable, and nothing reads it in the other modes.
        if (tracksSourceClasses) classHistogram[normalizedClass]++

        // Class 7 is Low Point — returns the producer identified as sitting below true ground —
        // and class 18 is High Noise. Rejecting them here, before the statistics below, keeps them
        // from defining a cell's minimum and from polluting the low-band corroboration counts that
        // decide whether a lone low return is a spike.
        if (isNoise(normalizedClass)) return true

        if (z < allMin[index]) {
            // A newly observed low demotes the previous minimum to second place. Returns that
            // corroborate each other within a narrow band keep the band count high; a lone return
            // far below everything else stays isolated and can later be rejected as a low spike.
            allSecondMin[index] = allMin[index]
            allMin[index] = z
            allLowBandCount[index] =
                if (allSecondMin[index] - z <= LOW_BAND_METERS) 2 else 1
        } else if (z < allSecondMin[index]) {
            allSecondMin[index] = z
            if (z - allMin[index] <= LOW_BAND_METERS) allLowBandCount[index]++
        } else if (z - allMin[index] <= LOW_BAND_METERS) {
            allLowBandCount[index]++
        }
        if (z > allMax[index]) allMax[index] = z
        if (allCount[index] == 0) elevationCellsPopulated++
        allCount[index]++
        pointsBinned++

        if (tracksSourceClasses) {
            // Class 2 is Ground. Class 8 was historically Model Key-Point; modern files use the key-point flag.
            val isSourceGround = normalizedClass == 2 || normalizedClass == 8 ||
                (isKeyPoint && normalizedClass == 2)
            if (isSourceGround) {
                if (z < groundMin[index]) groundMin[index] = z
                groundCount[index]++
                groundPointsBinned++
            }
        }
        return true
    }

    /**
     * Safety valve for enormous full-footprint files only. Does not stop at a coarse “preview”
     * density — the scan budget already targets full overview quality (see [usefulSampleBudget]).
     */
    fun shouldStopDecoding(): Boolean {
        if (!isOverview) return false
        return pointsDecoded >= maxDecodedPoints && pointsDecoded >= MIN_OVERVIEW_DECODE
    }

    private fun cellIndex(x: Double, y: Double): Int? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (x < cropMinX || x > cropMaxX || y < cropMinY || y > cropMaxY) return null
        // Multiplication by precomputed scales matches the prior division mapping and is
        // substantially cheaper on every return of large LAZ tiles.
        val gx = ((x - cropMinX) * xToGrid).toInt().coerceIn(0, width - 1)
        val gy = ((cropMaxY - y) * yToGrid).toInt().coerceIn(0, height - 1)
        return gy * width + gx
    }

    fun finish(pointFormat: Int, sourceLabel: String): DemGenerator.LasLoadResult? {
        if (pointsBinned == 0 || allCount.none { it > 0 }) return null

        val populatedCells = allCount.count { it > 0 }
        val classifiedCells = if (tracksSourceClasses) groundCount.count { it > 0 } else 0
        val classifiedCoverageIsUsable =
            tracksSourceClasses &&
                groundPointsBinned >= MIN_CLASSIFIED_POINTS &&
                classifiedCells >= max(MIN_CLASSIFIED_CELLS, (populatedCells * 0.08f).roundToInt())

        val requestedMode = options.groundMode
        val appliedMode = when {
            requestedMode == GroundSurfaceMode.SOURCE_CLASSIFIED && classifiedCoverageIsUsable ->
                GroundSurfaceMode.SOURCE_CLASSIFIED
            requestedMode == GroundSurfaceMode.SOURCE_CLASSIFIED -> GroundSurfaceMode.AUTO_LOWEST
            else -> requestedMode
        }
        var lowSpikesRejected = 0
        val automaticGround: FloatArray? = if (appliedMode == GroundSurfaceMode.AUTO_LOWEST) {
            // Robust automatic ground: reject a cell's lowest return when it is isolated (no
            // corroborating returns within a narrow band) and sits far below the next-lowest
            // return. Bird/wire strikes and misclassified below-ground noise disappear, while
            // real ground under canopy — corroborated by several low returns — is preserved.
            FloatArray(width * height) { index ->
                val second = allSecondMin[index]
                val isolatedLow = allCount[index] >= MIN_SAMPLES_FOR_SPIKE_REJECT &&
                    allLowBandCount[index] == 1 &&
                    second != Float.MAX_VALUE &&
                    second - allMin[index] > LOW_SPIKE_DROP_METERS
                if (isolatedLow) {
                    lowSpikesRejected++
                    second
                } else {
                    allMin[index]
                }
            }
        } else {
            null
        }
        val source = when (appliedMode) {
            GroundSurfaceMode.SOURCE_CLASSIFIED -> groundMin
            GroundSurfaceMode.AUTO_LOWEST -> automaticGround ?: allMin
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
            multiScaleSmooth(cleaned, width, height, options.smoothingRadius)
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

        val totalCells = width * height
        // Overview first view: draw the continuous filled surface (no skip-holes).
        // Focused refine keeps the coverage mask so empty margins stay transparent.
        val validData = if (isOverview) {
            BooleanArray(totalCells) { bareEarth[it].isFinite() }
        } else {
            coverageMask
        }
        val groundSamplesPerCell = if (populatedCells > 0) {
            val groundSamples = if (appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) {
                groundPointsBinned
            } else {
                pointsBinned
            }
            groundSamples.toFloat() / populatedCells
        } else {
            0f
        }
        val groundCellFraction = when (appliedMode) {
            GroundSurfaceMode.SOURCE_CLASSIFIED -> classifiedCells.toFloat() / totalCells
            GroundSurfaceMode.AUTO_LOWEST -> populatedCells.toFloat() / totalCells
            GroundSurfaceMode.SURFACE_MODEL -> 0f
        }
        val groundQuality = when (appliedMode) {
            GroundSurfaceMode.SURFACE_MODEL -> GroundSurfaceQuality.SURFACE_MODEL
            GroundSurfaceMode.SOURCE_CLASSIFIED ->
                if (groundSamplesPerCell >= 4f && classifiedCells * 2 >= populatedCells) {
                    GroundSurfaceQuality.CLASSIFIED_DENSE
                } else {
                    GroundSurfaceQuality.CLASSIFIED_SPARSE
                }
            GroundSurfaceMode.AUTO_LOWEST ->
                if (groundSamplesPerCell >= 6f) {
                    GroundSurfaceQuality.ESTIMATED_ROBUST
                } else {
                    GroundSurfaceQuality.ESTIMATED_FRAGILE
                }
        }
        val groundReport = GroundSurfaceReport(
            quality = groundQuality,
            groundCellFraction = groundCellFraction,
            groundSamplesPerCell = groundSamplesPerCell,
            lowSpikesRejected = lowSpikesRejected,
        )

        val cellSize = max(rangeX / (width - 1), rangeY / (height - 1))
            .takeIf { it.isFinite() && it in 0.001..100_000.0 }
            ?.toFloat() ?: 1f
        val earlyOutNote = if (isOverview && pointsDecoded < estimatedPointsInFocus.toLong()) {
            " · overview early-out"
        } else {
            ""
        }
        val samplingNote = when {
            sampleStride > 1 ->
                "sampled every ${sampleStride}th elevation return and every ${coverageStride}th footprint return across $pointsDecoded decoded points$earlyOutNote"
            else -> "$pointsDecoded points decoded$earlyOutNote"
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
        val smoothingNote = if (options.smoothingRadius == 0) {
            "unsmoothed"
        } else {
            "multi-scale smoothing radius ${options.smoothingRadius}"
        }
        val qualityNote = buildString {
            append(" · ground quality ")
            append(groundQuality.name.lowercase().replace('_', ' '))
            if (lowSpikesRejected > 0) {
                append(" · ")
                append(lowSpikesRejected)
                append(" isolated low spikes rejected")
            }
        }
        val classNote = if (tracksSourceClasses) {
            classHistogram.withIndex()
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .take(5)
                .joinToString(prefix = "classes ", separator = ", ") { "${it.index}:${it.value}" }
        } else {
            "classification not tracked for this ground mode"
        }

        return DemGenerator.LasLoadResult(
            grid = ElevationGrid(width, height, bareEarth, canopy, cellSize, validData),
            totalPointsRead = pointsDecoded.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            groundPointsUsed = if (appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) {
                groundPointsBinned
            } else {
                pointsBinned
            },
            usedClassificationFilter = appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED,
            pointFormat = pointFormat,
            note = "$sourceLabel · $focusNote · $modeNote · $classNote · $samplingNote · ${width}×$height $smoothingNote$qualityNote",
            requestedGroundMode = requestedMode,
            appliedGroundMode = appliedMode,
            sampledPoints = pointsBinned,
            wasTruncated = false,
            groundReport = groundReport,
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
        private const val TARGET_SAMPLES_PER_CELL = 12.0
        /** Tighter than before so overview footprints fill continuously on first paint. */
        private const val MAX_COVERAGE_STRIDE = 4
        private const val MAX_BINNED_POINTS = 8_000_000.0
        /** Decoded returns per elevation sample budget for full-footprint opens. */
        private const val OVERVIEW_SCAN_MULTIPLIER = 16.0
        private const val OVERVIEW_MIN_RETURNS_PER_CELL = 20L
        private const val MIN_OVERVIEW_DECODE = 200_000L
        private const val OVERVIEW_CELL_FILL_TARGET = 0.92
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

    // Bridge ordinary raster-bin gaps from strided sampling, but keep true empty flight
    // margins transparent. Wider radius than before so first overview is not Swiss cheese.
    val averageSpacing = sqrt(counts.size.toDouble() / populated)
    val radius = (ceil(averageSpacing * 2.5).toInt()).coerceIn(2, 20)
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
    val interiorHeight = (height - 2).coerceAtLeast(0)
    if (interiorHeight == 0 || width < 3) return output

    val threadCount = min(POST_PROCESS_PARALLELISM, interiorHeight)
    if (threadCount <= 1 || width * height < MIN_CELLS_FOR_PARALLEL_POST) {
        for (y in 1 until height - 1) {
            suppressIsolatedLowNoiseRow(source, output, width, y)
        }
        return output
    }

    val rowsPerThread = (interiorHeight + threadCount - 1) / threadCount
    val pending = (1 until threadCount).map { chunk ->
        val startY = 1 + chunk * rowsPerThread
        val endY = min(1 + (chunk + 1) * rowsPerThread, height - 1)
        postProcessPool.submit {
            for (y in startY until endY) {
                suppressIsolatedLowNoiseRow(source, output, width, y)
            }
        }
    }
    for (y in 1 until min(1 + rowsPerThread, height - 1)) {
        suppressIsolatedLowNoiseRow(source, output, width, y)
    }
    pending.forEach { it.get() }
    return output
}

private fun suppressIsolatedLowNoiseRow(
    source: FloatArray,
    output: FloatArray,
    width: Int,
    y: Int,
) {
    val neighbors = FloatArray(8)
    for (x in 1 until width - 1) {
        var count = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                neighbors[count++] = source[(y + dy) * width + x + dx]
            }
        }
        // Eight-element insertion sort avoids generic Array.sort overhead across up to a
        // million raster cells while producing the same median.
        for (i in 1 until count) {
            val value = neighbors[i]
            var j = i - 1
            while (j >= 0 && neighbors[j] > value) {
                neighbors[j + 1] = neighbors[j]
                j--
            }
            neighbors[j + 1] = value
        }
        val median = neighbors[count / 2]
        val index = y * width + x
        // Remove only extreme low outliers; shallow cellars, ditches and tracks remain untouched.
        if (source[index] < median - LOW_NOISE_THRESHOLD_METERS) output[index] = median
    }
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

/**
 * Two-scale edge-preserving smoothing. A fine pass keeps small earthworks; a coarser pass removes
 * residual noise in flat areas. Cells where the two scales disagree (sharp banks, cellar walls)
 * keep the fine result, so smoothing strength adapts per cell instead of blurring everything.
 */
internal fun multiScaleSmooth(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
    if (radius <= 0) return source.copyOf()
    val fine = boxSmooth(source, width, height, radius)
    val coarse = boxSmooth(source, width, height, radius * 2)
    val output = FloatArray(source.size)
    for (index in output.indices) {
        val disagreement = kotlin.math.abs(fine[index] - coarse[index])
        val fineWeight = (disagreement / MULTI_SCALE_EDGE_METERS).coerceIn(0f, 1f)
        output[index] = fineWeight * fine[index] + (1f - fineWeight) * coarse[index]
    }
    return output
}

private const val LOW_NOISE_THRESHOLD_METERS = 3f
/** Returns within this height of the cell minimum corroborate it as real ground. */
private const val LOW_BAND_METERS = 0.6f
/** An isolated lowest return must sit this far below the next-lowest to be rejected. */
private const val LOW_SPIKE_DROP_METERS = 1.2f
/** Spike rejection needs enough samples for the corroboration band to be meaningful. */
private const val MIN_SAMPLES_FOR_SPIKE_REJECT = 4
/** Fine/coarse disagreement (meters) at which multi-scale smoothing fully keeps the fine pass. */
private const val MULTI_SCALE_EDGE_METERS = 0.5f
private const val MIN_CELLS_FOR_PARALLEL_POST = 40_000
private val POST_PROCESS_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
private val postProcessPool = Executors.newFixedThreadPool(POST_PROCESS_PARALLELISM) { task ->
    Thread(task, "terrain-post").apply { isDaemon = true }
}
