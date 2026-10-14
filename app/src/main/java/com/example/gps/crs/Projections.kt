package com.example.gps.crs

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A map projection on an ellipsoid. Coordinates in metres (unit conversion lives in [ProjectedCrs]). */
interface Projection {
    /** (lat, lon) degrees → (easting, northing) metres including false origin. */
    fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double>

    /** (easting, northing) metres → (lat, lon) degrees. */
    fun inverse(easting: Double, northing: Double): Pair<Double, Double>
}

private fun conformalToGeodetic(chi: Double, e: Double): Double {
    var phi = chi
    repeat(12) {
        val es = e * sin(phi)
        phi = 2.0 * atan(((1.0 + es) / (1.0 - es)).pow(e / 2.0) * tan(PI / 4.0 + chi / 2.0)) - PI / 2.0
    }
    return phi
}

/**
 * Transverse Mercator, Krüger series to n⁶ (Karney 2011; same as PROJ `etmerc`): sub-millimetre
 * within a UTM zone and well beyond it.
 */
class TransverseMercator(
    private val ell: Ellipsoid,
    latOriginDeg: Double,
    private val centralMeridianDeg: Double,
    private val k0: Double,
    private val falseEastingM: Double,
    private val falseNorthingM: Double,
) : Projection {
    private val n = ell.f / (2.0 - ell.f)
    private val bigA = ell.a / (1.0 + n) * (1.0 + n * n / 4.0 + n.pow(4) / 64.0 + n.pow(6) / 256.0)
    private val alpha = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 5 * n.pow(3) / 16 + 41 * n.pow(4) / 180 - 127 * n.pow(5) / 288 + 7891 * n.pow(6) / 37800,
        13 * n * n / 48 - 3 * n.pow(3) / 5 + 557 * n.pow(4) / 1440 + 281 * n.pow(5) / 630 - 1983433 * n.pow(6) / 1935360,
        61 * n.pow(3) / 240 - 103 * n.pow(4) / 140 + 15061 * n.pow(5) / 26880 + 167603 * n.pow(6) / 181440,
        49561 * n.pow(4) / 161280 - 179 * n.pow(5) / 168 + 6601661 * n.pow(6) / 7257600,
        34729 * n.pow(5) / 80640 - 3418889 * n.pow(6) / 1995840,
        212378941 * n.pow(6) / 319334400,
    )
    private val beta = doubleArrayOf(
        n / 2 - 2 * n * n / 3 + 37 * n.pow(3) / 96 - n.pow(4) / 360 - 81 * n.pow(5) / 512 + 96199 * n.pow(6) / 604800,
        n * n / 48 + n.pow(3) / 15 - 437 * n.pow(4) / 1440 + 46 * n.pow(5) / 105 - 1118711 * n.pow(6) / 3870720,
        17 * n.pow(3) / 480 - 37 * n.pow(4) / 840 - 209 * n.pow(5) / 4480 + 5569 * n.pow(6) / 90720,
        4397 * n.pow(4) / 161280 - 11 * n.pow(5) / 504 - 830251 * n.pow(6) / 7257600,
        4583 * n.pow(5) / 161280 - 108847 * n.pow(6) / 3991680,
        20648693 * n.pow(6) / 638668800,
    )
    private val xi0 = forwardXiEta(Math.toRadians(latOriginDeg), 0.0).first

    private fun forwardXiEta(phi: Double, lambda: Double): Pair<Double, Double> {
        val e = ell.e
        val t = sinh(atanh(sin(phi)) - e * atanh(e * sin(phi)))
        val xiP = atan2(t, cos(lambda))
        val etaP = atanh(sin(lambda) / sqrt(1.0 + t * t))
        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            xi += alpha[j - 1] * sin(2 * j * xiP) * cosh(2 * j * etaP)
            eta += alpha[j - 1] * cos(2 * j * xiP) * sinh(2 * j * etaP)
        }
        return xi to eta
    }

    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val (xi, eta) = forwardXiEta(Math.toRadians(latDeg), Math.toRadians(lonDeg - centralMeridianDeg))
        return (falseEastingM + k0 * bigA * eta) to (falseNorthingM + k0 * bigA * (xi - xi0))
    }

    override fun inverse(easting: Double, northing: Double): Pair<Double, Double> {
        val xi = (northing - falseNorthingM) / (k0 * bigA) + xi0
        val eta = (easting - falseEastingM) / (k0 * bigA)
        var xiP = xi
        var etaP = eta
        for (j in 1..6) {
            xiP -= beta[j - 1] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j - 1] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val chi = asin(sin(xiP) / cosh(etaP))
        val lambda = atan2(sinh(etaP), cos(xiP))
        return Math.toDegrees(conformalToGeodetic(chi, ell.e)) to (centralMeridianDeg + Math.toDegrees(lambda))
    }
}

/** Lambert Conformal Conic, EPSG methods 9801 (1SP, [k0]) and 9802 (2SP). */
class LambertConformalConic(
    private val ell: Ellipsoid,
    latOriginDeg: Double,
    private val centralMeridianDeg: Double,
    standardParallel1Deg: Double?,
    standardParallel2Deg: Double?,
    k0: Double,
    private val falseEastingM: Double,
    private val falseNorthingM: Double,
) : Projection {
    private val e = ell.e
    private fun m(phi: Double) = cos(phi) / sqrt(1.0 - ell.e2 * sin(phi) * sin(phi))
    private fun t(phi: Double): Double {
        val es = e * sin(phi)
        return tan(PI / 4.0 - phi / 2.0) / ((1.0 - es) / (1.0 + es)).pow(e / 2.0)
    }

    private val nCone: Double
    private val bigF: Double
    private val rF: Double

    init {
        val phiF = Math.toRadians(latOriginDeg)
        if (standardParallel1Deg != null && standardParallel2Deg != null) {
            val p1 = Math.toRadians(standardParallel1Deg)
            val p2 = Math.toRadians(standardParallel2Deg)
            nCone = if (abs(p1 - p2) < 1e-12) sin(p1) else (ln(m(p1)) - ln(m(p2))) / (ln(t(p1)) - ln(t(p2)))
            bigF = m(p1) / (nCone * t(p1).pow(nCone))
        } else {
            nCone = sin(phiF)
            bigF = m(phiF) / (nCone * t(phiF).pow(nCone)) * k0
        }
        rF = ell.a * bigF * t(phiF).pow(nCone)
    }

    override fun forward(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val r = ell.a * bigF * t(Math.toRadians(latDeg)).pow(nCone)
        val theta = nCone * Math.toRadians(lonDeg - centralMeridianDeg)
        return (falseEastingM + r * sin(theta)) to (falseNorthingM + rF - r * cos(theta))
    }

    override fun inverse(easting: Double, northing: Double): Pair<Double, Double> {
        val dx = easting - falseEastingM
        val dy = rF - (northing - falseNorthingM)
        val r = sign(nCone) * sqrt(dx * dx + dy * dy)
        val theta = if (nCone > 0) atan2(dx, dy) else atan2(-dx, -dy)
        val tp = (r / (ell.a * bigF)).pow(1.0 / nCone)
        var phi = PI / 2.0 - 2.0 * atan(tp)
        repeat(12) {
            val es = e * sin(phi)
            phi = PI / 2.0 - 2.0 * atan(tp * ((1.0 - es) / (1.0 + es)).pow(e / 2.0))
        }
        return Math.toDegrees(phi) to (centralMeridianDeg + Math.toDegrees(theta / nCone))
    }
}
