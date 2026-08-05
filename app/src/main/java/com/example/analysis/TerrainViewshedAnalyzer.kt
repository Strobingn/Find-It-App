package com.example.analysis

import com.example.data.ElevationGrid
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

data class TerrainViewshed(
    val observerXPercent: Float,
    val observerYPercent: Float,
    val observerHeightMeters: Float,
    val maxRadiusMeters: Float,
    val analyzedCells: Int,
    val visibleCells: Int,
    val canceled: Boolean,
    /** One entry per grid cell; true when the cell is visible from the observer. */
    val visibility: BooleanArray,
) {
    val visibilityRatio: Float
        get() = if (analyzedCells > 0) visibleCells.toFloat() / analyzedCells else 0f

    override fun equals(other: Any?): Boolean =
        other is TerrainViewshed &&
            observerXPercent == other.observerXPercent &&
            observerYPercent == other.observerYPercent &&
            observerHeightMeters == other.observerHeightMeters &&
            maxRadiusMeters == other.maxRadiusMeters &&
            analyzedCells == other.analyzedCells &&
            visibleCells == other.visibleCells &&
            canceled == other.canceled &&
            visibility.contentEquals(other.visibility)

    override fun hashCode(): Int =
        visibility.contentHashCode() * 31 + analyzedCells
}

data class TerrainHorizonSample(
    val azimuthDegrees: Float,
    /** Elevation angle from the observer's eye to the skyline in this direction. */
    val elevationAngleDegrees: Float,
    /** Distance to the terrain cell that forms the skyline; zero when nothing blocks. */
    val distanceMeters: Float,
    val elevationMeters: Float,
)

data class TerrainHorizon(
    val observerXPercent: Float,
    val observerYPercent: Float,
    val observerHeightMeters: Float,
    val samples: List<TerrainHorizonSample>,
)

/**
 * Line-of-sight analysis on the real elevation grid. Rays walk cell by cell and compare
 * elevation angles, so results reflect the terrain data itself — never screen-pixel colors.
 * The observer's eye sits [TerrainViewshed.observerHeightMeters] above the local surface.
 */
object TerrainViewshedAnalyzer {
    private const val MAX_RAY_WORKERS = 4
    private const val MIN_ROWS_FOR_PARALLEL = 64
    private val viewshedRayPool = Executors.newFixedThreadPool(MAX_RAY_WORKERS) { task ->
        Thread(task, "viewshed-ray").apply { isDaemon = true }
    }

    fun sample(
        grid: ElevationGrid,
        observerXPercent: Float,
        observerYPercent: Float,
        observerHeightMeters: Float = 1.7f,
        maxRadiusMeters: Float = Float.POSITIVE_INFINITY,
        vegetationFilter: Float = 0f,
        isCanceled: () -> Boolean = { false },
        maxWorkers: Int = 0,
    ): TerrainViewshed {
        val observerX = observerXPercent.coerceIn(0f, 100f) / 100f * (grid.width - 1)
        val observerY = observerYPercent.coerceIn(0f, 100f) / 100f * (grid.height - 1)
        val observerCol = observerX.roundToInt().coerceIn(0, grid.width - 1)
        val observerRow = observerY.roundToInt().coerceIn(0, grid.height - 1)
        val eyeElevation = grid.getElevationAt(observerCol, observerRow, vegetationFilter) +
            observerHeightMeters.coerceAtLeast(0f)
        val cellSize = grid.cellSizeMeters.coerceAtLeast(0.001f)
        val maxRadiusCells = if (maxRadiusMeters.isFinite() && maxRadiusMeters > 0f) {
            maxRadiusMeters / cellSize
        } else {
            Float.POSITIVE_INFINITY
        }

        val visibility = BooleanArray(grid.width * grid.height)
        val cancelScan = AtomicBoolean(false)

        // Cells are independent, so row ranges scan in parallel with identical results.
        // Cancellation is polled once per row for responsive aborts on any worker count.
        fun scanRows(startRow: Int, endRow: Int, counts: IntArray) {
            var row = startRow
            while (row < endRow && !cancelScan.get()) {
                if (isCanceled()) {
                    cancelScan.set(true)
                    break
                }
                for (col in 0 until grid.width) {
                    val distanceCells = hypot(
                        (col - observerX).toDouble(),
                        (row - observerY).toDouble(),
                    ).toFloat()
                    if (distanceCells > maxRadiusCells) continue
                    val index = row * grid.width + col
                    if (!grid.validData[index]) continue
                    counts[0]++
                    val isVisible = isLineOfSightClear(
                        grid = grid,
                        observerX = observerX,
                        observerY = observerY,
                        eyeElevation = eyeElevation,
                        targetCol = col,
                        targetRow = row,
                        targetDistanceCells = distanceCells,
                        cellSize = cellSize,
                        vegetationFilter = vegetationFilter,
                    )
                    if (isVisible) {
                        visibility[index] = true
                        counts[1]++
                    }
                }
                row++
            }
        }

        val workerTarget = if (maxWorkers > 0) {
            maxWorkers.coerceIn(1, MAX_RAY_WORKERS)
        } else {
            Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_RAY_WORKERS)
        }
        val analyzed: Int
        val visible: Int
        if (workerTarget <= 1 || grid.height < MIN_ROWS_FOR_PARALLEL) {
            val counts = IntArray(2)
            scanRows(0, grid.height, counts)
            analyzed = counts[0]
            visible = counts[1]
        } else {
            val workers = workerTarget.coerceAtMost(grid.height)
            val rowsPerWorker = (grid.height + workers - 1) / workers
            val futures = (0 until workers).map { worker ->
                val startRow = worker * rowsPerWorker
                val endRow = minOf(startRow + rowsPerWorker, grid.height)
                viewshedRayPool.submit<IntArray> {
                    val counts = IntArray(2)
                    scanRows(startRow, endRow, counts)
                    counts
                }
            }
            var analyzedSum = 0
            var visibleSum = 0
            for (future in futures) {
                val counts = future.get()
                analyzedSum += counts[0]
                visibleSum += counts[1]
            }
            analyzed = analyzedSum
            visible = visibleSum
        }
        val canceled = cancelScan.get()

