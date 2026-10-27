package com.example.thermals

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Geometry for drawing thermals on a map: scent cones, offsets and local slope from elevations. */
object ThermalMapGeometry {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val MPH_TO_MPS = 0.44704

    /** How far scent drifts in [minutes] at [mph], clamped so the cone stays readable on a stand-sized map. */
    fun driftMeters(mph: Double, minutes: Double = 10.0): Double =
        (mph * MPH_TO_MPS * minutes * 60.0).coerceIn(80.0, 1_500.0)

    /** Cone half-width: tight when confident, wide when not; a switching thermal swirls all around. */
    fun coneHalfAngle(phase: ThermalPhase, confidence: Double): Double =
        if (phase == ThermalPhase.SWITCHING) 180.0 else 15.0 + (1.0 - confidence.coerceIn(0.0, 1.0)) * 35.0

    /** Great-circle destination from ([lat], [lon]) along [bearingDeg] for [meters]. Returns (lat, lon). */
    fun destination(lat: Double, lon: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val d = meters / EARTH_RADIUS_M
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    /**
     * Closed polygon ring (lat, lon) for the scent cone: apex at the stand, opening toward
     * [towardDeg]. A half-angle of 180° gives a full circle (swirling scent).
     */
    fun scentCone(
        lat: Double,
        lon: Double,
        towardDeg: Double,
        lengthMeters: Double,
        halfAngleDeg: Double,
        arcSteps: Int = 18,
    ): List<Pair<Double, Double>> {
        val half = halfAngleDeg.coerceIn(5.0, 180.0)
        val ring = mutableListOf<Pair<Double, Double>>()
        val fullCircle = half >= 179.0
        if (!fullCircle) ring += lat to lon
        val steps = if (fullCircle) arcSteps * 2 else arcSteps
        for (i in 0..steps) {
            val bearing = towardDeg - half + (2.0 * half) * i / steps
            ring += destination(lat, lon, bearing, lengthMeters)
        }
        ring += ring.first()
        return ring
    }

    /**
     * Local slope from four elevation samples taken [spacingMeters] north/south/east/west of a
     * point. Returns (uphill bearing or null on flat ground, slope in degrees).
     */
    fun slopeFromSamples(
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        spacingMeters: Double,
    ): Pair<Double?, Double> {
        val dzNorth = (north - south) / (2.0 * spacingMeters)
        val dzEast = (east - west) / (2.0 * spacingMeters)
        val rise = hypot(dzEast, dzNorth)
        val slope = Math.toDegrees(atan(rise))
        val uphill = if (rise < 0.01) null else ThermalWindEngine.normalize(Math.toDegrees(atan2(dzEast, dzNorth)))
        return uphill to slope
    }

    /** Coarse flat-or-slope label when a center elevation isn't available. */
    fun cellTerrain(uphill: Double?, slopeDeg: Double): SiteTerrain = when {
        uphill == null || slopeDeg < 1.5 -> SiteTerrain(TerrainSetting.FLAT, null, 0.0, source = "Map")
        else -> SiteTerrain(TerrainSetting.SLOPE, uphill, slopeDeg.coerceAtMost(45.0), source = "Map")
    }

    /**
     * Landform from the center elevation and the four neighbors [spacingMeters] away.
     * A crest (center above the ring) is a ridge: thermals lift in place. A hollow is a
     * valley/draw and keeps the uphill bearing so drainage runs along the channel.
     * A plane is a slope. This is what the wind map and stand cells use.
     */
    fun terrainFromNeighborhood(
        center: Double,
        north: Double,
        south: Double,
        east: Double,
        west: Double,
        spacingMeters: Double,
        source: String = "Map",
    ): SiteTerrain {
        val (uphill, slope) = slopeFromSamples(north, south, east, west, spacingMeters)
        val relief = center - (north + south + east + west) / 4.0
        val bump = (6.0 * spacingMeters / 100.0).coerceAtLeast(2.0)
        val limitedSlope = slope.coerceAtMost(45.0)
        return when {
            relief > bump && (uphill == null || slope < 12.0) ->
                SiteTerrain(TerrainSetting.RIDGE, uphillBearingDegrees = null, slopeDegrees = limitedSlope, source = source)
            relief < -bump ->
                SiteTerrain(TerrainSetting.VALLEY, uphill, limitedSlope, source = source)
            uphill == null || slope < 1.5 ->
                SiteTerrain(TerrainSetting.FLAT, null, 0.0, source = source)
            else ->
                SiteTerrain(TerrainSetting.SLOPE, uphill, limitedSlope, source = source)
        }
    }
}
