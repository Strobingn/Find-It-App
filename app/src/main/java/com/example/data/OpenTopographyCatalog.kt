package com.example.data

import com.example.geospatial.GeoSpatialLibrary
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenTopography hosted point clouds, cropped to the search box.
 *
 * Dataset discovery uses the public `otCatalog` search (no API key). Each hit becomes one LAZ
 * download of `/API/pointcloud` for the user's envelope rather than a pile of whole tiles. The
 * crop URL is stored without `API_Key`; [attachApiKeyIfNeeded] adds a myOpenTopo key at transfer
 * time so the key is not persisted in the picker session.
 */
class OpenTopographyCatalog(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun tilesInBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<NysHistoricLazTileCatalog.Tile> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(catalogUrl(west, south, east, north))
            .header("Accept", "application/json")
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OpenTopography catalog failed with HTTP ${response.code}")
            }
            tilesFromCatalogJson(body, west, south, east, north)
        }
    }

    companion object {
        const val CATALOG_URL = "https://portal.opentopography.org/API/otCatalog"
        const val POINT_CLOUD_URL = "https://portal.opentopography.org/API/pointcloud"
        private const val MAX_DATASETS = 24

        internal fun catalogUrl(
            west: Double,
            south: Double,
            east: Double,
            north: Double,
        ): String {
            fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            return buildString {
                append(CATALOG_URL)
                append("?productFormat=PointCloud")
                append("&minx=").append(encoded(west.toString()))
                append("&miny=").append(encoded(south.toString()))
                append("&maxx=").append(encoded(east.toString()))
                append("&maxy=").append(encoded(north.toString()))
                append("&detail=true")
                append("&outputFormat=json")
            }
        }

        internal fun cropUrl(
            datasetName: String,
            west: Double,
            south: Double,
            east: Double,
            north: Double,
        ): String {
            fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            return buildString {
                append(POINT_CLOUD_URL)
                append("?datasetName=").append(encoded(datasetName))
                append("&west=").append(encoded(west.toString()))
                append("&south=").append(encoded(south.toString()))
                append("&east=").append(encoded(east.toString()))
                append("&north=").append(encoded(north.toString()))
                append("&outputFormat=LAZ")
            }
        }

        fun needsApiKey(url: String): Boolean {
            val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            val host = uri.host.orEmpty().lowercase()
            if (host != "portal.opentopography.org") return false
            val path = uri.path.orEmpty().lowercase()
            if (!path.contains("/api/pointcloud")) return false
            return !url.contains("API_Key=", ignoreCase = true)
        }

        fun attachApiKeyIfNeeded(url: String, apiKey: String): String {
            if (!needsApiKey(url)) return url
            val cleaned = apiKey.trim()
            if (cleaned.isBlank()) {
                throw IOException(
                    "Add a free OpenTopography API key from myOpenTopo to download cropped LAZ.",
                )
            }
            val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8.name())
            return if (url.contains('?')) "$url&API_Key=$encoded" else "$url?API_Key=$encoded"
        }

        internal fun tilesFromCatalogJson(
            json: String,
            west: Double,
            south: Double,
            east: Double,
            north: Double,
        ): List<NysHistoricLazTileCatalog.Tile> =
            parseDatasets(json).mapNotNull { dataset ->
                tileFor(dataset, west, south, east, north)
            }.let(::rankLidarTiles)

        internal fun parseDatasets(json: String): List<OtDataset> {
            val datasets = JSONObject(json).optJSONArray("Datasets") ?: return emptyList()
            return buildList {
                for (index in 0 until min(datasets.length(), MAX_DATASETS)) {
                    val dataset = datasets.optJSONObject(index)?.optJSONObject("Dataset") ?: continue
                    val identifier = dataset.optJSONObject("identifier")?.optString("value")
                        ?.takeIf { it.isNotBlank() }
                        ?: dataset.optString("identifier").takeIf { it.isNotBlank() }.orEmpty()
                    val shortName = dataset.optString("alternateName").ifBlank { identifier }
                    if (shortName.isBlank()) continue
                    val fileFormat = dataset.optString("fileFormat")
                    if (fileFormat.isNotBlank() &&
                        !fileFormat.contains("Point", ignoreCase = true) &&
                        !fileFormat.contains("LAS", ignoreCase = true)
                    ) {
                        continue
                    }
                    add(
                        OtDataset(
                            datasetName = shortName,
                            openTopoId = identifier,
                            title = dataset.optString("name").ifBlank { shortName },
                            acquiredYear = parseYear(
                                dataset.optString("temporalCoverage").ifBlank {
                                    dataset.optString("dateCreated")
                                },
                            ),
                            bounds = parseSpatialCoverage(dataset.optJSONObject("spatialCoverage")),
                        ),
                    )
                }
            }
        }

        internal fun parseYear(raw: String): Int? {
            val years = Regex("(20\\d{2}|19\\d{2})").findAll(raw).mapNotNull { it.value.toIntOrNull() }
            return years.maxOrNull()
        }

        internal fun parseSpatialCoverage(coverage: JSONObject?): GeoSpatialLibrary.GeographicBounds? {
            coverage ?: return null
            return geojsonEnvelope(coverage)
        }

        internal fun geojsonEnvelope(node: Any?): GeoSpatialLibrary.GeographicBounds? {
            val lons = ArrayList<Double>()
            val lats = ArrayList<Double>()
            fun walk(value: Any?) {
                when (value) {
                    is JSONArray -> {
                        if (value.length() >= 2 && value.optJSONArray(0) == null &&
                            value.optJSONObject(0) == null
                        ) {
                            val lon = value.optDouble(0, Double.NaN)
                            val lat = value.optDouble(1, Double.NaN)
                            if (lon.isFinite() && lat.isFinite() &&
                                lat in -90.0..90.0 && lon in -180.0..180.0
                            ) {
                                lons += lon
                                lats += lat
                                return
                            }
                        }
                        for (index in 0 until value.length()) walk(value.opt(index))
                    }
                    is JSONObject -> {
                        value.optJSONArray("coordinates")?.let { walk(it); return }
                        value.optJSONArray("features")?.let { walk(it); return }
                        value.optJSONObject("geometry")?.let { walk(it); return }
                        value.optJSONObject("geojson")?.let { walk(it); return }
                        value.optJSONObject("geo")?.let { walk(it); return }
                        val keys = value.keys()
                        while (keys.hasNext()) walk(value.opt(keys.next()))
                    }
                }
            }
            walk(node)
            if (lons.isEmpty() || lats.isEmpty()) return null
            val minLat = lats.min()
            val maxLat = lats.max()
            val minLon = lons.min()
            val maxLon = lons.max()
            return GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon).takeIf {
                minLat < maxLat && minLon < maxLon
            }
        }

        internal fun intersect(
            west: Double,
            south: Double,
            east: Double,
            north: Double,
            datasetBounds: GeoSpatialLibrary.GeographicBounds?,
        ): GeoSpatialLibrary.GeographicBounds? {
            val minLat = max(south, datasetBounds?.minLat ?: south)
            val maxLat = min(north, datasetBounds?.maxLat ?: north)
            val minLon = max(west, datasetBounds?.minLon ?: west)
            val maxLon = min(east, datasetBounds?.maxLon ?: east)
            return GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon).takeIf {
                minLat < maxLat && minLon < maxLon
            }
        }

        private fun tileFor(
            dataset: OtDataset,
            west: Double,
            south: Double,
            east: Double,
            north: Double,
        ): NysHistoricLazTileCatalog.Tile? {
            val crop = intersect(west, south, east, north, dataset.bounds) ?: return null
            val url = cropUrl(dataset.datasetName, crop.minLon, crop.minLat, crop.maxLon, crop.maxLat)
            val fileName = "${dataset.datasetName.replace(Regex("[^A-Za-z0-9._-]+"), "_")}_crop.laz"
            return NysHistoricLazTileCatalog.Tile(
                objectId = url.hashCode().toLong(),
                name = fileName,
                downloadUrl = url,
                minLongitude = crop.minLon,
                minLatitude = crop.minLat,
                maxLongitude = crop.maxLon,
                maxLatitude = crop.maxLat,
                project = dataset.title,
                source = LidarDownloadSource.OPEN_TOPOGRAPHY,
                hostLabel = LidarDownloadSource.OPEN_TOPOGRAPHY.shortHost,
                acquiredYear = dataset.acquiredYear,
            )
        }
    }

    internal data class OtDataset(
        val datasetName: String,
        val openTopoId: String,
        val title: String,
        val acquiredYear: Int?,
        val bounds: GeoSpatialLibrary.GeographicBounds?,
    )
}
