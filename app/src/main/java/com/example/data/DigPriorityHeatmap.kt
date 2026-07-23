package com.example.data

import kotlin.math.sqrt

private const val EXCAVATED_STATUS = "Excavated"
private const val TRASH_STATUS = "Trash"
private const val SPLAT_RADIUS_CELLS = 1

/**
 * Bins unexcavated [signals] into a [bins] x [bins] density grid (row-major, 0..1 normalized) so
 * a "dig priority" heatmap can be drawn over the terrain without touching the elevation-rendering
 * pipeline. Excavated and trash finds don't need attention, so they're excluded from the density.
 */
fun computeDigPriorityHeatmap(signals: List<TargetSignal>, bins: Int = 24): FloatArray {
    require(bins > 0) { "bins must be positive" }
    val density = FloatArray(bins * bins)
    val unexcavated = signals.filter { it.status != EXCAVATED_STATUS && it.status != TRASH_STATUS }
    if (unexcavated.isEmpty()) return density

    val cellSize = 100f / bins
    for (signal in unexcavated) {
        val centerX = (signal.gridX.coerceIn(0f, 99.999f) / cellSize).toInt()
        val centerY = (signal.gridY.coerceIn(0f, 99.999f) / cellSize).toInt()
        for (dy in -SPLAT_RADIUS_CELLS..SPLAT_RADIUS_CELLS) {
            val y = centerY + dy
            if (y !in 0 until bins) continue
            for (dx in -SPLAT_RADIUS_CELLS..SPLAT_RADIUS_CELLS) {
                val x = centerX + dx
                if (x !in 0 until bins) continue
                val distance = sqrt((dx * dx + dy * dy).toFloat())
                val weight = (1f - distance / (SPLAT_RADIUS_CELLS + 1)).coerceAtLeast(0f)
                density[y * bins + x] += weight
            }
        }
    }
    val maxDensity = density.max()
    if (maxDensity <= 0f) return density
    for (i in density.indices) density[i] = (density[i] / maxDensity).coerceIn(0f, 1f)
    return density
}
