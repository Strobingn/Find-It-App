package com.example.data.field

import com.example.data.TargetSignal
import com.example.data.VerificationOutcome

/**
 * One-screen summary of how the current field effort is going: how much ground was walked, how
 * many targets were logged, how the verified outcomes split, and the logging pace. Derived
 * entirely from data the app already records - finds and GPS breadcrumb trails.
 */
data class FieldSessionStats(
    val totalFinds: Int,
    val positionedFinds: Int,
    val confirmedFinds: Int,
    val rejectedFinds: Int,
    val distanceMeters: Double,
    val activeMinutes: Long?,
    val topFindType: String?,
) {
    /** Finds per active hour; null when the session span is too short to rate meaningfully. */
    val findsPerHour: Double?
        get() = activeMinutes?.takeIf { it >= MIN_RATEABLE_MINUTES && totalFinds > 0 }
            ?.let { totalFinds * 60.0 / it }

    val confirmRate: Float?
        get() {
            val decided = confirmedFinds + rejectedFinds
            return if (decided > 0) confirmedFinds.toFloat() / decided else null
        }

    /** Plain-text day debrief suitable for [android.content.Intent.ACTION_SEND]. */
    fun toShareText(siteName: String? = null): String = buildString {
        appendLine("Find It · field session debrief")
        siteName?.takeIf { it.isNotBlank() }?.let { appendLine("Site: $it") }
        appendLine("Finds: $totalFinds ($positionedFinds with GPS)")
        appendLine("Confirmed: $confirmedFinds · Rejected: $rejectedFinds")
        confirmRate?.let { appendLine("Confirm rate: ${(it * 100).toInt()}%") }
        appendLine(
            "Distance walked: ${
                when {
                    distanceMeters >= 1000.0 -> String.format("%.2f km", distanceMeters / 1000.0)
                    else -> String.format("%.0f m", distanceMeters)
                }
            }",
        )
        activeMinutes?.let { appendLine("Active span: ${it} min") }
        findsPerHour?.let { appendLine("Pace: ${String.format("%.1f", it)} finds/h") }
        topFindType?.let { appendLine("Top find type: $it") }
        appendLine()
        append(
            "LiDAR ranks surface morphology and historic context — not buried metal, age, or dig depth.",
        )
    }

    companion object {
        /** Sessions shorter than this report no finds/hour - the rate would be noise. */
        const val MIN_RATEABLE_MINUTES = 10L
    }
}

object FieldSessionStatsCalculator {

    fun compute(signals: List<TargetSignal>, tracks: List<BreadcrumbTrack>): FieldSessionStats {
        var distanceMeters = 0.0
        var earliest = Long.MAX_VALUE
        var latest = Long.MIN_VALUE

        for (track in tracks) {
            var previous: BreadcrumbPoint? = null
            for (point in track.points) {
                previous?.let {
                    distanceMeters += FieldNavigation.distanceMeters(
                        it.latitude,
                        it.longitude,
                        point.latitude,
                        point.longitude,
                    )
                }
                if (point.recordedAtMillis < earliest) earliest = point.recordedAtMillis
                if (point.recordedAtMillis > latest) latest = point.recordedAtMillis
                previous = point
            }
            if (track.points.isNotEmpty()) {
                if (track.createdAtMillis < earliest) earliest = track.createdAtMillis
                if (track.updatedAtMillis > latest) latest = track.updatedAtMillis
            }
        }
        for (signal in signals) {
            if (signal.timestamp < earliest) earliest = signal.timestamp
            if (signal.timestamp > latest) latest = signal.timestamp
        }

        val activeMinutes = if (earliest == Long.MAX_VALUE || latest <= earliest) {
            null
        } else {
            (latest - earliest) / 60_000L
        }

        val topType = signals
            .groupingBy { it.metalType.label }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

        return FieldSessionStats(
            totalFinds = signals.size,
            positionedFinds = signals.count { it.latitude != null && it.longitude != null },
            confirmedFinds = signals.count { it.outcome == VerificationOutcome.CONFIRMED_FEATURE },
            rejectedFinds = signals.count { it.outcome == VerificationOutcome.REJECTED_FALSE_POSITIVE },
            distanceMeters = distanceMeters,
            activeMinutes = activeMinutes,
            topFindType = topType,
        )
    }
}
