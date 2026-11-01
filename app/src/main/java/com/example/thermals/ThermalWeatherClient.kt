package com.example.thermals

import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    /** Weather model that supplied most of this day's wind, e.g. "NOAA NBM 2.5 km". */
    val windSource: String = "",
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

/** Forecast models, finest first. Each hour takes its values from the first model that has them. */
enum class WindModel(val id: String, val label: String) {
    /** NOAA National Blend of Models: 2.5 km, bias-corrected, hourly to 36 h then 3-hourly, 11 days. CONUS. */
    NBM("ncep_nbm_conus", "NOAA NBM 2.5 km"),
    /** NOAA High-Resolution Rapid Refresh: 3 km, updated hourly, 18–48 h. CONUS. */
    HRRR("ncep_hrrr_conus", "NOAA HRRR 3 km"),
    /** Open-Meteo's pick for anywhere else (GFS / ECMWF / regional). */
    BEST_MATCH("best_match", "Open-Meteo best match"),
}

/**
 * Free, key-less weather and terrain for the thermal tab:
 *  - Open-Meteo forecast (hourly temperature °F, 10 m wind mph + direction + gusts, cloud cover), asking for
 *    the highest-resolution models first: NOAA NBM 2.5 km, then NOAA HRRR 3 km, then best match ([WindModel]).
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
                "%s?latitude=%.5f&longitude=%.5f&hourly=%s&models=%s" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=auto&forecast_days=%d",
                forecastEndpoint,
                latitude,
                longitude,
                HOURLY_VARS.joinToString(","),
                WindModel.entries.joinToString(",") { it.id },
                days.coerceIn(1, 7),
            )
            try {
                parseForecast(getWithRetry(url))
            } catch (e: IOException) {
                // Open-Meteo's free tier is limited per IP address, and phones on cellular share one. NWS is key-less.
                if (!isRateLimit(e)) throw e
                runCatching { fetchNwsForecast(latitude, longitude) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrElse { throw e }
            }
        }

    private fun isRateLimit(e: IOException): Boolean {
        val m = e.message.orEmpty().lowercase(Locale.US)
        return "limit" in m || "429" in m
    }

    /** One pause-and-retry when the per-minute limit is hit. */
    private suspend fun getWithRetry(url: String): String = try {
        get(url)
    } catch (e: IOException) {
        if (!isRateLimit(e)) throw e
        kotlinx.coroutines.delay(4_000)
        get(url)
    }

    /** US-only fallback: api.weather.gov gridpoint data (2.5 km NDFD), no key needed. */
    private suspend fun fetchNwsForecast(latitude: Double, longitude: Double): List<ThermalWeatherDay> {
        val point = JSONObject(getNws(String.format(Locale.US, "https://api.weather.gov/points/%.4f,%.4f", latitude, longitude)))
            .getJSONObject("properties")
        val grid = getNws(point.getString("forecastGridData"))
        return parseNwsGrid(grid, point.optString("timeZone", "UTC"))
    }

    private suspend fun getNws(url: String): String {
        currentCoroutineContext().ensureActive()
        val request = Request.Builder().url(url).header("Accept", "application/geo+json")
            .header("User-Agent", "FindItApp (wildlife hunting app)").get().build()
        httpClient.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("NWS HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }

    suspend fun fetchTerrain(latitude: Double, longitude: Double): SiteTerrain = withContext(Dispatchers.IO) {
        // Terrain doesn't move — reuse a recent result for this spot instead of hammering the
        // free elevation services on every refresh (they rate-limit bursts with HTTP 429).
        cachedTerrain(cacheKey(latitude, longitude))?.let { return@withContext it }
        val samples = terrainSamplePoints(latitude, longitude)
        // Prefer the USGS 3DEP point service — 1 m bare-earth LiDAR DEM in most of the eastern US.
        // Mapbox terrain next: ~10 m instead of 90 m, and not subject to Open-Meteo's per-IP limit.
        val terrain = lidarTerrain(samples)
            ?: MapboxDem.elevations(samples)?.let { meters ->
                terrainFromSamples(meters).copy(source = MapboxDem.SOURCE_LABEL)
            }
            ?: run {
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
        // Cache every source — an early return here used to skip caching for LiDAR/Mapbox results.
        storeTerrain(cacheKey(latitude, longitude), terrain)
        terrain
    }

    /**
     * Samples the ring against USGS EPQS; null when 3DEP has no fine-grained coverage here or the
     * service rate-limits us (HTTP 429) — the caller then quietly falls back to coarser sources.
     * Requests are paced so a single refresh never looks like a burst.
     */
    private suspend fun lidarTerrain(samples: List<Pair<Double, Double>>): SiteTerrain? = runCatching {
        val meters = mutableListOf<Double>()
        var bestResolution = 0.0
        for ((index, point) in samples.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (index > 0) kotlinx.coroutines.delay(EPQS_PACE_MS)
            val (lat, lon) = point
            val url = String.format(
                Locale.US,
                "%s?x=%.5f&y=%.5f&units=Meters&wkid=4326&includeDate=false",
                EPQS_ENDPOINT,
                lon,
                lat,
            )
            val body = runCatching { get(url) }.getOrElse { firstError ->
                if (!firstError.message.orEmpty().contains("429")) throw firstError
                // Rate-limited: back off once, then give up on LiDAR for this refresh.
                kotlinx.coroutines.delay(EPQS_BACKOFF_MS)
                get(url)
            }
            val (value, resolution) = parseFineElevation(body) ?: return null
            bestResolution = maxOf(bestResolution, resolution)
            meters += value
        }
        terrainFromSamples(meters).copy(
            source = "USGS 3DEP LiDAR (≈%.0f m)".format(bestResolution.coerceAtLeast(1.0)),
        )
    }.onFailure { if (it is CancellationException) throw it }.getOrNull()

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
                WindModel.HRRR.id,
            )
            parseQuarterHourly(get(url))
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }

    /** NWS gridpoint wind (speed + direction) for cross-checking the forecast; empty when unavailable. */
    suspend fun fetchNwsWind(latitude: Double, longitude: Double): List<NwsWindSample> = withContext(Dispatchers.IO) {
        runCatching {
            val points = JSONObject(
                getNws(String.format(Locale.US, "https://api.weather.gov/points/%.4f,%.4f", latitude, longitude)),
            ).getJSONObject("properties")
            parseNwsWind(getNws(points.getString("forecastGridData")))
        }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
    }

    private suspend fun get(url: String): String {
        currentCoroutineContext().ensureActive()
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

        /** Pause between EPQS point queries so one refresh never fires a 17-request burst. */
        private const val EPQS_PACE_MS = 350L

        /** Single retry wait when EPQS answers HTTP 429. */
        private const val EPQS_BACKOFF_MS = 4_000L

        /** Terrain cache: ~100 m cells, 24 h TTL. Terrain doesn't move; refreshes shouldn't re-poll. */
        private const val TERRAIN_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        private val terrainCacheEntries = object : LinkedHashMap<String, Pair<Long, SiteTerrain>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, SiteTerrain>>?) = size > 64
        }

        private fun cacheKey(latitude: Double, longitude: Double): String =
            String.format(Locale.US, "%.3f,%.3f", latitude, longitude)

        private fun cachedTerrain(key: String): SiteTerrain? = synchronized(terrainCacheEntries) {
            val entry = terrainCacheEntries[key] ?: return null
            if (System.currentTimeMillis() - entry.first > TERRAIN_CACHE_TTL_MS) {
                terrainCacheEntries.remove(key)
                null
            } else {
                entry.second
            }
        }

        private fun storeTerrain(key: String, terrain: SiteTerrain) = synchronized(terrainCacheEntries) {
            terrainCacheEntries[key] = System.currentTimeMillis() to terrain
        }

        /** Only claim fine LiDAR coverage when EPQS supplies a finite, positive resolution. */
        internal fun parseFineElevation(body: String): Pair<Double, Double>? {
            val json = JSONObject(body)
            val value = json.optDouble("value", Double.NaN)
            val resolution = json.optDouble("resolution", Double.NaN)
            if (!value.isFinite() || value <= -1_000_000.0) return null
            if (!resolution.isFinite() || resolution <= 0.0 || resolution > 5.0) return null
            return value to resolution
        }

        /** Intersect independently changing speed and direction intervals before comparing forecasts. */
        fun parseNwsWind(body: String): List<NwsWindSample> {
            val grid = JSONObject(body).getJSONObject("properties")
            val speeds = intervalValues(grid.getJSONObject("windSpeed").getJSONArray("values"))
            val directions = intervalValues(grid.getJSONObject("windDirection").getJSONArray("values"))
            val out = mutableListOf<NwsWindSample>()
            var speedIndex = 0
            var directionIndex = 0
            while (speedIndex < speeds.size && directionIndex < directions.size) {
                val speed = speeds[speedIndex]
                val direction = directions[directionIndex]
                val start = maxOf(speed.first, direction.first)
                val end = minOf(speed.second, direction.second)
                if (start < end) out += NwsWindSample(start, end, speed.third * 0.621371, direction.third)
                if (speed.second <= direction.second) speedIndex++ else directionIndex++
            }
            return out
        }

        /** NWS intervals can include days, hours, minutes and seconds, e.g. P1DT2H or PT90M. */
        private fun intervalValues(values: org.json.JSONArray): List<Triple<Long, Long, Double>> {
            val out = mutableListOf<Triple<Long, Long, Double>>()
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                isLenient = false
            }
            val duration = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?")
            for (i in 0 until values.length()) {
                val entry = values.optJSONObject(i) ?: continue
                val value = entry.optDouble("value", Double.NaN)
                if (!value.isFinite()) continue
                val parts = entry.optString("validTime").split("/")
                if (parts.size != 2) continue
                val start = runCatching { format.parse(parts[0])?.time }.getOrNull() ?: continue
                val match = duration.matchEntire(parts[1]) ?: continue
                val seconds = match.groupValues.drop(1).map { it.toDoubleOrNull() ?: 0.0 }
                    .zip(listOf(86_400.0, 3_600.0, 60.0, 1.0)).sumOf { (value, unit) -> value * unit }
                if (!seconds.isFinite() || seconds <= 0.0) continue
                out += Triple(start, start + (seconds * 1_000).toLong(), value)
            }
            return out.sortedBy { it.first }
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

        val HOURLY_VARS = listOf("temperature_2m", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m", "cloud_cover")

        /**
         * Parses a single- or multi-model response. With several models Open-Meteo suffixes each series with the
         * model id ("wind_speed_10m_ncep_nbm_conus"); every hour takes each value from the first model, in
         * [WindModel] order, that has it. Speed and direction always come from the same model.
         */
        fun parseForecast(body: String): List<ThermalWeatherDay> {
            val json = JSONObject(body)
            val offsetMinutes = json.optInt("utc_offset_seconds", 0) / 60
            val hourly = json.optJSONObject("hourly") ?: throw IOException("Forecast has no hourly data")
            val times = hourly.getJSONArray("time")

            fun series(name: String): List<Pair<WindModel?, org.json.JSONArray>> = buildList {
                WindModel.entries.forEach { m -> hourly.optJSONArray("${name}_${m.id}")?.let { add(m to it) } }
                hourly.optJSONArray(name)?.let { add(null to it) } // single-model response
            }
            fun first(list: List<Pair<WindModel?, org.json.JSONArray>>, i: Int): Pair<WindModel?, Double>? =
                list.firstNotNullOfOrNull { (m, a) -> if (i < a.length() && !a.isNull(i)) m to a.getDouble(i) else null }

            val temps = series("temperature_2m")
            val speeds = series("wind_speed_10m")
            val dirs = series("wind_direction_10m")
            val gusts = series("wind_gusts_10m")
            val clouds = series("cloud_cover")
            if (temps.isEmpty() || speeds.isEmpty() || dirs.isEmpty()) throw IOException("Forecast is missing temperature or wind")

            val byDate = linkedMapOf<String, MutableList<HourlyWeather>>()
            val modelsByDate = linkedMapOf<String, MutableList<WindModel?>>()
            for (i in 0 until times.length()) {
                val stamp = times.getString(i) // "2026-10-26T06:00"
                val temp = first(temps, i) ?: continue
                // Wind speed and direction from one model, so they always describe the same wind.
                val wind = speeds.firstNotNullOfOrNull { (m, a) ->
                    val d = dirs.firstOrNull { it.first == m }?.second
                    if (d != null && i < a.length() && i < d.length() && !a.isNull(i) && !d.isNull(i)) Triple(m, a.getDouble(i), d.getDouble(i)) else null
                } ?: continue
                val date = stamp.substringBefore('T')
                val hour = stamp.substringAfter('T').substringBefore(':').toIntOrNull() ?: continue
                byDate.getOrPut(date) { mutableListOf() } += HourlyWeather(
                    hour = hour,
                    temperatureF = temp.second,
                    windSpeedMph = wind.second,
                    windFromDegrees = wind.third,
                    cloudCoverPercent = first(clouds, i)?.second ?: 0.0,
                    windGustMph = first(gusts, i)?.second,
                )
                modelsByDate.getOrPut(date) { mutableListOf() } += wind.first
            }
            return byDate.filterValues { it.size >= 6 }.map { (date, hours) ->
                val source = modelsByDate[date].orEmpty().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ThermalWeatherDay(
                    date = date,
                    utcOffsetMinutes = offsetMinutes,
                    hourly = hours,
                    windSource = source?.label ?: "Open-Meteo",
                )
            }
        }

        /** NWS gridpoint JSON -> hourly weather. Values are °C and km/h in ISO-interval series; expanded to hours. */
        fun parseNwsGrid(body: String, timeZoneId: String): List<ThermalWeatherDay> {
            val props = JSONObject(body).getJSONObject("properties")
            val tz = java.util.TimeZone.getTimeZone(timeZoneId)
            val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            val durationRe = Regex("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?)?")
            fun series(name: String): Map<Long, Double> {
                val out = HashMap<Long, Double>()
                val values = props.optJSONObject(name)?.optJSONArray("values") ?: return out
                for (i in 0 until values.length()) {
                    val o = values.getJSONObject(i)
                    if (o.isNull("value")) continue
                    val (start, dur) = o.getString("validTime").split('/').let { it[0] to it.getOrElse(1) { "PT1H" } }
                    val t0 = parser.parse(start)?.time ?: continue
                    val m = durationRe.matchEntire(dur) ?: continue
                    val hours = ((m.groupValues[1].toIntOrNull() ?: 0) * 24 + (m.groupValues[2].toIntOrNull() ?: 0)).coerceIn(1, 168)
                    for (h in 0 until hours) out[t0 + h * 3_600_000L] = o.getDouble("value")
                }
                return out
            }
            val temp = series("temperature")
            val speed = series("windSpeed")
            val dir = series("windDirection")
            val gust = series("windGust")
            val sky = series("skyCover")
            val dateFmt = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
            val hourFmt = java.text.SimpleDateFormat("H", Locale.US).apply { timeZone = tz }
            val byDate = linkedMapOf<String, MutableList<HourlyWeather>>()
            for (t in temp.keys.sorted()) {
                val sp = speed[t] ?: continue
                val d = dir[t] ?: continue
                val date = dateFmt.format(java.util.Date(t))
                byDate.getOrPut(date) { mutableListOf() } += HourlyWeather(
                    hour = hourFmt.format(java.util.Date(t)).toInt(),
                    temperatureF = temp.getValue(t) * 9.0 / 5.0 + 32.0,
                    windSpeedMph = sp * 0.621371,
                    windFromDegrees = d,
                    cloudCoverPercent = sky[t] ?: 0.0,
                    windGustMph = gust[t]?.let { it * 0.621371 },
                )
            }
            return byDate.filterValues { it.size >= 6 }.map { (date, hours) ->
                ThermalWeatherDay(date, tz.getOffset(System.currentTimeMillis()) / 60_000, hours, "NWS 2.5 km (fallback)")
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
