package com.example.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Coverage and empty-cell diagnostics for a finished bare-earth raster.
 *
 * Used by the guardrail tests and by [logCoverageDiagnostics] so field logs name the same
 * buckets as LAStools/PDAL/Whitebox practice: total points, ground count, empty-cell share.
 */
data class TerrainCoverageDiagnostics(
    val totalPoints: Long,
    val groundPoints: Int,
    val width: Int,
    val height: Int,
    val cellSizeMeters: Float,
    val meanGroundSpacingMeters: Float,
    val recommendedCellMeters: Float,
    val emptyCellPercent: Float,
    val emptyInsideHullPercent: Float,
    val measuredCells: Int,
    val interpolatedCells: Int,
    val hullCells: Int,
    val countHistogram: IntArray,
    val tilesTriangulated: Int,
    val trianglesKept: Int,
    val trianglesKilledByEdge: Int,
    val tilesIdwFilled: Int,
)

data class BareEarthInterpolationResult(
    val elevations: FloatArray,
    val validData: BooleanArray,
    val interpolatedData: BooleanArray,
    val hull: BooleanArray,
    val diagnostics: TerrainCoverageDiagnostics,
)

/**
 * Caps [LidarImportOptions.rasterResolution] so the cell is about 1.5–2× the mean point spacing.
 *
 * [pointCount] should be the points that will actually be binned in the crop (header count for a
 * full footprint, or the focus estimate). The rasterizer itself never changes size from a chunk
 * point budget — call this once from the full header before workers start.
 */
fun LidarImportOptions.withDensityLimitedResolution(
    rangeX: Double,
    rangeY: Double,
    pointCount: Long,
    cellToSpacing: Double = DEFAULT_CELL_TO_SPACING,
): LidarImportOptions {
    val recommended = recommendedRasterResolution(rangeX, rangeY, pointCount, cellToSpacing)
        ?: return sanitized()
    val capped = min(sanitized().rasterResolution, recommended)
    return copy(rasterResolution = capped).sanitized()
}

/**
 * Full-footprint opens stay density-capped so empty cells stay rare. Focused refine must keep
 * the requested pixel count: that is how a zoomed viewport gets a smaller cell size and looks
 * sharper than the overview.
 */
fun LidarImportOptions.forTerrainDecode(
    rangeX: Double,
    rangeY: Double,
    pointCount: Long,
    cellToSpacing: Double = DEFAULT_CELL_TO_SPACING,
): LidarImportOptions {
    val sanitized = sanitized()
    if (sanitized.focusBounds != null) return sanitized
    return sanitized.withDensityLimitedResolution(rangeX, rangeY, pointCount, cellToSpacing)
}

internal fun recommendedCellSizeMeters(
    areaMeters: Double,
    groundPointCount: Long,
    cellToSpacing: Double = DEFAULT_CELL_TO_SPACING,
): Float? {
    if (!areaMeters.isFinite() || areaMeters <= 0.0 || groundPointCount <= 0L) return null
    val spacing = 1.0 / sqrt(groundPointCount.toDouble() / areaMeters)
    if (!spacing.isFinite() || spacing <= 0.0) return null
    return (spacing * cellToSpacing).toFloat()
}

internal fun recommendedRasterResolution(
    rangeX: Double,
    rangeY: Double,
    pointCount: Long,
    cellToSpacing: Double = DEFAULT_CELL_TO_SPACING,
): Int? {
    val longSide = max(rangeX, rangeY)
    val area = rangeX * rangeY
    val cell = recommendedCellSizeMeters(area, pointCount, cellToSpacing)?.toDouble() ?: return null
    if (cell <= 0.0 || !longSide.isFinite() || longSide <= 0.0) return null
    return ceil(longSide / cell + 1.0).toInt()
        .coerceIn(LidarImportOptions.MIN_RASTER_RESOLUTION, LidarImportOptions.MAX_REFINED_RESOLUTION)
}

