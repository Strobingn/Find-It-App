package com.example.data

import android.graphics.Bitmap
import android.graphics.Color
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Below this pixel count, the fan-out and join overhead outweighs the parallel win — the
// built-in 100x100 demo sites and default 320x320 imports stay on the single-threaded path;
// only larger refined-detail grids (up to 1536x1536 on capable devices) actually parallelize.
private const val MIN_PIXELS_FOR_PARALLEL_RENDER = 200_000

private val RENDER_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

// Shared across grids and reused between renders. Daemon threads so an idle pool never keeps
// the process alive.
private val renderPool: ExecutorService = Executors.newFixedThreadPool(RENDER_PARALLELISM) { task ->
    Thread(task, "terrain-render").apply { isDaemon = true }
}

/** A memory-bounded elevation raster used for terrain visualization and screening. */
class ElevationGrid(
    val width: Int,
    val height: Int,
    val bareEarth: FloatArray,
    val canopySpikes: FloatArray,
    val cellSizeMeters: Float = 1f,
    val validData: BooleanArray = BooleanArray(width * height) { true },
    /**
     * Cells whose elevation was TIN/IDW filled from nearby measured ground. Never presented as
     * a field measurement; analysis code may ignore them when honesty matters.
     */
    val interpolatedData: BooleanArray = BooleanArray(width * height) { false },
    val cellSizeXMeters: Float = cellSizeMeters,
    val cellSizeYMeters: Float = cellSizeMeters,
) {
    init {
        require(cellSizeXMeters.isFinite() && cellSizeXMeters > 0f && cellSizeYMeters.isFinite() && cellSizeYMeters > 0f)
        require(width > 0 && height > 0) { "Grid dimensions must be positive" }
        require(bareEarth.size == width * height) { "bareEarth size must be width * height" }
        require(canopySpikes.size == width * height) { "canopySpikes size must be width * height" }
        require(validData.size == width * height) { "validData size must be width * height" }
        require(interpolatedData.size == width * height) { "interpolatedData size must be width * height" }
    }

    // Sun angle, contrast, palette, zScale, overlay and contour sliders change on nearly every
    // drag frame but don't affect these intermediates — only vegetationFilter/featureScaleMeters do.
    // Caching them here avoids redundant O(width*height) work on the majority of render calls.
    // Invariant: callers must serialize renderHillshade() per instance (HillshadeViewModel does
    // this via renderMutex) — concurrent calls would race these fields and corrupt the cache.
    private var cachedIntermediateKey: Pair<Float, Float>? = null
    private var cachedElevations: FloatArray? = null
    private var cachedMinElevation = 0f
    private var cachedMaxElevation = 1f
    private var cachedLocal: LocalStatistics? = null
    private var cachedResidualScale = 1f
    private var cachedRoughnessScale = 1f
    private var cachedCurvature: FloatArray? = null
    private var cachedCurvatureScale = 1f
    private val cachedCanopyScale: Float by lazy { robustPositiveScale(canopySpikes) }

    // Reused across renders under the same serialization invariant as the caches above. A refined
    // 1536x1536 grid would otherwise allocate a fresh ~9 MB int array on every drag frame.
    private var scratchPixels: IntArray? = null

    /** 0 shows the full surface model; 1 shows the extracted bare-earth model. */
    fun getElevationAt(col: Int, row: Int, vegetationFilter: Float): Float {
        val c = col.coerceIn(0, width - 1)
        val r = row.coerceIn(0, height - 1)
        val index = r * width + c
        return bareEarth[index] + canopySpikes[index] * (1f - vegetationFilter.coerceIn(0f, 1f))
    }

    /**
     * Render a terrain-analysis bitmap.
     *
     * Modes: 0 single hillshade, 1 multi-directional hillshade, 2 slope, 3 local relief,
     * 4 curvature, 5 disturbance candidates, 6 aspect, 7 elevation, and 8 canopy height.
     * Candidate views are screening aids, not proof of
     * archaeological origin; field verification and source-quality review remain essential.
     *
     * [shouldContinue] is polled once per raster row. Returning false abandons the render as soon
     * as the current row finishes, and the returned bitmap is then partially drawn - callers that
     * pass a real predicate must discard the result, which is what they wanted by cancelling. This
     * exists because the render loop never suspends, so coroutine cancellation alone cannot stop
     * it: a superseded frame would otherwise run to completion while the frame the user is
     * actually waiting for sits blocked behind it.
     */
    fun renderHillshade(
        sunAzimuth: Float,
        sunAltitude: Float,
        vegetationFilter: Float,
        palette: Int,
        contrast: Float = 1.5f,
        visualizationMode: Int = 0,
        overlayType: Int = 0,

        overlayOpacity: Float = 0.5f,
        zScale: Float = 1f,
        featureScaleMeters: Float = 6f,
        analysisSensitivity: Float = 1.2f,
        contourIntervalMeters: Float = 0f,
        shouldContinue: () -> Boolean = { true },
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = scratchPixels ?: IntArray(width * height).also { scratchPixels = it }
        val cellDistance = cellSizeMeters.coerceAtLeast(0.001f)

        val intermediateKey = vegetationFilter to featureScaleMeters
        if (cachedIntermediateKey != intermediateKey) {
            cachedIntermediateKey = intermediateKey
            cachedElevations = null
            cachedLocal = null
            cachedCurvature = null
        }
        val elevations = cachedElevations ?: run {
            val arr = FloatArray(width * height)
            for (index in arr.indices) {
                arr[index] = bareEarth[index] +
                    canopySpikes[index] * (1f - vegetationFilter.coerceIn(0f, 1f))
            }
            var minElevation = Float.MAX_VALUE
            var maxElevation = -Float.MAX_VALUE
            for (index in arr.indices) {
                if (!validData[index]) continue
                val elevation = arr[index]
                if (elevation < minElevation) minElevation = elevation
                if (elevation > maxElevation) maxElevation = elevation
            }
            if (minElevation == Float.MAX_VALUE) {
                minElevation = 0f
                maxElevation = 1f
            }
            cachedMinElevation = minElevation
            cachedMaxElevation = maxElevation
            cachedElevations = arr
            arr
        }

        val analysisRadius = (featureScaleMeters.coerceAtLeast(cellDistance) / cellDistance)
            .toInt()
            .coerceIn(1, max(1, min(width, height) / 4))
        val local = if (visualizationMode == 3 || visualizationMode == 5) {
            cachedLocal ?: localStatistics(elevations, analysisRadius).also { stats ->
                cachedLocal = stats
                cachedResidualScale = robustMagnitudeScale(stats.residual)
                cachedRoughnessScale = robustMagnitudeScale(stats.roughness)
            }
        } else {
            null
        }
        val curvatureArr = if (visualizationMode == 4 || visualizationMode == 5) {
            cachedCurvature ?: curvature(elevations, cellDistance).also { arr ->
                cachedCurvature = arr
                cachedCurvatureScale = robustMagnitudeScale(arr)
            }
        } else {
            null
        }
        val residualScale = cachedResidualScale
        val roughnessScale = cachedRoughnessScale
        val curvatureScale = cachedCurvatureScale
        val canopyScale = cachedCanopyScale

        val minElevation = cachedMinElevation
        val maxElevation = cachedMaxElevation
        val elevationRange = (maxElevation - minElevation).takeIf { it > 0f } ?: 1f
        val azimuth = normalizeDegrees(sunAzimuth)
        val altitude = sunAltitude.coerceIn(1f, 89f)
        val contrastValue = contrast.coerceIn(0.5f, 4f)
        val zMultiplier = zScale.coerceIn(0.25f, 8f)
        val contourInterval = contourIntervalMeters.coerceAtLeast(0f)
        val boundedMode = visualizationMode.coerceIn(0, 8)

        // Sun direction is constant across the raster, but shade() used to recompute
        // toRadians/sin/cos from it on every pixel - and it was called five times per pixel
        // (once directly, four more inside multiDirectionalShade), so a single pass burned
        // ~30 transcendental calls per pixel purely to rebuild the same four light vectors.
        val primaryLight = lightVector(azimuth, altitude)
        val rightLight = lightVector(azimuth + 90f, altitude)
        val oppositeLight = lightVector(azimuth + 180f, altitude)
        val leftLight = lightVector(azimuth + 270f, altitude)

        // Each mode consumes at most one of these, yet all three were computed unconditionally.
        val needsMultiShade = boundedMode == 1 || boundedMode in 3..6 || boundedMode == 8
        val needsPrimaryShade = needsMultiShade || boundedMode == 0
        val needsSlope = boundedMode == 2 || boundedMode == 6
        val needsGradient = needsPrimaryShade || needsSlope
        val gradientDivisorX = 8f * cellSizeXMeters
        val gradientDivisorY = 8f * cellSizeYMeters
        val lastX = width - 1
        val lastY = height - 1

        fun renderRow(y: Int) {
            val interiorRow = y > 0 && y < lastY
            for (x in 0 until width) {
                val index = y * width + x
                if (!validData[index] || !elevations[index].isFinite()) {
                    // True no-data stays transparent. Never paint NaN as black.
                    pixels[index] = Color.TRANSPARENT
                    continue
                }
                val centerZ = elevations[index]
                var gradientX = 0f
                var gradientY = 0f
                if (needsGradient) {
                    // Horn 3×3: a missing neighbour takes the centre value, never 0.
                    val z00: Float
                    val z01: Float
                    val z02: Float
                    val z10: Float
                    val z12: Float
                    val z20: Float
                    val z21: Float
                    val z22: Float
                    if (interiorRow && x > 0 && x < lastX) {
                        val above = index - width
                        val below = index + width
                        z00 = hornSample(elevations, validData, above - 1, centerZ)
                        z01 = hornSample(elevations, validData, above, centerZ)
                        z02 = hornSample(elevations, validData, above + 1, centerZ)
                        z10 = hornSample(elevations, validData, index - 1, centerZ)
                        z12 = hornSample(elevations, validData, index + 1, centerZ)
                        z20 = hornSample(elevations, validData, below - 1, centerZ)
                        z21 = hornSample(elevations, validData, below, centerZ)
                        z22 = hornSample(elevations, validData, below + 1, centerZ)
                    } else {
                        z00 = hornSampleAt(elevations, validData, x - 1, y - 1, centerZ)
                        z01 = hornSampleAt(elevations, validData, x, y - 1, centerZ)
                        z02 = hornSampleAt(elevations, validData, x + 1, y - 1, centerZ)
                        z10 = hornSampleAt(elevations, validData, x - 1, y, centerZ)
                        z12 = hornSampleAt(elevations, validData, x + 1, y, centerZ)
                        z20 = hornSampleAt(elevations, validData, x - 1, y + 1, centerZ)
                        z21 = hornSampleAt(elevations, validData, x, y + 1, centerZ)
                        z22 = hornSampleAt(elevations, validData, x + 1, y + 1, centerZ)
                    }
                    gradientX = ((z02 + 2f * z12 + z22) - (z00 + 2f * z10 + z20)) /
                        gradientDivisorX * zMultiplier
                    gradientY = ((z20 + 2f * z21 + z22) - (z00 + 2f * z01 + z02)) /
                        gradientDivisorY * zMultiplier
                    if (!gradientX.isFinite() || !gradientY.isFinite()) {
                        gradientX = 0f
                        gradientY = 0f
                    }
                }
                val slopeRadians = if (needsSlope) {
                    atan(sqrt(gradientX * gradientX + gradientY * gradientY))
                } else {
                    0f
                }
                // The surface normal depends only on the gradient, so it is identical for all four
                // light directions. Deriving it once here rather than inside each shade turns the
                // multi-directional modes from four square roots and twelve divisions per pixel
                // into one and three.
                var normalX = 0f
                var normalY = 0f
                var normalZ = 0f
                if (needsPrimaryShade) {
                    val normalLength = sqrt(gradientX * gradientX + gradientY * gradientY + 1f)
                    normalX = -gradientX / normalLength
                    normalY = -gradientY / normalLength
                    normalZ = 1f / normalLength
                }
                val primaryShade = if (needsPrimaryShade) {
                    illumination(normalX, normalY, normalZ, primaryLight)
                } else {
                    0f
                }
                val multiShade = if (needsMultiShade) {
                    (
                        primaryShade * 0.45f +
                            illumination(normalX, normalY, normalZ, rightLight) * 0.2f +
                            illumination(normalX, normalY, normalZ, oppositeLight) * 0.15f +
                            illumination(normalX, normalY, normalZ, leftLight) * 0.2f
                        ).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val elevationPercent = ((elevations[index] - minElevation) / elevationRange).coerceIn(0f, 1f)

                var color = when (boundedMode) {
                    1 -> shadePalette(getPaletteColor(palette, elevationPercent), multiShade, contrastValue)
                    2 -> slopeColor(getPaletteColor(palette, elevationPercent), slopeRadians)
                    3 -> {
                        val normalized = (requireNotNull(local).residual[index] / residualScale).coerceIn(-1f, 1f)
                        shadePalette(divergingReliefColor(normalized), multiShade, contrastValue * 0.75f)
                    }
                    4 -> {
                        val normalized = (requireNotNull(curvatureArr)[index] / curvatureScale).coerceIn(-1f, 1f)
                        shadePalette(divergingCurvatureColor(normalized), multiShade, contrastValue * 0.6f)
                    }
                    5 -> {
                        val stats = requireNotNull(local)
                        val residual = abs(stats.residual[index]) / residualScale
                        val roughness = stats.roughness[index] / roughnessScale
                        val bend = abs(requireNotNull(curvatureArr)[index]) / curvatureScale
                        val score = ((residual * 0.58f + bend * 0.27f + roughness * 0.15f) *
                            analysisSensitivity.coerceIn(0.4f, 2.5f)).coerceIn(0f, 1f)
                        disturbanceCandidateColor(score, multiShade)
                    }
                    6 -> aspectColor(gradientX, gradientY, slopeRadians, multiShade)
                    7 -> getPaletteColor(palette, elevationPercent)
                    8 -> canopyHeightColor(canopySpikes[index], canopyScale, multiShade)
                    else -> shadePalette(getPaletteColor(palette, elevationPercent), primaryShade, contrastValue)
                }

                if (overlayType > 0 && overlayOpacity > 0f) {
                    historicalOverlayColor(
                        x = x,
                        y = y,
                        elevations = elevations,
                        currentElevation = elevations[index],
                        overlayType = overlayType,
                    )?.let { overlay ->
                        color = blend(color, overlay, overlayOpacity.coerceIn(0f, 1f))
                    }
                }

                if (contourInterval >= 0.05f && isContourPixel(elevations, x, y, contourInterval)) {
                    color = blend(color, Color.rgb(235, 244, 255), 0.72f)
                }
                pixels[index] = color
            }
        }

        // Skip the pixel upload for an abandoned frame; the caller discards it either way, and on
        // a refined grid that copy alone is several megabytes.
        if (renderRowsInParallel(::renderRow, shouldContinue)) {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        }
        return bitmap
    }

    /**
     * Runs [rowAction] for every row, spread across the render pool. Rows write disjoint output,
     * so splitting them changes no value - only how long the pass takes.
     */
    private inline fun forEachRowInParallel(crossinline rowAction: (Int) -> Unit) {
        val threadCount = RENDER_PARALLELISM
        if (threadCount <= 1 || width * height < MIN_PIXELS_FOR_PARALLEL_RENDER || height < threadCount) {
            for (y in 0 until height) rowAction(y)
            return
        }
        val rowsPerThread = (height + threadCount - 1) / threadCount
        val pending = (1 until threadCount).map { chunk ->
            val startY = chunk * rowsPerThread
            val endY = min(startY + rowsPerThread, height)
            renderPool.submit { for (y in startY until endY) rowAction(y) }
        }
        for (y in 0 until min(rowsPerThread, height)) rowAction(y)
        pending.forEach { it.get() }
    }

    /**
     * Splits row rendering across available cores; each worker writes disjoint pixel rows.
     * Returns false if the render was abandoned before every row was drawn.
     */
    private fun renderRowsInParallel(renderRow: (Int) -> Unit, shouldContinue: () -> Boolean): Boolean {
        val threadCount = RENDER_PARALLELISM
        if (threadCount <= 1 || width * height < MIN_PIXELS_FOR_PARALLEL_RENDER || height < threadCount) {
            for (y in 0 until height) {
                if (!shouldContinue()) return false
                renderRow(y)
            }
            return true
        }
        val rowsPerThread = (height + threadCount - 1) / threadCount
        // The calling thread renders the first chunk itself, so only threadCount-1 tasks are
        // submitted. Dragging a slider re-renders every ~80 ms; spawning a fresh set of raw
        // Threads each time cost more than the pooled hand-off it replaces.
        val pending = (1 until threadCount).map { chunk ->
            val startY = chunk * rowsPerThread
            val endY = min(startY + rowsPerThread, height)
            renderPool.submit {
                for (y in startY until endY) {
                    if (!shouldContinue()) return@submit
                    renderRow(y)
                }
            }
        }
        var completed = true
        for (y in 0 until min(rowsPerThread, height)) {
            if (!shouldContinue()) {
                completed = false
                break
            }
            renderRow(y)
        }
        // Always join, even when abandoning: the workers write into the shared pixel buffer, so
        // returning while they are still running would let a discarded frame scribble over the
        // next one. They observe the same predicate and stop within a row.
        pending.forEach { it.get() }
        return completed && shouldContinue()
    }

    /**
     * Resolves a sun direction into a fixed light vector. Azimuth is clockwise from north; raster
     * y increases south, hence north carries a negative y component.
     */
    private fun lightVector(azimuthDegrees: Float, altitudeDegrees: Float): LightVector {
        val azimuth = Math.toRadians(azimuthDegrees.toDouble())
        val altitude = Math.toRadians(altitudeDegrees.toDouble())
        val horizontal = cos(altitude).toFloat()
        return LightVector(
            lx = (sin(azimuth) * horizontal).toFloat(),
            ly = (-cos(azimuth) * horizontal).toFloat(),
            lz = sin(altitude).toFloat(),
        )
    }

    /** Dot product of an already-normalized terrain normal and a precomputed light vector. */
    private fun illumination(nx: Float, ny: Float, nz: Float, light: LightVector): Float =
        (nx * light.lx + ny * light.ly + nz * light.lz).coerceIn(0f, 1f)

    private fun shadePalette(baseColor: Int, rawShade: Float, contrast: Float): Int {
        val adjusted = ((rawShade - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
        val illumination = 0.18f + adjusted * 0.82f
        return Color.rgb(
            (Color.red(baseColor) * illumination).toInt().coerceIn(0, 255),
            (Color.green(baseColor) * illumination).toInt().coerceIn(0, 255),
            (Color.blue(baseColor) * illumination).toInt().coerceIn(0, 255),
        )
    }

    private fun slopeColor(baseColor: Int, slopeRadians: Float): Int {
        val strength = (slopeRadians / Math.toRadians(35.0).toFloat()).coerceIn(0f, 1f)
        return blend(baseColor, Color.rgb(255, 70, 0), strength)
    }

    private fun isUsable(elevations: FloatArray, i: Int): Boolean =
        validData[i] && elevations[i].isFinite()

    private fun localStatistics(elevations: FloatArray, radius: Int): LocalStatistics {
        // NaN-aware box statistics: no-data cells (NaN since the DEM stopped nearest-filling)
        // are excluded from the sums and counted separately so they cannot poison the
        // integral image for every cell below/right of them.
        val stride = width + 1
        val sum = DoubleArray((width + 1) * (height + 1))
        val squareSum = DoubleArray(sum.size)
        val validCount = IntArray(sum.size)
        for (y in 0 until height) {
            var rowSum = 0.0
            var rowSquareSum = 0.0
            var rowCount = 0
            for (x in 0 until width) {
                val i = y * width + x
                if (isUsable(elevations, i)) {
                    val value = elevations[i].toDouble()
                    rowSum += value
                    rowSquareSum += value * value
                    rowCount++
                }
                val o = (y + 1) * stride + x + 1
                sum[o] = sum[y * stride + x + 1] + rowSum
                squareSum[o] = squareSum[y * stride + x + 1] + rowSquareSum
                validCount[o] = validCount[y * stride + x + 1] + rowCount
            }
        }
        val residual = FloatArray(elevations.size)
        val roughness = FloatArray(elevations.size)
        forEachRowInParallel { y ->
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val index = y * width + x
                if (!isUsable(elevations, index)) continue
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(width - 1)
                val count = validCount[(y1 + 1) * stride + x1 + 1] - validCount[y0 * stride + x1 + 1] -
                    validCount[(y1 + 1) * stride + x0] + validCount[y0 * stride + x0]
                if (count <= 0) continue
                val localSum = rectangleSum(sum, stride, x0, y0, x1, y1)
                val localSquareSum = rectangleSum(squareSum, stride, x0, y0, x1, y1)
                val mean = localSum / count
                val variance = max(0.0, localSquareSum / count - mean * mean)
                residual[index] = (elevations[index] - mean).toFloat()
                roughness[index] = sqrt(variance).toFloat()
            }
        }
        return LocalStatistics(residual, roughness)
    }

    private fun curvature(elevations: FloatArray, cellDistance: Float): FloatArray {
        // Laplacian; a missing (NaN/invalid) neighbour takes the centre value, invalid centres
        // yield 0 (they are rendered transparent anyway).
        val output = FloatArray(elevations.size)
        val divisorX = cellSizeXMeters * cellSizeXMeters
        val divisorY = cellSizeYMeters * cellSizeYMeters
        forEachRowInParallel { y ->
            for (x in 0 until width) {
                val i = y * width + x
                if (!isUsable(elevations, i)) continue
                val c = elevations[i]
                fun n(nx: Int, ny: Int): Float {
                    val j = index(nx, ny)
                    return if (isUsable(elevations, j)) elevations[j] else c
                }
                val v = (n(x - 1, y) + n(x + 1, y) - 2f * c) / divisorX +
                    (n(x, y - 1) + n(x, y + 1) - 2f * c) / divisorY
                output[i] = if (v.isFinite()) v else 0f
            }
        }
        return output
    }

    private fun robustMagnitudeScale(values: FloatArray): Float {
        var magnitudeSum = 0.0
        var maximum = 0f
        var counted = 0
        for (value in values) {
            if (!value.isFinite()) continue
            counted++
            val magnitude = abs(value)
            magnitudeSum += magnitude
            if (magnitude > maximum) maximum = magnitude
        }
        val mean = (magnitudeSum / counted.coerceAtLeast(1)).toFloat()
        return max(max(mean * 4.5f, maximum * 0.08f), 1e-5f)
    }

    private fun isContourPixel(elevations: FloatArray, x: Int, y: Int, interval: Float): Boolean {
        if (x == 0 && y == 0) return false
        val level = floor(elevations[index(x, y)] / interval).toInt()
        return (x > 0 && floor(elevations[index(x - 1, y)] / interval).toInt() != level) ||
            (y > 0 && floor(elevations[index(x, y - 1)] / interval).toInt() != level)
    }

    private fun historicalOverlayColor(
        x: Int,
        y: Int,
        elevations: FloatArray,
        currentElevation: Float,
        overlayType: Int,
    ): Int? {
        if (overlayType == 1) {
            val sectionX = max(1, width / 2)
            val sectionY = max(1, height / 2)
            val isSectionLine = x % sectionX == 0 || y % sectionY == 0
            val roadY = height * 0.65f + sin(x * 0.08f) * height * 0.12f
            val isRoad = abs(y - roadY) < max(1.5f, height / 100f)
            if (isSectionLine) return Color.rgb(180, 150, 110)
            if (isRoad) return Color.rgb(160, 130, 90)
        } else if (overlayType == 2) {
            val isContour = currentElevation.toInt() % 12 == 0 &&
                (elevations[index(x - 1, y)].toInt() % 12 != 0 ||
                    elevations[index(x, y - 1)].toInt() % 12 != 0)
            if (isContour) return Color.rgb(115, 80, 50)
            if (x == width / 2 || y == height / 2) return Color.rgb(50, 80, 115)
        }
        return null
    }

    private fun divergingReliefColor(value: Float): Int {
        val neutral = Color.rgb(198, 198, 194)
        return if (value < 0f) {
            blend(neutral, Color.rgb(32, 116, 210), -value)
        } else {
            blend(neutral, Color.rgb(232, 82, 30), value)
        }
    }

    private fun divergingCurvatureColor(value: Float): Int {
        val neutral = Color.rgb(190, 190, 184)
        return if (value < 0f) {
            blend(neutral, Color.rgb(24, 180, 196), -value)
        } else {
            blend(neutral, Color.rgb(208, 48, 146), value)
        }
    }

    private fun disturbanceCandidateColor(score: Float, shade: Float): Int {
        val base = Color.rgb(42, 49, 53)
        val heat = when {
            score < 0.35f -> blend(base, Color.rgb(44, 102, 126), score / 0.35f)
            score < 0.7f -> blend(Color.rgb(44, 102, 126), Color.rgb(255, 204, 52), (score - 0.35f) / 0.35f)
            else -> blend(Color.rgb(255, 204, 52), Color.rgb(230, 42, 38), (score - 0.7f) / 0.3f)
        }
        return shadePalette(heat, 0.3f + shade * 0.7f, 0.8f)
    }

    private fun aspectColor(dx: Float, dy: Float, slopeRadians: Float, shade: Float): Int {
        if (slopeRadians < Math.toRadians(1.0).toFloat()) return Color.rgb(148, 148, 148)
        val degrees = terrainAspectDegrees(dx, dy)
        val vivid = Color.HSVToColor(floatArrayOf(degrees, 0.82f, 0.95f))
        return shadePalette(vivid, 0.35f + shade * 0.65f, 0.72f)
    }

    private fun canopyHeightColor(heightMeters: Float, scale: Float, shade: Float): Int {
        val normalized = (heightMeters.coerceAtLeast(0f) / scale).coerceIn(0f, 1f)
        val base = when {
            normalized < 0.08f -> Color.rgb(82, 74, 62)
            normalized < 0.45f -> blend(Color.rgb(82, 74, 62), Color.rgb(46, 139, 87), normalized / 0.45f)
            else -> blend(Color.rgb(46, 139, 87), Color.rgb(8, 58, 32), (normalized - 0.45f) / 0.55f)
        }
        return shadePalette(base, 0.4f + shade * 0.6f, 0.65f)
    }

    private fun robustPositiveScale(values: FloatArray): Float {
        var sum = 0.0
        var maximum = 0f
        var count = 0
        for (value in values) {
            if (!value.isFinite() || value <= 0f) continue
            sum += value
            maximum = max(maximum, value)
            count++
        }
        if (count == 0) return 1f
        return max((sum / count).toFloat() * 3.5f, maximum * 0.15f).coerceAtLeast(0.1f)
    }

    private fun getPaletteColor(palette: Int, percent: Float): Int = when (palette) {
        0 -> {
            val shade = (150 + percent * 60).toInt().coerceIn(0, 255)
            Color.rgb(shade, shade, shade)
        }
        1 -> when {
            percent < 0.3f -> blend(Color.rgb(15, 20, 45), Color.rgb(80, 15, 20), percent / 0.3f)
            percent < 0.7f -> blend(Color.rgb(80, 15, 20), Color.rgb(200, 90, 40), (percent - 0.3f) / 0.4f)
            else -> blend(Color.rgb(200, 90, 40), Color.rgb(250, 180, 70), (percent - 0.7f) / 0.3f)
        }
        2 -> when {
            percent < 0.2f -> blend(Color.rgb(34, 112, 63), Color.rgb(46, 139, 87), percent / 0.2f)
            percent < 0.6f -> blend(Color.rgb(46, 139, 87), Color.rgb(196, 183, 101), (percent - 0.2f) / 0.4f)
            percent < 0.85f -> blend(Color.rgb(196, 183, 101), Color.rgb(130, 80, 40), (percent - 0.6f) / 0.25f)
            else -> blend(Color.rgb(130, 80, 40), Color.rgb(210, 210, 215), (percent - 0.85f) / 0.15f)
        }
        else -> Color.GRAY
    }

    private fun blend(background: Int, foreground: Int, amount: Float): Int {
        val fraction = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(background) * (1f - fraction) + Color.red(foreground) * fraction).toInt(),
            (Color.green(background) * (1f - fraction) + Color.green(foreground) * fraction).toInt(),
            (Color.blue(background) * (1f - fraction) + Color.blue(foreground) * fraction).toInt(),
        )
    }

    private fun index(x: Int, y: Int): Int =
        y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)

    private fun hornSample(elevations: FloatArray, valid: BooleanArray, sampleIndex: Int, centerZ: Float): Float {
        if (sampleIndex !in elevations.indices) return centerZ
        if (!valid[sampleIndex]) return centerZ
        val value = elevations[sampleIndex]
        return if (value.isFinite()) value else centerZ
    }

    private fun hornSampleAt(
        elevations: FloatArray,
        valid: BooleanArray,
        x: Int,
        y: Int,
        centerZ: Float,
    ): Float = hornSample(elevations, valid, index(x, y), centerZ)

    private fun rectangleSum(
        integral: DoubleArray,
        stride: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ): Double = integral[(y1 + 1) * stride + x1 + 1] - integral[y0 * stride + x1 + 1] -
        integral[(y1 + 1) * stride + x0] + integral[y0 * stride + x0]

    private fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

    private class LightVector(val lx: Float, val ly: Float, val lz: Float)
    private data class LocalStatistics(val residual: FloatArray, val roughness: FloatArray)
}

/** Downslope direction clockwise from north; grid Y grows south. */
internal fun terrainAspectDegrees(gradientX: Float, gradientY: Float): Float =
    ((Math.toDegrees(kotlin.math.atan2(-gradientX, gradientY).toDouble()).toFloat() + 360f) % 360f)
