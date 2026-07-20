package com.strobingn.findit.data.terrain

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Advanced terrain visualization helpers (roadmap #2, #8).
 *
 * Operates on a local DEM height grid (row-major, meters). Designed for
 * offline / on-device processing — no network required.
 */
object TerrainAnalyzer {

  data class TerrainMaps(
    val hillshade: FloatArray,
    val skyViewFactor: FloatArray,
    val openness: FloatArray,
    val disturbance: FloatArray,
    val width: Int,
    val height: Int,
  )

  /** Multi-style hillshade (default NW light). Values 0f..1f. */
  fun hillshade(
    dem: FloatArray,
    width: Int,
    height: Int,
    azimuthDeg: Double = 315.0,
    altitudeDeg: Double = 45.0,
    cellSizeM: Double = 1.0,
  ): FloatArray {
    val out = FloatArray(dem.size)
    val az = Math.toRadians(360.0 - azimuthDeg + 90.0)
    val alt = Math.toRadians(altitudeDeg)
    val zFactor = 1.0
    for (y in 1 until height - 1) {
      for (x in 1 until width - 1) {
        val a = dem[(y - 1) * width + (x - 1)].toDouble()
        val b = dem[(y - 1) * width + x].toDouble()
        val c = dem[(y - 1) * width + (x + 1)].toDouble()
        val d = dem[y * width + (x - 1)].toDouble()
        val f = dem[y * width + (x + 1)].toDouble()
        val g = dem[(y + 1) * width + (x - 1)].toDouble()
        val h = dem[(y + 1) * width + x].toDouble()
        val i = dem[(y + 1) * width + (x + 1)].toDouble()
        val dzdx = ((c + 2 * f + i) - (a + 2 * d + g)) / (8 * cellSizeM)
        val dzdy = ((g + 2 * h + i) - (a + 2 * b + c)) / (8 * cellSizeM)
        val slope = atan(zFactor * sqrt(dzdx * dzdx + dzdy * dzdy))
        val aspect =
          when {
            dzdx != 0.0 -> {
              var asp = atan2Safe(dzdy, -dzdx)
              if (asp < 0) asp += 2 * Math.PI
              asp
            }
            else -> if (dzdy > 0) Math.PI / 2 else if (dzdy < 0) 1.5 * Math.PI else 0.0
          }
        val hs =
          cos(alt) * cos(slope) +
            sin(alt) * sin(slope) * cos(az - aspect)
        out[y * width + x] = hs.toFloat().coerceIn(0f, 1f)
      }
    }
    return out
  }

  /**
   * Approximate Sky View Factor: fraction of sky visible from each cell.
   * Higher SVF = open; lower = depression / hollow (privies, cellars).
   */
  fun skyViewFactor(
    dem: FloatArray,
    width: Int,
    height: Int,
    radiusCells: Int = 8,
    directions: Int = 16,
  ): FloatArray {
    val out = FloatArray(dem.size) { 1f }
    for (y in 0 until height) {
      for (x in 0 until width) {
        val z0 = dem[y * width + x]
        var sum = 0.0
        for (d in 0 until directions) {
          val ang = 2 * Math.PI * d / directions
          val dx = cos(ang)
          val dy = sin(ang)
          var maxHorizon = 0.0
          for (r in 1..radiusCells) {
            val nx = (x + dx * r).toInt()
            val ny = (y + dy * r).toInt()
            if (nx !in 0 until width || ny !in 0 until height) break
            val dz = (dem[ny * width + nx] - z0).toDouble()
            val horizon = atan(dz / r)
            if (horizon > maxHorizon) maxHorizon = horizon
          }
          sum += 1.0 - maxHorizon / (Math.PI / 2)
        }
        out[y * width + x] = (sum / directions).toFloat().coerceIn(0f, 1f)
      }
    }
    return out
  }

  /** Positive openness-style metric (inverse of average horizon angle). */
  fun openness(
    dem: FloatArray,
    width: Int,
    height: Int,
    radiusCells: Int = 8,
    directions: Int = 8,
  ): FloatArray {
    val svf = skyViewFactor(dem, width, height, radiusCells, directions)
    // Openness emphasizes local free-line-of-sight similarly; remap for contrast.
    return FloatArray(svf.size) { i -> (svf[i] * 1.2f - 0.1f).coerceIn(0f, 1f) }
  }

  /**
   * Quick ground-disturbance detector (roadmap #8):
   * local residual vs neighborhood mean — highlights mounds / pits.
   */
  fun groundDisturbance(
    dem: FloatArray,
    width: Int,
    height: Int,
    window: Int = 5,
  ): FloatArray {
    val out = FloatArray(dem.size)
    val half = window / 2
    for (y in 0 until height) {
      for (x in 0 until width) {
        var sum = 0.0
        var n = 0
        for (yy in max(0, y - half)..min(height - 1, y + half)) {
          for (xx in max(0, x - half)..min(width - 1, x + half)) {
            sum += dem[yy * width + xx]
            n++
          }
        }
        val mean = sum / n
        val residual = dem[y * width + x] - mean
        out[y * width + x] = residual.toFloat()
      }
    }
    // Normalize to 0..1 by abs max
    var maxAbs = 1e-6f
    for (v in out) maxAbs = max(maxAbs, kotlin.math.abs(v))
    for (i in out.indices) {
      out[i] = ((out[i] / maxAbs) * 0.5f + 0.5f).coerceIn(0f, 1f)
    }
    return out
  }

  fun analyze(dem: FloatArray, width: Int, height: Int): TerrainMaps {
    require(dem.size == width * height)
    return TerrainMaps(
      hillshade = hillshade(dem, width, height),
      skyViewFactor = skyViewFactor(dem, width, height),
      openness = openness(dem, width, height),
      disturbance = groundDisturbance(dem, width, height),
      width = width,
      height = height,
    )
  }

  /** Synthetic micro-relief DEM for offline demos (mound + depression). */
  fun demoDem(width: Int = 64, height: Int = 64): FloatArray {
    val dem = FloatArray(width * height)
    val cx = width / 2f
    val cy = height / 2f
    for (y in 0 until height) {
      for (x in 0 until width) {
        val base = 100f + (x + y) * 0.02f
        val mound =
          2.5f *
            kotlin.math.exp(
              -((x - cx * 0.6f) * (x - cx * 0.6f) + (y - cy * 0.55f) * (y - cy * 0.55f)) / 40f,
            )
        val pit =
          -1.8f *
            kotlin.math.exp(
              -((x - cx * 1.3f) * (x - cx * 1.3f) + (y - cy * 1.2f) * (y - cy * 1.2f)) / 25f,
            )
        dem[y * width + x] = base + mound + pit
      }
    }
    return dem
  }

  private fun atan2Safe(y: Double, x: Double): Double = kotlin.math.atan2(y, x)
}
