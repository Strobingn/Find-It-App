package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class CopcAsset(
    val id: String,
    val title: String,
    val href: String,
    val bounds: GeoSpatialLibrary.GeographicBounds?,
)

/** Spatial search and access client for the public USGS 3DEP COPC collection. */
class CopcStacCatalog(private val httpClient: OkHttpClient = OkHttpClient()) {
    @Volatile private var cachedSasToken: String? = null
    @Volatile private var cachedSasTokenExpiresAt: Long = 0L

    suspend fun search(
        bounds: GeoSpatialLibrary.GeographicBounds,
        limit: Int = 100,
    ): List<CopcAsset> = withContext(Dispatchers.IO) {
        val body = buildSearchBody(bounds, limit)
        val request = Request.Builder()
            .url(SEARCH_URL)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/geo+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("COPC catalog search failed with HTTP ${response.code}")
            }
            parseSearchResponse(payload)
        }
    }

    internal fun buildSearchBody(
        bounds: GeoSpatialLibrary.GeographicBounds,
        limit: Int,
    ): JSONObject = JSONObject()
        .put("collections", JSONArray().put(COLLECTION_ID))
        .put(
            "bbox",
            JSONArray()
                .put(bounds.minLon)
                .put(bounds.minLat)
                .put(bounds.maxLon)
                .put(bounds.maxLat),
        )
        .put("limit", limit.coerceIn(1, 500))

    internal fun parseSearchResponse(json: String): List<CopcAsset> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        return buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val assets = feature.optJSONObject("assets") ?: continue
                val asset = sequenceOf("data", "copc", "laz")
                    .mapNotNull(assets::optJSONObject)
                    .firstOrNull { candidate -> candidate.optString("href").startsWith("https://") }
                    ?: continue
                val href = asset.optString("href")
                if (!href.contains(".copc.laz", ignoreCase = true) &&
                    !href.substringBefore('?').endsWith(".laz", ignoreCase = true)
                ) {
                    continue
                }
                val geographicBounds = parseBounds(feature.optJSONArray("bbox"))
                val id = feature.optString("id").ifBlank { href.substringAfterLast('/') }
                add(
                    CopcAsset(
                        id = id,
                        title = feature.optJSONObject("properties")
                            ?.optString("title")
                            ?.takeIf(String::isNotBlank)
                            ?: id,
                        href = href,
                        bounds = geographicBounds,
                    ),
                )
            }
        }.distinctBy(CopcAsset::href)
    }

    /** Adds a short-lived Planetary Computer SAS token before Azure Blob access. */
    suspend fun signedAsset(asset: CopcAsset): CopcAsset = withContext(Dispatchers.IO) {
        asset.copy(href = signUrl(asset.href))
    }

    internal fun appendToken(url: String, token: String): String =
        url + if (url.contains('?')) "&$token" else "?$token"

    private fun signUrl(url: String): String {
        if (url.contains("sig=")) return url
        return appendToken(url, sasToken())
    }

    @Synchronized
    private fun sasToken(): String {
        val now = System.currentTimeMillis()
        cachedSasToken
            ?.takeIf { cachedSasTokenExpiresAt > now + TOKEN_REFRESH_MARGIN_MS }
            ?.let { return it }
        val request = Request.Builder()
            .url(SAS_TOKEN_URL)
            .get()
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Planetary Computer signing failed with HTTP ${response.code}")
            }
            val token = JSONObject(body).optString("token").takeIf { it.isNotBlank() }
                ?: throw IOException("Planetary Computer returned no SAS token")
            cachedSasToken = token
            cachedSasTokenExpiresAt = now + TOKEN_LIFETIME_MS
            return token
        }
    }

    private fun parseBounds(bbox: JSONArray?): GeoSpatialLibrary.GeographicBounds? {
        bbox ?: return null
        if (bbox.length() < 4) return null
        val maxOffset = if (bbox.length() >= 6) 3 else 2
        val minLon = bbox.optDouble(0)
        val minLat = bbox.optDouble(1)
        val maxLon = bbox.optDouble(maxOffset)
        val maxLat = bbox.optDouble(maxOffset + 1)
        return GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon).takeIf {
            minLat.isFinite() && maxLat.isFinite() && minLon.isFinite() && maxLon.isFinite() &&
                minLat < maxLat && minLon < maxLon &&
                minLat in -90.0..90.0 && maxLat in -90.0..90.0 &&
                minLon in -180.0..180.0 && maxLon in -180.0..180.0
        }
    }

    companion object {
        const val SEARCH_URL = "https://planetarycomputer.microsoft.com/api/stac/v1/search"
        const val SAS_TOKEN_URL =
            "https://planetarycomputer.microsoft.com/api/sas/v1/token/3dep-lidar-copc"
        const val COLLECTION_ID = "3dep-lidar-copc"
        private const val TOKEN_LIFETIME_MS = 45 * 60 * 1000L
        private const val TOKEN_REFRESH_MARGIN_MS = 2 * 60 * 1000L
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
