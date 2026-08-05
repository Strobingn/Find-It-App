package com.example.geospatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarPositionTest {

    private val siteLatitude = 41.4 // Hudson Valley

    @Test
    fun summerSolsticeNoonIsHighAndSouth() {
        val noon = SolarPosition.calculate(siteLatitude, dayOfYear = 172, hourLocalSolar = 12f)
        // 90 - 41.4 + 23.44 = 72.04 degrees.
        assertEquals(72.0f, noon.altitudeDegrees, 0.6f)
        assertEquals(180f, noon.azimuthDegrees, 1.5f)
        assertTrue(noon.isAboveHorizon)
    }

    @Test
    fun winterSolsticeNoonIsLowAndSouth() {
        val noon = SolarPosition.calculate(siteLatitude, dayOfYear = 355, hourLocalSolar = 12f)
        // 90 - 41.4 - 23.44 = 25.16 degrees.
        assertEquals(25.2f, noon.altitudeDegrees, 0.6f)
        assertEquals(180f, noon.azimuthDegrees, 1.5f)
    }

    @Test
    fun morningSunIsInTheEastAndEveningInTheWest() {
        val morning = SolarPosition.calculate(siteLatitude, dayOfYear = 172, hourLocalSolar = 9f)
        assertTrue(morning.azimuthDegrees in 45f..135f)
        assertTrue(morning.altitudeDegrees > 0f)

        val evening = SolarPosition.calculate(siteLatitude, dayOfYear = 172, hourLocalSolar = 15f)
        assertTrue(evening.azimuthDegrees in 225f..315f)
        assertTrue(evening.altitudeDegrees > 0f)
    }

    @Test
    fun equinoxSunriseIsDueEastOnTheHorizon() {
        val sunrise = SolarPosition.calculate(siteLatitude, dayOfYear = 80, hourLocalSolar = 6f)
        assertEquals(90f, sunrise.azimuthDegrees, 2f)
        assertEquals(0f, sunrise.altitudeDegrees, 1f)
    }

    @Test
    fun midnightSunIsBelowTheHorizon() {
        val midnight = SolarPosition.calculate(siteLatitude, dayOfYear = 355, hourLocalSolar = 0f)
        assertTrue(midnight.altitudeDegrees < 0f)
        assertTrue(!midnight.isAboveHorizon)
    }

    @Test
    fun outOfRangeInputsAreClampedNotCrash() {
        val position = SolarPosition.calculate(95.0, dayOfYear = 999, hourLocalSolar = 37f)
        assertTrue(position.altitudeDegrees.isFinite())
        assertTrue(position.azimuthDegrees in 0f..360f)
    }

    @Test
    fun dateLabelsRollAcrossMonths() {
        assertEquals("Jan 1", SolarPosition.dateLabel(1))
        assertEquals("Jun 21", SolarPosition.dateLabel(172))
        assertEquals("Dec 21", SolarPosition.dateLabel(355))
        assertEquals("Dec 31", SolarPosition.dateLabel(365))
    }
}
