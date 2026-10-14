package com.example.gps.averaging

import com.example.gps.FixQuality
import com.example.gps.FixSource
import com.example.gps.GnssFix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Precise fix": accuracy-weighted average of fixes over [minDurationMs]..[maxDurationMs],
 * rejecting outliers, until the estimated accuracy reaches [targetMeters].
 *
 * Phone GNSS errors are strongly time-correlated (multipath, atmosphere), so averaging N fixes
 * does not shrink the error by √N. The estimate therefore uses an effective sample count of
 * duration / [correlationMs] and a floor of [biasFloorMeters]. Results are always labelled
 * as estimates.
 */
class PreciseFixAverager(
    private val minDurationMs: Long = 30_000L,
    private val maxDurationMs: Long = 60_000L,
    private val targetMeters: Double = 1.0,
    private val correlationMs: Long = 5_000L,
    private val biasFloorMeters: Double = 0.3,
) {
    private val fixes = ArrayList<GnssFix>()

    data class Progress(
        val elapsedMs: Long,
        val acceptedFixes: Int,
        val rejectedFixes: Int,
        val estimate: GnssFix?,
        val done: Boolean,
        val reachedTarget: Boolean,
    )

    fun reset() = fixes.clear()

    fun add(fix: GnssFix): Progress {
        if (fix.horizontalAccuracyMeters.isFinite() && fix.horizontalAccuracyMeters > 0f) fixes.add(fix)
        return progress(fix.timeMillis)
    }

    fun progress(nowMillis: Long): Progress {
        if (fixes.isEmpty()) return Progress(0, 0, 0, null, false, false)
        val start = fixes.first().timeMillis
        val elapsed = nowMillis - start
        val lat0 = fixes.first().latitude
        val lon0 = fixes.first().longitude
        val mPerDegLat = 111_132.954
        val mPerDegLon = 111_319.49 * cos(Math.toRadians(lat0))
        val e = DoubleArray(fixes.size) { (fixes[it].longitude - lon0) * mPerDegLon }
        val n = DoubleArray(fixes.size) { (fixes[it].latitude - lat0) * mPerDegLat }
        val sigma = DoubleArray(fixes.size) { fixes[it].horizontalAccuracyMeters.toDouble() }
        val keep = BooleanArray(fixes.size) { true }
        var meanE = 0.0
        var meanN = 0.0
        repeat(3) {
            var sw = 0.0; var se = 0.0; var sn = 0.0
            for (i in fixes.indices) if (keep[i]) {
                val w = 1.0 / (sigma[i] * sigma[i]); sw += w; se += w * e[i]; sn += w * n[i]
            }
            if (sw <= 0.0) return@repeat
            meanE = se / sw; meanN = sn / sw
            // Reject fixes farther than 3σ (their own) and 3× the median distance from the mean.
            val distances = fixes.indices.map { sqrt((e[it] - meanE).let { d -> d * d } + (n[it] - meanN).let { d -> d * d }) }
            val median = distances.sorted()[distances.size / 2]
            for (i in fixes.indices) keep[i] = distances[i] <= max(3.0 * sigma[i], 3.0 * median).coerceAtLeast(0.5)
        }
        val accepted = keep.count { it }
        val rejected = fixes.size - accepted
        var sw = 0.0
        for (i in fixes.indices) if (keep[i]) sw += 1.0 / (sigma[i] * sigma[i])
        val perFixSigma = if (sw > 0) sqrt(accepted / sw) else Double.POSITIVE_INFINITY
        val independent = (elapsed.toDouble() / correlationMs).coerceIn(1.0, accepted.toDouble().coerceAtLeast(1.0))
        val estimateSigma = max(perFixSigma / sqrt(independent), biasFloorMeters)
        val estimate = GnssFix(
            latitude = lat0 + meanN / mPerDegLat,
            longitude = lon0 + meanE / mPerDegLon,
            altitudeMeters = fixes.mapNotNull { it.altitudeMeters }.takeIf { it.isNotEmpty() }?.average(),
            horizontalAccuracyMeters = estimateSigma.toFloat(),
            source = FixSource.AVERAGED,
            quality = FixQuality.ESTIMATED,
            frame = fixes.first().frame,
            timeMillis = nowMillis,
            satellitesUsed = fixes.last().satellitesUsed,
        )
        val reached = estimateSigma <= targetMeters
        val done = elapsed >= maxDurationMs || (elapsed >= minDurationMs && reached)
        return Progress(elapsed, accepted, rejected, estimate, done, reached && elapsed >= minDurationMs)
    }
}
