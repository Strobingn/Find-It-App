package com.example.data

import kotlin.math.ceil
import kotlin.math.floor
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
 * Ground samples to a smooth bare-earth surface.
 *
 * The previous per-tile TIN filled holes with flat triangles. Tile borders landed on a 32-cell
 * grid, and a hard clamp on the cubic fill turned real slopes into plateaus, so hillshade showed
 * blocky facets and straight lines that are not in the ground. This pass keeps the measured
 * minimum in each spacing-sized bin (bare earth, not whichever return sits nearest the centre)
 * and Catmull-Rom upsamples that bin grid. Catmull-Rom is C1, so slope does not jump on a grid
 * line, and it is not clamped back onto the control points. A missing coarse sample does not
 * hold the last height out to the bin edge (that shelf is a straight line in hillshade); the
 * span stops short and IDW blends the rest. A grid that is already near the
 * point spacing only gets the short IDW fill. True voids outside the data hull stay NaN.
 * The parallel LAZ decode is unchanged.
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
    val maxEdgeMeters = maxEdgeMeters(cellSizeMeters, spacing)
    val maxEdgeCells = (maxEdgeMeters / cellSizeMeters.coerceAtLeast(0.001f)).coerceIn(2f, 48f)
    val hull = buildDataHull(valid, width, height, maxEdgeCells)

    val tilesIdw = fillSmoothSurface(
        elevations = elevations,
        valid = valid,
        interpolated = interpolated,
        hull = hull,
        width = width,
        height = height,
        cellSizeMeters = cellSizeMeters,
        spacingMeters = spacing,
    )

    // Anything still empty inside the hull, a few cells from real ground.
    fillBoundedIdw(
        elevations, valid, interpolated, hull, width, height,
        0, 0, width, height,
        min(HOLE_FILL_RADIUS_CELLS, maxEdgeCells.roundToInt().coerceAtLeast(2)),
        passes = 2,
    )

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
        tilesTriangulated = 0,
        trianglesKept = 0,
        trianglesKilledByEdge = 0,
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
    val spacing = meanSpacingMeters(
        grid.width.toDouble() * grid.height.toDouble() *
            grid.cellSizeMeters.toDouble() * grid.cellSizeMeters.toDouble(),
        measured.count { it }.toLong(),
    )
    val maxEdgeCells = (maxEdgeMeters(grid.cellSizeMeters, spacing) /
        grid.cellSizeMeters.coerceAtLeast(0.001f)).coerceIn(2f, 48f)
    val hull = buildDataHull(measured, grid.width, grid.height, maxEdgeCells)
    return emptyShareInsideHull(grid.validData, hull)
}

/**
 * Re-runs the smooth fill when a cached or preview grid is still holey inside the data hull.
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
private const val MAX_TRIANGLE_EDGE_METERS = 15.0
private const val MIN_TRIANGLE_EDGE_METERS = 10.0
private const val HOLE_FILL_RADIUS_CELLS = 8

internal fun maxEdgeMeters(cellSizeMeters: Float, meanSpacingMeters: Float): Float {
    val fromSpacing = (meanSpacingMeters * 3f).coerceAtLeast(MIN_TRIANGLE_EDGE_METERS.toFloat())
    val fromCell = cellSizeMeters * 4f
    return max(fromSpacing, fromCell).coerceAtMost(MAX_TRIANGLE_EDGE_METERS.toFloat())
}

/**
 * Dilated occupancy of measured cells — a concave-ish data hull that does not fill flight-line
 * bays the way a convex hull would.
 */
internal fun buildDataHull(
    measured: BooleanArray,
    width: Int,
    height: Int,
    radiusCells: Float,
): BooleanArray {
    val radius = ceil(radiusCells.toDouble()).toInt().coerceIn(1, 24)
    return dilateMask(measured, width, height, radius)
}

private fun dilateMask(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    if (radius <= 0) return source.copyOf()
    val distance = IntArray(source.size) { Int.MAX_VALUE }
    val queue = IntArray(source.size)
    var head = 0
    var tail = 0
    for (index in source.indices) {
        if (!source[index]) continue
        distance[index] = 0
        queue[tail++] = index
    }
    if (tail == 0) return BooleanArray(source.size)
    while (head < tail) {
        val index = queue[head++]
        val next = distance[index] + 1
        if (next > radius) continue
        val x = index % width
        val y = index / width
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until width || ny !in 0 until height) continue
                val neighbor = ny * width + nx
                if (next >= distance[neighbor]) continue
                distance[neighbor] = next
                queue[tail++] = neighbor
            }
        }
    }
    return BooleanArray(source.size) { distance[it] <= radius }
}

