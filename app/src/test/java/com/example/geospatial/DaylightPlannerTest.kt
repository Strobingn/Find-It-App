package com.example.geospatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DaylightPlannerTest {

    // Cornwall-on-Hudson, NY — the app's home ground.
    private val siteLat = 41.44
    private val siteLon = -73.99

    @Test
    fun `summer solstice daylight matches known values`() {
        val window = DaylightPlanner.compute(siteLat, siteLon, DaylightPlanner.dayOfYear(2026, 6, 21))
        assertNotNull(window.sunriseUtcMinutes)
        assertNotNull(window.sunsetUtcMinutes)
        // EDT (UTC-4) sunrise ~05:21 → UTC ~09:21 = 561 min; allow generous tolerance.
        assertEquals(561f, window.sunriseUtcMinutes!!, 12f)
        // Day length on the solstice at 41.4N is about 15 h 07 m.
        assertEquals(907f, window.dayLengthMinutes!!, 12f)
    }

    @Test
    fun `winter day is shorter than summer day`() {
        val summer = DaylightPlanner.compute(siteLat, siteLon, DaylightPlanner.dayOfYear(2026, 6, 21))
        val winter = DaylightPlanner.compute(siteLat, siteLon, DaylightPlanner.dayOfYear(2026, 12, 21))
        assertTrue(winter.dayLengthMinutes!! < summer.dayLengthMinutes!!)
        // Winter solstice day length ~9 h 11 m at this latitude.
        assertEquals(551f, winter.dayLengthMinutes!!, 12f)
    }

    @Test
    fun `polar day reports no sunrise or sunset`() {
        val window = DaylightPlanner.compute(80.0, 0.0, DaylightPlanner.dayOfYear(2026, 6, 21))
        assertTrue(window.isPolar)
        assertNull(window.sunriseUtcMinutes)
        assertNull(window.dayLengthMinutes)
    }

    @Test
    fun `solar noon sits between sunrise and sunset`() {
        val window = DaylightPlanner.compute(siteLat, siteLon, DaylightPlanner.dayOfYear(2026, 4, 15))
        val sunrise = window.sunriseUtcMinutes!!
        val sunset = window.sunsetUtcMinutes!!
        assertTrue(window.solarNoonUtcMinutes > sunrise)
        assertTrue(window.solarNoonUtcMinutes < sunset)
    }

    @Test
    fun `formatLocal wraps across midnight and renders 12-hour clock`() {
        assertEquals("5:21 AM", DaylightPlanner.formatLocal(561f, -240))
        assertEquals("12:05 AM", DaylightPlanner.formatLocal(5f, 0))
        assertEquals("12:00 PM", DaylightPlanner.formatLocal(720f, 0))
        // Sunset at 00:31 UTC renders as the previous local evening.
        assertEquals("8:31 PM", DaylightPlanner.formatLocal(1471f, -240))
    }

    @Test
    fun `dayOfYear handles leap years`() {
        assertEquals(60, DaylightPlanner.dayOfYear(2026, 3, 1)) // non-leap Mar 1
        assertEquals(60, DaylightPlanner.dayOfYear(2024, 2, 29))
        assertEquals(61, DaylightPlanner.dayOfYear(2024, 3, 1))
        assertEquals(172, DaylightPlanner.dayOfYear(2026, 6, 21))
    }

    @Test
    fun `solar day maps leap March 1 onto the non-leap formula`() {
        assertEquals(60, DaylightPlanner.solarDayOfYear(2024, 3, 1))
        assertEquals(DaylightPlanner.solarDayOfYear(2023, 3, 1), DaylightPlanner.solarDayOfYear(2024, 3, 1))
        assertEquals(59, DaylightPlanner.solarDayOfYear(2024, 2, 29))
        assertEquals(365, DaylightPlanner.solarDayOfYear(2024, 12, 31))
        val leap = DaylightPlanner.compute(siteLat, siteLon, DaylightPlanner.solarDayOfYear(2024, 3, 1))
        val common = DaylightPlanner.compute(siteLat, siteLon, DaylightPlanner.solarDayOfYear(2023, 3, 1))
        assertEquals(common.solarNoonUtcMinutes, leap.solarNoonUtcMinutes, 0.05f)
    }
}
