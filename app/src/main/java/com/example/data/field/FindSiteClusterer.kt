package com.example.data.field

import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import kotlin.math.PI
import kotlin.math.abs

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
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    private class SiteGroupSummary(
        val size: Int,
        val centerLat: Double,
        val centerLon: Double,
        val members: List<TargetSignal>,
    )

    fun cluster(
        signals: List<TargetSignal>,
        radiusMeters: Float = DEFAULT_CLUSTER_RADIUS_METERS,
    ): List<FindSite> {
        // Only finds with real WGS84 positions can be sited; grid-only finds are skipped.
        val positioned = signals.filter { it.latitude != null && it.longitude != null }
        if (positioned.isEmpty()) return emptyList()

        val n = positioned.size
        // Pre-extract primitive arrays to avoid repeated boxing / property accesses on the hot path
        val lats = DoubleArray(n) { positioned[it].latitude!! }
        val lons = DoubleArray(n) { positioned[it].longitude!! }

        // Maximum delta latitude in degrees for the given radius:
        // arcDistance >= |lat1 - lat2|, so if |lat1 - lat2| > maxLatDeltaDeg, the points cannot be within radius.
        val maxLatDeltaDeg = (radiusMeters / EARTH_RADIUS_METERS) * (180.0 / PI)

        // Union-find over pairs within the radius.
        val parent = IntArray(n) { it }
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

        // Fast bounding-box check: skips expensive Haversine computation (sin/cos/atan2/sqrt)
        // for candidate pairs whose latitude difference alone exceeds the search radius.
        for (first in 0 until n) {
            val latA = lats[first]
            val lonA = lons[first]
            for (second in first + 1 until n) {
                if (abs(latA - lats[second]) > maxLatDeltaDeg) continue
                val distance = FieldNavigation.distanceMeters(
                    latA, lonA, lats[second], lons[second],
                )
                if (distance <= radiusMeters) union(first, second)
            }
        }

        val groups = (0 until n).groupBy { root(it) }.values

        // Single pass per group: precompute center coordinates and sort member signals once,
        // avoiding repeated list transformations and allocations inside sortedWith's comparator.
        val siteSummaries = groups.map { groupIndices ->
            var sumLat = 0.0
            var sumLon = 0.0
            val memberSignals = ArrayList<TargetSignal>(groupIndices.size)
            for (idx in groupIndices) {
                sumLat += lats[idx]
                sumLon += lons[idx]
                memberSignals.add(positioned[idx])
            }
            memberSignals.sortBy { it.id }
            val count = groupIndices.size
            SiteGroupSummary(
                size = count,
                centerLat = sumLat / count,
                centerLon = sumLon / count,
                members = memberSignals,
            )
        }

        val ordered = siteSummaries.sortedWith(
            compareByDescending<SiteGroupSummary> { it.size }.thenBy { it.centerLat },
        )

        return ordered.mapIndexed { index, summary ->
            FindSite(
                label = if (index < MAX_LABELLED_SITES) {
                    "Site ${'A' + index}"
                } else {
                    "Site ${index + 1}"
                },
                centerLatitude = summary.centerLat,
                centerLongitude = summary.centerLon,
                signals = summary.members,
            )
        }
    }
}