/**
 * Drops rooftop and canopy cells before the surface fill.
 *
 * Unclassified "lowest return" still keeps a roof or a tree when that cell never hit the
 * ground, and those cells become the blocky blobs in the hillshade. This is the raster form
 * of Pingel's simple morphological filter (the same idea as PDAL `filters.smrf` / lasground):
 * open the minimum surface with a growing window, and clear a cell only when it stands more
 * than about 5.5 m above that opening. Pits, ditches and mounds under that height stay.
 * Cleared cells become holes so the smooth fill rebuilds them from the surrounding ground
 * instead of painting the opened (eroded) value, which would leave a flat pad.
 */
internal fun rejectRaisedObjects(
    elevations: FloatArray,
    measured: BooleanArray,
    width: Int,
    height: Int,
    cellSizeMeters: Float,
): Int {
    if (width < 3 || height < 3) return 0
    val cell = cellSizeMeters.coerceAtLeast(0.05f)
    val maxRadius = (18f / cell).toInt().coerceIn(1, 48)
    val current = elevations.copyOf()
    val opened = FloatArray(elevations.size)
    val scratch = FloatArray(elevations.size)
    val removed = BooleanArray(elevations.size)
    var radius = 1
    var count = 0
    while (radius <= maxRadius) {
        morphologicalOpenInto(current, width, height, radius, opened, scratch)
        val thresh = 5.5f + 0.2f * radius * cell
        for (index in current.indices) {
            if (!measured[index] || removed[index]) continue
            val z = current[index]
            val low = opened[index]
            if (!z.isFinite() || !low.isFinite()) continue
            if (z - low > thresh) {
                removed[index] = true
                current[index] = low
                count++
            }
        }
        radius = if (radius < 4) radius + 1 else radius * 2
    }
    for (index in removed.indices) {
        if (!removed[index]) continue
        measured[index] = false
        elevations[index] = Float.NaN
    }
    return count
}

private fun morphologicalOpenInto(
    source: FloatArray,
    width: Int,
    height: Int,
    radius: Int,
    opened: FloatArray,
    scratch: FloatArray,
) {
    slidingFilter(source, width, height, radius, wantMax = false, scratch)
    slidingFilter(scratch, width, height, radius, wantMax = true, opened)
}

/** Separable square min/max. Non-finite samples are ignored, so holes do not spread. */
private fun slidingFilter(
    source: FloatArray,
    width: Int,
    height: Int,
    radius: Int,
    wantMax: Boolean,
    dest: FloatArray,
) {
    val horizontal = FloatArray(source.size)
    val line = FloatArray(max(width, height))
    val filtered = FloatArray(line.size)
    for (y in 0 until height) {
        val base = y * width
        for (x in 0 until width) line[x] = source[base + x]
        slidingWindow(line, width, radius, wantMax, filtered)
        for (x in 0 until width) horizontal[base + x] = filtered[x]
    }
    for (x in 0 until width) {
        for (y in 0 until height) line[y] = horizontal[y * width + x]
        slidingWindow(line, height, radius, wantMax, filtered)
        for (y in 0 until height) dest[y * width + x] = filtered[y]
    }
}

private fun slidingWindow(
    source: FloatArray,
    length: Int,
    radius: Int,
    wantMax: Boolean,
    dest: FloatArray,
) {
    val window = ArrayDeque<Int>(radius * 2 + 1)
    var next = 0
    for (index in 0 until length) {
        val addUntil = min(length - 1, index + radius)
        while (next <= addUntil) {
            val value = source[next]
            if (value.isFinite()) {
                while (window.isNotEmpty()) {
                    val tail = source[window.last()]
                    val dominated = if (wantMax) tail <= value else tail >= value
                    if (!dominated) break
                    window.removeLast()
                }
                window.addLast(next)
            }
            next++
        }
        val left = index - radius
        while (window.isNotEmpty() && window.first() < left) window.removeFirst()
        dest[index] = if (window.isEmpty()) Float.NaN else source[window.first()]
    }
}