        return TerrainViewshed(
            observerXPercent = observerXPercent.coerceIn(0f, 100f),
            observerYPercent = observerYPercent.coerceIn(0f, 100f),
            observerHeightMeters = observerHeightMeters,
            maxRadiusMeters = maxRadiusMeters,
            analyzedCells = analyzed,
            visibleCells = visible,
            canceled = canceled,
            visibility = visibility,
        )
    }

    /**
     * Skyline outline around the observer: for each azimuth, the elevation angle and distance of
     * the terrain cell that forms the visible horizon. Open directions report the angle of the
     * farthest analyzed ground instead.
     */
    fun horizon(
        grid: ElevationGrid,
        observerXPercent: Float,
        observerYPercent: Float,
        observerHeightMeters: Float = 1.7f,
        azimuthSteps: Int = 72,
        maxRadiusMeters: Float = Float.POSITIVE_INFINITY,
        vegetationFilter: Float = 0f,
    ): TerrainHorizon {
        val steps = azimuthSteps.coerceIn(8, 720)
        val observerX = observerXPercent.coerceIn(0f, 100f) / 100f * (grid.width - 1)
        val observerY = observerYPercent.coerceIn(0f, 100f) / 100f * (grid.height - 1)
        val observerCol = observerX.roundToInt().coerceIn(0, grid.width - 1)
        val observerRow = observerY.roundToInt().coerceIn(0, grid.height - 1)
        val eyeElevation = grid.getElevationAt(observerCol, observerRow, vegetationFilter) +
            observerHeightMeters.coerceAtLeast(0f)
        val cellSize = grid.cellSizeMeters.coerceAtLeast(0.001f)
        val maxRadiusCells = if (maxRadiusMeters.isFinite() && maxRadiusMeters > 0f) {
            maxRadiusMeters / cellSize
        } else {
            Float.POSITIVE_INFINITY
        }
        val edgeDistance = maxOf(
            hypot(observerX.toDouble(), observerY.toDouble()),
            hypot((grid.width - 1 - observerX).toDouble(), observerY.toDouble()),
            hypot(observerX.toDouble(), (grid.height - 1 - observerY).toDouble()),
            hypot(
                (grid.width - 1 - observerX).toDouble(),
                (grid.height - 1 - observerY).toDouble(),
            ),
        ).toFloat()
        val walkLimit = minOf(maxRadiusCells, edgeDistance)

        val samples = ArrayList<TerrainHorizonSample>(steps)
        for (step in 0 until steps) {
            val azimuth = step.toFloat() / steps * 360f
            // Azimuth 0 = north (decreasing row), 90 = east (increasing column).
            val dirX = sin(Math.toRadians(azimuth.toDouble())).toFloat()
            val dirY = -cos(Math.toRadians(azimuth.toDouble())).toFloat()
            var maxAngle = Float.NEGATIVE_INFINITY
            var horizonDistance = 0f
            var horizonElevation = 0f
            var lastValidDistance = 0f
            var lastValidAngle = Float.NEGATIVE_INFINITY
            var lastValidElevation = 0f

            var distance = 1f
            while (distance <= walkLimit) {
                val col = (observerX + dirX * distance).roundToInt()
                val row = (observerY + dirY * distance).roundToInt()
                if (col !in 0 until grid.width || row !in 0 until grid.height) break
                val index = row * grid.width + col
                if (grid.validData[index]) {
                    val elevation = grid.getElevationAt(col, row, vegetationFilter)
                    val angle = (elevation - eyeElevation) / (distance * cellSize)
                    if (angle > maxAngle) {
                        maxAngle = angle
                        horizonDistance = distance * cellSize
                        horizonElevation = elevation
                    }
                    lastValidDistance = distance * cellSize
                    lastValidAngle = angle
                    lastValidElevation = elevation
                }
                distance += 1f
            }

            samples += if (horizonDistance > 0f) {
                TerrainHorizonSample(
                    azimuthDegrees = azimuth,
                    elevationAngleDegrees = Math.toDegrees(atan(maxAngle.toDouble())).toFloat(),
                    distanceMeters = horizonDistance,
                    elevationMeters = horizonElevation,
                )
            } else {
                // Nothing rises above the eye line; report the farthest valid ground instead.
                TerrainHorizonSample(
                    azimuthDegrees = azimuth,
                    elevationAngleDegrees = if (lastValidAngle.isFinite()) {
                        Math.toDegrees(atan(lastValidAngle.toDouble())).toFloat()
                    } else {
                        0f
                    },
                    distanceMeters = lastValidDistance,
                    elevationMeters = lastValidElevation,
                )
            }
        }

        return TerrainHorizon(
            observerXPercent = observerXPercent.coerceIn(0f, 100f),
            observerYPercent = observerYPercent.coerceIn(0f, 100f),
            observerHeightMeters = observerHeightMeters,
            samples = samples,
        )
    }

    /**
     * True when the target cell is visible from the eye position. Intermediate cells block the
     * target when their elevation angle (rise over horizontal distance) meets or exceeds the
     * target's own angle. Invalid cells are transparent.
     */
    private fun isLineOfSightClear(
        grid: ElevationGrid,
        observerX: Float,
        observerY: Float,
        eyeElevation: Float,
        targetCol: Int,
        targetRow: Int,
        targetDistanceCells: Float,
        cellSize: Float,
        vegetationFilter: Float,
    ): Boolean {
        if (targetDistanceCells < 0.5f) return true
        val steps = ceil(
            maxOf(
                abs(targetCol - observerX).toDouble(),
                abs(targetRow - observerY).toDouble(),
            ),
        ).toInt().coerceAtLeast(1)
        val targetElevation = grid.getElevationAt(targetCol, targetRow, vegetationFilter)
        val targetAngle = (targetElevation - eyeElevation) / (targetDistanceCells * cellSize)

        var maxBlockingAngle = Float.NEGATIVE_INFINITY
        for (step in 1 until steps) {
            val fraction = step.toFloat() / steps
            val col = (observerX + (targetCol - observerX) * fraction).roundToInt()
                .coerceIn(0, grid.width - 1)
            val row = (observerY + (targetRow - observerY) * fraction).roundToInt()
                .coerceIn(0, grid.height - 1)
            val index = row * grid.width + col
            if (!grid.validData[index]) continue
            if (col == targetCol && row == targetRow) continue
            val distanceCells = hypot(
                (col - observerX).toDouble(),
                (row - observerY).toDouble(),
            ).toFloat()
            if (distanceCells < 0.5f || distanceCells >= targetDistanceCells) continue
            val angle = (grid.getElevationAt(col, row, vegetationFilter) - eyeElevation) /
                (distanceCells * cellSize)
            if (angle > maxBlockingAngle) maxBlockingAngle = angle
        }
        return targetAngle > maxBlockingAngle
    }
}
