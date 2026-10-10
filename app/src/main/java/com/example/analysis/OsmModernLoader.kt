package com.example.analysis

import com.example.geospatial.GeoSpatialLibrary
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Loads modern OSM roads and buildings for the current tile so detectors can reject them.
 * Network failure returns [OsmModernContext.Empty]; analysis still runs.
 */
object OsmModernLoader {
    const val USER_AGENT =
        "FindIt-LidarSurveyApp/2.0 (personal historic LiDAR research; Android; Overpass client)"

    fun load(
        bounds: GeoSpatialLibrary.GeographicBounds,
        opener: (String) -> String? = { queryOverpass(it) },
    ): OsmModernContext {
        val south = minOf(bounds.minLat, bounds.maxLat)
        val north = maxOf(bounds.minLat, bounds.maxLat)
        val west = minOf(bounds.minLon, bounds.maxLon)
        val east = maxOf(bounds.minLon, bounds.maxLon)
        val query = """
            [out:json][timeout:25];
            (
              way["highway"~"motorway|trunk|primary|secondary|tertiary|residential|unclassified|service"]($south,$west,$north,$east);
              way["building"]($south,$west,$north,$east);
            );
            out center;
        """.trimIndent()
        val body = opener(query) ?: return OsmModernContext.Empty
        return parse(body, bounds)
    }

    internal fun parse(body: String, bounds: GeoSpatialLibrary.GeographicBounds): OsmModernContext {
        val elements = runCatching { JSONObject(body).optJSONArray("elements") }.getOrNull()
            ?: return OsmModernContext.Empty
        val spanLat = (bounds.maxLat - bounds.minLat).takeIf { it > 0.0 } ?: return OsmModernContext.Empty
        val spanLon = (bounds.maxLon - bounds.minLon).takeIf { it > 0.0 } ?: return OsmModernContext.Empty
        val features = buildList {
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                val tags = item.optJSONObject("tags") ?: JSONObject()
                val center = item.optJSONObject("center") ?: continue
                val lat = center.optDouble("lat")
                val lon = center.optDouble("lon")
                if (!lat.isFinite() || !lon.isFinite()) continue
                val kind = when {
                    tags.has("building") -> OsmModernFeature.Kind.BUILDING
                    tags.has("highway") -> OsmModernFeature.Kind.ROAD
                    else -> continue
                }
                add(
                    OsmModernFeature(
                        xPercent = (((lon - bounds.minLon) / spanLon) * 100.0).toFloat().coerceIn(0f, 100f),
                        yPercent = (((bounds.maxLat - lat) / spanLat) * 100.0).toFloat().coerceIn(0f, 100f),
                        radiusMeters = if (kind == OsmModernFeature.Kind.ROAD) 14f else 10f,
                        kind = kind,
                    ),
                )
            }
        }
        return OsmModernContext(features)
    }

    private fun queryOverpass(ql: String): String? {
        val encoded = URLEncoder.encode(ql, StandardCharsets.UTF_8.name())
        val connection = (URL("https://overpass-api.de/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connectTimeout = 15_000
            readTimeout = 30_000
            outputStream.use { it.write("data=$encoded".toByteArray(StandardCharsets.UTF_8)) }
        }
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
