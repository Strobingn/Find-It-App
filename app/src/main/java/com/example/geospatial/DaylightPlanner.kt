package com.example.geospatial

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sunrise / sunset / solar-noon for a site, so a field day can be planned around usable light.
 *
 * NOAA solar equations (fractional-year form): equation of time and solar declination from the
 * day of year, then the hour angle where the sun center sits 0.833° below the horizon (the
 * standard sunrise/sunset convention that includes refraction and solar disc radius).
 *
 * All results are UTC minutes-of-day; callers add their local offset for display. Sunrise and
 * sunset are null during polar day/night (the site is in New York, but the math stays honest).
 */
data class DaylightWindow(
    val sunriseUtcMinutes: Float?,
    val sunsetUtcMinutes: Float?,
    val solarNoonUtcMinutes: Float,
    val dayLengthMinutes: Float?,
) {
    /** True when the sun never rises on this date (or never sets - either way no normal window). */
    val isPolar: Boolean get() = sunriseUtcMinutes == null || sunsetUtcMinutes == null
}

object DaylightPlanner {

    fun compute(latitudeDegrees: Double, longitudeDegrees: Double, dayOfYear: Int): DaylightWindow {
        // [dayOfYear] is the non-leap solar index (1 = Jan 1 … 60 = Mar 1 … 365 = Dec 31).
        // Leap-year calendar ordinals are one day high after Feb 28; pass [solarDayOfYear].
        val gamma = 2.0 * PI / 365.0 * (dayOfYear.coerceIn(1, 365) - 1)
        val equationOfTimeMinutes = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val declination =
            0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)

        val latRad = Math.toRadians(latitudeDegrees)
        val cosHourAngle =
            (cos(Math.toRadians(90.833)) / (cos(latRad) * cos(declination))) -
                tan(latRad) * tan(declination)

        // Solar noon is always defined, even when the sun never crosses the horizon.
        val solarNoonUtc = (720.0 - 4.0 * longitudeDegrees - equationOfTimeMinutes).toFloat()

        if (cosHourAngle > 1.0 || cosHourAngle < -1.0) {
            return DaylightWindow(
                sunriseUtcMinutes = null,
                sunsetUtcMinutes = null,
                solarNoonUtcMinutes = solarNoonUtc,
                dayLengthMinutes = null,
            )
        }

        val hourAngleDegrees = Math.toDegrees(acos(cosHourAngle))
        val sunriseUtc = 720.0 - 4.0 * (longitudeDegrees + hourAngleDegrees) - equationOfTimeMinutes
        val sunsetUtc = 720.0 - 4.0 * (longitudeDegrees - hourAngleDegrees) - equationOfTimeMinutes
        return DaylightWindow(
            sunriseUtcMinutes = sunriseUtc.toFloat(),
            sunsetUtcMinutes = sunsetUtc.toFloat(),
            solarNoonUtcMinutes = solarNoonUtc,
            dayLengthMinutes = (8.0 * hourAngleDegrees).toFloat(),
        )
    }

    /** Calendar day of year (1..366). Leap years count Feb 29, so Mar 1 is 61. */
    fun dayOfYear(year: Int, month: Int, dayOfMonth: Int): Int {
        val leap = isLeapYear(year)
        val daysBefore = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        var total = daysBefore[(month - 1).coerceIn(0, 11)] + dayOfMonth.coerceIn(1, 31)
        if (leap && month > 2) total++
        return total.coerceIn(1, 366)
    }

    /**
     * Day index for the non-leap solar formulas in [compute] and [com.example.geospatial.SolarPosition].
     * Mar 1 is day 60 in both leap and non-leap years. Feb 29 maps to day 59 (beside Feb 28).
     */
    fun solarDayOfYear(year: Int, month: Int, dayOfMonth: Int): Int {
        val ordinal = dayOfYear(year, month, dayOfMonth)
        val solar = if (isLeapYear(year) && ordinal >= 60) ordinal - 1 else ordinal
        return solar.coerceIn(1, 365)
    }

    private fun isLeapYear(year: Int): Boolean =
        year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    /** Formats UTC minutes-of-day as a local "h:mm AM" clock string with [utcOffsetMinutes]. */
    fun formatLocal(utcMinutes: Float, utcOffsetMinutes: Int): String {
        val wrapped = ((floor(utcMinutes).toInt() + utcOffsetMinutes) % 1440 + 1440) % 1440
        val hour24 = wrapped / 60
        val minute = wrapped % 60
        val suffix = if (hour24 < 12) "AM" else "PM"
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        return "%d:%02d %s".format(hour12, minute, suffix)
    }
}
