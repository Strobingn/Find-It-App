package com.example.ai

import com.example.analysis.TerrainFeatureCandidate
import com.example.data.TargetSignal
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.ExcavationLogEntry
import com.example.data.field.FieldNavigation
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Pure offline (no network) drafts for return-trip order and coverage-gap map targets.
 * Complements cloud [FieldAiFeature.RETURN_TRIP_PLANNER] / [FieldAiFeature.COVERAGE_GAP_AI]
 * when keys are missing or the operator wants an instant local draft.
 */
object FieldOfflineAssist {

    /** Minimum percent-space distance from a logged find before a candidate counts as a gap. */
    const val GAP_MIN_DIST_PERCENT: Float = 8f

    /** Max map gap targets returned. */
    const val MAX_GAP_TARGETS: Int = 5

    data class GapTarget(
        val xPercent: Float,
        val yPercent: Float,
        val label: String,
        val confidence: Float,
    )

    /**
     * Nearest-neighbor order of starred finds (or all georeferenced if none starred),
     * unioned with open digs, starting from device GPS or the first stop with coordinates.
     * Multi-line draft with `NAV_TARGET id=` lines for each ordered stop.
     */
    fun returnTripDraft(
        signals: List<TargetSignal>,
        excavationLogs: List<ExcavationLogEntry>,
        deviceLat: Double?,
        deviceLon: Double?,
    ): String {
        if (signals.isEmpty()) {
            return buildString {
                appendLine("Offline return-trip draft")
                appendLine()
                appendLine("No logged finds in this session. Star or log georeferenced finds, then retry.")
            }.trimEnd()
        }

        val openDigTargetIds = excavationLogs
            .filter { !it.isComplete }
            .map { it.targetId }
            .toSet()
        val byId = signals.associateBy { it.id }

        val starred = signals.filter { it.starred }
        val basePool = if (starred.isNotEmpty()) {
            starred
        } else {
            val geo = signals.filter { hasGeo(it) }
            if (geo.isNotEmpty()) geo else signals
        }

        // Union open digs (even if not starred / not in base pool)
        val stopMap = LinkedHashMap<Long, TargetSignal>()
        basePool.forEach { stopMap[it.id] = it }
        openDigTargetIds.forEach { id ->
            byId[id]?.let { stopMap.putIfAbsent(id, it) }
        }
        val stops = stopMap.values.toList()
        if (stops.isEmpty()) {
            return buildString {
                appendLine("Offline return-trip draft")
                appendLine()
                appendLine("No visit stops available.")
            }.trimEnd()
        }

        val ordered = nearestNeighborOrder(stops, deviceLat, deviceLon)
        val usedStarred = starred.isNotEmpty()
        val openCount = ordered.count { it.id in openDigTargetIds }

        return buildString {
            appendLine("Offline return-trip draft")
            appendLine(
                if (usedStarred) {
                    "Stops: ${ordered.size} (starred priority" +
                        if (openCount > 0) " + $openCount open dig(s))" else ")"
                } else {
                    "Stops: ${ordered.size} (no stars — using georeferenced / all finds" +
                        if (openCount > 0) " + open digs)" else ")"
                },
            )
            if (deviceLat != null && deviceLon != null) {
                appendLine(
                    String.format(Locale.US, "Start: device GPS %.5f, %.5f", deviceLat, deviceLon),
                )
            } else {
                appendLine("Start: first stop (device GPS unavailable)")
            }
            appendLine()
            appendLine("Visit order (nearest-neighbor):")
            var prevLat = deviceLat
            var prevLon = deviceLon
            ordered.forEachIndexed { index, stop ->
                val starMark = if (stop.starred) " ★" else ""
                val openMark = if (stop.id in openDigTargetIds) " · open dig" else ""
                val geo = geoLabel(stop)
                val dist = distanceLabel(prevLat, prevLon, stop)
                appendLine(
                    "${index + 1}. id=${stop.id}$starMark · ${stop.metalType.label} · " +
                        "${stop.status}$openMark · $geo$dist",
                )
                if (stop.notes.isNotBlank()) {
                    appendLine("   notes: ${stop.notes.take(80)}")
                }
                val lat = effectiveLat(stop)
                val lon = effectiveLon(stop)
                if (lat != null && lon != null) {
                    prevLat = lat
                    prevLon = lon
                }
            }
            appendLine()
            appendLine("NAV targets (machine lines):")
            ordered.forEach { stop ->
                appendLine("NAV_TARGET id=${stop.id}")
            }
            appendLine()
            appendLine("Offline draft only — not a cloud plan. LiDAR does not prove buried metal.")
        }.trimEnd()
    }

