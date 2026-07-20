package com.strobingn.findit.data.team

import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.data.model.TeamMember
import com.strobingn.findit.data.model.haversineM
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-point team visibility helpers (roadmap #6).
 * Flat-earth LOS stub: useful for open fields; terrain occlusion comes later
 * via DEM ray casting from Viewshade-style engine.
 */
object TeamVisibility {

  data class VisibilityEdge(
    val fromId: String,
    val toId: String,
    val distanceM: Double,
    val bearingDeg: Double,
    val likelyVisible: Boolean,
    val note: String,
  )

  fun pairwise(members: List<TeamMember>, maxRangeM: Double = 400.0): List<VisibilityEdge> {
    val edges = mutableListOf<VisibilityEdge>()
    for (i in members.indices) {
      for (j in i + 1 until members.size) {
        val a = members[i]
        val b = members[j]
        val d = haversineM(a.location.lat, a.location.lng, b.location.lat, b.location.lng)
        val bearing = bearingDeg(a.location, b.location)
        val visible = d <= maxRangeM
        edges +=
          VisibilityEdge(
            fromId = a.id,
            toId = b.id,
            distanceM = d,
            bearingDeg = bearing,
            likelyVisible = visible,
            note =
              if (visible) "${a.name} ↔ ${b.name}: open-field LOS likely (${d.toInt()} m)"
              else "${a.name} ↔ ${b.name}: beyond ${maxRangeM.toInt()} m range",
          )
      }
    }
    return edges
  }

  private fun bearingDeg(from: GeoPoint, to: GeoPoint): Double {
    val φ1 = Math.toRadians(from.lat)
    val φ2 = Math.toRadians(to.lat)
    val Δλ = Math.toRadians(to.lng - from.lng)
    val y = sin(Δλ) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    val θ = atan2(y, x)
    return (Math.toDegrees(θ) + 360.0) % 360.0
  }
}
