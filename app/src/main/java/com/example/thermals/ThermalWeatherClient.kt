package com.example.thermals

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** One forecast day for a site, in site-local hours. */
data class ThermalWeatherDay(
    /** ISO date, e.g. "2026-10-27". */
    val date: String,
    val utcOffsetMinutes: Int,
    val hourly: List<HourlyWeather>,
)

/**
 * Free, key-less weather and terrain for the thermal tab:
 *  - Open-Meteo forecast (hourly temperature °F, 10 m wind mph + direction, cloud cover).
 *  - Open-Meteo elevation (Copernicus 90 m DEM) sampled around the stand to estimate slope,
 *    the uphill direction and whether the spot sits in a draw/valley or on a ridge.
 */
class ThermalWeatherClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val forecastEndpoint: String = FORECAST_ENDPOINT,
    private val elevationEndpoint: String = ELEVATION_ENDPOINT,
) {

    suspend fun fetchForecast(latitude: Double, longitude: Double, days: Int = 3): List<ThermalWeatherDay> =
        withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "%s?latitude=%.5f&longitude=%.5f&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,cloud_cover" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=%d",
                forecastEndpoint,
                latitude,
                longitude,
                days.coerceIn(1, 7),
            )
            parseForecast(get(url))
        }

    suspend fun fetchTerrain(latitude: Double, longitude: Double): SiteTerrain = withContext(Dispatchers.IO) {
        val samples = terrainSamplePoints(latitude, longitude)
        val url = String.format(
            Locale.US,
            "%s?latitude=%s&longitude=%s",
            elevationEndpoint,
            samples.joinToString(",") { String.format(Locale.US, "%.5f", it.first) },
            samples.joinToString(",") { String.format(Locale.US, "%.5f", it.second) },
        )
        val json = JSONObject(get(url))
        val array = json.optJSONArray("elevation") ?: throw IOException("Elevation service returned no data")
        val meters = (0 until array.length()).map { array.getDouble(it) }
        if (meters.size != samples.size) throw IOException("Elevation service returned ${meters.size} of ${samples.size} points")
        terrainFromSamples(meters)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching { JSONObject(body).optString("reason") }.getOrNull().orEmpty()
                throw IOException(reason.ifBlank { "Weather service HTTP ${response.code}" })
            }
            return body
        }
    }

    companion object {
        const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
        const val ELEVATION_ENDPOINT = "https://api.open-meteo.com/v1/elevation"

        /** Inner ring (slope) and outer ring (valley vs ridge) radii in meters. */
        const val INNER_RING_METERS = 150.0
        const val OUTER_RING_METERS = 450.0

        private const val METERS_PER_DEG_LAT = 111_320.0

        /** Center, then 8 inner-ring points (N, NE, E, … NW), then 8 outer-ring points. */
        fun terrainSamplePoints(latitude: Double, longitude: Double): List<Pair<Double, Double>> {
            val points = mutableListOf(latitude to longitude)
            for (radius in listOf(INNER_RING_METERS, OUTER_RING_METERS)) {
                for (i in 0 until 8) {
                    val bearing = Math.toRadians(i * 45.0)
                    val dNorth = radius * cos(bearing)
                    val dEast = radius * kotlin.math.sin(bearing)
                    val dLat = dNorth / METERS_PER_DEG_LAT
                    val dLon = dEast / (METERS_PER_DEG_LAT * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
                    points += (latitude + dLat) to (longitude + dLon)
                }
            }
            return points
        }

        /**
         * Slope and uphill direction from the inner ring's elevation gradient; valley/ridge from how
         * the center compares to the outer ring. [meters] is in [terrainSamplePoints] order.
         */
        fun terrainFromSamples(meters: List<Double>): SiteTerrain {
            require(meters.size == 17) { "Expected 17 elevation samples" }
            val center = meters[0]
            val inner = meters.subList(1, 9)
            val outer = meters.subList(9, 17)

            // Least-squares plane gradient over the ring: d(elev)/d(east), d(elev)/d(north).
            var gEast = 0.0
            var gNorth = 0.0
            inner.forEachIndexed { i, z ->
                val b = Math.toRadians(i * 45.0)
                gEast += (z - center) * kotlin.math.sin(b)
                gNorth += (z - center) * cos(b)
            }
            // Σ sin² = Σ cos² = 4 over 8 evenly spaced bearings.
            gEast /= 4.0 * INNER_RING_METERS
            gNorth /= 4.0 * INNER_RING_METERS
            val rise = hypot(gEast, gNorth)
            val slopeDeg = Math.toDegrees(kotlin.math.atan(rise))
            val uphill = if (rise < 1e-4) null else ThermalWindEngine.normalize(Math.toDegrees(atan2(gEast, gNorth)))

            val outerMean = outer.average()
            val relief = center - outerMean
            // Remove the planar component from the outer ring before judging concave vs convex.
            val planarResidual = outer.mapIndexed { i, z ->
                val b = Math.toRadians(i * 45.0)
                z - (center + OUTER_RING_METERS * (gEast * kotlin.math.sin(b) + gNorth * cos(b)))
            }.average()

            val setting = when {
                slopeDeg < 2.0 && kotlin.math.abs(relief) < 4.0 -> TerrainSetting.FLAT
                planarResidual > 6.0 -> TerrainSetting.VALLEY // ground rises around you faster than the slope alone
                planarResidual < -6.0 && relief > 3.0 -> TerrainSetting.RIDGE
                else -> TerrainSetting.SLOPE
            }
            return SiteTerrain(
                setting = setting,
                uphillBearingDegrees = uphill,
                slopeDegrees = slopeDeg,
                elevationFeet = center * 3.28084,
                source = "Elevation model (90 m)",
            )
        }

        fun parseForecast(body: String): List<ThermalWeatherDay> {
            val json = JSONObject(body)
            val offsetMinutes = json.optInt("utc_offset_seconds", 0) / 60
            val hourly = json.optJSONObject("hourly") ?: throw IOException("Forecast has no hourly data")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val speeds = hourly.getJSONArray("wind_speed_10m")
            val dirs = hourly.getJSONArray("wind_direction_10m")
            val clouds = hourly.optJSONArray("cloud_cover")

            val byDate = linkedMapOf<String, MutableList<HourlyWeather>>()
            for (i in 0 until times.length()) {
                val stamp = times.getString(i) // "2026-10-26T06:00"
                if (temps.isNull(i) || speeds.isNull(i) || dirs.isNull(i)) continue
                val date = stamp.substringBefore('T')
                val hour = stamp.substringAfter('T').substringBefore(':').toIntOrNull() ?: continue
                byDate.getOrPut(date) { mutableListOf() } += HourlyWeather(
                    hour = hour,
                    temperatureF = temps.getDouble(i),
                    windSpeedMph = speeds.getDouble(i),
                    windFromDegrees = dirs.getDouble(i),
                    cloudCoverPercent = if (clouds == null || clouds.isNull(i)) 0.0 else clouds.getDouble(i),
                )
            }
            return byDate.filterValues { it.size >= 6 }.map { (date, hours) ->
                ThermalWeatherDay(date = date, utcOffsetMinutes = offsetMinutes, hourly = hours)
            }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