internal fun meanSpacingMeters(areaMeters: Double, pointCount: Long): Float {
    if (!areaMeters.isFinite() || areaMeters <= 0.0 || pointCount <= 0L) return 0f
    val spacing = 1.0 / sqrt(pointCount.toDouble() / areaMeters)
    return if (spacing.isFinite()) spacing.toFloat() else 0f
}

internal fun pointCountHistogram(counts: IntArray): IntArray {
    val buckets = IntArray(5)
    for (count in counts) {
        when {
            count <= 0 -> buckets[0]++
            count == 1 -> buckets[1]++
            count <= 3 -> buckets[2]++
            count <= 7 -> buckets[3]++
            else -> buckets[4]++
        }
    }
    return buckets
}

/**
 * Ground points (min-Z cell samples) → per-tile buffered Delaunay TIN → raster with a max-edge
 * kill → bounded IDW for leftover pinholes. True voids outside the data hull stay NaN.
 */
internal fun interpolateBareEarth(
    measuredElevations: FloatArray,
    measuredMask: BooleanArray,
    width: Int,
    height: Int,
    cellSizeMeters: Float,
    totalPoints: Long,
    groundPoints: Int,
    sourceCounts: IntArray? = null,
): BareEarthInterpolationResult {
    require(measuredElevations.size == width * height)
    require(measuredMask.size == width * height)
    val elevations = measuredElevations.copyOf()
    val interpolated = BooleanArray(width * height)
    val valid = BooleanArray(width * height) { measuredMask[it] && elevations[it].isFinite() }

    val area = width.toDouble() * height.toDouble() * cellSizeMeters.toDouble() * cellSizeMeters.toDouble()
    val spacing = meanSpacingMeters(area, groundPoints.coerceAtLeast(valid.count { it }).toLong())
    val recommendedCell = recommendedCellSizeMeters(area, groundPoints.coerceAtLeast(1).toLong())
        ?: (spacing * DEFAULT_CELL_TO_SPACING.toFloat())
    // Voids (water, buildings, dropouts) are filled like las2dem/blast2dem: anything a TIN with
    // edges up to VOID_MAX_EDGE would cover is interpolated, wider true voids stay NaN. The data
    // hull is a morphological closing, so nothing is extruded outside the real footprint.
    val maxEdgeCells = voidMaxEdgeCells(cellSizeMeters)
    val hull = buildDataHull(valid, width, height, maxEdgeCells)
    val tilesTriangulated = 0
    val trianglesKept = 0
    val trianglesKilled = 0
    val tilesIdw = 0
    fillVoidsSmooth(elevations, valid, interpolated, hull, width, height)

    var interpolatedCells = 0
    var emptyTotal = 0
    var emptyInHull = 0
    var hullCells = 0
    for (index in valid.indices) {
        if (interpolated[index]) interpolatedCells++
        if (!valid[index]) {
            emptyTotal++
            elevations[index] = Float.NaN
        }
        if (hull[index]) {
            hullCells++
            if (!valid[index]) emptyInHull++
        }
    }
    val histogram = sourceCounts?.let { pointCountHistogram(it) } ?: IntArray(5)
    val diagnostics = TerrainCoverageDiagnostics(
        totalPoints = totalPoints,
        groundPoints = groundPoints,
        width = width,
        height = height,
        cellSizeMeters = cellSizeMeters,
        meanGroundSpacingMeters = spacing,
        recommendedCellMeters = recommendedCell,
        emptyCellPercent = if (valid.isNotEmpty()) 100f * emptyTotal / valid.size else 0f,
        emptyInsideHullPercent = if (hullCells > 0) 100f * emptyInHull / hullCells else 0f,
        measuredCells = valid.count { it } - interpolatedCells,
        interpolatedCells = interpolatedCells,
        hullCells = hullCells,
        countHistogram = histogram,
        tilesTriangulated = tilesTriangulated,
        trianglesKept = trianglesKept,
        trianglesKilledByEdge = trianglesKilled,
        tilesIdwFilled = tilesIdw,
    )
    TerrainCoverageLog.last = diagnostics
    logCoverageDiagnostics(diagnostics)
    return BareEarthInterpolationResult(elevations, valid, interpolated, hull, diagnostics)
}

