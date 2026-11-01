package com.example.thermals

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThermalWeatherCancellationTest {
    private fun cancellingClient(onRequest: () -> Unit) = ThermalWeatherClient(
        httpClient = OkHttpClient.Builder().addInterceptor {
            onRequest()
            throw CancellationException("Cancelled request")
        }.build(),
    )

    @Test
    fun cancelledLidarRequestDoesNotStartAnotherElevationSource() {
        var requests = 0
        val client = cancellingClient { requests++ }
        try {
            runBlocking { client.fetchTerrain(41.23456, -74.65432) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals("No fallback elevation request after cancellation", 1, requests)
        }
    }

    @Test
    fun optionalWindSourcesPropagateCancellation() {
        val client = cancellingClient {}
        try {
            runBlocking { client.fetchQuarterHourly(42.75, -75.5) }
            fail("Quarter-hour cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
        try {
            runBlocking { client.fetchNwsWind(42.75, -75.5) }
            fail("NWS cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun unknownResolutionIsNotAdvertisedAsOneMeterLidar() {
        assertNull(ThermalWeatherClient.parseFineElevation("""{"value":300}"""))
        assertNull(ThermalWeatherClient.parseFineElevation("""{"value":300,"resolution":0}"""))
        assertNull(ThermalWeatherClient.parseFineElevation("""{"value":300,"resolution":10}"""))
        assertNull(ThermalWeatherClient.parseFineElevation("""{"value":-1000000,"resolution":1}"""))
        assertEquals(300.0 to 1.0, ThermalWeatherClient.parseFineElevation("""{"value":300,"resolution":1}"""))
    }
}
