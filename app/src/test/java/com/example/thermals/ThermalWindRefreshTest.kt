package com.example.thermals

import androidx.lifecycle.ViewModelStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ThermalWindRefreshTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val store = ViewModelStore()

    private class Weather : ThermalWeatherProvider {
        var forecast = CompletableDeferred<List<ThermalWeatherDay>>()
        val terrain = CompletableDeferred<SiteTerrain>()
        val quarters = CompletableDeferred<List<QuarterWind>>()
        val nws = CompletableDeferred<List<NwsWindSample>>()
        override suspend fun fetchForecast(latitude: Double, longitude: Double, days: Int) = forecast.await()
        override suspend fun fetchTerrain(latitude: Double, longitude: Double) = terrain.await()
        override suspend fun fetchQuarterHourly(latitude: Double, longitude: Double) = quarters.await()
        override suspend fun fetchNwsWind(latitude: Double, longitude: Double) = nws.await()
    }

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() {
        store.clear()
        scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    private fun model(weather: Weather): ThermalWindViewModel =
        ThermalWindViewModel(RuntimeEnvironment.getApplication(), weather).also { store.put("thermal", it) }

    private fun days(date: String = "2026-10-27") = listOf(
        ThermalWeatherDay(date, -240, (0..23).map { HourlyWeather(it, 45.0, 4.0, 0.0, 10.0) }),
    )

    private fun flatTerrain() = SiteTerrain(TerrainSetting.FLAT, null, 0.0, source = "Test terrain")

    @Test
    fun forecastAppearsBeforeSlowTerrainOrOptionalChecksFinish() = runTest(dispatcher) {
        val weather = Weather()
        val model = model(weather)
        model.useLocation(42.75, -75.5, "Test")
        runCurrent()
        weather.forecast.complete(days())
        runCurrent()
        assertEquals(ThermalWeatherSource.FORECAST, model.state.value.source)
        assertEquals(days(), model.state.value.forecastDays)
        assertNotNull(model.state.value.forecast)
        assertTrue(model.state.value.loading)
        assertEquals("Loading terrain…", model.state.value.status)
        weather.terrain.complete(flatTerrain())
        runCurrent()
        assertFalse("Optional checks must not keep the loading spinner up", model.state.value.loading)
        assertNull(model.state.value.error)
    }

    @Test
    fun unavailableOptionalSourcesDoNotTurnALoadedForecastIntoAnError() = runTest(dispatcher) {
        val weather = Weather()
        val model = model(weather)
        model.useLocation(42.75, -75.5, "Test")
        weather.forecast.complete(days())
        weather.terrain.complete(flatTerrain())
        weather.quarters.completeExceptionally(IOException("Quarter-hour service unavailable"))
        weather.nws.completeExceptionally(IOException("NWS unavailable"))
        runCurrent()
        assertFalse(model.state.value.loading)
        assertNotNull(model.state.value.forecast)
        assertNull(model.state.value.error)
        assertNull(model.state.value.nwsBadge)
    }

    @Test
    fun terrainTimeoutPreservesLoadedForecastAndStopsSpinner() = runTest(dispatcher) {
        val weather = Weather()
        val model = model(weather)
        model.useLocation(42.75, -75.5, "Test")
        weather.forecast.complete(days())
        runCurrent()
        advanceTimeBy(ThermalWindViewModel.TERRAIN_TIMEOUT_MS)
        runCurrent()
        assertFalse(model.state.value.loading)
        assertNotNull(model.state.value.forecast)
        assertTrue(model.state.value.error!!.contains("Terrain request timed out"))
        assertEquals(days(), model.state.value.forecastDays)
    }

    @Test
    fun emptyForecastReportsFailureWhileTerrainCanStillLoad() = runTest(dispatcher) {
        val weather = Weather()
        val model = model(weather)
        model.useLocation(42.75, -75.5, "Test")
        weather.forecast.complete(emptyList())
        weather.terrain.complete(flatTerrain())
        runCurrent()
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.forecastDays.isEmpty())
        assertTrue(model.state.value.error!!.contains("Forecast"))
        assertEquals("Test terrain", model.state.value.autoTerrain!!.source)
    }

    @Test
    fun aNewLocationCannotReceiveTheCancelledLocationsForecast() = runTest(dispatcher) {
        val weather = Weather()
        val model = model(weather)
        val oldForecast = weather.forecast
        model.useLocation(42.75, -75.5, "Old")
        runCurrent()
        weather.forecast = CompletableDeferred()
        model.useLocation(43.0, -76.0, "New")
        runCurrent()
        oldForecast.complete(days("2026-10-26"))
        weather.forecast.complete(days())
        weather.terrain.complete(flatTerrain())
        weather.quarters.complete(emptyList())
        weather.nws.complete(emptyList())
        runCurrent()
        assertEquals("New", model.state.value.locationLabel)
        assertEquals(days(), model.state.value.forecastDays)
        assertNull(model.state.value.error)
    }
}
