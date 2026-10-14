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
    lasVersionMajor: Int = 1,
    lasVersionMinor: Int = 2,
    pointFormat: Int = 0,
) {
    private val options = options.sanitized()
    private val isOverview = this.options.focusBounds == null
    /** Optional ASPRS class allow-list; null means no extra class filter. */
    private val allowedClasses: Set<Int>? = this.options.allowedClasses
    /** Classification / ground-class tracking for source-classified mode or explicit class filters. */
    private val tracksSourceClasses =
        this.options.groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED || allowedClasses != null
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
    private val lasVersionMajor: Int
    private val lasVersionMinor: Int
    private val pointFormat: Int

    var pointsDecoded: Long = 0
        private set
    var pointsBinned: Int = 0
        private set
    private var groundPointsBinned: Int = 0
    private var elevationCellsPopulated: Int = 0
    /** Set when a reader stops before the declared point count (cancel, I/O, or early exit). */
    var truncatedByReader: Boolean = false

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
        this.lasVersionMajor = lasVersionMajor
        this.lasVersionMinor = lasVersionMinor
        this.pointFormat = pointFormat
        // maxBinnedPoints is retained for API compatibility but no longer thins returns.
        @Suppress("UNUSED_VARIABLE")
        val ignoredBudget = maxBinnedPoints
    }

    /** Full-detail path: every decoded return is an elevation sample. */
    fun nextPointWork(): LidarPointWork = LidarPointWork.ELEVATION

    private fun advance() {
        pointsDecoded++
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
     * Adds a complete elevation sample. Every finite in-bounds return is binned; the grid itself
     * is the memory bound, so there is no sample stride.
     */
    fun addPoint(x: Double, y: Double, z: Float, classification: Int, isKeyPoint: Boolean = false, isWithheld: Boolean = false): Boolean {
        advance()
        if (!z.isFinite() || isWithheld || classification == 7 || classification == 18) return true
        val normalizedClass = classification.coerceIn(0, 255)
        // Explicit class filter: skip elevation/coverage for non-matching returns entirely.
        if (allowedClasses != null && normalizedClass !in allowedClasses) {
            if (tracksSourceClasses) {
                classHistogram[normalizedClass]++
            }
            return true
        }
        val index = cellIndex(x, y) ?: return true

        coverageCount[index]++

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

        // Skip classification work when the requested surface does not use ASPRS ground classes.
        // On multi-hundred-million-point tiles this avoids histogram + class tests for every sample.
        if (tracksSourceClasses) {
            classHistogram[normalizedClass]++
            val isSourceGround = isSourceGround(
                classification = normalizedClass,
                isKeyPoint = isKeyPoint,
                versionMajor = lasVersionMajor,
                versionMinor = lasVersionMinor,
                pointFormat = pointFormat,
            )
            if (isSourceGround) {
                if (z < groundMin[index]) groundMin[index] = z
                groundCount[index]++
                groundPointsBinned++
            }
        }
        return true
    }

    /**
     * Full-detail path never drops remaining returns. Readers may still stop on cancellation;
     * they must then call [markTruncated].
     */
    fun shouldStopDecoding(): Boolean = false

    fun markTruncated() {
        truncatedByReader = true
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
            automaticGroundSource().also { lowSpikesRejected = it.second }.first
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

        val measuredMask = BooleanArray(width * height) { sourceCounts[it] > 0 }
        val surface = FloatArray(width * height)
        for (index in surface.indices) {
            surface[index] = if (measuredMask[index]) source[index] else Float.NaN
        }
        val cellSize = max(rangeX / (width - 1), rangeY / (height - 1))
            .takeIf { it.isFinite() && it in 0.001..100_000.0 }
            ?.toFloat() ?: 1f
        val groundSamples = if (appliedMode == GroundSurfaceMode.SOURCE_CLASSIFIED) {
            groundPointsBinned
        } else {
            pointsBinned
        }
        // Ground samples → per-tile buffered Delaunay TIN → max-edge kill → bounded hole fill.
        // True voids outside the data hull stay NaN and are never shown as measured ground.
        val filled = interpolateBareEarth(
            measuredElevations = surface,
            measuredMask = measuredMask,
            width = width,
            height = height,
            cellSizeMeters = cellSize,
            totalPoints = pointsDecoded,
            groundPoints = groundSamples,
            sourceCounts = sourceCounts,
        )

        val cleaned = if (appliedMode != GroundSurfaceMode.AUTO_LOWEST) {
            filled.elevations
        } else {
            suppressIsolatedLowNoise(filled.elevations, width, height)
        }
        val bareEarth = if (options.smoothingRadius > 0) {
            multiScaleSmooth(cleaned, width, height, options.smoothingRadius)
        } else {
            cleaned
        }
        if (appliedMode != GroundSurfaceMode.SURFACE_MODEL) {
            // Ground can never sit above the highest return in its own cell. Low-noise median
            // replacement (and smoothing) could lift a cell to its neighbours' level, e.g. at a
            // pit or cliff edge, putting "auto lowest" up to ~50 m above the DSM on real LAZ.
            for (index in bareEarth.indices) {
                if (allCount[index] > 0 && bareEarth[index] > allMax[index]) bareEarth[index] = allMax[index]
            }
        }
        if (appliedMode == GroundSurfaceMode.SURFACE_MODEL) {
            // The DSM and auto-lowest surfaces are gap-filled independently, so a filled DSM cell
            // could sit below the filled auto-lowest one. Build the auto-lowest surface with the
            // exact same pipeline and keep the DSM at or above it everywhere.
            val lowest = finishedGroundSurface(
                automaticGroundSource().first, allCount, cellSize, pointsBinned,
            )
            for (index in bareEarth.indices) {
                val low = lowest[index]
                if (low.isFinite() && bareEarth[index].isFinite() && low > bareEarth[index]) bareEarth[index] = low
            }
        }
        val canopy = FloatArray(surface.size)
        if (appliedMode != GroundSurfaceMode.SURFACE_MODEL) {
            for (index in canopy.indices) {
                if (allCount[index] > 0 && bareEarth[index].isFinite()) {
                    canopy[index] = (allMax[index] - bareEarth[index]).coerceAtLeast(0f)
                }
            }
        }

        val totalCells = width * height
        val validData = filled.validData
        val interpolatedData = filled.interpolatedData
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

        val wasTruncated = truncatedByReader
        val earlyOutNote = if (wasTruncated) " · truncated" else ""
        val samplingNote = "every return used · $pointsDecoded points decoded$earlyOutNote"
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
            grid = ElevationGrid(
                width = width,
                height = height,
                bareEarth = bareEarth,
                canopySpikes = canopy,
                cellSizeMeters = cellSize,
                validData = validData,
                interpolatedData = interpolatedData,
            ),
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
            wasTruncated = wasTruncated,
            groundReport = groundReport,
        )
    }

    /**
     * Robust automatic ground: reject a cell's lowest return when it is isolated (no
     * corroborating returns within a narrow band) and sits far below the next-lowest
     * return. Bird/wire strikes and misclassified below-ground noise disappear, while
     * real ground under canopy — corroborated by several low returns — is preserved.
     */
    private fun automaticGroundSource(): Pair<FloatArray, Int> {
        var rejected = 0
        val values = FloatArray(width * height) { index ->
            val second = allSecondMin[index]
            val isolatedLow = allCount[index] >= MIN_SAMPLES_FOR_SPIKE_REJECT &&
                allLowBandCount[index] == 1 &&
                second != Float.MAX_VALUE &&
                second - allMin[index] > LOW_SPIKE_DROP_METERS
            if (isolatedLow) {
                rejected++
                second
            } else {
                allMin[index]
            }
        }
        return values to rejected
    }

    /** Same fill → low-noise → smoothing → per-cell clamp pipeline that [finish] applies to ground. */
    private fun finishedGroundSurface(
        source: FloatArray,
        counts: IntArray,
        cellSize: Float,
        samples: Int,
    ): FloatArray {
        val mask = BooleanArray(width * height) { counts[it] > 0 }
        val surface = FloatArray(width * height) { if (mask[it]) source[it] else Float.NaN }
        val filled = interpolateBareEarth(
            measuredElevations = surface,
            measuredMask = mask,
            width = width,
            height = height,
            cellSizeMeters = cellSize,
            totalPoints = pointsDecoded,
            groundPoints = samples,
            sourceCounts = counts,
        )
        val cleaned = suppressIsolatedLowNoise(filled.elevations, width, height)
        val ground = if (options.smoothingRadius > 0) {
            multiScaleSmooth(cleaned, width, height, options.smoothingRadius)
        } else {
            cleaned
        }
        for (index in ground.indices) {
            if (allCount[index] > 0 && ground[index] > allMax[index]) ground[index] = allMax[index]
        }
        return ground
    }

    /**
     * Absorbs another rasterizer that used the same crop, resolution, and options.
     * Used to merge per-core LAZ chunk ranges.
     */
    fun mergeFrom(other: LidarRasterizer) {
        require(other.width == width && other.height == height) {
            "Cannot merge rasters of different size (${other.width}×${other.height} into ${width}×${height})"
        }
        pointsDecoded += other.pointsDecoded
        pointsBinned += other.pointsBinned
        groundPointsBinned += other.groundPointsBinned
        truncatedByReader = truncatedByReader || other.truncatedByReader
        for (index in 0 until width * height) {
            if (other.coverageCount[index] > 0) coverageCount[index] += other.coverageCount[index]
            if (other.allCount[index] > 0) {
                val previousMin = allMin[index]
                if (other.allMin[index] < allMin[index]) {
                    allSecondMin[index] = min(previousMin, other.allSecondMin[index])
                    allMin[index] = other.allMin[index]
                } else if (other.allMin[index] < allSecondMin[index]) {
                    allSecondMin[index] = other.allMin[index]
                }
                if (other.allMax[index] > allMax[index]) allMax[index] = other.allMax[index]
                if (allCount[index] == 0) elevationCellsPopulated++
                allCount[index] += other.allCount[index]
                allLowBandCount[index] = allLowBandCount[index] + other.allLowBandCount[index]
            }
            if (other.groundCount[index] > 0) {
                if (other.groundMin[index] < groundMin[index]) groundMin[index] = other.groundMin[index]
                groundCount[index] += other.groundCount[index]
            }
        }
        for (cls in classHistogram.indices) {
            classHistogram[cls] += other.classHistogram[cls]
        }
    }

    companion object {
        private const val MIN_SHORT_SIDE = 48
        private const val MIN_CLASSIFIED_POINTS = 100
        private const val MIN_CLASSIFIED_CELLS = 12
        /** Retained so existing constructor call sites keep compiling. Unused for thinning. */
        private const val MAX_BINNED_POINTS = 8_000_000.0
    }
}

