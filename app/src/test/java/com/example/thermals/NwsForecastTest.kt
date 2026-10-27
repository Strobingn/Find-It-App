package com.example.thermals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NwsForecastTest {
    private fun series(vararg v: Pair<String, Number>) =
        """{"values":[${v.joinToString(",") { """{"validTime":"${it.first}","value":${it.second}}""" }}]}"""

    @Test
    fun expandsIntervalsToLocalHours() {
        val body = """{"properties":{
            "temperature":${series("2026-10-27T10:00:00+00:00/PT8H" to 10)},
            "windSpeed":${series("2026-10-27T10:00:00+00:00/PT8H" to 16.0934)},
            "windDirection":${series("2026-10-27T10:00:00+00:00/PT8H" to 270)},
            "windGust":${series("2026-10-27T10:00:00+00:00/PT8H" to 32.1868)},
            "skyCover":${series("2026-10-27T10:00:00+00:00/PT8H" to 40)}}}"""
        val days = ThermalWeatherClient.parseNwsGrid(body, "America/New_York")
        assertEquals(1, days.size)
        val h = days[0].hourly
        assertEquals(8, h.size)
        assertEquals(6, h[0].hour) // 10:00 UTC = 06:00 EDT
        assertEquals(50.0, h[0].temperatureF, 0.01)
        assertEquals(10.0, h[0].windSpeedMph, 0.01)
        assertEquals(20.0, h[0].windGustMph!!, 0.01)
        assertTrue(days[0].windSource.contains("NWS"))
    }
}
