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

        val n = positioned.size
        // Pre-extract primitive coordinates to avoid boxing and redundant null checks in the O(N^2) loop.
        val lats = DoubleArray(n) { positioned[it].latitude!! }
        val lons = DoubleArray(n) { positioned[it].longitude!! }

        // Union-find over pairs within the radius with two-pass full path compression.
        val parent = IntArray(n) { it }
        fun root(index: Int): Int {
            var r = index
            while (parent[r] != r) r = parent[r]
            var curr = index
            while (curr != r) {
                val next = parent[curr]
                parent[curr] = r
                curr = next
            }
            return r
        }
        fun union(first: Int, second: Int) {
            val rootFirst = root(first)
            val rootSecond = root(second)
            if (rootFirst != rootSecond) {
                // Keep the smaller index as root to preserve deterministic component identifiers
                parent[maxOf(rootFirst, rootSecond)] = minOf(rootFirst, rootSecond)
            }
        }

        // 1 deg latitude ≈ 111,000 m. Any pair with |lat1 - lat2| * 110_000 > radiusMeters
        // cannot possibly be within radiusMeters, allowing us to skip expensive haversine math.
        val maxLatDelta = radiusMeters / 110_000.0

        for (first in 0 until n) {
            val latA = lats[first]
            val lonA = lons[first]
            for (second in first + 1 until n) {
                val latB = lats[second]
                val dLat = if (latA > latB) latA - latB else latB - latA
                if (dLat > maxLatDelta) continue

                val distance = FieldNavigation.distanceMeters(latA, lonA, latB, lons[second])
                if (distance <= radiusMeters) union(first, second)
            }
        }

        val groups = (0 until n).groupBy { root(it) }.values

        // Compute group metrics in a single pass per group without intermediate allocations.
        class GroupSummary(
            val group: List<Int>,
            val centerLat: Double,
            val centerLon: Double,
        )

        val summaries = groups.map { group ->
            var sumLat = 0.0
            var sumLon = 0.0
            for (idx in group) {
                sumLat += lats[idx]
                sumLon += lons[idx]
            }
            val size = group.size
            GroupSummary(
                group = group,
                centerLat = sumLat / size,
                centerLon = sumLon / size,
            )
        }

        val ordered = summaries.sortedWith(
            compareByDescending<GroupSummary> { it.group.size }.thenBy { it.centerLat },
        )

        return ordered.mapIndexed { index, summary ->
            val members = summary.group.map { positioned[it] }
            FindSite(
                label = if (index < MAX_LABELLED_SITES) {
                    "Site ${'A' + index}"
                } else {
                    "Site ${index + 1}"
                },
                centerLatitude = summary.centerLat,
                centerLongitude = summary.centerLon,
                signals = members.sortedBy { it.id },
            )
        }
    }
}
