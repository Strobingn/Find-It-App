package com.example.analysis.epoch

import com.example.data.ElevationGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bare-earth surface change between two co-aligned grids (epoch B − epoch A after align).
 * Reports relative Z change only — never dig depth to metal.
 */
object SurfaceChangeDetector {

    data class ChangeZone(
        val id: Int,
        val xPercent: Float,
        val yPercent: Float,
        val meanAbsDeltaMeters: Float,
        val meanDeltaMeters: Float,
        val cellCount: Int,
        val areaMetersApprox: Float,
    )

    data class Result(
        val residual: FloatArray,
        val width: Int,
        val height: Int,
        val zones: List<ChangeZone>,
        val thresholdMeters: Float,
        val changedCellFraction: Float,
        val note: String,
        val honestyLine: String =
            "Relative bare-earth ΔZ only — LiDAR does not prove buried metal, age, or dig depth.",
    )

    /**
     * @param thresholdMeters cells with |ΔZ| above this count as changed (default 0.4 m)
     */
    fun detect(
        epochA: ElevationGrid,
        alignedB: ElevationGrid,
        thresholdMeters: Float = 0.4f,
        maxZones: Int = 24,
        blockSize: Int = 8,
    ): Result {
        require(epochA.width == alignedB.width && epochA.height == alignedB.height) {
            "Grids must be same size — run DemAligner first"
        }
        val w = epochA.width
        val h = epochA.height
        val residual = FloatArray(w * h)
        var changed = 0
        var valid = 0
        for (i in residual.indices) {
            if (!epochA.validData.getOrElse(i) { true } || !alignedB.validData.getOrElse(i) { true }) {
                residual[i] = 0f
                continue
            }
            valid++
            val d = alignedB.bareEarth[i] - epochA.bareEarth[i]
            residual[i] = d
            if (abs(d) >= thresholdMeters) changed++
        }
        val frac = if (valid > 0) changed.toFloat() / valid else 0f
        val zones = extractZones(
            residual = residual,
            w = w,
            h = h,
            validA = epochA.validData,
            validB = alignedB.validData,
            threshold = thresholdMeters,
            cellSize = epochA.cellSizeMeters,
            blockSize = blockSize.coerceIn(4, 32),
            maxZones = maxZones,
        )
        return Result(
            residual = residual,
            width = w,
            height = h,
            zones = zones,
            thresholdMeters = thresholdMeters,
            changedCellFraction = frac,
            note = "Changed cells ${(frac * 100f).toInt()}% · threshold ${thresholdMeters} m · ${zones.size} zone(s)",
        )
    }

    private fun extractZones(
        residual: FloatArray,
        w: Int,
        h: Int,
        validA: BooleanArray,
        validB: BooleanArray,
        threshold: Float,
        cellSize: Float,
        blockSize: Int,
        maxZones: Int,
    ): List<ChangeZone> {
        val bw = (w + blockSize - 1) / blockSize
        val bh = (h + blockSize - 1) / blockSize
        data class Block(val bx: Int, val by: Int, val meanAbs: Float, val mean: Float, val n: Int)
        val blocks = ArrayList<Block>()
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                var sumAbs = 0.0
                var sum = 0.0
                var n = 0
                val x0 = bx * blockSize
                val y0 = by * blockSize
                val x1 = min(w, x0 + blockSize)
                val y1 = min(h, y0 + blockSize)
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        val i = y * w + x
                        if (!validA.getOrElse(i) { true } || !validB.getOrElse(i) { true }) continue
                        val d = residual[i]
                        if (abs(d) < threshold) continue
                        sumAbs += abs(d)
                        sum += d
                        n++
                    }
                }
                if (n >= 3) {
                    blocks += Block(bx, by, (sumAbs / n).toFloat(), (sum / n).toFloat(), n)
                }
            }
        }
        return blocks
            .sortedByDescending { it.meanAbs * it.n }
            .take(maxZones)
            .mapIndexed { index, b ->
                val cx = (b.bx + 0.5f) * blockSize
                val cy = (b.by + 0.5f) * blockSize
                ChangeZone(
                    id = index + 1,
                    xPercent = (cx / (w - 1).coerceAtLeast(1)) * 100f,
                    yPercent = (cy / (h - 1).coerceAtLeast(1)) * 100f,
                    meanAbsDeltaMeters = b.meanAbs,
                    meanDeltaMeters = b.mean,
                    cellCount = b.n,
                    areaMetersApprox = b.n * cellSize * cellSize,
                )
            }
    }

    /** Greyscale ARGB pixels for residual visualization (mid-grey = 0). */
    fun residualToGreyscaleArgb(
        residual: FloatArray,
        maxAbsDisplay: Float = 2f,
    ): IntArray {
        val scale = maxAbsDisplay.coerceAtLeast(0.1f)
        return IntArray(residual.size) { i ->
            val t = ((residual[i] / scale) + 1f) / 2f
            val g = (t.coerceIn(0f, 1f) * 255f).toInt()
            0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
        }
    }
}
