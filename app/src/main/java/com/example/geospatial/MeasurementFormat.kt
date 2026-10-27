package com.example.geospatial

import java.util.Locale
import kotlin.math.abs

/**
 * Formats measurements for display in US customary units.
 *
 * Everything upstream of this object stays in meters on purpose: LAZ/LAS payloads, the CRS
 * definitions they carry, and the geodesic math in [GeoSpatialLibrary] are all metric, so
 * converting at the presentation layer keeps a single source of truth and avoids compounding
 * rounding through the analysis pipeline. This is the only place the app decides what a user
 * actually reads, so switching the whole app back to metric later means changing these functions
 * rather than hunting call sites.
 */
object MeasurementFormat {
    const val FEET_PER_METER = 3.280839895013123
    const val INCHES_PER_FOOT = 12.0
    const val FEET_PER_MILE = 5280.0

    /** Below this many feet a value reads more naturally in inches. */
    private const val INCH_THRESHOLD_FEET = 1.0

    fun metersToFeet(meters: Double): Double = meters * FEET_PER_METER

    fun metersToFeet(meters: Float): Double = meters.toDouble() * FEET_PER_METER

    fun metersToMiles(meters: Double): Double = metersToFeet(meters) / FEET_PER_MILE

    /**
     * A length with the unit chosen to suit its magnitude: inches under a foot, miles at a mile
     * and above, feet in between. Use for distances and extents, not for elevation.
     */
    fun length(meters: Double): String {
        if (!meters.isFinite()) return "—"
        val feet = metersToFeet(meters)
        val magnitude = abs(feet)
        return when {
            magnitude < INCH_THRESHOLD_FEET -> format(feet * INCHES_PER_FOOT, 1, "in")
            magnitude < FEET_PER_MILE -> format(feet, if (magnitude < 10.0) 1 else 0, "ft")
            else -> format(feet / FEET_PER_MILE, 2, "mi")
        }
    }

    fun length(meters: Float): String = length(meters.toDouble())

    /** Same selection as [length] but keeping an explicit sign, for rises and drops. */
    fun signedLength(meters: Double): String {
        if (!meters.isFinite()) return "—"
        val sign = if (meters < 0) "−" else "+"
        return sign + length(abs(meters))
    }

    fun signedLength(meters: Float): String = signedLength(meters.toDouble())

    /** Always feet. Elevation is quoted in feet at every magnitude by convention. */
    fun feet(meters: Double, decimals: Int = 1): String {
        if (!meters.isFinite()) return "—"
        return format(metersToFeet(meters), decimals, "ft")
    }

    fun feet(meters: Float, decimals: Int = 1): String = feet(meters.toDouble(), decimals)

    /** Bare feet value with no unit suffix, for compact composite readouts. */
    fun feetValue(meters: Float, decimals: Int = 0): String =
        String.format(Locale.US, "%.${decimals}f", metersToFeet(meters))

    /**
     * Always inches, whatever the magnitude. For a span where both ends must share one unit —
     * a dig-depth band, say — so the reader isn't comparing inches at one end with feet at the
     * other the way [length] would.
     */
    fun inches(meters: Double, decimals: Int = 0): String {
        if (!meters.isFinite()) return "—"
        return format(metersToFeet(meters) * INCHES_PER_FOOT, decimals, "in")
    }

    /** Bare inches value with no unit suffix, for a range that prints the unit once. */
    fun inchesValue(meters: Double, decimals: Int = 0): String =
        String.format(Locale.US, "%.${decimals}f", metersToFeet(meters) * INCHES_PER_FOOT)

    /** Converts a per-meter rate (such as curvature) into a per-foot rate. */
    fun perFoot(perMeter: Double, decimals: Int = 4): String {
        if (!perMeter.isFinite()) return "—"
        return format(perMeter / FEET_PER_METER, decimals, "ft⁻¹")
    }

    fun perFoot(perMeter: Float, decimals: Int = 4): String = perFoot(perMeter.toDouble(), decimals)

    /**
     * Cell resolution. Sub-foot LiDAR grids are common, so inches are used below a foot to keep
     * the number meaningful instead of collapsing to "0.3 ft".
     */
    fun resolution(meters: Float): String = length(meters.toDouble())

    private fun format(value: Double, decimals: Int, unit: String): String =
        String.format(Locale.US, "%.${decimals}f %s", value, unit)
}