/**
 * Fills empty ground cells without painting flat triangles.
 *
 * Stride 1 keeps the measured grid and IDW-fills pinholes. A finer grid is reduced to the
 * minimum return in each spacing-sized bin (the bare-earth sample), hole-filled there, then
 * Catmull-Rom expanded. Measured cells are never overwritten, so a real pit stays a pit.
 * The cubic is not clamped to the control points — that clamp was what flattened slopes into
 * axis-aligned blocks.
 */
private fun fillSmoothSurface(
    elevations: FloatArray,
    valid: BooleanArray,
    interpolated: BooleanArray,
    hull: BooleanArray,
    width: Int,
    height: Int,
    cellSizeMeters: Float,
    spacingMeters: Float,
): Int {
    var measuredCount = 0
    for (cell in valid) if (cell) measuredCount++
    if (measuredCount == 0) return 0
    val spacingCells = (spacingMeters / cellSizeMeters.coerceAtLeast(0.001f)).coerceIn(1f, 512f)
    // Bridge neighbouring ground samples. One or two returns must not grow a surface across the tile.
    val bridge = if (measuredCount < 3) {
        2
    } else {
        ceil(spacingCells * 1.65f).toInt().coerceIn(3, 96)
    }
    val distance = distanceToMeasured(valid, width, height, bridge)
    val stride = spacingCells.roundToInt().coerceIn(1, 64)
    if (stride <= 1) {
        fillBoundedIdw(
            elevations, valid, interpolated, hull, width, height,
            0, 0, width, height,
            min(bridge, HOLE_FILL_RADIUS_CELLS),
            passes = 2,
        )
        return 1
    }
    val coarseWidth = (width + stride - 1) / stride
    val coarseHeight = (height + stride - 1) / stride
    val coarse = FloatArray(coarseWidth * coarseHeight) { Float.NaN }
    val coarseValid = BooleanArray(coarse.size)
    for (y in 0 until height) {
        val cy = (y / stride).coerceAtMost(coarseHeight - 1)
        for (x in 0 until width) {
            val index = y * width + x
            if (!valid[index]) continue
            val cx = (x / stride).coerceAtMost(coarseWidth - 1)
            val coarseIndex = cy * coarseWidth + cx
            val z = elevations[index]
            if (!coarseValid[coarseIndex] || z < coarse[coarseIndex]) {
                coarseValid[coarseIndex] = true
                coarse[coarseIndex] = z
            }
        }
    }
    val coarseHull = dilateMask(coarseValid, coarseWidth, coarseHeight, radius = 3)
    val coarseInterpolated = BooleanArray(coarse.size)
    fillBoundedIdw(
        coarse, coarseValid, coarseInterpolated, coarseHull,
        coarseWidth, coarseHeight,
        0, 0, coarseWidth, coarseHeight,
        radiusCells = 3,
        passes = 2,
    )
    val strideF = stride.toFloat()
    for (y in 0 until height) {
        val gy = y / strideF
        val row = y * width
        for (x in 0 until width) {
            val index = row + x
            if (valid[index] || distance[index] > bridge || !hull[index]) continue
            val z = sampleCatmull(coarse, coarseValid, coarseWidth, coarseHeight, x / strideF, gy)
            if (!z.isFinite()) continue
            elevations[index] = z
            valid[index] = true
            interpolated[index] = true
        }
    }
    return 1
}

private fun distanceToMeasured(
    measured: BooleanArray,
    width: Int,
    height: Int,
    maxDistance: Int,
): IntArray {
    val distance = IntArray(measured.size) { Int.MAX_VALUE }
    val queue = IntArray(measured.size)
    var head = 0
    var tail = 0
    for (index in measured.indices) {
        if (!measured[index]) continue
        distance[index] = 0
        queue[tail++] = index
    }
    while (head < tail) {
        val index = queue[head++]
        val next = distance[index] + 1
        if (next > maxDistance) continue
        val x = index % width
        val y = index / width
        for (dy in -1..1) {
            val ny = y + dy
            if (ny !in 0 until height) continue
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                if (nx !in 0 until width) continue
                val neighbor = ny * width + nx
                if (next >= distance[neighbor]) continue
                distance[neighbor] = next
                queue[tail++] = neighbor
            }
        }
    }
    return distance
}

