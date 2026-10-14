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
    val maxEdgeMeters = maxEdgeMeters(cellSizeMeters, spacing)
    val maxEdgeCells = (maxEdgeMeters / cellSizeMeters.coerceAtLeast(0.001f)).coerceIn(2f, 48f)
    val hull = buildDataHull(valid, width, height, maxEdgeCells)

    // One global Delaunay over every measured cell. The previous per-tile TIN left a seam at
    // every 32-cell boundary (shared edges were triangulated twice with different neighbours),
    // which showed as straight lines under hillshade. Integer grid coordinates keep orientation
    // and in-circle exact, so this is stable at million-cell refine sizes.
    var tilesTriangulated = 0
    var trianglesKept = 0
    var trianglesKilled = 0
    var tilesIdw = 0
    // Only rim cells (measured, touching an empty in-hull cell) can be corners of a triangle
    // that covers an empty cell, so the mesh is built from those alone: a dense refine keeps
    // memory to a fraction of the raster instead of ~80 B per cell.
    val rim = BooleanArray(width * height)
    var measuredCount = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            if (!valid[index]) continue
            var touches = false
            var dy = -1
            while (dy <= 1 && !touches) {
                val ny = y + dy
                if (ny in 0 until height) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx !in 0 until width) continue
                        val ni = ny * width + nx
                        if (!valid[ni] && hull[ni]) { touches = true; break }
                    }
                }
                dy++
            }
            if (touches) { rim[index] = true; measuredCount++ }
        }
    }
    if (measuredCount in 3..MAX_GLOBAL_TIN_VERTICES) {
        val xs = IntArray(measuredCount)
        val ys = IntArray(measuredCount)
        val zs = FloatArray(measuredCount)
        var n = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!rim[index]) continue
                xs[n] = x; ys[n] = y; zs[n] = elevations[index]; n++
            }
        }
        val coords = DoubleArray(n * 2)
        for (i in 0 until n) { coords[2 * i] = xs[i].toDouble(); coords[2 * i + 1] = ys[i].toDouble() }
        val mesh = GridDelaunay(coords)
        tilesTriangulated = 1
        val maxEdge = maxEdgeCells.toDouble()
        val maxEdge2 = maxEdge * maxEdge
        for (t in 0 until mesh.triangleCount) {
            val ia = mesh.triangles[3 * t]; val ib = mesh.triangles[3 * t + 1]; val ic = mesh.triangles[3 * t + 2]
            val ax = xs[ia].toDouble(); val ay = ys[ia].toDouble()
            val bx = xs[ib].toDouble(); val by = ys[ib].toDouble()
            val cx = xs[ic].toDouble(); val cy = ys[ic].toDouble()
            val ab2 = (ax - bx) * (ax - bx) + (ay - by) * (ay - by)
            val bc2 = (bx - cx) * (bx - cx) + (by - cy) * (by - cy)
            val ca2 = (cx - ax) * (cx - ax) + (cy - ay) * (cy - ay)
            if (max(ab2, max(bc2, ca2)) > maxEdge2) { trianglesKilled++; continue }
            trianglesKept++
            val za = zs[ia]; val zb = zs[ib]; val zc = zs[ic]
            val minX = floorToInt(min(ax, min(bx, cx))).coerceAtLeast(0)
            val maxX = ceilToInt(max(ax, max(bx, cx))).coerceAtMost(width - 1)
            val minY = floorToInt(min(ay, min(by, cy))).coerceAtLeast(0)
            val maxY = ceilToInt(max(ay, max(by, cy))).coerceAtMost(height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val index = y * width + x
                    if (valid[index] || !hull[index]) continue
                    val bary = barycentric(x.toDouble(), y.toDouble(), ax, ay, bx, by, cx, cy) ?: continue
                    val z = (bary[0] * za + bary[1] * zb + bary[2] * zc).toFloat()
                    if (!z.isFinite()) continue
                    elevations[index] = z
                    valid[index] = true
                    interpolated[index] = true
                }
            }
        }
    } else if (measuredCount >= 3) {
        tilesIdw = 1
        fillBoundedIdw(
            elevations, valid, interpolated, hull, width, height,
            0, 0, width, height, maxEdgeCells.roundToInt(),
        )
    }

    // Pinholes the TIN/IDW window left behind, still inside the hull (gdal_fillnodata -md ~5–10).
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
private const val MAX_TRIANGLE_EDGE_METERS = 15.0
private const val MIN_TRIANGLE_EDGE_METERS = 10.0
private const val HOLE_FILL_RADIUS_CELLS = 8
private const val MAX_GLOBAL_TIN_VERTICES = 2_500_000

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

private fun barycentric(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
): DoubleArray? {
    val v0x = bx - ax
    val v0y = by - ay
    val v1x = cx - ax
    val v1y = cy - ay
    val v2x = px - ax
    val v2y = py - ay
    val den = v0x * v1y - v1x * v0y
    if (abs(den) < 1e-12) return null
    val v = (v2x * v1y - v1x * v2y) / den
    val w = (v0x * v2y - v2x * v0y) / den
    val u = 1.0 - v - w
    if (u < -1e-7 || v < -1e-7 || w < -1e-7) return null
    return doubleArrayOf(u, v, w)
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

private fun floorToInt(value: Double): Int = kotlin.math.floor(value).toInt()

private fun ceilToInt(value: Double): Int = ceil(value).toInt()
