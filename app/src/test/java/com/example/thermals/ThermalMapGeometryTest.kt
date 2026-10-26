package com.example.thermals

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalMapGeometryTest {

    @Test
    fun destinationMovesTheRightWayAndDistance() {
        val (lat, lon) = ThermalMapGeometry.destination(42.0, -75.0, 0.0, 1_000.0)
        assertEquals(42.0 + 1_000.0 / 111_195.0, lat, 1e-4)
        assertEquals(-75.0, lon, 1e-9)
        val (_, eastLon) = ThermalMapGeometry.destination(42.0, -75.0, 90.0, 1_000.0)
        assertTrue(eastLon > -75.0)
    }

    @Test
    fun coneOpensTowardScentDirection() {
        val ring = ThermalMapGeometry.scentCone(42.0, -75.0, towardDeg = 90.0, lengthMeters = 500.0, halfAngleDeg = 20.0)
        assertEquals(ring.first(), ring.last())
        assertEquals(42.0 to -75.0, ring.first()) // apex at the stand
        val tip = ring[ring.size / 2]
        assertTrue("cone should extend east", tip.second > -75.0)
        assertTrue(abs(tip.first - 42.0) < 0.002)
    }

    @Test
    fun switchingConeIsAFullCircle() {
        val ring = ThermalMapGeometry.scentCone(42.0, -75.0, 0.0, 200.0, ThermalMapGeometry.coneHalfAngle(ThermalPhase.SWITCHING, 0.5))
        assertTrue("no apex point for a swirl", ring.none { it == (42.0 to -75.0) })
        assertEquals(ring.first(), ring.last())
    }

    @Test
    fun driftAndConeWidthScaleSensibly() {
        assertEquals(80.0, ThermalMapGeometry.driftMeters(0.0), 0.0)
        assertEquals(2.0 * 0.44704 * 600.0, ThermalMapGeometry.driftMeters(2.0), 1e-6)
        assertEquals(1_500.0, ThermalMapGeometry.driftMeters(40.0), 0.0)
        assertTrue(ThermalMapGeometry.coneHalfAngle(ThermalPhase.RISING, 0.9) < ThermalMapGeometry.coneHalfAngle(ThermalPhase.RISING, 0.3))
    }

    @Test
    fun slopeFromSamplesFindsUphill() {
        // Ground rises 12 m over 120 m toward the east → ~5.7°, uphill E.
        val (uphill, slope) = ThermalMapGeometry.slopeFromSamples(north = 100.0, south = 100.0, east = 106.0, west = 94.0, spacingMeters = 60.0)
        assertEquals(90.0, uphill!!, 0.5)
        assertEquals(Math.toDegrees(Math.atan(0.1)), slope, 0.1)
        val (flat, _) = ThermalMapGeometry.slopeFromSamples(100.0, 100.0, 100.0, 100.0, 60.0)
        assertNull(flat)
        assertEquals(TerrainSetting.FLAT, ThermalMapGeometry.cellTerrain(null, 0.0).setting)
    }

    @Test
    fun flowFieldTreatsEachSlopeOnItsOwn() {
        val curve = DiurnalTemperatureCurve.build(
            listOf(DiurnalTemperatureCurve.Reading(6.0, 32.0), DiurnalTemperatureCurve.Reading(12.0, 66.0)),
            sunriseHour = 7.4,
        )
        val inputs = ThermalInputs(
            latitude = 42.75,
            longitude = -75.5,
            dayOfYear = 299,
            utcOffsetMinutes = -240,
            hourly = curve.mapIndexed { h, t -> HourlyWeather(h, t, 0.0, 0.0, 0.0) },
            terrain = SiteTerrain(TerrainSetting.SLOPE, 0.0, 15.0),
        )
        val field = ThermalWindEngine.flowField(inputs)
        val eastFace = SiteTerrain(TerrainSetting.SLOPE, uphillBearingDegrees = 270.0, slopeDegrees = 20.0)
        val westFace = SiteTerrain(TerrainSetting.SLOPE, uphillBearingDegrees = 90.0, slopeDegrees = 20.0)
        // Mid-morning: the sunlit east face has turned uphill while the shaded west face still drains.
        val t = 9.0
        val east = field.at(t, eastFace)
        val west = field.at(t, westFace)
        assertTrue("east face tendency ${east.tendency} should exceed west ${west.tendency}", east.tendency > west.tendency)
        assertEquals(ThermalPhase.RISING, east.phase)
        // Field at the stand's own terrain matches the hourly forecast.
        val forecast = ThermalWindEngine.forecast(inputs)
        assertEquals(forecast.hour(12)!!.scentTowardDegrees, field.at(12.0).scentTowardDegrees, 1e-9)
    }
}
