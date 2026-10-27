package com.example.thermals

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalWindEngineTest {

    // Central New York, late October, EDT.
    private val lat = 42.75
    private val lon = -75.5
    private val dayOfYear = 299 // Oct 26
    private val edt = -240

    private fun day(
        readings: List<Pair<Int, Double>> = listOf(6 to 32.0, 12 to 66.0),
        windMph: Double = 0.0,
        windFrom: Double = 315.0,
        cloud: Double = 0.0,
    ): List<HourlyWeather> {
        val curve = DiurnalTemperatureCurve.build(readings.map { DiurnalTemperatureCurve.Reading(it.first.toDouble(), it.second) }, sunriseHour = 7.4)
        return curve.mapIndexed { h, t -> HourlyWeather(h, t, windMph, windFrom, cloud) }
    }

    private fun slope(uphill: Double, deg: Double = 15.0) =
        SiteTerrain(TerrainSetting.SLOPE, uphillBearingDegrees = uphill, slopeDegrees = deg)

    private fun run(terrain: SiteTerrain, weather: List<HourlyWeather> = day()) =
        ThermalWindEngine.forecast(ThermalInputs(lat, lon, dayOfYear, edt, weather, terrain))

    private fun angleDiff(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

    @Test
    fun aHillsideStillSplitsTheDriftBetweenThermalAndWind() {
        // The positive counterpart to flatAndRidgeDoNotCountVerticalThermalsAsScentShare: where
        // the thermal does push air sideways it keeps its share of the drift.
        val wind = day(windMph = 4.0, windFrom = 315.0)
        val noon = run(slope(uphill = 0.0), wind).hour(12)!!

        assertNotNull(noon.thermalTowardDegrees)
        assertTrue("hillside thermal should own part of the drift", noon.thermalShare > 0.1)
        assertTrue(noon.thermalShare < 1.0)
    }

    @Test
    fun coldDawnDrainsDownhillAndWarmNoonRisesUphill() {
        // Uphill is to the north → morning scent goes south (downhill), midday north (uphill).
        val forecast = run(slope(uphill = 0.0))
        val six = forecast.hour(6)!!
        val noon = forecast.hour(12)!!
        assertEquals(ThermalPhase.DRAINING, six.phase)
        assertEquals(180.0, six.thermalTowardDegrees!!, 0.001)
        assertEquals(ThermalPhase.RISING, noon.phase)
        assertEquals(0.0, noon.thermalTowardDegrees!!, 0.001)
        assertTrue("noon thermal should be a real breeze", noon.thermalSpeedMph > 1.0)
    }

    @Test
    fun morningSwitchFallsAfterSunriseAndEveningSwitchBeforeSunset() {
        val forecast = run(slope(uphill = 0.0))
        val sunrise = forecast.sunriseLocalHour!!
        val sunset = forecast.sunsetLocalHour!!
        val morning = forecast.morningSwitchHour!!
        val evening = forecast.eveningSwitchHour!!
        assertTrue("morning switch $morning should follow sunrise $sunrise", morning > sunrise)
        assertTrue("morning switch $morning should be within ~3.5 h of sunrise", morning < sunrise + 3.5)
        assertTrue("evening switch $evening should precede sunset $sunset", evening < sunset)
        assertTrue(evening > 13.0)
    }

    @Test
    fun eastFacingSlopeSwitchesBeforeWestFacingSlope() {
        // East-facing = uphill to the west; west-facing = uphill to the east.
        val eastFacing = run(slope(uphill = 270.0, deg = 20.0)).morningSwitchHour!!
        val westFacing = run(slope(uphill = 90.0, deg = 20.0)).morningSwitchHour!!
        assertTrue("east face $eastFacing should warm before west face $westFacing", eastFacing < westFacing)
    }

    @Test
    fun westFacingSlopeHoldsRisingThermalLaterInEvening() {
        val eastFacing = run(slope(uphill = 270.0, deg = 20.0)).eveningSwitchHour!!
        val westFacing = run(slope(uphill = 90.0, deg = 20.0)).eveningSwitchHour!!
        assertTrue("west face $westFacing should stay warm after east face $eastFacing", westFacing > eastFacing)
    }

    @Test
    fun biggerTemperatureSwingMakesStrongerThermals() {
        val strong = run(slope(0.0), day(listOf(6 to 32.0, 12 to 66.0))).hour(12)!!.thermalSpeedMph
        val weak = run(slope(0.0), day(listOf(6 to 50.0, 12 to 56.0))).hour(12)!!.thermalSpeedMph
        assertTrue("34° swing ($strong) should beat 6° swing ($weak)", strong > weak)
    }

    @Test
    fun overcastWeakensMiddayThermal() {
        val clear = run(slope(0.0), day(cloud = 0.0)).hour(12)!!.thermalSpeedMph
        val overcast = run(slope(0.0), day(cloud = 100.0)).hour(12)!!.thermalSpeedMph
        assertTrue(overcast < clear)
    }

    @Test
    fun strongPrevailingWindOverridesThermals() {
        val windy = run(slope(0.0), day(windMph = 20.0, windFrom = 270.0)).hour(12)!!
        assertEquals(ThermalPhase.WIND_DOMINATED, windy.phase)
        // Wind from the west pushes scent east, regardless of the uphill thermal to the north.
        assertTrue(angleDiff(windy.scentTowardDegrees, 90.0) < 30.0)
        assertTrue(windy.thermalShare < 0.35)
    }

    @Test
    fun scentIsVectorSumOfThermalAndWind() {
        // Light wind from the south (blowing north) + rising thermal uphill to the east → scent heads NE-ish.
        val h = run(slope(uphill = 90.0, deg = 25.0), day(windMph = 4.0, windFrom = 180.0)).hour(13)!!
        assertEquals(ThermalPhase.RISING, h.phase)
        assertTrue("scent ${h.scentTowardDegrees} should sit between N and E", h.scentTowardDegrees in 1.0..89.0)
    }

    @Test
    fun flatGroundHasNoHorizontalThermalDirection() {
        val flat = SiteTerrain(TerrainSetting.FLAT, uphillBearingDegrees = null, slopeDegrees = 0.0)
        val forecast = run(flat, day(windMph = 6.0, windFrom = 0.0))
        val noon = forecast.hour(12)!!
        assertNull(noon.thermalTowardDegrees)
        assertTrue(angleDiff(noon.scentTowardDegrees, 180.0) < 1.0)
        assertTrue(ThermalWindEngine.summarize(forecast).any { "flat ground" in it.lowercase() })
    }

    @Test
    fun valleyThermalsRunUpAndDownTheValleyAxis() {
        val valley = SiteTerrain(TerrainSetting.VALLEY, uphillBearingDegrees = 45.0, slopeDegrees = 4.0)
        val forecast = run(valley)
        assertEquals(225.0, forecast.hour(6)!!.thermalTowardDegrees!!, 0.001)
        assertEquals(45.0, forecast.hour(13)!!.thermalTowardDegrees!!, 0.001)
    }

    @Test
    fun sunExposureFavorsSlopesFacingTheSun() {
        // Sun due south at 30°: a south-facing slope (uphill north) sees more sun than a north-facing one.
        val southFacing = ThermalWindEngine.slopeExposure(slope(0.0, 20.0), 30.0, 180.0)
        val northFacing = ThermalWindEngine.slopeExposure(slope(180.0, 20.0), 30.0, 180.0)
        assertTrue(southFacing > northFacing)
        assertEquals(0.0, ThermalWindEngine.slopeExposure(slope(0.0), -5.0, 180.0), 0.0)
    }

    @Test
    fun summaryMentionsSwitchTimesAndDirections() {
        val lines = ThermalWindEngine.summarize(run(slope(uphill = 0.0)))
        val text = lines.joinToString("\n")
        assertTrue(text, "downhill toward the S" in text)
        assertTrue(text, "uphill toward the N" in text)
        assertTrue(text, "strong thermals" in text)
    }

    @Test
    fun compassAndClockFormatting() {
        assertEquals("N", ThermalWindEngine.compass(359.0))
        assertEquals("NE", ThermalWindEngine.compass(45.0))
        assertEquals("WSW", ThermalWindEngine.compass(247.5))
        assertEquals("6:00 AM", ThermalWindEngine.formatHour(6.0))
        assertEquals("12:00 PM", ThermalWindEngine.formatHour(12.0))
        assertEquals("8:30 AM", ThermalWindEngine.formatHour(8.5))
        assertEquals("12:00 AM", ThermalWindEngine.formatHour(0.0))
    }

    @Test
    fun diurnalCurvePassesThroughEveryReading() {
        val curve = DiurnalTemperatureCurve.build(
            listOf(DiurnalTemperatureCurve.Reading(6.0, 32.0), DiurnalTemperatureCurve.Reading(12.0, 66.0)),
            sunriseHour = 7.0,
        )
        assertEquals(24, curve.size)
        assertEquals(32.0, curve[6], 0.01)
        assertEquals(66.0, curve[12], 0.01)
        assertTrue("should keep warming into early afternoon", curve[14] >= curve[12])
        assertTrue("should cool by evening", curve[20] < curve[14])
        assertTrue("coldest near sunrise, not before", curve[7] <= curve[6])
        assertTrue("monotonic morning warm-up", (8..12).all { curve[it] >= curve[it - 1] - 0.01 })
    }

    @Test
    fun weatherSeriesInterpolatesWindDirectionAcrossNorth() {
        val series = WeatherSeries(
            listOf(
                HourlyWeather(0, 40.0, 5.0, 350.0, 0.0),
                HourlyWeather(2, 44.0, 5.0, 10.0, 0.0),
            ),
        )
        assertEquals(0.0, series.windFrom(1.0), 0.001)
        assertEquals(42.0, series.temperature(1.0), 0.001)
        assertEquals(2.0, series.warmingRate(1.0), 0.001)
    }

    @Test
    fun flatAndRidgeDoNotCountVerticalThermalsAsScentShare() {
        val flat = SiteTerrain(TerrainSetting.FLAT, uphillBearingDegrees = null, slopeDegrees = 0.0)
        val ridge = SiteTerrain(TerrainSetting.RIDGE, uphillBearingDegrees = 0.0, slopeDegrees = 8.0)
        val noon = run(flat, day(windMph = 6.0, windFrom = 0.0)).hour(12)!!
        assertNull(noon.thermalTowardDegrees)
        assertEquals(0.0, noon.thermalShare, 0.001)
        assertTrue(angleDiff(noon.scentTowardDegrees, 180.0) < 1.0)
        assertEquals("Rising", ThermalWindEngine.phaseText(noon))
        val ridgeNoon = run(ridge, day(windMph = 0.0)).hour(12)!!
        assertNull(ridgeNoon.thermalTowardDegrees)
        assertEquals(0.0, ridgeNoon.thermalShare, 0.001)
        assertFalse(ThermalWindEngine.phaseText(ridgeNoon).contains("uphill"))
        assertFalse(ThermalWindEngine.phaseText(ridgeNoon).contains("downhill"))
    }

    @Test
    fun calmAirDoesNotInventANorthBearing() {
        assertNull(ThermalWindEngine.bearingOrNull(0.0, 0.0))
        assertEquals(0.0, ThermalWindEngine.bearingOrNull(0.0, 1.0)!!, 0.001)
        val flat = SiteTerrain(TerrainSetting.FLAT, null, 0.0)
        val still = run(flat, day(windMph = 0.0)).hour(3)!!
        assertTrue(still.scentSpeedMph < ThermalWindEngine.CALM_SCENT_MPH)
        assertTrue(ThermalWindEngine.adviceFor(still).contains("nearly still"))
        assertFalse(ThermalWindEngine.adviceFor(still).contains("toward the N"))
    }

    @Test
    fun adviceNamesTheDownwindSide() {
        val noon = run(slope(uphill = 0.0)).hour(12)!!
        val advice = ThermalWindEngine.adviceFor(noon)
        assertNotNull(advice)
        assertTrue(advice, "toward the N" in advice)
        assertTrue(advice, "to the S side" in advice)
    }
}
