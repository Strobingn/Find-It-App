package com.example.data.field

import com.example.data.TargetSignal
import com.example.data.VerificationOutcome

/**
 * A group of field finds close enough together to treat as one site — a cellar hole scatter, a
 * home lot, a campsite. Derived on the fly from logged finds; nothing is persisted separately,
 * so sites always reflect the current find list.
 */
data class FindSite(
    /** Stable within one clustering run: sites are ordered by size, then by latitude. */
    val label: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val signals: List<TargetSignal>,
) {
    val confirmedCount: Int
        get() = signals.count { it.outcome == VerificationOutcome.CONFIRMED_FEATURE }

    val rejectedCount: Int
        get() = signals.count { it.outcome == VerificationOutcome.REJECTED_FALSE_POSITIVE }

    /** Most common find labels first, e.g. ["Iron Nail/Spike", "Silver Relic"]. */
    val topTypes: List<String>
        get() = signals.groupingBy { it.metalType.label }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }
}

/**
 * Groups logged finds into sites by geographic proximity. Single-linkage connected components:
 * two finds belong to the same site when a chain of finds connects them with each link shorter
 * than [DEFAULT_CLUSTER_RADIUS_METERS]. Deterministic — input order never changes the result.
 */
object FindSiteClusterer {
    const val DEFAULT_CLUSTER_RADIUS_METERS = 50f
    private const val MAX_LABELLED_SITES = 26

    fun cluster(
        signals: List<TargetSignal>,
        radiusMeters: Float = DEFAULT_CLUSTER_RADIUS_METERS,
    ): List<FindSite> {
        // Only finds with real WGS84 positions can be sited; grid-only finds are skipped.
        val positioned = signals.filter { it.latitude != null && it.longitude != null }
        if (positioned.isEmpty()) return emptyList()

        // Union-find over pairs within the radius.
        val parent = IntArray(positioned.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parent[current] != current) {
                parent[current] = parent[parent[current]]
                current = parent[current]
            }
            return current
        }
        fun union(first: Int, second: Int) {
            val rootFirst = root(first)
            val rootSecond = root(second)
            if (rootFirst != rootSecond) parent[maxOf(rootFirst, rootSecond)] = minOf(rootFirst, rootSecond)
        }

        val count = positioned.size
        // Pre-extract primitive coordinates to avoid repeated unboxing and non-null checks.
        val lats = DoubleArray(count) { positioned[it].latitude!! }
        val lons = DoubleArray(count) { positioned[it].longitude!! }

        // Bounding-box fast reject: 1 degree of latitude is ~111,195 m.
        // If |lat1 - lat2| exceeds this conservative threshold, the great-circle distance
        // is guaranteed to exceed radiusMeters, skipping the expensive haversine computation.
        val maxLatDelta = radiusMeters / 111_000.0

        for (first in 0 until count) {
            val lat1 = lats[first]
            val lon1 = lons[first]
            for (second in first + 1 until count) {
                val lat2 = lats[second]
                val dLat = if (lat1 > lat2) lat1 - lat2 else lat2 - lat1
                if (dLat > maxLatDelta) continue

                val distance = FieldNavigation.distanceMeters(lat1, lon1, lat2, lons[second])
                if (distance <= radiusMeters) union(first, second)
            }
        }

        val groups = positioned.indices.groupBy { root(it) }

        // Precompute group metrics in a single pass to eliminate intermediate collection allocations
        // from repeated .map { ... }.average() during sorting and centroid computation.
        class GroupSummary(
            val members: List<Int>,
            val centerLat: Double,
            val centerLon: Double,
        )

        val summaries = groups.values.map { memberIndices ->
            var sumLat = 0.0
            var sumLon = 0.0
            val size = memberIndices.size
            for (idx in memberIndices) {
                sumLat += lats[idx]
                sumLon += lons[idx]
            }
            GroupSummary(
                members = memberIndices,
                centerLat = sumLat / size,
                centerLon = sumLon / size,
            )
        }

        val ordered = summaries.sortedWith(
            compareByDescending<GroupSummary> { it.members.size }
                .thenBy { it.centerLat },
        )

        return ordered.mapIndexed { index, summary ->
            val members = ArrayList<TargetSignal>(summary.members.size)
            for (idx in summary.members) {
                members.add(positioned[idx])
            }
            members.sortBy { it.id }

            FindSite(
                label = if (index < MAX_LABELLED_SITES) {
                    "Site ${'A' + index}"
                } else {
                    "Site ${index + 1}"
                },
                centerLatitude = summary.centerLat,
                centerLongitude = summary.centerLon,
                signals = members,
            )
        }
    }
}
