package com.example.data

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolves the exact NYS Southeast 4 County 2022 LiDAR tiles intersecting a point or map box.
 *
 * The NYS ITS LAS index is used instead of guessing USGS filenames. Each returned feature
 * contains a DIRECT_DL value that points to the matching LAS/LAZ payload. Results are cached
 * by the caller and downloaded through the existing LazImportRepository.
 */
class NysHistoricLazTileCatalog(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    data class Tile(
        val objectId: Long,
        val name: String,
        val downloadUrl: String,
        val minLongitude: Double?,
        val minLatitude: Double?,
        val maxLongitude: Double?,
        val maxLatitude: Double?,
    )

    suspend fun tilesAt(longitude: Double, latitude: Double): List<Tile> = query(
        geometry = "$longitude,$latitude",
        geometryType = "esriGeometryPoint",
    )

    suspend fun tilesInBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<Tile> = query(
        geometry = "$west,$south,$east,$north",
        geometryType = "esriGeometryEnvelope",
    )

    private suspend fun query(geometry: String, geometryType: String): List<Tile> = withContext(Dispatchers.IO) {
        val url = buildQueryUrl(geometry, geometryType)
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("NYS LiDAR tile lookup failed with HTTP ${response.code}")
            parse(body)
        }
    }

    internal fun buildQueryUrl(geometry: String, geometryType: String): String {
        fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        return buildString {
            append(LAYER_QUERY_URL)
            append("?f=json")
            append("&where=1%3D1")
            append("&geometry=").append(encoded(geometry))
            append("&geometryType=").append(encoded(geometryType))
            append("&inSR=4326&outSR=4326")
            append("&spatialRel=esriSpatialRelIntersects")
            append("&outFields=*")
            append("&returnGeometry=true")
        }
    }

    internal fun parse(json: String): List<Tile> {
        val root = JSONObject(json)
        root.optJSONObject("error")?.let { error ->
            throw IOException(error.optString("message", "NYS LiDAR tile lookup failed"))
        }
        val features = root.optJSONArray("features") ?: return emptyList()
        return buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val attributes = feature.optJSONObject("attributes") ?: continue
                val direct = firstNonBlank(
                    attributes.optString("DIRECT_DL"),
                    attributes.optString("DIRECTDL"),
                    attributes.optString("DOWNLOAD"),
                    attributes.optString("URL"),
                ) ?: continue
                val cleanUrl = extractHttpUrl(direct) ?: continue
                if (!cleanUrl.endsWith(".laz", ignoreCase = true) && !cleanUrl.endsWith(".las", ignoreCase = true)) continue
                val name = firstNonBlank(
                    attributes.optString("TILE_NAME"),
                    attributes.optString("FILENAME"),
                    attributes.optString("FILE_NAME"),
                    cleanUrl.substringAfterLast('/'),
                ) ?: cleanUrl.substringAfterLast('/')
                val geometry = feature.optJSONObject("geometry")
                val bounds = geometry?.let(::geometryBounds)
                add(
                    Tile(
                        objectId = attributes.optLong("OBJECTID", index.toLong()),
                        name = name,
                        downloadUrl = cleanUrl,
                        minLongitude = bounds?.getOrNull(0),
                        minLatitude = bounds?.getOrNull(1),
                        maxLongitude = bounds?.getOrNull(2),
                        maxLatitude = bounds?.getOrNull(3),
                    ),
                )
            }
        }.distinctBy(Tile::downloadUrl)
    }

    private fun geometryBounds(geometry: JSONObject): List<Double>? {
        val rings = geometry.optJSONArray("rings") ?: return null
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (ringIndex in 0 until rings.length()) {
            val ring = rings.optJSONArray(ringIndex) ?: continue
            for (pointIndex in 0 until ring.length()) {
                val point = ring.optJSONArray(pointIndex) ?: continue
                val x = point.optDouble(0, Double.NaN)
                val y = point.optDouble(1, Double.NaN)
                if (!x.isFinite() || !y.isFinite()) continue
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
            }
        }
        return if (minX.isFinite()) listOf(minX, minY, maxX, maxY) else null
    }

    private fun firstNonBlank(vararg values: String): String? = values.firstOrNull { it.isNotBlank() }

    private fun extractHttpUrl(value: String): String? {
        val unescaped = value.replace("&amp;", "&").replace("\\/", "/")
        val start = unescaped.indexOf("http", ignoreCase = true)
        if (start < 0) return null
        val tail = unescaped.substring(start)
        return tail.substringBefore('"').substringBefore('\'').substringBefore('<').trim()
    }

    companion object {
        const val PROJECT_NAME = "NYS Southeast 4 County 2022"
        const val SOURCE_DIRECTORY = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/"
        const val LAYER_QUERY_URL = "https://orthos.its.ny.gov/arcgis/rest/services/vector/las_indexes/MapServer/4/query"
    }
}
