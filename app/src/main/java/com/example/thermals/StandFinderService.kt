package com.example.thermals

import android.content.Context
import com.example.BuildConfig
import com.example.ai.GeminiConversationTurn
import com.example.ai.GeminiImageInput
import com.example.ai.TerrainAiGateway
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Where the stand-search elevations came from. */
data class ElevationFetch(val grid: ElevationGrid, val sourceLabel: String)

/** Result of a stand search: the analysed candidates and the picks to pin on the map. */
data class StandSearchResult(
    val candidates: List<StandCandidate>,
    val picks: List<StandPick>,
    /** "Grok grok-4.7" or "On-device" — who chose the picks. */
    val source: String,
    val note: String? = null,
)

/**
 * Network side of the stand finder: a 750-yard-radius elevation grid (Open-Meteo, key-less), a satellite
 * picture of the same square (Mapbox Static Images) and the cloud AI that turns terrain + thermals
 * + imagery into ranked stand and hunting-area picks.
 */
class StandFinderService internal constructor(
    context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    /** Overpass is best-effort: a slow public instance must not hold up the search. */
    private val overpassClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(OVERPASS_TIMEOUT_S + 2L, TimeUnit.SECONDS)
        .build(),
    elevationCache: ElevationGridCache? = null,
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    private val randomUnit: () -> Double = { Math.random() },
    private val mapboxElevations: (suspend (List<Pair<Double, Double>>, File?) -> List<Double?>?)? = null,
) {
    private val appContext = context.applicationContext
    private val gateway = TerrainAiGateway(appContext)
    private val grids = elevationCache ?: ElevationGridCache.inDirectory(File(appContext.cacheDir, ElevationGridCache.DIR_NAME))

    /**
     * Elevation for the stand search. Uses [siteDem] when the search center falls inside a
     * loaded LiDAR / site grid and enough of the square is covered; otherwise a cached grid for
     * this spot; otherwise Mapbox terrain-RGB tiles (~10 m, a handful of tiles, one at a time);
     * then USGS 3DEP in one request; Open-Meteo only when those can't be had.
     */
    suspend fun fetchGrid(lat: Double, lon: Double, siteDem: SiteDem? = null): ElevationFetch = withContext(Dispatchers.IO) {
        siteDem?.let { dem ->
            SiteDemGrids.fromSamples(lat, lon, dem)?.let { grid ->
                return@withContext ElevationFetch(grid, "LiDAR on this phone")
            }
        }
        grids.get(lat, lon)?.let { return@withContext it }
        val points = ElevationGrid.samplePoints(lat, lon, StandFinder.GRID_SIZE, StandFinder.GRID_SPACING_M)
        val mapbox = try {
            if (mapboxElevations != null) {
                mapboxElevations.invoke(points, appContext.cacheDir)
            } else {
                MapboxDem.elevationSamples(points, appContext.cacheDir, sleeper = sleeper, random = randomUnit)
            }
        } catch (e: IOException) {
            MapboxDem.lastError = e.message
            null
        }
        if (mapbox != null) {
            runCatching { gridFromSamples(lat, lon, mapbox) }.getOrNull()?.let { grid ->
                val fetch = ElevationFetch(grid, MapboxDem.SOURCE_LABEL)
                grids.put(lat, lon, fetch)
                return@withContext fetch
            }
        }
        runCatching { fetchUsgsGrid(points) }.getOrNull()?.let { meters ->
            runCatching {
                gridFromSamples(lat, lon, meters.map { z -> if (z.isFinite()) z else null })
            }.getOrNull()
        }?.let { grid ->
            val fetch = ElevationFetch(grid, USGS_SOURCE)
            grids.put(lat, lon, fetch)
            return@withContext fetch
        }
        val grid = try {
            fetchOpenMeteoGrid(lat, lon, points)
        } catch (e: IOException) {
            val why = MapboxDem.lastError ?: throw e
            throw IOException("${e.message} (Mapbox terrain failed: $why)", e)
        }
        val fetch = ElevationFetch(grid, "Elevation model (90 m)")
        grids.put(lat, lon, fetch)
        fetch
    }

    /**
     * US-only, key-less: USGS 3DEP samples for the whole grid in one POST.
     * A 429 is retried; a missing point throws instead of being filled with 0 m.
     */
    private suspend fun fetchUsgsGrid(points: List<Pair<Double, Double>>): List<Double> =
        RateLimitBackoff.withRetries(sleeper, randomUnit) {
            val geometry = JSONObject()
                .put("points", JSONArray(points.map { JSONArray(listOf(it.second, it.first)) }))
                .put("spatialReference", JSONObject().put("wkid", 4326))
            val form = okhttp3.FormBody.Builder()
                .add("geometry", geometry.toString())
                .add("geometryType", "esriGeometryMultipoint")
                .add("returnFirstValueOnly", "true")
                .add("f", "json")
                .build()
            val request = Request.Builder().url(USGS_SAMPLES_URL).post(form).build()
            val body = httpClient.newCall(request).execute().use { r ->
                if (r.code == 429 || r.code == 503) {
                    throw RateLimitedException("USGS elevation", r.code, r.header("Retry-After"))
                }
                if (!r.isSuccessful) throw IOException("USGS elevation HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            parseUsgsSamples(body, points.size)
        }

    private suspend fun fetchOpenMeteoGrid(
        lat: Double,
        lon: Double,
        points: List<Pair<Double, Double>>,
    ): ElevationGrid {
        // 841 points, 100 per call (Open-Meteo's max), one call at a time. A parallel burst of
        // per-cell requests is what gets the shared carrier IP answered with HTTP 429.
        val meters = ArrayList<Double?>(points.size)
        for (chunk in points.chunked(ELEVATION_BATCH_SIZE)) {
            val url = String.format(
                Locale.US,
                "%s?latitude=%s&longitude=%s",
                ThermalWeatherClient.ELEVATION_ENDPOINT,
                chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.first) },
                chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.second) },
            )
            meters.addAll(parseElevationBody(getText(url), chunk.size))
        }
        return gridFromSamples(lat, lon, meters)
    }

    private suspend fun getText(url: String): String = RateLimitBackoff.withRetries(sleeper, randomUnit) {
        httpClient.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429 || response.code == 503) {
                throw RateLimitedException("Elevation service", response.code, response.header("Retry-After"))
            }
            if (!response.isSuccessful) throw IOException("Elevation service HTTP ${response.code}")
            body
        }
    }

    /**
     * Roads and buildings around the search square from OpenStreetMap (Overpass, key-less).
     * Footpaths and farm tracks are left out — hunters walk those. Empty on any failure.
     */
    suspend fun fetchObstacles(lat: Double, lon: Double): MappedObstacles = withContext(Dispatchers.IO) {
        val half = StandFinder.GRID_SPACING_M * (StandFinder.GRID_SIZE - 1) / 2.0 + 100.0
        val dLat = half / 111_320.0
        val dLon = half / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01))
        val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", lat - dLat, lon - dLon, lat + dLat, lon + dLon)
        val query = "[out:json][timeout:${OVERPASS_TIMEOUT_S}];(way[\"highway\"]($bbox);way[\"building\"]($bbox););out geom;"
        runCatching {
            val request = Request.Builder()
                .url(OVERPASS_ENDPOINT)
                .post(okhttp3.FormBody.Builder().add("data", query).build())
                .build()
            overpassClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use MappedObstacles.NONE
                parseObstacles(response.body?.string().orEmpty())
            }
        }.getOrDefault(MappedObstacles.NONE)
    }

    /** Satellite picture of the search square, or null without a Mapbox token / network. */
    suspend fun fetchSatellite(lat: Double, lon: Double): SatelliteImage? = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN.trim()
        if (token.isBlank()) return@withContext null
        val span = StandFinder.GRID_SPACING_M * (StandFinder.GRID_SIZE - 1) + 120.0
        val zoom = StandFinder.zoomForSpan(lat, span, IMAGE_PX)
        val url = String.format(
            Locale.US,
            "https://api.mapbox.com/styles/v1/mapbox/satellite-v9/static/%.6f,%.6f,%.2f,0/%dx%d?access_token=%s&attribution=false&logo=false",
            lon, lat, zoom, IMAGE_PX, IMAGE_PX, token,
        )
        runCatching {
            httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                val mime = response.header("Content-Type")?.substringBefore(';')?.ifBlank { null } ?: "image/jpeg"
                SatelliteImage(bytes, mime, lat, lon, zoom, IMAGE_PX, IMAGE_PX)
            }
        }.getOrNull()
    }

    /**
     * Asks the AI for picks. Throws when no provider is configured or the call fails, so the
     * caller can keep showing the on-device picks.
     */
    suspend fun aiPicks(
        candidates: List<StandCandidate>,
        forecast: ThermalForecast?,
        image: SatelliteImage?,
        centerLat: Double,
        centerLon: Double,
        locationLabel: String,
        dateLabel: String,
        onStage: (String) -> Unit,
        obstacles: MappedObstacles = MappedObstacles.NONE,
        /** Called once the answer starts streaming in. */
        onWriting: () -> Unit = {},
    ): Pair<List<StandPick>, String> {
        val prompt = buildPrompt(candidates, forecast, image, centerLat, centerLon, locationLabel, dateLabel, obstacles)
        var writing = false
        val answer = gateway.generate(
            conversation = listOf(GeminiConversationTurn(role = "user", text = prompt.second)),
            systemContext = prompt.first,
            image = image?.let { GeminiImageInput(it.bytes, it.mimeType, "Satellite view of the search area (750 yards each way from the center), north up, search center in the middle") },
            onProviderStage = onStage,
            featureName = "stand_finder",
            // Low reasoning effort + streaming: picks in seconds instead of minutes, and the
            // connection never sits silent long enough to hit the read timeout.
            fast = true,
            onPartialText = { text -> if (text.isNotEmpty() && !writing) { writing = true; onWriting() } },
        )
        val picks = parsePicks(answer.text, candidates, image, centerLat, centerLon, obstacles)
        if (picks.isEmpty()) throw IOException("The AI didn't return any usable picks")
        return picks to "${answer.provider.label} ${TerrainAiGateway.modelFor(answer.provider)}"
    }


    data class SatelliteImage(
        val bytes: ByteArray,
        val mimeType: String,
        val centerLat: Double,
        val centerLon: Double,
        val zoom: Double,
        val widthPx: Int,
        val heightPx: Int,
    )

    companion object {
        const val IMAGE_PX = 640
        const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        const val OVERPASS_TIMEOUT_S = 8

        /** Open-Meteo elevation accepts at most this many coordinates in one request. */
        const val ELEVATION_BATCH_SIZE = 100

        /** Below this (terrain-RGB void is -10_000) the sample is missing, not a height. */
        private const val VOID_METERS = -9_999.0

        /**
         * One elevation per requested point. JSON null is a hole, not 0 m.
         * A short body is an error — it is never padded with zeros.
         */
        fun parseElevationBody(body: String, expected: Int): List<Double?> {
            val array = try {
                JSONObject(body).optJSONArray("elevation")
            } catch (_: org.json.JSONException) {
                null
            } ?: throw IOException("Elevation service returned no data")
            if (array.length() != expected) {
                throw IOException("Elevation service returned ${array.length()} of $expected points")
            }
            return List(expected) { i ->
                if (array.isNull(i)) null else array.optDouble(i, Double.NaN).takeIf { it.isFinite() }
            }
        }

        /**
         * Builds the search grid. Holes stay uncovered (NaN) so they are not scored.
         * Too few real samples is a failure, not a flat 0 m surface.
         */
        fun gridFromSamples(lat: Double, lon: Double, samples: List<Double?>): ElevationGrid {
            val expected = StandFinder.GRID_SIZE * StandFinder.GRID_SIZE
            if (samples.size != expected) {
                throw IOException("Elevation service returned ${samples.size} of $expected points")
            }
            val covered = BooleanArray(samples.size) { i ->
                val z = samples[i]
                z != null && z.isFinite() && z > VOID_METERS
            }
            val center = (StandFinder.GRID_SIZE / 2) * StandFinder.GRID_SIZE + StandFinder.GRID_SIZE / 2
            if (!covered[center] || covered.count { it } < SiteDemGrids.MIN_HITS) {
                throw IOException("Elevation service returned no usable terrain")
            }
            val meters = DoubleArray(samples.size) { i -> if (covered[i]) samples[i]!! else Double.NaN }
            return ElevationGrid(lat, lon, StandFinder.GRID_SIZE, StandFinder.GRID_SPACING_M, meters, covered)
        }

        /** Hosts the cloud AI providers are called on. */
        val AI_HOSTS = setOf("api.x.ai", "api.openai.com", "generativelanguage.googleapis.com")

        private val UNRESOLVED_HOST = Regex("""Unable to resolve host \\?"?([A-Za-z0-9.-]+)""", RegexOption.IGNORE_CASE)

        private val FOOT_TRAFFIC = setOf("footway", "path", "track", "bridleway", "cycleway", "steps", "pedestrian")

        fun parseObstacles(body: String): MappedObstacles {
            val elements = JSONObject(body).optJSONArray("elements") ?: return MappedObstacles.NONE
            val roads = mutableListOf<List<Pair<Double, Double>>>()
            val buildings = mutableListOf<List<Pair<Double, Double>>>()
            for (i in 0 until elements.length()) {
                val e = elements.optJSONObject(i) ?: continue
                val tags = e.optJSONObject("tags") ?: continue
                val geometry = e.optJSONArray("geometry") ?: continue
                val line = (0 until geometry.length()).mapNotNull { j ->
                    geometry.optJSONObject(j)?.let { it.optDouble("lat") to it.optDouble("lon") }
                }.filter { !it.first.isNaN() && !it.second.isNaN() }
                when {
                    line.isEmpty() -> Unit
                    tags.has("building") -> buildings += listOf(line)
                    tags.has("highway") && tags.optString("highway") !in FOOT_TRAFFIC -> roads += listOf(line)
                }
            }
            return MappedObstacles(roads, buildings)
        }

        /**
         * Turns "Unable to resolve host" into something actionable, naming the host that
         * actually failed. Only AI hosts get the DNS-filter advice; any other message is kept.
         */
        const val USGS_SAMPLES_URL = "https://elevation.nationalmap.gov/arcgis/rest/services/3DEPElevation/ImageServer/getSamples"
        const val USGS_SOURCE = "USGS 3DEP elevation"

        /** ArcGIS getSamples response -> elevations in meters, in request order. */
        fun parseUsgsSamples(body: String, expected: Int): List<Double> {
            val samples = JSONObject(body).optJSONArray("samples") ?: throw IOException("USGS returned no samples")
            val byIndex = HashMap<Int, Double>()
            for (i in 0 until samples.length()) {
                val o = samples.getJSONObject(i)
                val v = o.optString("value").toDoubleOrNull() ?: continue
                byIndex[o.optInt("locationId", i)] = v
            }
            if (byIndex.size != expected) throw IOException("USGS returned ${byIndex.size} of $expected points")
            return (0 until expected).map { byIndex.getValue(it) }
        }

        fun explainNetworkFailure(message: String): String {
            val why = Regex("""\(Mapbox terrain failed: [^)]*\)""").find(message)?.value?.let { " $it" }.orEmpty()
            val hosts = UNRESOLVED_HOST.findAll(message).map { it.groupValues[1].trimEnd('.') }.distinct().toList()
            if (hosts.isEmpty()) {
                return if (Regex("""\b429\b""").containsMatchIn(message)) {
                    "The free elevation service is rate-limiting your connection (HTTP 429). Wait a minute and tap Find again.$why"
                } else {
                    message
                }
            }
            val aiHosts = hosts.filter { it in AI_HOSTS }
            return if (aiHosts.isNotEmpty() && aiHosts.size == hosts.size) {
                "Your connection is blocking the AI servers (${aiHosts.joinToString()}). Try cell data, or turn off Private DNS / ad-blocker / VPN filtering, then tap Again."
            } else {
                "Couldn't reach ${hosts.joinToString()} — check your connection, then tap Again."
            }
        }

        /** (system context, user message) for the AI. Pure so it can be unit tested. */
        fun buildPrompt(
            candidates: List<StandCandidate>,
            forecast: ThermalForecast?,
            image: SatelliteImage?,
            centerLat: Double,
            centerLon: Double,
            locationLabel: String,
            dateLabel: String,
            obstacles: MappedObstacles = MappedObstacles.NONE,
        ): Pair<String, String> {
            val system = buildString {
                appendLine("ROLE: Expert whitetail hunter and terrain scout. Pick the best tree-stand sites and hunting areas on this property.")
                appendLine("Use the terrain candidates (computed from elevation), the thermal/wind forecast, and the satellite image if attached")
                appendLine("(look for field edges, inside corners, thick cover, oak flats, creek crossings, pinch points).")
                appendLine("A stand works when scent drifts AWAY from where deer come from during that sit. Morning: deer move from feeding up to bedding. Evening: bedding back down to feed.")
                appendLine("Candidate lines say \"away from deer\", \"marginal\", or \"POOR\". Only \"away from deer\" is a confident setup. Do not describe a POOR or marginal sit as scent blowing away from deer. If every candidate is POOR, say the wind is wrong instead of inventing a stand.")
                appendLine("Never place picks on or near roads, buildings, driveways, water or yards (stay 150+ ft from roads, 200+ ft from houses). Remind nothing about permission; the app already does.")
                appendLine("The user is in the US: use feet, yards and miles, never meters.")
                appendLine()
                appendLine("OUTPUT: only a JSON array, no prose, no code fences. 3-4 stands + up to 2 areas, best first:")
                appendLine("""[{"rank":1,"kind":"stand","candidate":3,"title":"Saddle stand","best_time":"Evening sit","wind":"NW or W","why":"...","entry":"..."}]""")
                appendLine("Use \"candidate\" with a candidate id when picking one; for a spot you see in the image instead, omit candidate and give \"x\" and \"y\" (0-100 % of the image from left/top).")
                appendLine("kind is \"stand\" or \"area\". Keep title ≤ 5 words; why, entry and wind ≤ 22 words each, plain compass words (NE, SW).")
            }
            val user = buildString {
                appendLine("SITE: ${locationLabel.ifBlank { "Hunting property" }} · center %.5f, %.5f · %s".format(centerLat, centerLon, dateLabel))
                if (forecast != null) {
                    val day = forecast.hours.filter { it.hour in 5..20 }
                    appendLine("FORECAST: %.0f°F → %.0f°F".format(day.minOf { it.temperatureF }, day.maxOf { it.temperatureF }))
                    val windy = forecast.hours.firstOrNull { it.hour == 7 }
                    val evening = forecast.hours.firstOrNull { it.hour == 17 }
                    windy?.let { appendLine("7 AM wind: %.0f mph in cover toward %s".format(it.groundWindMph, ThermalWindEngine.compass(it.windTowardDegrees))) }
                    evening?.let { appendLine("5 PM wind: %.0f mph in cover toward %s".format(it.groundWindMph, ThermalWindEngine.compass(it.windTowardDegrees))) }
                    forecast.morningSwitchHour?.let { appendLine("Morning thermal switch (falling→rising) ~${ThermalWindEngine.formatHour(it)}") }
                    forecast.eveningSwitchHour?.let { appendLine("Evening thermal switch (rising→falling) ~${ThermalWindEngine.formatHour(it)}") }
                } else {
                    appendLine("FORECAST: not loaded — reason from terrain and generic thermals.")
                }
                appendLine()
                appendLine("SEARCH AREA: everything within 750 yards of the center.")
                appendLine("CANDIDATES (id | feature | image x%,y% | elev ft | slope° | uphill | AM score, honesty, scent→ | PM score, honesty, scent→ | road ft | house ft):")
                candidates.forEach { c ->
                    val pos = image?.let {
                        val (x, y) = StandFinder.latLonToImagePercent(c.latitude, c.longitude, it.centerLat, it.centerLon, it.zoom, it.widthPx, it.heightPx)
                        "%.0f,%.0f".format(x, y)
                    } ?: "-"
                    appendLine(
                        "%d | %s | %s | %.0f | %.0f | %s | %d%% %s → %s | %d%% %s → %s".format(
                            c.id,
                            c.feature.label,
                            pos,
                            c.elevationFeet,
                            c.slopeDegrees,
                            c.uphillBearing?.let { ThermalWindEngine.compass(it) } ?: "flat",
                            (c.morningScore * 100).toInt(),
                            StandFinder.sitHonesty(c.morningAlign),
                            c.morningScentToward?.let { ThermalWindEngine.compass(it) } ?: "calm",
                            (c.eveningScore * 100).toInt(),
                            StandFinder.sitHonesty(c.eveningAlign),
                            c.eveningScentToward?.let { ThermalWindEngine.compass(it) } ?: "calm",
                        ) + " | " + (obstacles.nearestRoadFeet(c.latitude, c.longitude)?.let { "%.0f".format(it) } ?: "?") +
                            " | " + (obstacles.nearestBuildingFeet(c.latitude, c.longitude)?.let { "%.0f".format(it) } ?: "?"),
                    )
                }
                if (candidates.isEmpty()) appendLine("(none found — pick from the image)")
            }
            return system to user
        }

        /** Parses the AI's JSON array into picks, dropping anything that can't be placed. */
        fun parsePicks(
            text: String,
            candidates: List<StandCandidate>,
            image: SatelliteImage?,
            centerLat: Double,
            centerLon: Double,
            obstacles: MappedObstacles = MappedObstacles.NONE,
        ): List<StandPick> {
            val start = text.indexOf('[')
            val end = text.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val array = runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
            val byId = candidates.associateBy { it.id }
            val picks = mutableListOf<StandPick>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val candidate = if (o.has("candidate") && !o.isNull("candidate")) byId[o.optInt("candidate", -1)] else null
                val position: Pair<Double, Double> = when {
                    candidate != null -> candidate.latitude to candidate.longitude
                    image != null && o.has("x") && o.has("y") -> {
                        val x = o.optDouble("x", Double.NaN)
                        val y = o.optDouble("y", Double.NaN)
                        if (x.isNaN() || y.isNaN() || x !in 0.0..100.0 || y !in 0.0..100.0) continue
                        StandFinder.imagePercentToLatLon(x, y, image.centerLat, image.centerLon, image.zoom, image.widthPx, image.heightPx)
                    }
                    else -> continue
                }
                // Keep everything near the search square.
                if (StandFinder.distanceMeters(centerLat, centerLon, position.first, position.second) > StandFinder.MAX_PICK_DISTANCE_M) continue
                // Spots placed from the photo never went through findCandidates — keep them off roads and yards too.
                if (obstacles.tooClose(position.first, position.second)) continue
                val kind = if (o.optString("kind").equals("area", ignoreCase = true)) StandPick.Kind.AREA else StandPick.Kind.STAND
                val bestTime = o.optString("best_time").ifBlank { candidate?.bestWindow?.label ?: "" }
                val scent = candidate?.let {
                    when {
                        bestTime.contains("morning", ignoreCase = true) -> it.morningScentToward
                        bestTime.contains("evening", ignoreCase = true) -> it.eveningScentToward
                        else -> it.bestScentToward
                    }
                }
                picks += StandPick(
                    rank = o.optInt("rank", picks.size + 1),
                    latitude = position.first,
                    longitude = position.second,
                    kind = kind,
                    title = o.optString("title").ifBlank { candidate?.feature?.label ?: if (kind == StandPick.Kind.AREA) "Hunt area" else "Stand" },
                    bestTime = bestTime,
                    wind = o.optString("wind"),
                    why = o.optString("why"),
                    entry = o.optString("entry"),
                    scentToward = if (kind == StandPick.Kind.STAND) scent else null,
                    candidateId = candidate?.id,
                )
            }
            return picks.sortedBy { it.rank }.mapIndexed { i, p -> p.copy(rank = i + 1) }
        }

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
