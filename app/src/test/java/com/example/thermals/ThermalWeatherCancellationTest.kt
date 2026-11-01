package com.example.thermals

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThermalWeatherCancellationTest {
    /** Hold a real OkHttp request open, then cancel its coroutine and verify the call is cancelled. */
    private fun checkCancellation(fetch: suspend (ThermalWeatherClient) -> Unit) = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            started.complete(Unit)
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"value":300,"resolution":1}""".toResponseBody())
                .build()
        }.build()
        val client = ThermalWeatherClient(httpClient = http)
        val job = launch { fetch(client) }
        try {
            kotlinx.coroutines.withTimeout(5_000) { started.await() }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals("Cancellation must not start a fallback request", 1, requests.get())
            assertTrue("The HTTP call must be cancelled too", http.dispatcher.runningCalls().all { it.isCanceled() })
        } finally {
            release.countDown()
            job.cancelAndJoin()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun cancelledLidarRequestDoesNotStartAnotherElevationSource() {
        checkCancellation { it.fetchTerrain(41.23456, -74.65432) }
    }

    @Test
    fun optionalWindSourcesPropagateCancellation() {
        checkCancellation { it.fetchQuarterHourly(42.75, -75.5) }
        checkCancellation { it.fetchNwsWind(42.75, -75.5) }
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
