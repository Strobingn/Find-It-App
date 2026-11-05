package com.example.data.field

import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import kotlin.math.abs
import kotlin.math.cos

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
        val count = signals.count { it.latitude != null && it.longitude != null }
        if (count == 0) return emptyList()

        // Extract primitives upfront to avoid repeated nullable unboxing and property dereferences.
        val positioned = ArrayList<TargetSignal>(count)
        val lats = DoubleArray(count)
        val lons = DoubleArray(count)
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var idx = 0
        for (signal in signals) {
            val lat = signal.latitude
            val lon = signal.longitude
            if (lat != null && lon != null) {
                positioned.add(signal)
                lats[idx] = lat
                lons[idx] = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                idx++
            }
        }

        // Bounding box delta threshold in degrees to prune pairs before expensive Haversine trigonometric calls:
        // 1 degree latitude is ~111,320 m everywhere on Earth (conservative lower bound 110,000 m).
        // 1 degree longitude is ~111,320 * cos(latitude) m.
        // To ensure maxDegLon is a safe conservative bound across all points in the set,
        // use the minimum cos(lat), which corresponds to the maximum absolute latitude.
        val maxDegLat = radiusMeters / 110_000.0
        val maxAbsLat = maxOf(abs(minLat), abs(maxLat)).coerceAtMost(89.0)
        val minCosLat = cos(Math.toRadians(maxAbsLat)).coerceAtLeast(0.01)
        val maxDegLon = radiusMeters / (110_000.0 * minCosLat)

        // Union-find over pairs within the radius.
        val parent = IntArray(count) { it }
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

        for (first in 0 until count) {
            val latA = lats[first]
            val lonA = lons[first]
            for (second in first + 1 until count) {
                // Quick bounding-box check in degrees prunes >90% of pairwise distance computations.
                if (abs(latA - lats[second]) > maxDegLat) continue
                if (abs(lonA - lons[second]) > maxDegLon) continue

                val distance = FieldNavigation.distanceMeters(
                    latA, lonA, lats[second], lons[second],
                )
                if (distance <= radiusMeters) union(first, second)
            }
        }

        // Group members by root and precalculate group size and average latitude in a single pass,
        // avoiding O(G log G * M) temporary allocations during sorting.
        val groups = (0 until count).groupBy { root(it) }

        class GroupSummary(
            val members: List<Int>,
            val size: Int,
            val avgLat: Double,
            val avgLon: Double,
        )

        val summaries = groups.values.map { memberIndices ->
            var sumLat = 0.0
            var sumLon = 0.0
            val memberSize = memberIndices.size
            for (i in 0 until memberSize) {
                val memberIdx = memberIndices[i]
                sumLat += lats[memberIdx]
                sumLon += lons[memberIdx]
            }
            GroupSummary(
                members = memberIndices,
                size = memberSize,
                avgLat = sumLat / memberSize,
                avgLon = sumLon / memberSize,
            )
        }

        val ordered = summaries.sortedWith(
            compareByDescending<GroupSummary> { it.size }.thenBy { it.avgLat },
        )

        return ordered.mapIndexed { index, summary ->
            val memberSignals = ArrayList<TargetSignal>(summary.size)
            for (i in 0 until summary.size) {
                memberSignals.add(positioned[summary.members[i]])
            }
            memberSignals.sortBy { it.id }

            FindSite(
                label = if (index < MAX_LABELLED_SITES) {
                    "Site ${'A' + index}"
                } else {
                    "Site ${index + 1}"
                },
                centerLatitude = summary.avgLat,
                centerLongitude = summary.avgLon,
                signals = memberSignals,
            )
        }
    }
}