/**
 * ASPRS ground used for [GroundSurfaceMode.SOURCE_CLASSIFIED].
 *
 * Class 2 is always Ground. Class 8 is Model Key-Point only in LAS 1.0–1.3 (point formats 0–5).
 * LAS 1.4 moved key-points to a classification flag and reserved class 8, so class 8 is not
 * treated as ground there. The key-point flag on class 2 is redundant and ignored.
 */
@Suppress("UNUSED_PARAMETER")
internal fun isSourceGround(
    classification: Int,
    isKeyPoint: Boolean = false,
    versionMajor: Int = 1,
    versionMinor: Int = 2,
    pointFormat: Int = 0,
): Boolean {
    // The key-point flag is ignored: class 2 is always ground, and LAS 1.4 key-points are a flag
    // on whatever class they already have — they are not a substitute for class 8.
    if (classification == 2) return true
    val preLas14 = versionMajor < 1 || (versionMajor == 1 && versionMinor < 4) && pointFormat < 6
    return preLas14 && classification == 8
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
            val index = y * width + x
            if (!source[index].isFinite()) continue
            var count = 0
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val neighbor = source[(y + dy) * width + x + dx]
                    if (!neighbor.isFinite()) continue
                    neighbors[count++] = neighbor
                }
            }
            if (count < 5) continue
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
        // Remove only extreme low outliers; shallow cellars, ditches and tracks remain untouched.
        if (source[index] < median - LOW_NOISE_THRESHOLD_METERS) output[index] = median
    }
}

