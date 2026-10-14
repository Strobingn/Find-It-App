package com.example.gps

import com.example.gps.crs.GeodeticFrame
import java.util.Locale

/** Where a fix came from. */
enum class FixSource { PHONE, EXTERNAL_RECEIVER, MOCK_PROVIDER, AVERAGED }

/** Solution type. RTK labels are only used when the receiver reports corrections applied. */
enum class FixQuality { NONE, AUTONOMOUS, DGPS, RTK_FLOAT, RTK_FIXED, ESTIMATED }

data class GnssFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    /** 1-sigma-ish horizontal accuracy in metres as reported or estimated; never a guarantee. */
    val horizontalAccuracyMeters: Float,
    val source: FixSource,
    val quality: FixQuality,
    /** Datum of [latitude]/[longitude]: phone GNSS is ITRF/WGS84; NYSNet RTK is NAD83(2011). */
    val frame: GeodeticFrame,
    val timeMillis: Long,
    val satellitesUsed: Int? = null,
    /** True when the accuracy came from the receiver's own GST/statistics, not Android's model. */
    val accuracyFromReceiver: Boolean = false,
) {
    /**
     * Honest label. Android's accuracy is a model estimate, so it is always marked "est."; only a
     * receiver reporting RTK fixed/float (GGA quality 4/5) earns an RTK label.
     */
    fun accuracyLabel(): String {
        val acc = horizontalAccuracyMeters
        val value = when {
            !acc.isFinite() -> "?"
            acc < 0.1f -> String.format(Locale.US, "%.2f", acc)
            acc < 10f -> String.format(Locale.US, "%.1f", acc)
            else -> String.format(Locale.US, "%.0f", acc)
        }
        return when (quality) {
            FixQuality.RTK_FIXED -> "RTK fixed ±$value m"
            FixQuality.RTK_FLOAT -> "RTK float ±$value m"
            FixQuality.DGPS -> "DGPS ±$value m est."
            FixQuality.NONE -> "No fix"
            else -> "±$value m est."
        }
    }
}
