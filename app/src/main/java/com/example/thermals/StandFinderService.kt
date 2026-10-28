package com.example.thermals

import android.content.Context
import com.example.BuildConfig
import com.example.ai.GeminiConversationTurn
import com.example.ai.GeminiImageInput
import com.example.ai.TerrainAiGateway
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Result of a stand search: the analysed candidates and the picks to pin on the map. */
data class StandSearchResult(
    val candidates: List<StandCandidate>,
    val picks: List<StandPick>,
    /** "Grok grok-4.7" or "On-device" — who chose the picks. */
    val source: String,
    val note: String? = null,
)

/**
 * Network side of the stand finder: a 1.6 km elevation grid (Open-Meteo, key-less), a satellite
 * picture of the same square (Mapbox Static Images) and the cloud AI that turns terrain + thermals
 * + imagery into ranked stand and hunting-area picks.
 */
class StandFinderService(
    context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    private val appContext = context.applicationContext
    private val gateway = TerrainAiGateway(appContext)

    suspend fun fetchGrid(lat: Double, lon: Double): ElevationGrid = withContext(Dispatchers.IO) {
        val points = ElevationGrid.samplePoints(lat, lon, StandFinder.GRID_SIZE, StandFinder.GRID_SPACING_M)
        val meters = points.chunked(100).flatMapIndexed { index, chunk ->
            if (index > 0) kotlinx.coroutines.delay(400) // don't burst the free elevation service
            val url = String.format(
                Locale.US,
                "%s?latitude=%s&longitude=%s",
                ThermalWeatherClient.ELEVATION_ENDPOINT,
                chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.first) },
                chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.second) },
            )
            val body = runCatching { getText(url) }.getOrElse { firstError ->
                if (!firstError.message.orEmpty().contains("429")) throw firstError
                kotlinx.coroutines.delay(4_000) // rate-limited: back off once, then retry
                getText(url)
            }
            val array = JSONObject(body).optJSONArray("elevation") ?: throw IOException("Elevation service returned no data")
            (0 until array.length()).map { array.getDouble(it) }
        }
        if (meters.size != points.size) throw IOException("Elevation service returned ${meters.size} of ${points.size} points")
        ElevationGrid(lat, lon, StandFinder.GRID_SIZE, StandFinder.GRID_SPACING_M, meters.toDoubleArray())
    }

    /** Satellite picture of the search square, or null without a Mapbox token / network. */
    suspend fun fetchSatellite(lat: Double, lon: Double): SatelliteImage? = withContext(Dispatchers.IO) {
        val token = BuildConfig.MAPBOX_ACCESS_TOKEN.trim()
        if (token.isBlank()) return@withContext null
        val span = StandFinder.GRID_SPACING_M * (StandFinder.GRID_SIZE - 1) + 200.0
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
    ): Pair<List<StandPick>, String> {
        val prompt = buildPrompt(candidates, forecast, image, centerLat, centerLon, locationLabel, dateLabel)
        val answer = gateway.generate(
            conversation = listOf(GeminiConversationTurn(role = "user", text = prompt.second)),
            systemContext = prompt.first,
            image = image?.let { GeminiImageInput(it.bytes, it.mimeType, "Satellite view of the 1.7 km search square, north up, stand search center in the middle") },
            onProviderStage = onStage,
            featureName = "stand_finder",
        )
        val picks = parsePicks(answer.text, candidates, image, centerLat, centerLon)
        if (picks.isEmpty()) throw IOException("The AI didn't return any usable picks")
        return picks to "${answer.provider.label} ${TerrainAiGateway.modelFor(answer.provider)}"
    }

    private fun getText(url: String): String {
        httpClient.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("Elevation service HTTP ${response.code}")
            return body
        }
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

        /** (system context, user message) for the AI. Pure so it can be unit tested. */
        fun buildPrompt(
            candidates: List<StandCandidate>,
            forecast: ThermalForecast?,
            image: SatelliteImage?,
            centerLat: Double,
            centerLon: Double,
            locationLabel: String,
            dateLabel: String,
        ): Pair<String, String> {
            val system = buildString {
                appendLine("ROLE: Expert whitetail hunter and terrain scout. Pick the best tree-stand sites and hunting areas on this property.")
                appendLine("Use the terrain candidates (computed from elevation), the thermal/wind forecast, and the satellite image if attached")
                appendLine("(look for field edges, inside corners, thick cover, oak flats, creek crossings, pinch points).")
                appendLine("A stand works when scent drifts AWAY from where deer come from during that sit. Morning: deer move from feeding up to bedding. Evening: bedding back down to feed.")
                appendLine("Never place picks on roads, buildings, water or yards. Remind nothing about permission; the app already does.")
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
                appendLine("CANDIDATES (id | feature | image x%,y% | elev ft | slope° | uphill | AM score, scent→ | PM score, scent→):")
                candidates.forEach { c ->
                    val pos = image?.let {
                        val (x, y) = StandFinder.latLonToImagePercent(c.latitude, c.longitude, it.centerLat, it.centerLon, it.zoom, it.widthPx, it.heightPx)
                        "%.0f,%.0f".format(x, y)
                    } ?: "-"
                    appendLine(
                        "%d | %s | %s | %.0f | %.0f | %s | %d%% → %s | %d%% → %s".format(
                            c.id,
                            c.feature.label,
                            pos,
                            c.elevationFeet,
                            c.slopeDegrees,
                            c.uphillBearing?.let { ThermalWindEngine.compass(it) } ?: "flat",
                            (c.morningScore * 100).toInt(),
                            ThermalWindEngine.compass(c.morningScentToward),
                            (c.eveningScore * 100).toInt(),
                            ThermalWindEngine.compass(c.eveningScentToward),
                        ),
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
                if (StandFinder.distanceMeters(centerLat, centerLon, position.first, position.second) > 1_500.0) continue
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
