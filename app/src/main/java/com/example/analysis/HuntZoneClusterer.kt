package com.example.analysis

import kotlin.math.sqrt

/**
 * Groups ranked targets that lie within [epsilonMeters] of each other into "hunt zones":
 * one cellar hole, its trash pit, and a nearby foundation are usually ONE site, and the
 * field visit should be planned around the zone, not around three separate pins.
 *
 * Zones are scored by their best target so the review list stays ordered by site value.
 */
data class HuntZone(
    val id: Int,
    val centerXPercent: Float,
    val centerYPercent: Float,
    val targetCount: Int,
    val bestScore: Float,
    val dominantType: MetalDetectingTargetType,
    /** Largest pairwise target spacing inside the zone - roughly how far you walk it. */
    val spanMeters: Float,
    /** Member targets, best score first. */
    val targets: List<MetalDetectingTarget> = emptyList(),
)

object HuntZoneClusterer {

    const val DEFAULT_EPSILON_METERS = 40f

    fun cluster(
        targets: List<MetalDetectingTarget>,
        layers: TerrainDerivedLayers,
        epsilonMeters: Float = DEFAULT_EPSILON_METERS,
    ): List<HuntZone> {
        if (targets.isEmpty()) return emptyList()
        val gridWidthMeters = layers.width * layers.cellSizeMeters
        val gridHeightMeters = layers.height * layers.cellSizeMeters
        if (gridWidthMeters <= 0f || gridHeightMeters <= 0f || epsilonMeters <= 0f) return emptyList()

        val xs = FloatArray(targets.size) { targets[it].xPercent / 100f * gridWidthMeters }
        val ys = FloatArray(targets.size) { targets[it].yPercent / 100f * gridHeightMeters }

        val parent = IntArray(targets.size) { it }
        fun find(i: Int): Int {
            var root = i
            while (parent[root] != root) root = parent[root]
            var cur = i
            while (parent[cur] != root) {
                val next = parent[cur]
                parent[cur] = root
                cur = next
            }
            return root
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[rb] = ra
        }

        val epsilonSq = epsilonMeters * epsilonMeters
        for (i in targets.indices) {
            for (j in i + 1 until targets.size) {
                val dx = xs[i] - xs[j]
                val dy = ys[i] - ys[j]
                if (dx * dx + dy * dy <= epsilonSq) union(i, j)
            }
        }

        val groups = targets.indices.groupBy { find(it) }
        val targetTypes = MetalDetectingTargetType.values()
        val numTypes = targetTypes.size
        val typeScoreSums = DoubleArray(numTypes)
        val hasType = BooleanArray(numTypes)

        // Single pass per cluster group to compute metrics without extra object allocations.
        return groups.values.map { members ->
            var sumX = 0.0
            var sumY = 0.0
            var bestIdx = members[0]
            var bestScore = targets[bestIdx].score

            typeScoreSums.fill(0.0)
            hasType.fill(false)

            val memberTargets = ArrayList<MetalDetectingTarget>(members.size)

            for (idx in members) {
                val t = targets[idx]
                sumX += t.xPercent
                sumY += t.yPercent
                if (t.score > bestScore) {
                    bestScore = t.score
                    bestIdx = idx
                }
                val ord = t.type.ordinal
                typeScoreSums[ord] += t.score.toDouble()
                hasType[ord] = true
                memberTargets.add(t)
            }

            val centroidX = (sumX / members.size).toFloat()
            val centroidY = (sumY / members.size).toFloat()

            // Find dominant type with highest sum score (break ties with highest ordinal to match comparator)
            var maxTypeScore = Double.NEGATIVE_INFINITY
            var dominantOrdinal = 0
            for (ord in 0 until numTypes) {
                if (hasType[ord]) {
                    val scoreSum = typeScoreSums[ord]
                    if (scoreSum > maxTypeScore || (scoreSum == maxTypeScore && ord > dominantOrdinal)) {
                        maxTypeScore = scoreSum
                        dominantOrdinal = ord
                    }
                }
            }
            val dominantType = targetTypes[dominantOrdinal]

            // Calculate max pairwise distance span in O(M^2) indexing without object allocation
            var maxPairSq = 0f
            val count = members.size
            for (i in 0 until count) {
                val a = members[i]
                for (j in i + 1 until count) {
                    val b = members[j]
                    val dx = xs[a] - xs[b]
                    val dy = ys[a] - ys[b]
                    val dSq = dx * dx + dy * dy
                    if (dSq > maxPairSq) maxPairSq = dSq
                }
            }

            memberTargets.sortByDescending { it.score }

            HuntZone(
                id = 0, // assigned after sorting
                centerXPercent = centroidX,
                centerYPercent = centroidY,
                targetCount = members.size,
                bestScore = bestScore,
                dominantType = dominantType,
                spanMeters = sqrt(maxPairSq),
                targets = memberTargets,
            )
        }
            .sortedByDescending { it.bestScore }
            .mapIndexed { index, zone -> zone.copy(id = index + 1) }
    }
}