private fun sampleCatmull(
    grid: FloatArray,
    known: BooleanArray,
    width: Int,
    height: Int,
    gx: Float,
    gy: Float,
): Float {
    val x1 = floor(gx).toInt()
    val y1 = floor(gy).toInt()
    val tx = gx - x1
    val rows = FloatArray(4) { offset ->
        catmullRow(grid, known, width, height, x1, y1 - 1 + offset, tx)
    }
    val c1 = rows[1]
    val c2 = rows[2]
    // A missing central tap used to return the other tap unchanged. That painted a
    // flat shelf a full bin wide, and the shelf edge was a straight hillshade line.
    return spanOrShortRamp(rows[0], c1, c2, rows[3], gy - y1)
}

private fun catmullRow(
    grid: FloatArray,
    known: BooleanArray,
    width: Int,
    height: Int,
    x1: Int,
    y: Int,
    tx: Float,
): Float {
    if (y !in 0 until height) return Float.NaN
    fun at(x: Int): Float {
        if (x !in 0 until width) return Float.NaN
        val index = y * width + x
        return if (known[index]) grid[index] else Float.NaN
    }
    val c1 = at(x1)
    val c2 = at(x1 + 1)
    return spanOrShortRamp(at(x1 - 1), c1, c2, at(x1 + 2), tx)
}

/**
 * Both central samples: Catmull-Rom (outer taps mirror inward when missing).
 * One central sample: keep sloping a short distance off that sample, then stop.
 * A constant hold out to the bin edge is what drew the straight H/V lines.
 * The IDW pass after this fills the rest inside the hull.
 */
private fun spanOrShortRamp(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    if (p1.isFinite() && p2.isFinite()) {
        val c0 = if (p0.isFinite()) p0 else p1
        val c3 = if (p3.isFinite()) p3 else p2
        return catmull(c0, p1, p2, c3, t)
    }
    val reach = 0.42f
    if (p1.isFinite() && !p2.isFinite()) {
        if (t > reach) return Float.NaN
        val slope = if (p0.isFinite()) p1 - p0 else 0f
        return p1 + slope * t
    }
    if (p2.isFinite() && !p1.isFinite()) {
        if (t < 1f - reach) return Float.NaN
        val slope = if (p3.isFinite()) p3 - p2 else 0f
        return p2 + slope * (t - 1f)
    }
    return Float.NaN
}

/** Interpolating cubic. t = 0 returns [p1], t = 1 returns [p2]. */
private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5f * (
        2f * p1 +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3
        )
}

private fun fillBoundedIdw(
    elevations: FloatArray,
    valid: BooleanArray,
    interpolated: BooleanArray,
    hull: BooleanArray,
    width: Int,
    height: Int,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    radiusCells: Int,
    passes: Int = 1,
) {
    val radius = radiusCells.coerceIn(1, 16)
    val radiusSq = radius * radius
    repeat(passes) {
        val updates = ArrayList<Int>(16)
        val values = ArrayList<Float>(16)
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val index = y * width + x
                if (valid[index] || !hull[index]) continue
                var weightSum = 0.0
                var valueSum = 0.0
                var samples = 0
                val yy0 = (y - radius).coerceAtLeast(0)
                val yy1 = (y + radius).coerceAtMost(height - 1)
                val xx0 = (x - radius).coerceAtLeast(0)
                val xx1 = (x + radius).coerceAtMost(width - 1)
                for (ny in yy0..yy1) {
                    for (nx in xx0..xx1) {
                        val dx = nx - x
                        val dy = ny - y
                        val distSq = dx * dx + dy * dy
                        if (distSq == 0 || distSq > radiusSq) continue
                        val neighbor = ny * width + nx
                        if (!valid[neighbor]) continue
                        val z = elevations[neighbor]
                        if (!z.isFinite()) continue
                        val weight = 1.0 / distSq
                        weightSum += weight
                        valueSum += weight * z
                        samples++
                    }
                }
                if (samples >= 3 && weightSum > 0.0) {
                    updates += index
                    values += (valueSum / weightSum).toFloat()
                }
            }
        }
        for (i in updates.indices) {
            val index = updates[i]
            elevations[index] = values[i]
            valid[index] = true
            interpolated[index] = true
        }
    }
}

