package com.example.gps.crs

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * American Polyconic on an ellipsoid (Snyder, "Map Projections — A Working Manual", eq. 18-1..18-3).
 * Used by most pre-1950 USGS 15' and 30' quadrangles in the Historical Topographic Map Collection.
 */
class Polyconic(
    private val ell: Ellipsoid,
    latOriginDeg: Double,
    private val centralMeridianDeg: Double,
    private val falseEastingM: Double,
    private val falseNorthingM: Double,
) : Projection {
    private val m0 = meridianArc(Math.toRadians(latOriginDeg))

    private fun meridianArc(phi: Double): Double {
        val e2 = ell.e2
        val e4 = e2 * e2
        val e6 = e4 * e2
        return ell.a * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }

    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val dLambda = Math.toRadians(lonDeg - centralMeridianDeg)
        if (abs(phi) < 1e-12) {
            return (falseEastingM + ell.a * dLambda) to (falseNorthingM - m0)
        }
        val sinPhi = sin(phi)
        val nu = ell.a / sqrt(1 - ell.e2 * sinPhi * sinPhi)
        val bigE = dLambda * sinPhi
        val cotPhi = 1.0 / tan(phi)
        val x = nu * cotPhi * sin(bigE)
        val y = meridianArc(phi) - m0 + nu * cotPhi * (1 - cos(bigE))
        return (falseEastingM + x) to (falseNorthingM + y)
    }

    /** Newton iteration on [forward]; converges to well under a millimetre in a few steps. */
    override fun inverse(easting: Double, northing: Double): Pair<Double, Double> {
        var lat = Math.toDegrees((northing - falseNorthingM + m0) / ell.a)
        var lon = centralMeridianDeg + Math.toDegrees((easting - falseEastingM) / (ell.a * cos(Math.toRadians(lat))))
        repeat(20) {
            val (e0, n0) = forward(lat, lon)
            val h = 1e-6
            val (e1, n1) = forward(lat + h, lon)
            val (e2, n2) = forward(lat, lon + h)
            val a11 = (e1 - e0) / h
            val a21 = (n1 - n0) / h
            val a12 = (e2 - e0) / h
            val a22 = (n2 - n0) / h
            val det = a11 * a22 - a12 * a21
            if (abs(det) < 1e-12) return lat to lon
            val de = easting - e0
            val dn = northing - n0
            val dLat = (a22 * de - a12 * dn) / det
            val dLon = (-a21 * de + a11 * dn) / det
            lat += dLat
            lon += dLon
            if (abs(dLat) < 1e-11 && abs(dLon) < 1e-11) return lat to lon
        }
        return lat to lon
    }
}

/** Datums found on USGS historical topographic GeoTIFFs. */
enum class HistoricDatum { NAD27, WGS84 }

object HistoricDatumShift {
    /** Clarke 1866, the NAD27 ellipsoid. */
    val CLARKE_1866 = Ellipsoid(6_378_206.4, 294.978_698_213_9)

    // EPSG:1173 NAD27 → WGS84 for the contiguous US. Good to about 5–10 m, which is finer than
    // the line weights on a 1:24,000 sheet; NADCON grids would be needed for sub-metre work.
    private const val DX = -8.0
    private const val DY = 160.0
    private const val DZ = 176.0

    /** (lat, lon) on [datum] → (lat, lon) on WGS84. */
    fun toWgs84(latDeg: Double, lonDeg: Double, datum: HistoricDatum): Pair<Double, Double> {
        if (datum == HistoricDatum.WGS84) return latDeg to lonDeg
        val p = Geodesy.toEcef(latDeg, lonDeg, 0.0, CLARKE_1866)
        val (lat, lon, _) = Geodesy.fromEcef(Ecef(p.x + DX, p.y + DY, p.z + DZ), Ellipsoid.WGS84)
        return lat to lon
    }
}
