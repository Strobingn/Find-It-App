package com.example.data.field

/** A field target to visit, identified by its stable target id and GPS position. */
data class FieldWaypoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val displayName: String,
)

data class OptimizedFieldRoute(
    val waypoints: List<FieldWaypoint>,
    val totalDistanceMeters: Double,
)

/**
 * Orders field targets into a short walking route. A nearest-neighbor pass from the current
 * position builds an initial order, then bounded 2-opt reversal passes remove crossings.
 * Distances are haversine via [FieldNavigation], so the route is symmetric and deterministic.
 * Every input waypoint appears exactly once in the output — optimization never drops a target.
 */
object TargetRouteOptimizer {
    private const val MAX_2_OPT_PASSES = 25
    private const val MIN_IMPROVEMENT_METERS = 0.5

    fun optimize(
        waypoints: List<FieldWaypoint>,
        startLatitude: Double? = null,
        startLongitude: Double? = null,
    ): OptimizedFieldRoute {
        if (waypoints.size <= 2) {
            return OptimizedFieldRoute(
                waypoints = waypoints,
                totalDistanceMeters = totalDistanceMeters(waypoints, startLatitude, startLongitude),
            )
        }

        val remaining = waypoints.toMutableList()
        val ordered = ArrayList<FieldWaypoint>(waypoints.size)
        var anchorLatitude = startLatitude
        var anchorLongitude = startLongitude
        while (remaining.isNotEmpty()) {
            val next = if (anchorLatitude != null && anchorLongitude != null) {
                remaining.minByOrNull {
                    FieldNavigation.distanceMeters(anchorLatitude, anchorLongitude, it.latitude, it.longitude)
                } ?: remaining.first()
            } else {
                remaining.first()
            }
            remaining.remove(next)
            ordered += next
            anchorLatitude = next.latitude
            anchorLongitude = next.longitude
        }

        var improved = true
        var passes = 0
        while (improved && passes < MAX_2_OPT_PASSES) {
            improved = false
            passes++
            for (i in 0 until ordered.size - 1) {
                for (j in i + 1 until ordered.size) {
                    val gain = reversalGainMeters(ordered, i, j, startLatitude, startLongitude)
                    if (gain > MIN_IMPROVEMENT_METERS) {
                        ordered.subList(i, j + 1).reverse()
                        improved = true
                    }
                }
            }
        }

        return OptimizedFieldRoute(
            waypoints = ordered,
            totalDistanceMeters = totalDistanceMeters(ordered, startLatitude, startLongitude),
        )
    }

    fun totalDistanceMeters(
        ordered: List<FieldWaypoint>,
        startLatitude: Double? = null,
        startLongitude: Double? = null,
    ): Double {
        var total = 0.0
        var previousLatitude = startLatitude
        var previousLongitude = startLongitude
        for (waypoint in ordered) {
            val fromLatitude = previousLatitude
            val fromLongitude = previousLongitude
            if (fromLatitude != null && fromLongitude != null) {
                total += FieldNavigation.distanceMeters(
                    fromLatitude,
                    fromLongitude,
                    waypoint.latitude,
                    waypoint.longitude,
                )
            }
            previousLatitude = waypoint.latitude
            previousLongitude = waypoint.longitude
        }
        return total
    }

    /**
     * Distance saved by reversing the segment from index i to j of an open path. Internal edges
     * keep their length (haversine is symmetric), so only the two boundary edges change.
     */
    private fun reversalGainMeters(
        route: List<FieldWaypoint>,
        i: Int,
        j: Int,
        startLatitude: Double?,
        startLongitude: Double?,
    ): Double {
        val first = route[i]
        val last = route[j]
        val before = route.getOrNull(i - 1)
        val after = route.getOrNull(j + 1)
        var oldDistance = 0.0
        var newDistance = 0.0
        if (before != null) {
            oldDistance += FieldNavigation.distanceMeters(
                before.latitude, before.longitude, first.latitude, first.longitude,
            )
            newDistance += FieldNavigation.distanceMeters(
                before.latitude, before.longitude, last.latitude, last.longitude,
            )
        } else if (startLatitude != null && startLongitude != null) {
            oldDistance += FieldNavigation.distanceMeters(
                startLatitude, startLongitude, first.latitude, first.longitude,
            )
            newDistance += FieldNavigation.distanceMeters(
                startLatitude, startLongitude, last.latitude, last.longitude,
            )
        }
        if (after != null) {
            oldDistance += FieldNavigation.distanceMeters(
                last.latitude, last.longitude, after.latitude, after.longitude,
            )
            newDistance += FieldNavigation.distanceMeters(
                first.latitude, first.longitude, after.latitude, after.longitude,
            )
        }
        return oldDistance - newDistance
    }
}