    /**
     * Coverage gaps: high-score candidates far from logged find grid positions.
     * When [candidates] is empty, returns a message and an empty list.
     * Breadcrumbs (lat/lon only) are summarized in text; gap geometry uses find grid % as proxy.
     */
    fun coverageGapTargets(
        candidates: List<TerrainFeatureCandidate>,
        breadcrumbTracks: List<BreadcrumbTrack>,
        signals: List<TargetSignal> = emptyList(),
    ): Pair<String, List<GapTarget>> {
        if (candidates.isEmpty()) {
            val trailPts = breadcrumbTracks.sumOf { it.points.size }
            val msg = buildString {
                appendLine("Offline coverage-gap draft")
                appendLine()
                appendLine("No terrain candidates available. Run local analysis first, then retry.")
                if (breadcrumbTracks.isNotEmpty()) {
                    appendLine(
                        "GPS trails on file: ${breadcrumbTracks.size} track(s), $trailPts point(s) " +
                            "(lat/lon only — used as context, not grid clip).",
                    )
                }
            }.trimEnd()
            return msg to emptyList()
        }

        val findGrid = signals.map { it.gridX to it.gridY }
        val meanFind = if (findGrid.isNotEmpty()) {
            val mx = findGrid.map { it.first }.average().toFloat()
            val my = findGrid.map { it.second }.average().toFloat()
            mx to my
        } else {
            null
        }

        val scored = candidates.map { c ->
            val minToFind = if (findGrid.isEmpty()) {
                Float.POSITIVE_INFINITY
            } else {
                findGrid.minOf { (fx, fy) ->
                    hypot((c.xPercent - fx).toDouble(), (c.yPercent - fy).toDouble()).toFloat()
                }
            }
            val distFromMean = meanFind?.let { (mx, my) ->
                hypot((c.xPercent - mx).toDouble(), (c.yPercent - my).toDouble()).toFloat()
            } ?: Float.POSITIVE_INFINITY
            Triple(c, minToFind, distFromMean)
        }

        val gaps = if (findGrid.isEmpty()) {
            // No find coverage proxy — top candidates as unverified high-score zones
            scored.sortedByDescending { it.first.score }.take(MAX_GAP_TARGETS)
        } else {
            val far = scored.filter { it.second > GAP_MIN_DIST_PERCENT }
            if (far.isNotEmpty()) {
                far.sortedWith(
                    compareByDescending<Triple<TerrainFeatureCandidate, Float, Float>> { it.first.score }
                        .thenByDescending { it.second },
                ).take(MAX_GAP_TARGETS)
            } else {
                // Everything is near a find; still surface top score candidates farthest from mean
                scored.sortedWith(
                    compareByDescending<Triple<TerrainFeatureCandidate, Float, Float>> { it.third }
                        .thenByDescending { it.first.score },
                ).take(MAX_GAP_TARGETS)
            }
        }

        val targets = gaps.map { (c, _, _) ->
            GapTarget(
                xPercent = c.xPercent.coerceIn(0f, 100f),
                yPercent = c.yPercent.coerceIn(0f, 100f),
                label = "Gap · ${c.type.label}",
                confidence = c.score.coerceIn(0f, 1f),
            )
        }

        val trailPts = breadcrumbTracks.sumOf { it.points.size }
        val text = buildString {
            appendLine("Offline coverage-gap draft")
            appendLine()
            if (breadcrumbTracks.isNotEmpty()) {
                appendLine(
                    "GPS trails: ${breadcrumbTracks.size} track(s), $trailPts point(s) " +
                        "(lat/lon only — not projected to grid). " +
                        "Suggesting high-score zones away from logged finds as unverified gap targets.",
                )
            } else {
                appendLine(
                    "No GPS trails. Gaps inferred from candidates far from logged find grid positions " +
                        "(threshold ${GAP_MIN_DIST_PERCENT.toInt()}% of map).",
                )
            }
            if (findGrid.isEmpty()) {
                appendLine("No find grid proxy — listing top ${targets.size} high-score candidates.")
            } else {
                appendLine("Find coverage proxy: ${findGrid.size} logged position(s).")
            }
            appendLine()
            if (targets.isEmpty()) {
                appendLine("No gap targets selected.")
            } else {
                appendLine("Suggested gap targets (${targets.size}):")
                targets.forEachIndexed { i, t ->
                    appendLine(
                        String.format(
                            Locale.US,
                            "%d. %s · x=%.1f%% y=%.1f%% · conf=%.0f%%",
                            i + 1,
                            t.label,
                            t.xPercent,
                            t.yPercent,
                            t.confidence * 100f,
                        ),
                    )
                }
            }
            appendLine()
            appendLine("Offline draft only — not a cloud plan. LiDAR does not prove buried metal.")
        }.trimEnd()

        return text to targets
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun hasGeo(signal: TargetSignal): Boolean =
        effectiveLat(signal) != null && effectiveLon(signal) != null

    private fun effectiveLat(signal: TargetSignal): Double? =
        signal.latitude ?: signal.gpsLatitude

    private fun effectiveLon(signal: TargetSignal): Double? =
        signal.longitude ?: signal.gpsLongitude

    private fun geoLabel(signal: TargetSignal): String {
        val lat = effectiveLat(signal)
        val lon = effectiveLon(signal)
        return if (lat != null && lon != null) {
            String.format(Locale.US, "%.5f, %.5f", lat, lon)
        } else {
            String.format(Locale.US, "grid %.0f,%.0f", signal.gridX, signal.gridY)
        }
    }

    private fun distanceLabel(fromLat: Double?, fromLon: Double?, stop: TargetSignal): String {
        val lat = effectiveLat(stop)
        val lon = effectiveLon(stop)
        if (fromLat == null || fromLon == null || lat == null || lon == null) return ""
        val meters = FieldNavigation.distanceMeters(fromLat, fromLon, lat, lon)
        return if (meters < 1000.0) {
            String.format(Locale.US, " · ~%.0f m", meters)
        } else {
            String.format(Locale.US, " · ~%.2f km", meters / 1000.0)
        }
    }

    /**
     * NN order in geographic space when both ends have lat/lon; otherwise grid Euclidean.
     * Stops without any position metric are appended in input order at the end.
     */
    private fun nearestNeighborOrder(
        stops: List<TargetSignal>,
        deviceLat: Double?,
        deviceLon: Double?,
    ): List<TargetSignal> {
        if (stops.size <= 1) return stops

        val remaining = stops.toMutableList()
        val ordered = ArrayList<TargetSignal>(stops.size)

        var anchorLat = deviceLat
        var anchorLon = deviceLon
        var anchorGx: Float? = null
        var anchorGy: Float? = null

        // If no device GPS, seed from first stop with geo, else first stop overall
        if (anchorLat == null || anchorLon == null) {
            val seed = remaining.firstOrNull { hasGeo(it) } ?: remaining.first()
            // Don't remove seed yet — NN will pick nearest to seed position, may be seed itself
            anchorLat = effectiveLat(seed)
            anchorLon = effectiveLon(seed)
            anchorGx = seed.gridX
            anchorGy = seed.gridY
        }

        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { candidate ->
                distanceFromAnchor(anchorLat, anchorLon, anchorGx, anchorGy, candidate)
            } ?: remaining.first()
            remaining.remove(next)
            ordered += next
            val lat = effectiveLat(next)
            val lon = effectiveLon(next)
            if (lat != null && lon != null) {
                anchorLat = lat
                anchorLon = lon
            }
            anchorGx = next.gridX
            anchorGy = next.gridY
        }
        return ordered
    }

    private fun distanceFromAnchor(
        anchorLat: Double?,
        anchorLon: Double?,
        anchorGx: Float?,
        anchorGy: Float?,
        candidate: TargetSignal,
    ): Double {
        val cLat = effectiveLat(candidate)
        val cLon = effectiveLon(candidate)
        if (anchorLat != null && anchorLon != null && cLat != null && cLon != null) {
            return FieldNavigation.distanceMeters(anchorLat, anchorLon, cLat, cLon)
        }
        if (anchorGx != null && anchorGy != null) {
            val dx = (candidate.gridX - anchorGx).toDouble()
            val dy = (candidate.gridY - anchorGy).toDouble()
            return sqrt(dx * dx + dy * dy)
        }
        return Double.MAX_VALUE
    }
}
