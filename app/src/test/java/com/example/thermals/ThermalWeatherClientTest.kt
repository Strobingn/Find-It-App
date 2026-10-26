package com.example.thermals

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThermalWeatherClientTest {

    @Test
    fun parsesOpenMeteoHourlyIntoLocalDays() {
        val times = JSONArray()
        val temps = JSONArray()
        val speeds = JSONArray()
        val dirs = JSONArray()
        val clouds = JSONArray()
        for (d in 26..27) {
            for (h in 0..23) {
                times.put("2026-10-%02dT%02d:00".format(d, h))
                temps.put(30.0 + h)
                speeds.put(4.0)
                dirs.put(300.0)
                clouds.put(20.0)
            }
        }
        temps.put(5, JSONObject.NULL) // a missing hour is skipped, not fatal
        val body = JSONObject()
            .put("utc_offset_seconds", -14_400)
            .put(
                "hourly",
                JSONObject()
                    .put("time", times)
                    .put("temperature_2m", temps)
                    .put("wind_speed_10m", speeds)
                    .put("wind_direction_10m", dirs)
                    .put("cloud_cover", clouds),
            )
            .toString()

        val days = ThermalWeatherClient.parseForecast(body)
        assertEquals(2, days.size)
        assertEquals("2026-10-26", days[0].date)
        assertEquals(-240, days[0].utcOffsetMinutes)
        assertEquals(23, days[0].hourly.size)
        assertEquals(24, days[1].hourly.size)
        val six = days[0].hourly.first { it.hour == 6 }
        assertEquals(36.0, six.temperatureF, 0.0)
        assertEquals(300.0, six.windFromDegrees, 0.0)
        assertEquals(20.0, six.cloudCoverPercent, 0.0)
    }

    @Test
    fun samplePointsRingTheStand() {
        val points = ThermalWeatherClient.terrainSamplePoints(42.0, -75.0)
        assertEquals(17, points.size)
        assertEquals(42.0 to -75.0, points[0])
        assertTrue("first inner point is north", points[1].first > 42.0 && abs(points[1].second + 75.0) < 1e-9)
        assertTrue("third inner point is east", points[3].second > -75.0)
    }

    /** Elevation for a plane rising [gradePerMeter] toward [uphillDeg], plus optional bowl/dome curvature. */
    private fun synthetic(uphillDeg: Double, gradePerMeter: Double, curvature: Double = 0.0): List<Double> {
        val center = 300.0
        val out = mutableListOf(center)
        for (radius in listOf(ThermalWeatherClient.INNER_RING_METERS, ThermalWeatherClient.OUTER_RING_METERS)) {
            for (i in 0 until 8) {
                val b = Math.toRadians(i * 45.0)
                val east = radius * sin(b)
                val north = radius * cos(b)
                val u = Math.toRadians(uphillDeg)
                val along = east * sin(u) + north * cos(u)
                out += center + along * gradePerMeter + curvature * radius * radius
            }
        }
        return out
    }

    @Test
    fun planarSlopeGivesUphillBearingAndSteepness() {
        val terrain = ThermalWeatherClient.terrainFromSamples(synthetic(uphillDeg = 135.0, gradePerMeter = 0.2))
        assertEquals(TerrainSetting.SLOPE, terrain.setting)
        assertEquals(135.0, terrain.uphillBearingDegrees!!, 1.0)
        assertEquals(Math.toDegrees(Math.atan(0.2)), terrain.slopeDegrees, 0.5)
        assertNotNull(terrain.elevationFeet)
    }

    @Test
    fun bowlReadsAsValleyAndDomeAsRidge() {
        val valley = ThermalWeatherClient.terrainFromSamples(synthetic(0.0, 0.03, curvature = 0.0002))
        assertEquals(TerrainSetting.VALLEY, valley.setting)
        val ridge = ThermalWeatherClient.terrainFromSamples(synthetic(0.0, 0.0, curvature = -0.0002))
        assertEquals(TerrainSetting.RIDGE, ridge.setting)
    }

    @Test
    fun levelGroundReadsAsFlat() {
        val flat = ThermalWeatherClient.terrainFromSamples(List(17) { 250.0 })
        assertEquals(TerrainSetting.FLAT, flat.setting)
        assertNull(flat.uphillBearingDegrees)
    }

    private fun <T> inNewYork(block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun viewModelBuildsManualInputsFromUserTemps() = inNewYork {
        val state = ThermalUiState(latitudeText = "42.75", longitudeText = "-75.5")
        val today = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 26, 12, 0, 0)
        }
        val inputs = ThermalWindViewModel.buildInputs(state, today)!!
        assertEquals(299, inputs.dayOfYear)
        assertEquals(-240, inputs.utcOffsetMinutes)
        assertEquals(24, inputs.hourly.size)
        assertEquals(32.0, inputs.hourly[6].temperatureF, 0.01)
        assertEquals(66.0, inputs.hourly[12].temperatureF, 0.01)
        assertEquals(TerrainSetting.SLOPE, inputs.terrain.setting)
        assertEquals(GroundCover.WOODS.windFactor, inputs.groundWindFactor, 0.0)
        assertNotNull(ThermalWindEngine.forecast(inputs).morningSwitchHour)
    }

    @Test
    fun viewModelNeedsALocation() {
        assertNull(ThermalWindViewModel.buildInputs(ThermalUiState()))
        assertNull(ThermalWindViewModel.buildInputs(ThermalUiState(latitudeText = "95", longitudeText = "-75")))
    }

    @Test
    fun viewModelUsesForecastDayWhenSelected() {
        val hours = (0..23).map { HourlyWeather(it, 40.0 + it, 3.0, 200.0, 50.0) }
        val state = ThermalUiState(
            latitudeText = "42.75",
            longitudeText = "-75.5",
            source = ThermalWeatherSource.FORECAST,
            forecastDays = listOf(ThermalWeatherDay("2026-10-27", -240, hours)),
        )
        val inputs = ThermalWindViewModel.buildInputs(state)!!
        assertEquals(300, inputs.dayOfYear)
        assertEquals(-240, inputs.utcOffsetMinutes)
        assertEquals(hours, inputs.hourly)
    }

    @Test
    fun aiContextCarriesTheHourlyTableAndSwitchTimes() = inNewYork {
        val state = ThermalUiState(latitudeText = "42.75", longitudeText = "-75.5")
        val forecast = ThermalWindEngine.forecast(ThermalWindViewModel.buildInputs(state)!!)
        val prompt = ThermalAiAdvisor.buildContext(forecast, "North ridge stand", "Mon Oct 26")
        assertTrue(prompt.contains("North ridge stand"))
        assertTrue(prompt.contains("MORNING THERMAL SWITCH"))
        assertTrue(prompt.contains("NET SCENT"))
        assertTrue(prompt.contains("6:00 AM | 32"))
        assertTrue(prompt.contains("12:00 PM | 66"))
    }
}
