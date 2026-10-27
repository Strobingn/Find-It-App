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

/** One 15-minute HRRR wind step, in site-local clock time. */
data class QuarterWind(
    /** ISO date, e.g. "2026-10-27". */
    val date: String,
    val hour: Int,
    val minute: Int,
    val windSpeedMph: Double,
    val windFromDegrees: Double,
    val gustMph: Double? = null,
)

/** One NWS gridpoint wind sample covering a UTC interval (speed already in mph). */
data class NwsWindSample(
    val utcStartMillis: Long,
    val utcEndMillis: Long,
    val speedMph: Double,
    val fromDegrees: Double,
)

/**
 * Free, key-less weather and terrain for the thermal tab:
 *  - Open-Meteo forecast. The first ~48 h come from NOAA's HRRR CONUS model — 3 km grid,
 *    refreshed every hour, the highest-resolution operational forecast over the continental US —
 *    laid over the multi-model best-match blend that covers the full range. Hourly temperature
 *    °F, 10 m wind mph + direction, gusts and cloud cover, plus HRRR 15-minute wind steps.
 *  - USGS 3DEP Elevation Point Query Service (1 m bare-earth LiDAR where covered) for slope,
 *    uphill direction and valley/ridge setting, falling back to Open-Meteo's 90 m DEM.
 *  - NWS gridpoint wind as an independent cross-check on the HRRR hours.
 */
class ThermalWeatherClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val forecastEndpoint: String = FORECAST_ENDPOINT,
    private val elevationEndpoint: String = ELEVATION_ENDPOINT,
) {

    suspend fun fetchForecast(latitude: Double, longitude: Double, days: Int = 3): List<ThermalWeatherDay> =
        withContext(Dispatchers.IO) {
            // NOAA's HRRR (3 km grid, refreshed every hour) only reaches ~48 h out. Fetch it for
            // the near window and lay it over the multi-model blend, which covers the full range —
            // so hour-by-hour detail is the highest available at every lead time.
            val daysWanted = days.coerceIn(1, 7)
            val blend = parseForecast(get(url(latitude, longitude, daysWanted, model = null)))
            val hrrr = runCatching { parseForecast(get(url(latitude, longitude, 2, model = HRRR_MODEL))) }.getOrNull()
            mergeHrrr(blend, hrrr)
        }

    private fun url(latitude: Double, longitude: Double, days: Int, model: String?): String = String.format(
        Locale.US,
        "%s?latitude=%.5f&longitude=%.5f" +
            "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m,wind_gusts_10m,cloud_cover" +
            (model?.let { "&models=$it" } ?: "") +
            "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=%d",
        forecastEndpoint,
        latitude,
        longitude,
        days,
    )

    suspend fun fetchTerrain(latitude: Double, longitude: Double): SiteTerrain = withContext(Dispatchers.IO) {
        val samples = terrainSamplePoints(latitude, longitude)
        // Prefer the USGS 3DEP point service — 1 m bare-earth LiDAR DEM in most of the eastern US.
        lidarTerrain(samples)?.let { return@withContext it }
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

    /** Samples the ring against USGS EPQS; null when 3DEP has no fine-grained coverage here. */
    private fun lidarTerrain(samples: List<Pair<Double, Double>>): SiteTerrain? = runCatching {
        val meters = mutableListOf<Double>()
        var bestResolution = 0.0
        for ((lat, lon) in samples) {
            val url = String.format(
                Locale.US,
                "%s?x=%.5f&y=%.5f&units=Meters&wkid=4326&includeDate=false",
                EPQS_ENDPOINT,
                lon,
                lat,
            )
            val json = JSONObject(get(url))
            val value = json.optDouble("value", Double.NaN)
            if (value.isNaN() || value <= -1_000_000.0) return null // EPQS nodata
            val resolution = json.optDouble("resolution", Double.NaN)
            if (!resolution.isNaN()) bestResolution = maxOf(bestResolution, resolution)
            if (bestResolution > 5.0) return null // only 10 m+ coverage here — the blend is comparable
            meters += value
        }
        terrainFromSamples(meters).copy(
            source = "USGS 3DEP LiDAR (≈%.0f m)".format(bestResolution.coerceAtLeast(1.0)),
        )
    }.getOrNull()

    /** HRRR wind at 15-minute steps for roughly the next 24 h (empty when unavailable). */
    suspend fun fetchQuarterHourly(latitude: Double, longitude: Double): List<QuarterWind> = withContext(Dispatchers.IO) {
        runCatching {
            val url = String.format(
                Locale.US,
                "%s?latitude=%.5f&longitude=%.5f" +
                    "&minutely_15=wind_speed_10m,wind_direction_10m,wind_gusts_10m&models=%s" +
                    "&wind_speed_unit=mph&timezone=auto&forecast_days=2",
                forecastEndpoint,
                latitude,
                longitude,
                HRRR_MODEL,
            )
            parseQuarterHourly(get(url))
        }.getOrDefault(emptyList())
    }

    /** NWS gridpoint wind (speed + direction) for cross-checking HRRR; empty when unavailable. */
    suspend fun fetchNwsWind(latitude: Double, longitude: Double): List<NwsWindSample> = withContext(Dispatchers.IO) {
        runCatching {
            val points = JSONObject(
                get(String.format(Locale.US, "%s/%.4f,%.4f", NWS_POINTS_ENDPOINT, latitude, longitude)),
            ).getJSONObject("properties")
            val gridUrl = points.getString("forecastGridData")
            val grid = JSONObject(get(gridUrl)).getJSONObject("properties")
            val speeds = intervalValues(grid.getJSONObject("windSpeed").getJSONArray("values"))
            val dirs = intervalValues(grid.getJSONObject("windDirection").getJSONArray("values"))
            dirs.mapNotNull { (start, end, dir) ->
                // NWS km/h → mph. Pair each direction interval with the speed interval containing it.
                val speed = speeds.firstOrNull { it.first <= start && it.second > start }?.third ?: return@mapNotNull null
                NwsWindSample(start, end, speed * 0.621371, dir)
            }
        }.getOrDefault(emptyList())
    }

    /** Parses NWS ISO-8601 interval values ("2026-10-27T12:00:00+00:00/PT2H") into (startMs, endMs, value). */
    private fun intervalValues(values: org.json.JSONArray): List<Triple<Long, Long, Double>> {
        val out = mutableListOf<Triple<Long, Long, Double>>()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        for (i in 0 until values.length()) {
            val entry = values.optJSONObject(i) ?: continue
            if (entry.isNull("value")) continue
            val parts = entry.optString("validTime").split("/")
            if (parts.size != 2) continue
            val start = runCatching { format.parse(parts[0])?.time }.getOrNull() ?: continue
            val hours = parts[1].removePrefix("PT").removeSuffix("H").toDoubleOrNull() ?: continue
            out += Triple(start, start + (hours * 3_600_000).toLong(), entry.getDouble("value"))
        }
        return out
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

        /** USGS 3DEP Elevation Point Query Service — 1 m bare-earth LiDAR where 3DEP covers. */
        const val EPQS_ENDPOINT = "https://epqs.nationalmap.gov/v1/json"

        /** NOAA National Weather Service point/grid lookup (free, key-less). */
        const val NWS_POINTS_ENDPOINT = "https://api.weather.gov/points"

        /** NOAA High-Resolution Rapid Refresh, continental US, as exposed by Open-Meteo. */
        private const val HRRR_MODEL = "ncep_hrrr_conus"

        /** Overwrites blend hours with HRRR hours for the same local date/hour; keeps blend-only days. */
        private fun mergeHrrr(blend: List<ThermalWeatherDay>, hrrr: List<ThermalWeatherDay>?): List<ThermalWeatherDay> {
            if (hrrr == null) return blend
            val hrrrHours = mutableMapOf<Pair<String, Int>, HourlyWeather>()
            hrrr.forEach { day -> day.hourly.forEach { hrrrHours[day.date to it.hour] = it } }
            if (hrrrHours.isEmpty()) return blend
            return blend.map { day ->
                day.copy(
                    hourly = day.hourly.map { hour -> hrrrHours[day.date to hour.hour] ?: hour },
                )
            }
        }

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
            val gusts = hourly.optJSONArray("wind_gusts_10m")

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
                    gustMph = if (gusts == null || gusts.isNull(i)) null else gusts.getDouble(i),
                )
            }
            return byDate.filterValues { it.size >= 6 }.map { (date, hours) ->
                ThermalWeatherDay(date = date, utcOffsetMinutes = offsetMinutes, hourly = hours)
            }
        }

        fun parseQuarterHourly(body: String): List<QuarterWind> {
            val minutely = JSONObject(body).optJSONObject("minutely_15") ?: return emptyList()
            val times = minutely.getJSONArray("time")
            val speeds = minutely.getJSONArray("wind_speed_10m")
            val dirs = minutely.getJSONArray("wind_direction_10m")
            val gusts = minutely.optJSONArray("wind_gusts_10m")
            val out = mutableListOf<QuarterWind>()
            for (i in 0 until times.length()) {
                val stamp = times.getString(i) // "2026-10-27T21:15"
                if (speeds.isNull(i) || dirs.isNull(i)) continue
                val date = stamp.substringBefore('T')
                val clock = stamp.substringAfter('T').split(':')
                val hour = clock.getOrNull(0)?.toIntOrNull() ?: continue
                val minute = clock.getOrNull(1)?.toIntOrNull() ?: 0
                out += QuarterWind(
                    date = date,
                    hour = hour,
                    minute = minute,
                    windSpeedMph = speeds.getDouble(i),
                    windFromDegrees = dirs.getDouble(i),
                    gustMph = if (gusts == null || gusts.isNull(i)) null else gusts.getDouble(i),
                )
            }
            return out
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
