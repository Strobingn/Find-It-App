package com.example.data.field

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A raster of which ground cells have been swept, derived from GPS breadcrumb tracks. Cells are
 * square meters over a fixed geographic extent; a cell counts as swept when it falls within the
 * detector's sweep radius of any recorded track segment.
 */
data class SweepCoverageGrid(
    val width: Int,
    val height: Int,
    val minLatitude: Double,
    val maxLatitude: Double,
    val minLongitude: Double,
    val maxLongitude: Double,
    val cellWidthMeters: Float,
    val cellHeightMeters: Float,
    val covered: BooleanArray,
    val coveredCells: Int,
) {
    val totalCells: Int get() = width * height

    /** Fraction of the whole extent swept, 0..1. */
    val coverageRatio: Float
        get() = if (totalCells > 0) coveredCells.toFloat() / totalCells else 0f

    val coveredAreaSquareMeters: Float
        get() = coveredCells * cellWidthMeters * cellHeightMeters

    override fun equals(other: Any?): Boolean =
        other is SweepCoverageGrid &&
            width == other.width &&
            height == other.height &&
            minLatitude == other.minLatitude &&
            maxLatitude == other.maxLatitude &&
            minLongitude == other.minLongitude &&
            maxLongitude == other.maxLongitude &&
            coveredCells == other.coveredCells &&
            covered.contentEquals(other.covered)

    override fun hashCode(): Int = covered.contentHashCode() * 31 + coveredCells
}

/**
 * Builds [SweepCoverageGrid]s from breadcrumb tracks. Track segments are stamped with the
 * sweep radius using a local equirectangular approximation — accurate to well under a cell at
 * the survey extents this app handles, and fast enough to recompute while recording.
 */
object SweepCoverageTracker {
    /** Default detector swing width: a coil swept in a tight arc covers roughly this corridor. */
    const val DEFAULT_SWEEP_WIDTH_METERS = 2f
    const val DEFAULT_CELL_SIZE_METERS = 2f
    private const val MAX_GRID_SIDE = 512
    private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

    fun build(
        tracks: List<BreadcrumbTrack>,
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
        sweepWidthMeters: Float = DEFAULT_SWEEP_WIDTH_METERS,
        cellSizeMeters: Float = DEFAULT_CELL_SIZE_METERS,
    ): SweepCoverageGrid {
        require(maxLatitude > minLatitude && maxLongitude > minLongitude) {
            "coverage extent must have positive span"
        }
        val midLatitude = (minLatitude + maxLatitude) / 2.0
        val metersPerDegreeLongitude =
            (METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(midLatitude))).coerceAtLeast(1.0)
        val spanXMeters = (maxLongitude - minLongitude) * metersPerDegreeLongitude
        val spanYMeters = (maxLatitude - minLatitude) * METERS_PER_DEGREE_LATITUDE
        val cell = cellSizeMeters.coerceAtLeast(0.5f)
        val width = ceil(spanXMeters / cell).toInt().coerceIn(1, MAX_GRID_SIDE)
        val height = ceil(spanYMeters / cell).toInt().coerceIn(1, MAX_GRID_SIDE)
        val cellWidthMeters = (spanXMeters / width).toFloat()
        val cellHeightMeters = (spanYMeters / height).toFloat()
        val covered = BooleanArray(width * height)
        val radius = (sweepWidthMeters / 2f).coerceAtLeast(0.25f)

