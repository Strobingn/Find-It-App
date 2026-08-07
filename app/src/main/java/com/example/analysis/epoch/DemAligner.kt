package com.example.analysis.epoch

import com.example.data.ElevationGrid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Aligns / resamples epoch B elevation into epoch A's grid for two-epoch change detection.
 * Does not invent CRS transforms beyond same-size or bilinear resize of bare-earth arrays.
 */
object DemAligner {

    data class AlignResult(
        val alignedB: ElevationGrid?,
        val rmseMeters: Double?,
        val note: String,
        val confidence: AlignConfidence,
    )

    enum class AlignConfidence { GOOD, FAIR, POOR, FAILED }

    /**
     * If dimensions match, returns B as-is (optionally recentered by mean bias).
     * If dimensions differ, bilinear-resamples B bare-earth into A's width/height.
     */
    fun alignBToA(
        epochA: ElevationGrid,
        epochB: ElevationGrid,
        removeMeanBias: Boolean = true,
    ): AlignResult {
        if (epochA.width < 2 || epochA.height < 2 || epochB.width < 2 || epochB.height < 2) {
            return AlignResult(null, null, "Grids too small to align.", AlignConfidence.FAILED)
        }
        val sameSize = epochA.width == epochB.width && epochA.height == epochB.height
        val resampled = if (sameSize) {
            epochB
        } else {
            resampleBareEarth(epochB, epochA.width, epochA.height, epochA.cellSizeMeters)
        }

        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        val size = epochA.width * epochA.height
        for (i in 0 until size) {
            if (!epochA.validData.getOrElse(i) { true }) continue
            if (!resampled.validData.getOrElse(i) { true }) continue
            val d = resampled.bareEarth[i] - epochA.bareEarth[i]
            sum += d
            sumSq += d * d
            n++
        }
        if (n < 16) {
            return AlignResult(null, null, "Too few overlapping valid cells to align.", AlignConfidence.FAILED)
        }
        val mean = sum / n
        val rmse = kotlin.math.sqrt(sumSq / n)
        val adjusted = if (removeMeanBias) {
            val bare = FloatArray(size) { i ->
                resampled.bareEarth[i] - mean.toFloat()
            }
            ElevationGrid(
                width = epochA.width,
                height = epochA.height,
                bareEarth = bare,
                canopySpikes = FloatArray(size),
                cellSizeMeters = epochA.cellSizeMeters,
                validData = BooleanArray(size) { i ->
                    epochA.validData.getOrElse(i) { true } && resampled.validData.getOrElse(i) { true }
                },
            )
        } else {
            resampled
        }
        // After mean removal, residual RMSE estimate
        var sumSq2 = 0.0
        var n2 = 0
        for (i in 0 until size) {
            if (!adjusted.validData[i]) continue
            val d = adjusted.bareEarth[i] - epochA.bareEarth[i]
            sumSq2 += d * d
            n2++
        }
        val residualRmse = if (n2 > 0) kotlin.math.sqrt(sumSq2 / n2) else rmse
        val conf = when {
            residualRmse < 0.35 -> AlignConfidence.GOOD
            residualRmse < 0.9 -> AlignConfidence.FAIR
            residualRmse < 2.0 -> AlignConfidence.POOR
            else -> AlignConfidence.POOR
        }
        val sizeNote = if (sameSize) "same grid size" else "resampled ${epochB.width}x${epochB.height}→${epochA.width}x${epochA.height}"
        return AlignResult(
            alignedB = adjusted,
            rmseMeters = residualRmse,
            note = "Align $sizeNote · residual RMSE ${"%.2f".format(residualRmse)} m · mean bias removed=${removeMeanBias}",
            confidence = conf,
        )
    }

    fun resampleBareEarth(
        source: ElevationGrid,
        targetW: Int,
        targetH: Int,
        cellSizeMeters: Float,
    ): ElevationGrid {
        val bare = FloatArray(targetW * targetH)
        val valid = BooleanArray(targetW * targetH)
        val canopy = FloatArray(targetW * targetH)
        val sw = source.width
        val sh = source.height
        for (y in 0 until targetH) {
            val sy = (y + 0.5f) * sh / targetH - 0.5f
            val y0 = floorClamp(sy, sh - 1)
            val y1 = min(y0 + 1, sh - 1)
            val ty = sy - y0
            for (x in 0 until targetW) {
                val sx = (x + 0.5f) * sw / targetW - 0.5f
                val x0 = floorClamp(sx, sw - 1)
                val x1 = min(x0 + 1, sw - 1)
                val tx = sx - x0
                val v00 = source.bareEarth[y0 * sw + x0]
                val v10 = source.bareEarth[y0 * sw + x1]
                val v01 = source.bareEarth[y1 * sw + x0]
                val v11 = source.bareEarth[y1 * sw + x1]
                val top = v00 * (1 - tx) + v10 * tx
                val bot = v01 * (1 - tx) + v11 * tx
                bare[y * targetW + x] = top * (1 - ty) + bot * ty
                valid[y * targetW + x] =
                    source.validData.getOrElse(y0 * sw + x0) { true } &&
                        source.validData.getOrElse(y1 * sw + x1) { true }
            }
        }
        return ElevationGrid(
            width = targetW,
            height = targetH,
            bareEarth = bare,
            canopySpikes = canopy,
            cellSizeMeters = cellSizeMeters,
            validData = valid,
        )
    }

    private fun floorClamp(v: Float, maxIndex: Int): Int =
        max(0, min(maxIndex, kotlin.math.floor(v.toDouble()).toInt()))
}