internal fun emptyShareInsideHull(
    validData: BooleanArray,
    hull: BooleanArray,
): Float {
    var hullCells = 0
    var empty = 0
    for (index in validData.indices) {
        if (!hull[index]) continue
        hullCells++
        if (!validData[index]) empty++
    }
    return if (hullCells == 0) 1f else empty.toFloat() / hullCells
}

internal fun emptyShareInsideHull(grid: ElevationGrid): Float {
    val measured = BooleanArray(grid.width * grid.height) { index ->
        grid.validData[index] && grid.bareEarth[index].isFinite()
    }
    val hull = buildDataHull(measured, grid.width, grid.height, voidMaxEdgeCells(grid.cellSizeMeters))
    return emptyShareInsideHull(grid.validData, hull)
}

/**
 * Re-runs the TIN/IDW fill when a cached or preview grid is still holey inside the data hull.
 * Already-continuous rasters are returned unchanged.
 */
internal fun ElevationGrid.ensureBareEarthCoverage(): ElevationGrid {
    val share = emptyShareInsideHull(this)
    if (share < CONTINUOUS_HULL_EMPTY_SHARE) return this
    val measured = BooleanArray(width * height) { index ->
        validData[index] && bareEarth[index].isFinite() && !interpolatedData[index]
    }
    if (measured.none { it }) return this
    val filled = interpolateBareEarth(
        measuredElevations = bareEarth,
        measuredMask = measured,
        width = width,
        height = height,
        cellSizeMeters = cellSizeMeters,
        totalPoints = measured.count { it }.toLong(),
        groundPoints = measured.count { it },
    )
    return ElevationGrid(
        width = width,
        height = height,
        bareEarth = filled.elevations,
        canopySpikes = canopySpikes,
        cellSizeMeters = cellSizeMeters,
        validData = filled.validData,
        interpolatedData = filled.interpolatedData,
    )
}

internal fun logCoverageDiagnostics(diagnostics: TerrainCoverageDiagnostics) {
    val histogram = diagnostics.countHistogram
    val message = buildString {
        append("DEM coverage ")
        append(diagnostics.width).append('×').append(diagnostics.height)
        append(" · pts ").append(diagnostics.totalPoints)
        append(" · ground ").append(diagnostics.groundPoints)
        append(" · cell ").append("%.2f".format(diagnostics.cellSizeMeters)).append('m')
        append(" · spacing ").append("%.2f".format(diagnostics.meanGroundSpacingMeters)).append('m')
        append(" · recommended cell ").append("%.2f".format(diagnostics.recommendedCellMeters)).append('m')
        append(" · empty ").append("%.1f".format(diagnostics.emptyCellPercent)).append('%')
        append(" · empty-in-hull ").append("%.1f".format(diagnostics.emptyInsideHullPercent)).append('%')
        append(" · interpolated ").append(diagnostics.interpolatedCells)
        append(" · hist[0,1,2-3,4-7,8+]=")
        append(histogram.joinToString(","))
        append(" · tinTiles ").append(diagnostics.tilesTriangulated)
        append(" · triKept ").append(diagnostics.trianglesKept)
        append(" · triKilled ").append(diagnostics.trianglesKilledByEdge)
        append(" · idwTiles ").append(diagnostics.tilesIdwFilled)
        if (diagnostics.emptyCellPercent > 30f) {
            append(" · WARN empty>30% (cell too small or points lost)")
        }
    }
    TerrainCoverageLog.emit("FindItTerrain", message, diagnostics)
}

internal object TerrainCoverageLog {
    @Volatile
    var last: TerrainCoverageDiagnostics? = null

