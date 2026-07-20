package com.strobingn.findit.data.power

import com.strobingn.findit.data.model.AppSettings

/**
 * Battery + thermal knobs for long hunts (roadmap #7).
 * Callers should poll terrain / AR / location at [refreshIntervalMs].
 */
object BatteryOptimizer {

  fun refreshIntervalMs(settings: AppSettings): Long {
    return if (settings.lowPowerMode) {
      maxOf(settings.terrainRefreshMs * 3, 4_000L)
    } else {
      settings.terrainRefreshMs
    }
  }

  fun maxTerrainGridSide(settings: AppSettings): Int =
    if (settings.lowPowerMode) 32 else 64

  fun enableContinuousLocation(settings: AppSettings): Boolean = !settings.lowPowerMode

  fun arTargetFps(settings: AppSettings): Int = if (settings.lowPowerMode) 15 else 30
}
