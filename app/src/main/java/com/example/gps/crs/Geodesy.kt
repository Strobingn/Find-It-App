package com.example.gps.crs

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Reference ellipsoid. GRS80 and WGS84 differ by 0.1 mm in the polar radius. */
data class Ellipsoid(val a: Double, val inverseFlattening: Double) {
    val f: Double get() = 1.0 / inverseFlattening
    val e2: Double get() = f * (2.0 - f)
    val e: Double get() = sqrt(e2)

    companion object {
        val GRS80 = Ellipsoid(6_378_137.0, 298.257222101)
        val WGS84 = Ellipsoid(6_378_137.0, 298.257223563)
    }
}

data class Ecef(val x: Double, val y: Double, val z: Double)

object Geodesy {
    fun toEcef(latDeg: Double, lonDeg: Double, h: Double, ell: Ellipsoid): Ecef {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val n = ell.a / sqrt(1.0 - ell.e2 * sin(lat) * sin(lat))
        return Ecef((n + h) * cos(lat) * cos(lon), (n + h) * cos(lat) * sin(lon), (n * (1.0 - ell.e2) + h) * sin(lat))
    }

    /** Returns (latDeg, lonDeg, h). Iterative; converges to < 1e-12 rad in a few steps. */
    fun fromEcef(p: Ecef, ell: Ellipsoid): Triple<Double, Double, Double> {
        val lon = atan2(p.y, p.x)
        val r = hypot(p.x, p.y)
        var lat = atan2(p.z, r * (1.0 - ell.e2))
        var h = 0.0
        repeat(8) {
            val n = ell.a / sqrt(1.0 - ell.e2 * sin(lat) * sin(lat))
            h = r / cos(lat) - n
            lat = atan2(p.z, r * (1.0 - ell.e2 * n / (n + h)))
        }
        return Triple(Math.toDegrees(lat), Math.toDegrees(lon), h)
    }
}

/**
 * Time-dependent 14-parameter Helmert transformation, coordinate-frame rotation convention
 * (as PROJ `convention=coordinate_frame`). Translations in metres, rotations in arc-seconds,
 * scale in ppm, rates per year, reference epoch [epoch].
 */
data class Helmert14(
    val tx: Double, val ty: Double, val tz: Double,
    val rx: Double, val ry: Double, val rz: Double,
    val s: Double,
    val dtx: Double, val dty: Double, val dtz: Double,
    val drx: Double, val dry: Double, val drz: Double,
    val ds: Double,
    val epoch: Double,
) {
    fun apply(p: Ecef, atEpoch: Double): Ecef {
        val dt = atEpoch - epoch
        val arcsec = Math.PI / (180.0 * 3600.0)
        val cx = tx + dtx * dt
        val cy = ty + dty * dt
        val cz = tz + dtz * dt
        val ex = (rx + drx * dt) * arcsec
        val ey = (ry + dry * dt) * arcsec
        val ez = (rz + drz * dt) * arcsec
        val m = 1.0 + (s + ds * dt) * 1e-6
        return Ecef(
            cx + m * (p.x + ez * p.y - ey * p.z),
            cy + m * (-ez * p.x + p.y + ex * p.z),
            cz + m * (ey * p.x - ex * p.y + p.z),
        )
    }

    companion object {
        /** ITRF2014 → NAD83(2011), EPSG:9000→6318 as used by PROJ 9 (NGS published values). */
        val ITRF2014_TO_NAD83_2011 = Helmert14(
            tx = 1.0053, ty = -1.90921, tz = -0.54157,
            rx = 0.02678138, ry = -0.00042027, rz = 0.01093206,
            s = 0.00036891,
            dtx = 0.00079, dty = -0.0006, dtz = -0.00144,
            drx = 6.667e-05, dry = -0.00075744, drz = -5.133e-05,
            ds = -7.201e-05,
            epoch = 2010.0,
        )
    }
}

/** Horizontal reference frame of a position. */
enum class GeodeticFrame {
    /** Phone GNSS / WGS84 (G2139), aligned with ITRF2014 at the cm level. */
    ITRF,
    /** NAD83(2011) — US LiDAR CRSs and NYSNet RTK corrections. */
    NAD83_2011,
}

object DatumShift {
    fun decimalYear(epochMillis: Long): Double = 1970.0 + epochMillis / (365.2425 * 86_400_000.0)

    /** Converts lat/lon between frames at the observation epoch (no-op when frames match). */
    fun convert(latDeg: Double, lonDeg: Double, from: GeodeticFrame, to: GeodeticFrame, epoch: Double): Pair<Double, Double> {
        if (from == to) return latDeg to lonDeg
        val ecef = Geodesy.toEcef(latDeg, lonDeg, 0.0, Ellipsoid.GRS80)
        val shifted = if (from == GeodeticFrame.ITRF) {
            Helmert14.ITRF2014_TO_NAD83_2011.apply(ecef, epoch)
        } else {
            inverse(ecef, epoch)
        }
        val (lat, lon, _) = Geodesy.fromEcef(shifted, Ellipsoid.GRS80)
        return lat to lon
    }

    private fun inverse(target: Ecef, epoch: Double): Ecef {
        // Fixed-point inversion: rotations are ~1e-7 rad, so two iterations reach < 1e-9 m.
        var guess = target
        repeat(4) {
            val fwd = Helmert14.ITRF2014_TO_NAD83_2011.apply(guess, epoch)
            guess = Ecef(guess.x + (target.x - fwd.x), guess.y + (target.y - fwd.y), guess.z + (target.z - fwd.z))
        }
        return guess
    }
}
