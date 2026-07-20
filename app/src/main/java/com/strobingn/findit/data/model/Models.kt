package com.strobingn.findit.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Metal type categories hunters commonly log. */
@Serializable
enum class MetalType {
  UNKNOWN,
  COIN,
  RELIC,
  JEWELRY,
  IRON_JUNK,
  GOLD,
  SILVER,
  COPPER,
  OTHER,
}

@Serializable
data class GeoPoint(
  val lat: Double,
  val lng: Double,
  val elevM: Double? = null,
)

/** Structured find log entry — replaces paper notes (roadmap #3). */
@Serializable
data class FindRecord(
  val id: String = UUID.randomUUID().toString(),
  val title: String,
  val metalType: MetalType = MetalType.UNKNOWN,
  val depthInches: Double? = null,
  val notes: String = "",
  val photoUri: String? = null,
  val location: GeoPoint,
  val detectedAtEpochMs: Long = System.currentTimeMillis(),
  val huntSessionId: String? = null,
  val signalId: String? = null,
)

/** Search grid for fields / beaches (roadmap #4). */
@Serializable
data class SearchGrid(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val sw: GeoPoint,
  val ne: GeoPoint,
  val cellSizeMeters: Double = 5.0,
  val coveredCellIds: Set<String> = emptySet(),
  val createdAtEpochMs: Long = System.currentTimeMillis(),
) {
  fun rowCount(): Int {
    val heightM = haversineM(sw.lat, sw.lng, ne.lat, sw.lng)
    return (heightM / cellSizeMeters).toInt().coerceAtLeast(1)
  }

  fun colCount(): Int {
    val widthM = haversineM(sw.lat, sw.lng, sw.lat, ne.lng)
    return (widthM / cellSizeMeters).toInt().coerceAtLeast(1)
  }

  fun cellId(row: Int, col: Int): String = "$row,$col"
}

/** Partner location for multi-point team visibility (roadmap #6). */
@Serializable
data class TeamMember(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val location: GeoPoint,
  val observerHeightM: Double = 1.7,
)

/** Historical / terrain overlay layers (roadmap #1, #2). */
@Serializable
enum class OverlayKind {
  HISTORICAL_TOPO,
  SANBORN,
  HISTORICAL_IMAGERY,
  HILLSHADE,
  SKY_VIEW_FACTOR,
  OPENNESS,
  GROUND_DISTURBANCE,
}

@Serializable
data class OverlayLayer(
  val id: String,
  val name: String,
  val kind: OverlayKind,
  val description: String,
  val enabled: Boolean = false,
  /** Optional tile template or local asset path; empty = synthetic/demo. */
  val sourceUrl: String = "",
)

@Serializable
data class AppSettings(
  val lowPowerMode: Boolean = false,
  val terrainRefreshMs: Long = 1_500L,
  val cacheTiles: Boolean = true,
  val preferImperial: Boolean = true,
  val showTeamOnMap: Boolean = true,
  /** Keep map bounds around the live GPS fix. */
  val followMeOnMap: Boolean = true,
  /** Current hunt session id stamped onto new finds. */
  val activeHuntSessionId: String? = null,
  val activeHuntSessionName: String? = null,
  /** Shared Oracle backend (same host as Viewshade). */
  val useCloudBackend: Boolean = true,
  val backendUrl: String = "http://129.80.174.236:8000",
)

/** Optional hunt session wrapper (groups finds from one outing). */
@Serializable
data class HuntSession(
  val id: String = java.util.UUID.randomUUID().toString(),
  val name: String,
  val startedAtEpochMs: Long = System.currentTimeMillis(),
  val endedAtEpochMs: Long? = null,
)

/** Haversine distance in meters. */
fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
  val r = 6_371_000.0
  val dLat = Math.toRadians(lat2 - lat1)
  val dLng = Math.toRadians(lng2 - lng1)
  val a =
    kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
      kotlin.math.cos(Math.toRadians(lat1)) *
        kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLng / 2) *
        kotlin.math.sin(dLng / 2)
  return 2 * r * kotlin.math.asin(kotlin.math.sqrt(a))
}
