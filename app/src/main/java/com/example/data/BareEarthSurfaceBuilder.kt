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

    var tilesTriangulated = 0
    var trianglesKept = 0
    var trianglesKilled = 0
    var tilesIdw = 0

    val tileSize = TIN_TILE_CELLS
    val bufferCells = ceil((TIN_BUFFER_METERS / cellSizeMeters.coerceAtLeast(0.25f)).toDouble())
        .toInt()
        .coerceIn(4, 24)
    val tilesX = (width + tileSize - 1) / tileSize
    val tilesY = (height + tileSize - 1) / tileSize
    for (tileY in 0 until tilesY) {
        for (tileX in 0 until tilesX) {
            val x0 = tileX * tileSize
            val y0 = tileY * tileSize
            val x1 = min(width, x0 + tileSize)
            val y1 = min(height, y0 + tileSize)
            var emptyInterior = 0
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    val index = y * width + x
                    if (!valid[index] && hull[index]) emptyInterior++
                }
            }
            if (emptyInterior == 0) continue

            val bx0 = (x0 - bufferCells).coerceAtLeast(0)
            val by0 = (y0 - bufferCells).coerceAtLeast(0)
            val bx1 = (x1 + bufferCells).coerceAtMost(width)
            val by1 = (y1 + bufferCells).coerceAtMost(height)
            val vertices = ArrayList<TinVertex>(64)
            for (y in by0 until by1) {
                for (x in bx0 until bx1) {
                    val index = y * width + x
                    if (!valid[index]) continue
                    vertices += TinVertex(x.toDouble(), y.toDouble(), elevations[index], index)
                }
            }
            val useTin = vertices.size in 3..MAX_TIN_VERTICES_PER_TILE && emptyInterior >= 8
            if (useTin) {
                val mesh = delaunay(vertices)
                if (mesh != null) {
                    tilesTriangulated++
                    val rasterized = rasterizeTin(
                        triangles = mesh,
                        vertices = vertices,
                        elevations = elevations,
                        valid = valid,
                        interpolated = interpolated,
                        hull = hull,
                        width = width,
                        x0 = x0,
                        y0 = y0,
                        x1 = x1,
                        y1 = y1,
                        maxEdgeCells = maxEdgeCells.toDouble(),
                    )
                    trianglesKept += rasterized.first
                    trianglesKilled += rasterized.second
                } else {
                    tilesIdw++
                    fillBoundedIdw(
                        elevations, valid, interpolated, hull, width, height,
                        x0, y0, x1, y1, maxEdgeCells.roundToInt(),
                    )
                }
            } else if (vertices.size >= 3) {
                tilesIdw++
                fillBoundedIdw(
                    elevations, valid, interpolated, hull, width, height,
                    x0, y0, x1, y1, maxEdgeCells.roundToInt(),
                )
            }
        }
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

private data class TinVertex(val x: Double, val y: Double, val z: Float, val cellIndex: Int)

private data class TinTriangle(val a: Int, val b: Int, val c: Int)