internal fun boxSmooth(source: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
    if (radius <= 0) return source.copyOf()
    val stride = width + 1
    val integral = DoubleArray(stride * (height + 1))
    val countIntegral = DoubleArray(integral.size)
    for (y in 0 until height) {
        var rowSum = 0.0
        var rowCount = 0.0
        for (x in 0 until width) {
            val value = source[y * width + x]
            if (value.isFinite()) {
                rowSum += value
                rowCount += 1.0
            }
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + rowSum
            countIntegral[(y + 1) * stride + x + 1] = countIntegral[y * stride + x + 1] + rowCount
        }
    }
    val output = FloatArray(source.size) { Float.NaN }
    for (y in 0 until height) {
        val y0 = (y - radius).coerceAtLeast(0)
        val y1 = (y + radius).coerceAtMost(height - 1)
        for (x in 0 until width) {
            if (!source[y * width + x].isFinite()) continue
            val x0 = (x - radius).coerceAtLeast(0)
            val x1 = (x + radius).coerceAtMost(width - 1)
            val sum = rectangleSum(integral, stride, x0, y0, x1, y1)
            val count = rectangleSum(countIntegral, stride, x0, y0, x1, y1)
            if (count > 0.0) output[y * width + x] = (sum / count).toFloat()
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
    val output = FloatArray(source.size) { source[it] }
    for (index in output.indices) {
        if (!source[index].isFinite() || !fine[index].isFinite() || !coarse[index].isFinite()) continue
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