    fun emit(tag: String, message: String, diagnostics: TerrainCoverageDiagnostics? = null) {
        if (diagnostics != null) last = diagnostics
        runCatching { android.util.Log.i(tag, message) }
    }
}

internal const val DEFAULT_CELL_TO_SPACING = 1.75
internal const val CONTINUOUS_HULL_EMPTY_SHARE = 0.05f
private const val TIN_TILE_CELLS = 32
private const val TIN_BUFFER_METERS = 25.0
private const val MAX_TRIANGLE_EDGE_METERS = 15.0
private const val MIN_TRIANGLE_EDGE_METERS = 10.0
private const val HOLE_FILL_RADIUS_CELLS = 8
private const val MAX_TIN_VERTICES_PER_TILE = 800

internal fun maxEdgeMeters(cellSizeMeters: Float, meanSpacingMeters: Float): Float {
    val fromSpacing = (meanSpacingMeters * 3f).coerceAtLeast(MIN_TRIANGLE_EDGE_METERS.toFloat())
    val fromCell = cellSizeMeters * 4f
    return max(fromSpacing, fromCell).coerceAtMost(MAX_TRIANGLE_EDGE_METERS.toFloat())
}

/** blast2dem's default `-kill 100`: voids narrower than this (source units) are interpolated. */
internal const val VOID_MAX_EDGE = 100.0
private const val MAX_VOID_EDGE_CELLS = 256f

internal fun voidMaxEdgeCells(cellSize: Float): Float =
    (VOID_MAX_EDGE / cellSize.coerceAtLeast(0.001f)).toFloat().coerceIn(2f, MAX_VOID_EDGE_CELLS)

/**
 * Data hull = morphological closing of the measured cells with a window of [radiusCells]: every
 * void narrower than the window is inside the hull, but the hull never grows past the outer
 * footprint (a plain dilation smeared data into empty flight margins).
 */
internal fun buildDataHull(
    measured: BooleanArray,
    width: Int,
    height: Int,
    radiusCells: Float,
): BooleanArray {
    val radius = ceil(radiusCells / 2.0).toInt().coerceIn(1, (MAX_VOID_EDGE_CELLS / 2).toInt())
    val dilated = dilateMask(measured, width, height, radius)
    val outside = BooleanArray(dilated.size) { !dilated[it] }
    if (outside.none { it }) {
        // No footprint edge in view. Dense data: the whole raster is inside. A handful of
        // isolated samples is not a surface, so nothing is extrapolated around them.
        return if (measured.count { it } >= 16) dilated else measured.copyOf()
    }
    val grown = dilateMask(outside, width, height, radius)
    return BooleanArray(dilated.size) { measured[it] || !grown[it] }
}

/**
 * Smooth (harmonic) fill of hull voids: coarse-to-fine pyramid with bilinear prolongation and
 * Gauss–Seidel relaxation, measured cells fixed. Result is continuous with the surrounding ground
 * — no blocks, steps or IDW bull's-eyes — and flat water stays flat.
 */
internal fun fillVoidsSmooth(
    elevations: FloatArray,
    valid: BooleanArray,
    interpolated: BooleanArray,
    hull: BooleanArray,
    width: Int,
    height: Int,
) {
    val free = BooleanArray(width * height) { hull[it] && !valid[it] }
    if (free.none { it } || valid.none { it }) return
    val solved = harmonicLevel(
        FloatArray(width * height) { if (valid[it]) elevations[it] else 0f },
        FloatArray(width * height) { if (valid[it]) 1f else 0f },
        width, height, free,
    )
    for (i in free.indices) {
        if (!free[i]) continue
        val z = solved[i]
        if (!z.isFinite()) continue
        elevations[i] = z
        valid[i] = true
        interpolated[i] = true
    }
}

