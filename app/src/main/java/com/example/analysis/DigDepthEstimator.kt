package com.example.analysis

import com.example.geospatial.MeasurementFormat
import kotlin.math.roundToInt

/**
 * Heuristic dig-depth band for a ranked target, read from the measured depression at the
 * target's own cell. This is a planning aid ("how deep should I expect to dig?"), not a
 * promise: LiDAR sees the surface bowl, not the artifacts inside it.
 *
 * Reasoning per type:
 *  - CELLAR_HOLE: fill and collapsed floor scatter sit inside the measured bowl, so the band
 *    starts near the surface and runs past the measured depth plus typical fill.
 *  - TRASH_PIT: pit contents compact below the rim; band centers on the measured depth.
 *  - FOUNDATION / OLD_HOMESITE: occupation layer is shallow; measured depression mostly says
 *    "how much humus and collapse sit on top".
 *  - ROAD_TRAIL / STONE_WALL: surface features - no meaningful dig band, returns null.
 */
data class DigDepthEstimate(
    val minCm: Int,
    val maxCm: Int,
    val basis: String,
) {
    /**
     * Read out in inches like every other length the app shows. The band itself stays in
     * centimetres because that is the unit its thresholds were tuned in; only the display
     * converts, as [MeasurementFormat] asks.
     */
    val label: String get() =
        "${MeasurementFormat.inchesValue(minCm / 100.0)}–${MeasurementFormat.inches(maxCm / 100.0)}"
}

object DigDepthEstimator {

    fun estimate(target: MetalDetectingTarget, layers: TerrainDerivedLayers): DigDepthEstimate? {
        val width = layers.width
        val height = layers.height
        if (width < 3 || height < 3) return null
        val cellX = (target.xPercent.coerceIn(0f, 100f) / 100f * (width - 1)).roundToInt()
        val cellY = (target.yPercent.coerceIn(0f, 100f) / 100f * (height - 1)).roundToInt()
        val depression = layers.values[TerrainDerivedLayer.DEPRESSION_DEPTH]
            ?.getOrNull(cellY * width + cellX)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: 0f
        val bowlCm = (depression * 100f).roundToInt()

        return when (target.type) {
            MetalDetectingTargetType.CELLAR_HOLE -> DigDepthEstimate(
                minCm = (bowlCm / 3).coerceAtLeast(5),
                maxCm = (bowlCm + 45).coerceAtLeast(40),
                basis = "measured bowl ${MeasurementFormat.inches(bowlCm / 100.0)} + collapsed-fill allowance",
            )
            MetalDetectingTargetType.TRASH_PIT -> DigDepthEstimate(
                minCm = (bowlCm / 2).coerceAtLeast(5),
                maxCm = (bowlCm + 30).coerceAtLeast(35),
                basis = "compaction around the measured ${MeasurementFormat.inches(bowlCm / 100.0)} pit",
            )
            MetalDetectingTargetType.FOUNDATION, MetalDetectingTargetType.OLD_HOMESITE ->
                DigDepthEstimate(
                    minCm = 5,
                    maxCm = (bowlCm / 2 + 20).coerceAtLeast(25),
                    basis = "shallow occupation layer under humus/collapse",
                )
            MetalDetectingTargetType.ROAD_TRAIL, MetalDetectingTargetType.STONE_WALL -> null
        }
    }
}
