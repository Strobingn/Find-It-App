package com.example.data.field

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Computations used by the offline saved-target navigation card. */
object FieldNavigation {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    data class Solution(
        val distanceMeters: Double,
        /** Clockwise-from-north geographic bearing to the target. */
        val targetBearingDegrees: Float,
        /** Signed clockwise turn from the device heading; null when no heading is available. */
        val turnDegrees: Float?,
    )

    fun solve(
        currentLatitude: Double,
        currentLongitude: Double,
        targetLatitude: Double,
        targetLongitude: Double,
        headingDegrees: Float? = null,
    ): Solution {
        val bearing = bearingDegrees(currentLatitude, currentLongitude, targetLatitude, targetLongitude)
        return Solution(
            distanceMeters = distanceMeters(currentLatitude, currentLongitude, targetLatitude, targetLongitude),
            targetBearingDegrees = bearing,
            turnDegrees = headingDegrees?.let { signedTurnDegrees(it, bearing) },
        )
    }

    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val dLat = degreesToRadians(toLatitude - fromLatitude)
        val dLon = degreesToRadians(toLongitude - fromLongitude)
        val lat1 = degreesToRadians(fromLatitude)
        val lat2 = degreesToRadians(toLatitude)
        val haversine = sin(dLat / 2).let { it * it } +
            cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        val clampedHaversine = haversine.coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(clampedHaversine), sqrt(1.0 - clampedHaversine))
    }

    fun bearingDegrees(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Float {
        val lat1 = degreesToRadians(fromLatitude)
        val lat2 = degreesToRadians(toLatitude)
        val dLon = degreesToRadians(toLongitude - fromLongitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)).toFloat())
    }

    /** Returns a clockwise-positive turn in [-180, 180). */
    fun signedTurnDegrees(headingDegrees: Float, targetBearingDegrees: Float): Float =
        normalizeDegrees(targetBearingDegrees - headingDegrees + 180f) - 180f

    fun normalizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    fun compassDirection(degrees: Float): String = when (val normalized = normalizeDegrees(degrees)) {
        in 22.5f..67.499f -> "NE"
        in 67.5f..112.499f -> "E"
        in 112.5f..157.499f -> "SE"
        in 157.5f..202.499f -> "S"
        in 202.5f..247.499f -> "SW"
        in 247.5f..292.499f -> "W"
        in 292.5f..337.499f -> "NW"
        else -> "N"
    }

    fun turnInstruction(turnDegrees: Float): String = when {
        turnDegrees > 15f -> "Turn ${turnDegrees.toInt()}° right"
        turnDegrees < -15f -> "Turn ${-turnDegrees.toInt()}° left"
        else -> "Target ahead"
    }

    private fun degreesToRadians(degrees: Double): Double = degrees * PI / 180.0
}