/** [weight] > 0 marks known values; [free] (level 0 only) restricts which unknowns are solved. */
private fun harmonicLevel(
    values: FloatArray,
    weight: FloatArray,
    width: Int,
    height: Int,
    free: BooleanArray?,
): FloatArray {
    val out = FloatArray(width * height) { if (weight[it] > 0f) values[it] else Float.NaN }
    val unknown = BooleanArray(width * height) { weight[it] <= 0f && (free == null || free[it]) }
    if (unknown.none { it }) return out
    if (width <= 2 && height <= 2) {
        var sum = 0.0
        var n = 0.0
        for (i in values.indices) if (weight[i] > 0f) { sum += values[i] * weight[i]; n += weight[i] }
        val mean = if (n > 0) (sum / n).toFloat() else Float.NaN
        for (i in out.indices) if (unknown[i]) out[i] = mean
        return out
    }
    val cw = (width + 1) / 2
    val ch = (height + 1) / 2
    val cv = FloatArray(cw * ch)
    val cwt = FloatArray(cw * ch)
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        val wgt = weight[i]
        if (wgt <= 0f) continue
        val c = (y / 2) * cw + x / 2
        cv[c] += values[i] * wgt
        cwt[c] += wgt
    }
    for (c in cv.indices) if (cwt[c] > 0f) cv[c] /= cwt[c]
    val coarse = harmonicLevel(cv, cwt, cw, ch, null)
    // Bilinear prolongation of the coarse solution onto this level's unknowns.
    for (y in 0 until height) for (x in 0 until width) {
        val i = y * width + x
        if (!unknown[i]) continue
        val fx = ((x + 0.5) / 2.0 - 0.5).coerceIn(0.0, (cw - 1).toDouble())
        val fy = ((y + 0.5) / 2.0 - 0.5).coerceIn(0.0, (ch - 1).toDouble())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = min(x0 + 1, cw - 1)
        val y1 = min(y0 + 1, ch - 1)
        val tx = (fx - x0).toFloat()
        val ty = (fy - y0).toFloat()
        val a = coarse[y0 * cw + x0]
        val b = coarse[y0 * cw + x1]
        val c = coarse[y1 * cw + x0]
        val d = coarse[y1 * cw + x1]
        val top = if (b.isFinite()) a + (b - a) * tx else a
        val bottom = if (d.isFinite()) c + (d - c) * tx else c
        out[i] = if (bottom.isFinite()) top + (bottom - top) * ty else top
    }
    repeat(RELAX_ITERATIONS) {
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            if (!unknown[i]) continue
            var sum = 0f
            var n = 0
            if (x > 0) out[i - 1].let { if (it.isFinite()) { sum += it; n++ } }
            if (x < width - 1) out[i + 1].let { if (it.isFinite()) { sum += it; n++ } }
            if (y > 0) out[i - width].let { if (it.isFinite()) { sum += it; n++ } }
            if (y < height - 1) out[i + width].let { if (it.isFinite()) { sum += it; n++ } }
            if (n > 0) out[i] = sum / n
        }
    }
    return out
}

private const val RELAX_ITERATIONS = 12

private fun dilateMask(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    if (radius <= 0) return source.copyOf()
    // Alternating 3×3 square / plus steps grow an octagon ≈ disc of [radius]: a round window
    // leaves round true voids (like a TIN edge kill) instead of square, blocky ones.
    var cur = source.copyOf()
    var next = BooleanArray(source.size)
    for (step in 0 until radius) {
        val square = step % 2 == 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                var hit = cur[i] ||
                    (x > 0 && cur[i - 1]) || (x < width - 1 && cur[i + 1]) ||
                    (y > 0 && cur[i - width]) || (y < height - 1 && cur[i + width])
                if (!hit && square) {
                    hit = (y > 0 && ((x > 0 && cur[i - width - 1]) || (x < width - 1 && cur[i - width + 1]))) ||
                        (y < height - 1 && ((x > 0 && cur[i + width - 1]) || (x < width - 1 && cur[i + width + 1])))
                }
                next[i] = hit
            }
        }
        val t = cur; cur = next; next = t
    }
    return cur
}
