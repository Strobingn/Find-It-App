package com.example.thermals

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NwsWindRegressionTest {
    private fun epoch(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun grid(speeds: String, directions: String) =
        """{"properties":{"windSpeed":{"values":[$speeds]},"windDirection":{"values":[$directions]}}}"""

    private fun interval(start: String, duration: String, value: String) =
        """{"validTime":"$start/$duration","value":$value}"""

    @Test
    fun splitsDirectionIntervalsWhenSpeedChangesInsideThem() {
        val body = grid(
            listOf(
                interval("2026-10-27T10:00:00+00:00", "PT1H", "10"),
                interval("2026-10-27T11:00:00+00:00", "PT2H", "30"),
            ).joinToString(","),
            interval("2026-10-27T10:00:00+00:00", "PT3H", "270"),
        )
        val samples = ThermalWeatherClient.parseNwsWind(body)
        assertEquals(2, samples.size)
        assertEquals(epoch("2026-10-27T11:00:00Z"), samples[0].utcEndMillis)
        assertEquals(samples[0].utcEndMillis, samples[1].utcStartMillis)
        assertEquals(10 * 0.621371, samples[0].speedMph, 1e-6)
        assertEquals(30 * 0.621371, samples[1].speedMph, 1e-6)
    }

    @Test
    fun intersectsIntervalsEvenWhenDirectionStartsBeforeSpeed() {
        val body = grid(
            interval("2026-10-27T11:00:00+00:00", "PT1H", "16"),
            interval("2026-10-27T10:00:00+00:00", "PT3H", "90"),
        )
        val sample = ThermalWeatherClient.parseNwsWind(body).single()
        assertEquals(epoch("2026-10-27T11:00:00Z"), sample.utcStartMillis)
        assertEquals(epoch("2026-10-27T12:00:00Z"), sample.utcEndMillis)
    }

    @Test
    fun supportsDayAndMinuteDurationsAndSkipsMissingValues() {
        val body = grid(
            listOf(
                interval("2026-10-27T10:00:00+00:00", "P1DT2H", "16"),
                interval("2026-10-28T12:00:00+00:00", "PT1H", "null"),
            ).joinToString(","),
            interval("2026-10-27T10:30:00+00:00", "PT90M", "270"),
        )
        val sample = ThermalWeatherClient.parseNwsWind(body).single()
        assertEquals(epoch("2026-10-27T10:30:00Z"), sample.utcStartMillis)
        assertEquals(epoch("2026-10-27T12:00:00Z"), sample.utcEndMillis)
    }

    @Test
    fun crossCheckUsesSiteMidnightIndependentOfDeviceTimeZone() {
        val original = TimeZone.getDefault()
        try {
            for (deviceZone in listOf("UTC", "America/New_York", "Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(deviceZone))
                for ((date, offset, start) in listOf(
                    Triple("2026-10-27", -240, "2026-10-27T10:00:00Z"),
                    Triple("2026-11-02", -300, "2026-11-02T11:00:00Z"),
                    Triple("2026-10-27", 330, "2026-10-27T00:30:00Z"),
                )) {
                    val days = listOf(ThermalWeatherDay(date, offset, listOf(HourlyWeather(6, 45.0, 4.0, 0.0, 0.0))))
                    val sample = NwsWindSample(epoch(start), epoch(start) + 3_600_000, 12.0, 90.0)
                    val badge = ThermalWindViewModel.nwsDisagreement(days, listOf(sample))
                    assertNotNull("$deviceZone, $date: the hour must be compared", badge)
                    assertTrue(badge!!.contains("1 of 1 hours"))
                }
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun crossCheckDoesNotBorrowTheWindFromAnAdjacentHour() {
        val days = listOf(ThermalWeatherDay("2026-10-27", -240, listOf(HourlyWeather(6, 45.0, 4.0, 0.0, 0.0))))
        val start = epoch("2026-10-27T10:00:00Z")
        val samples = listOf(
            NwsWindSample(start, start + 3_600_000, 4.0, 0.0),
            NwsWindSample(start + 3_600_000, start + 86_400_000, 30.0, 180.0),
        )
        assertNull(ThermalWindViewModel.nwsDisagreement(days, samples))
    }

    @Test
    fun directionIsNotComparedWhenEitherModelPredictsCalmWind() {
        val start = epoch("2026-10-27T10:00:00Z")
        for ((appSpeed, nwsSpeed) in listOf(0.5 to 4.0, 4.0 to 0.5)) {
            val days = listOf(ThermalWeatherDay("2026-10-27", -240,
                listOf(HourlyWeather(6, 45.0, appSpeed, 0.0, 0.0))))
            val sample = NwsWindSample(start, start + 3_600_000, nwsSpeed, 160.0)
            assertNull(ThermalWindViewModel.nwsDisagreement(days, listOf(sample)))
        }
    }

    @Test
    fun ignoresDirectionWrapAndCalmDirectionChanges() {
        val start = epoch("2026-10-27T10:00:00Z")
        fun check(speed: Double, appDir: Double, nwsDir: Double): String? {
            val days = listOf(ThermalWeatherDay("2026-10-27", -240, listOf(HourlyWeather(6, 45.0, speed, appDir, 0.0))))
            return ThermalWindViewModel.nwsDisagreement(days, listOf(NwsWindSample(start, start + 3_600_000, speed, nwsDir)))
        }
        assertNull(check(5.0, 350.0, 10.0))
        assertNull(check(1.0, 0.0, 180.0))
    }
}