        fun stampDisk(centerXMeters: Double, centerYMeters: Double): Int {
            val minCellX = floor((centerXMeters - radius) / cellWidthMeters).toInt().coerceIn(0, width - 1)
            val maxCellX = floor((centerXMeters + radius) / cellWidthMeters).toInt().coerceIn(0, width - 1)
            val minCellY = floor((centerYMeters - radius) / cellHeightMeters).toInt().coerceIn(0, height - 1)
            val maxCellY = floor((centerYMeters + radius) / cellHeightMeters).toInt().coerceIn(0, height - 1)
            var stamped = 0
            for (cellY in minCellY..maxCellY) {
                val centerY = (cellY + 0.5f) * cellHeightMeters
                for (cellX in minCellX..maxCellX) {
                    val index = cellY * width + cellX
                    if (covered[index]) continue
                    val centerX = (cellX + 0.5f) * cellWidthMeters
                    val dx = centerX - centerXMeters
                    val dy = centerY - centerYMeters
                    if (dx * dx + dy * dy <= radius.toDouble() * radius) {
                        covered[index] = true
                        stamped++
                    }
                }
            }
            return stamped
        }

        fun stampSegment(
            axMeters: Double,
            ayMeters: Double,
            bxMeters: Double,
            byMeters: Double,
        ): Int {
            val minCellX = floor((min(axMeters, bxMeters) - radius) / cellWidthMeters)
                .toInt().coerceIn(0, width - 1)
            val maxCellX = floor((max(axMeters, bxMeters) + radius) / cellWidthMeters)
                .toInt().coerceIn(0, width - 1)
            val minCellY = floor((min(ayMeters, byMeters) - radius) / cellHeightMeters)
                .toInt().coerceIn(0, height - 1)
            val maxCellY = floor((max(ayMeters, byMeters) + radius) / cellHeightMeters)
                .toInt().coerceIn(0, height - 1)
            var stamped = 0
            for (cellY in minCellY..maxCellY) {
                val centerY = (cellY + 0.5) * cellHeightMeters
                for (cellX in minCellX..maxCellX) {
                    val index = cellY * width + cellX
                    if (covered[index]) continue
                    val centerX = (cellX + 0.5) * cellWidthMeters
                    if (distanceToSegmentMeters(
                            centerX, centerY, axMeters, ayMeters, bxMeters, byMeters,
                        ) <= radius
                    ) {
                        covered[index] = true
                        stamped++
                    }
                }
            }
            return stamped
        }

        var coveredCount = 0
        for (track in tracks) {
            val points = track.points
            if (points.size == 1) {
                val point = points.first()
                if (point.latitude in minLatitude..maxLatitude &&
                    point.longitude in minLongitude..maxLongitude
                ) {
                    coveredCount += stampDisk(
                        (point.longitude - minLongitude) * metersPerDegreeLongitude,
                        (point.latitude - minLatitude) * METERS_PER_DEGREE_LATITUDE,
                    )
                }
                continue
            }
            for ((from, to) in points.zipWithNext()) {
                coveredCount += stampSegment(
                    (from.longitude - minLongitude) * metersPerDegreeLongitude,
                    (from.latitude - minLatitude) * METERS_PER_DEGREE_LATITUDE,
                    (to.longitude - minLongitude) * metersPerDegreeLongitude,
                    (to.latitude - minLatitude) * METERS_PER_DEGREE_LATITUDE,
                )
            }
        }

        return SweepCoverageGrid(
            width = width,
            height = height,
            minLatitude = minLatitude,
            maxLatitude = maxLatitude,
            minLongitude = minLongitude,
            maxLongitude = maxLongitude,
            cellWidthMeters = cellWidthMeters,
            cellHeightMeters = cellHeightMeters,
            covered = covered,
            coveredCells = coveredCount,
        )
    }

    /** Distance from point P to segment AB, all in the same local meters frame. */
    internal fun distanceToSegmentMeters(
        px: Double,
        py: Double,
        ax: Double,
        ay: Double,
        bx: Double,
        by: Double,
    ): Double {
        val abx = bx - ax
        val aby = by - ay
        val lengthSquared = abx * abx + aby * aby
        if (lengthSquared <= 1e-12) {
            val dx = px - ax
            val dy = py - ay
            return sqrt(dx * dx + dy * dy)
        }
        val t = (((px - ax) * abx + (py - ay) * aby) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - (ax + t * abx)
        val dy = py - (ay + t * aby)
        return sqrt(dx * dx + dy * dy)
    }
}
