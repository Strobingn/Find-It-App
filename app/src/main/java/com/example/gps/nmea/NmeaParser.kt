package com.example.gps.nmea

import kotlin.math.hypot

/** GGA fix quality (NMEA 0183). 4 = RTK fixed, 5 = RTK float. */
data class GgaSentence(
    val utcTime: String,
    val latitude: Double,
    val longitude: Double,
    val quality: Int,
    val satellites: Int,
    val hdop: Double?,
    val altitudeMeters: Double?,
    val ageOfCorrectionsSeconds: Double?,
)

/** GST pseudorange error statistics: 1-sigma latitude/longitude errors in metres. */
data class GstSentence(val latSigmaMeters: Double, val lonSigmaMeters: Double) {
    val horizontalSigmaMeters: Double get() = hypot(latSigmaMeters, lonSigmaMeters)
}

object NmeaParser {
    /** Validates the *hh checksum; sentences without one are rejected (serial noise). */
    fun fields(line: String): List<String>? {
        val s = line.trim()
        if (!s.startsWith("$") && !s.startsWith("!")) return null
        val star = s.lastIndexOf('*')
        if (star < 0 || star + 3 > s.length) return null
        val expected = s.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
        var sum = 0
        for (i in 1 until star) sum = sum xor s[i].code
        if (sum != expected) return null
        return s.substring(1, star).split(',')
    }

    private fun coordinate(value: String, hemisphere: String, degreeDigits: Int): Double? {
        if (value.length <= degreeDigits) return null
        val degrees = value.substring(0, degreeDigits).toIntOrNull() ?: return null
        val minutes = value.substring(degreeDigits).toDoubleOrNull() ?: return null
        val v = degrees + minutes / 60.0
        return when (hemisphere) {
            "N", "E" -> v
            "S", "W" -> -v
            else -> null
        }
    }

    fun parseGga(line: String): GgaSentence? {
        val f = fields(line) ?: return null
        if (f.size < 10 || !f[0].endsWith("GGA")) return null
        val quality = f[6].toIntOrNull() ?: return null
        val lat = coordinate(f[2], f[3], 2) ?: return null
        val lon = coordinate(f[4], f[5], 3) ?: return null
        return GgaSentence(
            utcTime = f[1],
            latitude = lat,
            longitude = lon,
            quality = quality,
            satellites = f[7].toIntOrNull() ?: 0,
            hdop = f[8].toDoubleOrNull(),
            altitudeMeters = f[9].toDoubleOrNull(),
            ageOfCorrectionsSeconds = f.getOrNull(13)?.toDoubleOrNull(),
        )
    }

    fun parseGst(line: String): GstSentence? {
        val f = fields(line) ?: return null
        if (f.size < 8 || !f[0].endsWith("GST")) return null
        val latSigma = f[6].toDoubleOrNull() ?: return null
        val lonSigma = f[7].toDoubleOrNull() ?: return null
        return GstSentence(latSigma, lonSigma)
    }

    /** Builds a valid GGA to send to an NTRIP caster (VRS/MAC mountpoints need rover position). */
    fun buildGga(latitude: Double, longitude: Double, altitude: Double, utcMillis: Long, quality: Int = 1, satellites: Int = 12): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        val time = String.format(
            java.util.Locale.US, "%02d%02d%02d.00",
            cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND),
        )
        fun dm(v: Double, degDigits: Int): String {
            val a = kotlin.math.abs(v)
            val d = a.toInt()
            val m = (a - d) * 60.0
            return String.format(java.util.Locale.US, "%0${degDigits}d%011.8f", d, m)
        }
        val body = String.format(
            java.util.Locale.US,
            "GPGGA,%s,%s,%s,%s,%s,%d,%02d,1.0,%.3f,M,0.0,M,,",
            time, dm(latitude, 2), if (latitude >= 0) "N" else "S", dm(longitude, 3), if (longitude >= 0) "E" else "W",
            quality, satellites, altitude,
        )
        var sum = 0
        for (c in body) sum = sum xor c.code
        return "$" + body + "*" + String.format(java.util.Locale.US, "%02X", sum)
    }
}
