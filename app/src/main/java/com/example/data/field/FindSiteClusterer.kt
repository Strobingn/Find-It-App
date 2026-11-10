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

        val count = positioned.size
        // Pre-extract primitive coordinates to avoid repeated property lookups, null checks,
        // and unboxing of Double? inside the O(N^2) pairwise distance comparison loops.
        val lats = DoubleArray(count) { positioned[it].latitude!! }
        val lons = DoubleArray(count) { positioned[it].longitude!! }

        // Union-find over pairs within the radius with full path compression.
        val parent = IntArray(count) { it }
        fun root(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var current = index
            while (parent[current] != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }
        fun union(first: Int, second: Int) {
            val rootFirst = root(first)
            val rootSecond = root(second)
            if (rootFirst != rootSecond) parent[maxOf(rootFirst, rootSecond)] = minOf(rootFirst, rootSecond)
        }

        for (first in 0 until count) {
            val lat1 = lats[first]
            val lon1 = lons[first]
            for (second in first + 1 until count) {
                val distance = FieldNavigation.distanceMeters(
                    lat1, lon1, lats[second], lons[second],
                )
                if (distance <= radiusMeters) union(first, second)
            }
        }

        // Group members by cluster root without chained collection allocations.
        // LinkedHashMap preserves encounter order for tie-breaking determinism.
        val groups = LinkedHashMap<Int, ArrayList<Int>>()
        for (i in 0 until count) {
            val r = root(i)
            var list = groups[r]
            if (list == null) {
                list = ArrayList()
                groups[r] = list
            }
            list.add(i)
        }

        // Single-pass computation of group metrics (sumLat, sumLon) avoids intermediate
        // List<Double> allocations and multiple collection traversals.
        class ClusterGroup(
            val members: ArrayList<Int>,
            val sumLat: Double,
            val sumLon: Double,
        ) {
            val size: Int get() = members.size
            val avgLat: Double get() = sumLat / size
            val avgLon: Double get() = sumLon / size
        }

        val clusterGroups = ArrayList<ClusterGroup>(groups.size)
        for (members in groups.values) {
            var sumLat = 0.0
            var sumLon = 0.0
            for (idx in members) {
                sumLat += lats[idx]
                sumLon += lons[idx]
            }
            clusterGroups.add(ClusterGroup(members, sumLat, sumLon))
        }

        // Sort by size descending, then by average latitude ascending.
        clusterGroups.sortWith(
            compareByDescending<ClusterGroup> { it.size }.thenBy { it.avgLat },
        )

        val result = ArrayList<FindSite>(clusterGroups.size)
        for (index in clusterGroups.indices) {
            val cg = clusterGroups[index]
            val memberSignals = ArrayList<TargetSignal>(cg.size)
            for (idx in cg.members) {
                memberSignals.add(positioned[idx])
            }
            memberSignals.sortBy { it.id }

            result.add(
                FindSite(
                    label = if (index < MAX_LABELLED_SITES) {
                        "Site ${'A' + index}"
                    } else {
                        "Site ${index + 1}"
                    },
                    centerLatitude = cg.avgLat,
                    centerLongitude = cg.avgLon,
                    signals = memberSignals,
                ),
            )
        }
        return result
    }
}
