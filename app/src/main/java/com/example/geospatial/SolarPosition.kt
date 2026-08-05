package com.example.geospatial

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sun azimuth (clockwise from north) and altitude (above horizon), both in degrees. */
data class SunPosition(
    val azimuthDegrees: Float,
    val altitudeDegrees: Float,
) {
    val isAboveHorizon: Boolean get() = altitudeDegrees > 0f
}

/**
 * Low-precision solar position in local solar time (12:00 = the sun's daily high point).
 * NOAA-style approximation, accurate to roughly a degree — more than enough for hillshade
 * lighting, where the point is to preview how low morning or winter sun rakes across
 * earthworks. Longitude is unnecessary because time is already expressed in solar hours.
 */
object SolarPosition {

    fun calculate(latitudeDegrees: Double, dayOfYear: Int, hourLocalSolar: Float): SunPosition {
        val clampedDay = dayOfYear.coerceIn(1, 365)
        val declination = Math.toRadians(
            23.44 * sin(Math.toRadians(360.0 / 365.0 * (284 + clampedDay))),
        )
        val latitude = Math.toRadians(latitudeDegrees.coerceIn(-89.9, 89.9))
        val hourAngle = Math.toRadians(15.0 * (hourLocalSolar.coerceIn(0f, 24f) - 12f))

        val sinAltitude = sin(latitude) * sin(declination) +
            cos(latitude) * cos(declination) * cos(hourAngle)
        val altitudeDegrees = Math.toDegrees(asin(sinAltitude.coerceIn(-1.0, 1.0)))

        // Azimuth measured clockwise from north: 0° N, 90° E, 180° S, 270° W.
        val azimuthRadians = atan2(
            sin(hourAngle),
            cos(hourAngle) * sin(latitude) - tan(declination) * cos(latitude),
        )
        val azimuthDegrees = ((Math.toDegrees(azimuthRadians) + 180.0) % 360.0 + 360.0) % 360.0
        return SunPosition(azimuthDegrees.toFloat(), altitudeDegrees.toFloat())
    }

    private val MONTH_DAYS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    private val MONTH_NAMES = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** "Jun 21"-style label for a day-of-year (non-leap calendar). */
    fun dateLabel(dayOfYear: Int): String {
        var remaining = dayOfYear.coerceIn(1, 365)
        for (month in MONTH_DAYS.indices) {
            if (remaining <= MONTH_DAYS[month]) return "${MONTH_NAMES[month]} $remaining"
            remaining -= MONTH_DAYS[month]
        }
        return "Dec 31"
    }
}
