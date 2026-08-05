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

        for (first in positioned.indices) {
            val a = positioned[first]
            for (second in first + 1 until positioned.size) {
                val b = positioned[second]
                val distance = FieldNavigation.distanceMeters(
                    a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!,
                )
                if (distance <= radiusMeters) union(first, second)
            }
        }

        val groups = positioned.indices.groupBy { root(it) }.values
        val ordered = groups.sortedWith(
            compareByDescending<List<Int>> { it.size }.thenBy { group ->
                group.map { positioned[it].latitude!! }.average()
            },
        )
        return ordered.mapIndexed { index, group ->
            val members = group.map { positioned[it] }
            FindSite(
                label = if (index < MAX_LABELLED_SITES) {
                    "Site ${'A' + index}"
                } else {
                    "Site ${index + 1}"
                },
                centerLatitude = members.map { it.latitude!! }.average(),
                centerLongitude = members.map { it.longitude!! }.average(),
                signals = members.sortedBy { it.id },
            )
        }
    }
}