private fun delaunay(vertices: List<TinVertex>): List<TinTriangle>? {
    if (vertices.size < 3) return null
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (vertex in vertices) {
        if (vertex.x < minX) minX = vertex.x
        if (vertex.y < minY) minY = vertex.y
        if (vertex.x > maxX) maxX = vertex.x
        if (vertex.y > maxY) maxY = vertex.y
    }
    val dx = (maxX - minX).coerceAtLeast(1.0)
    val dy = (maxY - minY).coerceAtLeast(1.0)
    val d = max(dx, dy)
    val cx = (minX + maxX) * 0.5
    val cy = (minY + maxY) * 0.5
    val superA = TinVertex(cx, cy + 20.0 * d, 0f, -1)
    val superB = TinVertex(cx - 20.0 * d, cy - 10.0 * d, 0f, -1)
    val superC = TinVertex(cx + 20.0 * d, cy - 10.0 * d, 0f, -1)
    val points = ArrayList<TinVertex>(vertices.size + 3)
    points.addAll(vertices)
    val ia = points.size
    points += superA
    val ib = points.size
    points += superB
    val ic = points.size
    points += superC

    val triangles = ArrayList<TinTriangle>(vertices.size * 2)
    triangles += TinTriangle(ia, ib, ic)

    for (p in vertices.indices) {
        val bad = ArrayList<TinTriangle>()
        val px = points[p].x
        val py = points[p].y
        for (triangle in triangles) {
            if (circumcircleContains(points, triangle, px, py)) bad += triangle
        }
        if (bad.isEmpty()) continue
        val edges = LinkedHashMap<Long, Int>()
        fun edgeKey(a: Int, b: Int): Long {
            val lo = min(a, b)
            val hi = max(a, b)
            return (lo.toLong() shl 32) or (hi.toLong() and 0xFFFFFFFFL)
        }
        for (triangle in bad) {
            val e = intArrayOf(triangle.a, triangle.b, triangle.b, triangle.c, triangle.c, triangle.a)
            var i = 0
            while (i < e.size) {
                val key = edgeKey(e[i], e[i + 1])
                edges[key] = (edges[key] ?: 0) + 1
                i += 2
            }
        }
        triangles.removeAll(bad.toSet())
        for ((key, count) in edges) {
            if (count != 1) continue
            val a = (key ushr 32).toInt()
            val b = (key and 0xFFFFFFFFL).toInt()
            triangles += TinTriangle(a, b, p)
        }
    }

    val kept = triangles.filter { triangle ->
        triangle.a < vertices.size && triangle.b < vertices.size && triangle.c < vertices.size
    }
    return kept.takeIf { it.isNotEmpty() }
}

private fun circumcircleContains(
    points: List<TinVertex>,
    triangle: TinTriangle,
    px: Double,
    py: Double,
): Boolean {
    val ax = points[triangle.a].x
    val ay = points[triangle.a].y
    val bx = points[triangle.b].x
    val by = points[triangle.b].y
    val cx = points[triangle.c].x
    val cy = points[triangle.c].y
    val d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if (abs(d) < 1e-12) return false
    val a2 = ax * ax + ay * ay
    val b2 = bx * bx + by * by
    val c2 = cx * cx + cy * cy
    val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
    val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
    val r2 = (ax - ux) * (ax - ux) + (ay - uy) * (ay - uy)
    val p2 = (px - ux) * (px - ux) + (py - uy) * (py - uy)
    return p2 <= r2 + 1e-8
}

private fun rasterizeTin(
    triangles: List<TinTriangle>,
    vertices: List<TinVertex>,
    elevations: FloatArray,
    valid: BooleanArray,
    interpolated: BooleanArray,
    hull: BooleanArray,
    width: Int,
    x0: Int,
    y0: Int,
    x1: Int,
    y1: Int,
    maxEdgeCells: Double,
): Pair<Int, Int> {
    var kept = 0
    var killed = 0
    for (triangle in triangles) {
        val a = vertices[triangle.a]
        val b = vertices[triangle.b]
        val c = vertices[triangle.c]
        val ab = hypot(a.x - b.x, a.y - b.y)
        val bc = hypot(b.x - c.x, b.y - c.y)
        val ca = hypot(c.x - a.x, c.y - a.y)
        if (max(ab, max(bc, ca)) > maxEdgeCells) {
            killed++
            continue
        }
        kept++
        val minX = floorToInt(min(a.x, min(b.x, c.x))).coerceAtLeast(x0)
        val maxX = ceilToInt(max(a.x, max(b.x, c.x))).coerceAtMost(x1 - 1)
        val minY = floorToInt(min(a.y, min(b.y, c.y))).coerceAtLeast(y0)
        val maxY = ceilToInt(max(a.y, max(b.y, c.y))).coerceAtMost(y1 - 1)
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val index = y * width + x
                if (valid[index] || !hull[index]) continue
                val bary = barycentric(x.toDouble(), y.toDouble(), a.x, a.y, b.x, b.y, c.x, c.y)
                    ?: continue
                val z = (bary[0] * a.z + bary[1] * b.z + bary[2] * c.z).toFloat()
                if (!z.isFinite()) continue
                elevations[index] = z
                valid[index] = true
                interpolated[index] = true
            }
        }
    }
    return kept to killed
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
