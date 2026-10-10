package com.example.data

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Resolves the public LiDAR tiles intersecting a point or map box.
 *
 * USGS 3DEP by way of The National Map is the primary source and covers the whole country, so
 * lookups are not limited to any one state. The New York ITS LAS index remains a fallback for
 * queries inside New York, where it sometimes indexes surveys ahead of the national feed; each of
 * its features carries a DIRECT_DL value pointing at the matching LAS/LAZ payload. Results are
 * cached by the caller and downloaded through the existing LazImportRepository.
 */
class NysHistoricLazTileCatalog(
    private val httpClient: OkHttpClient = LazHttp.shared,
) {
    data class Tile(
        val objectId: Long,
        val name: String,
        val downloadUrl: String,
        val minLongitude: Double?,
        val minLatitude: Double?,
        val maxLongitude: Double?,
        val maxLatitude: Double?,
        /** Source acquisition project, so the picker can show which survey a tile came from. */
        val project: String = "",
    )

    data class DownloadEstimate(
        val knownBytes: Long,
        val unknownTileCount: Int,
    )

    suspend fun tilesAt(longitude: Double, latitude: Double): List<Tile> = withContext(Dispatchers.IO) {
        val national = runCatching { queryNationalMap(longitude, latitude, longitude, latitude) }
        national.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        if (NortheastLidarRegion.NEW_YORK.contains(longitude, latitude)) {
            return@withContext queryNys(
                geometry = "$longitude,$latitude",
                geometryType = "esriGeometryPoint",
            )
        }
        // The ITS index holds New York surveys only, so anywhere else its answer would be an empty
        // result dressed up as a NY failure. Report what USGS actually said instead.
        national.getOrThrow()
    }

    suspend fun tilesInBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<Tile> = withContext(Dispatchers.IO) {
        val national = runCatching { queryNationalMap(west, south, east, north) }
        national.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        if (NortheastLidarRegion.NEW_YORK.intersects(west, south, east, north)) {
            return@withContext queryNys(
                geometry = "$west,$south,$east,$north",
                geometryType = "esriGeometryEnvelope",
            )
        }
        national.getOrThrow()
    }

    /** Reads source file sizes without downloading a LAZ payload. */
    suspend fun estimateDownloadBytes(tiles: List<Tile>): DownloadEstimate = withContext(Dispatchers.IO) {
        var knownBytes = 0L
        var unknownTiles = 0
        tiles.distinctBy(Tile::downloadUrl).forEach { tile ->
            val bytes = runCatching { contentLength(tile.downloadUrl) }.getOrNull()
            if (bytes == null || bytes < 0L) unknownTiles++ else knownBytes += bytes
        }
        DownloadEstimate(knownBytes, unknownTiles)
    }

    private fun contentLength(url: String): Long? {
        val head = Request.Builder().url(url).head().build()
        httpClient.newCall(head).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
        }
        // Some public LiDAR hosts deliberately omit HEAD metadata. A single range byte obtains
        // Content-Range without transferring the compressed point cloud.
        val ranged = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
        httpClient.newCall(ranged).execute().use { response ->
            response.header("Content-Range")?.let(::contentRangeLength)?.let { return it }
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
        }
        return null
    }

    internal fun contentRangeLength(header: String): Long? =
        header.substringAfter('/', missingDelimiterValue = "").toLongOrNull()?.takeIf { it >= 0L }

    private fun queryNys(geometry: String, geometryType: String): List<Tile> {
        val url = buildQueryUrl(geometry, geometryType)
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("NYS LiDAR tile lookup failed with HTTP ${response.code}")
            parse(body)
        }
    }

    private data class NationalMapPage(val tiles: List<Tile>, val rawItemCount: Int)

    /**
     * Walks the paged product feed. A single page covers only a small area, so a search across a
     * whole state used to stop at the first 100 products and silently omit the rest.
     */
    private fun queryNationalMap(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<Tile> {
        val collected = ArrayList<Tile>()
        var offset = 0
        while (offset < MAX_NATIONAL_MAP_RESULTS) {
            val page = fetchNationalMapPage(west, south, east, north, offset)
            collected += page.tiles
            // Paging is decided on the raw product count: a page can parse down to far fewer tiles
            // once non-LAZ products and near-miss footprints are dropped, and treating that
            // shrinkage as the end of the feed would cut the search short.
            if (page.rawItemCount < NATIONAL_MAP_PAGE_SIZE) break
            offset += NATIONAL_MAP_PAGE_SIZE
        }
        return collected
            .distinctBy(Tile::downloadUrl)
            .sortedBy { if (it.project.contains(NATIONAL_MAP_PROJECT_ID, ignoreCase = true)) 0 else 1 }
    }

    private fun fetchNationalMapPage(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        offset: Int,
    ): NationalMapPage {
        val url = buildNationalMapUrl(west, south, east, north, offset)
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("USGS LiDAR tile lookup failed with HTTP ${response.code}")
            }
            val rawItemCount = runCatching {
                JSONObject(body).optJSONArray("items")?.length() ?: 0
            }.getOrDefault(0)
            return NationalMapPage(parseNationalMap(body, west, south, east, north), rawItemCount)
        }
    }

    internal fun buildNationalMapUrl(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        offset: Int = 0,
    ): String {
        fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        val paddedWest = west - QUERY_PADDING_DEGREES
        val paddedSouth = south - QUERY_PADDING_DEGREES
        val paddedEast = east + QUERY_PADDING_DEGREES
        val paddedNorth = north + QUERY_PADDING_DEGREES
        return buildString {
            append(NATIONAL_MAP_PRODUCTS_URL)
            append("?datasets=").append(encoded("Lidar Point Cloud (LPC)"))
            append("&bbox=").append(encoded("$paddedWest,$paddedSouth,$paddedEast,$paddedNorth"))
            append("&max=").append(NATIONAL_MAP_PAGE_SIZE)
            if (offset > 0) append("&offset=").append(offset)
        }
    }

    internal fun parseNationalMap(
        json: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<Tile> {
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val title = item.optString("title")
                val downloadUrl = item.optString("downloadURL")
                if (!downloadUrl.startsWith("https://", ignoreCase = true)) continue
                if (!downloadUrl.endsWith(".laz", ignoreCase = true) &&
                    !downloadUrl.endsWith(".las", ignoreCase = true)
                ) continue
                val bounds = item.optJSONObject("boundingBox") ?: continue
                val minX = bounds.optDouble("minX", Double.NaN)
                val minY = bounds.optDouble("minY", Double.NaN)
                val maxX = bounds.optDouble("maxX", Double.NaN)
                val maxY = bounds.optDouble("maxY", Double.NaN)
                if (!listOf(minX, minY, maxX, maxY).all(Double::isFinite)) continue
                if (maxX < west || minX > east || maxY < south || minY > north) continue
                add(
                    Tile(
                        objectId = downloadUrl.hashCode().toLong(),
                        name = downloadUrl.substringAfterLast('/'),
                        downloadUrl = downloadUrl,
                        minLongitude = minX,
                        minLatitude = minY,
                        maxLongitude = maxX,
                        maxLatitude = maxY,
                        project = title,
                    ),
                )
            }
        }
            .distinctBy(Tile::downloadUrl)
            // Any LPC tile whose footprint covers the query is usable. Restricting results to the
            // Hudson Valley SE 4-county 2022 project meant every coordinate outside that one
            // survey returned nothing at all; it is now merely ranked first as the best-known
            // source for this app's primary search area.
            .sortedBy { if (it.project.contains(NATIONAL_MAP_PROJECT_ID, ignoreCase = true)) 0 else 1 }
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
                        project = PROJECT_NAME,
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
        const val NATIONAL_MAP_PRODUCTS_URL = "https://tnmaccess.nationalmap.gov/api/v1/products"

        /** Ceiling on a single area search, so a state-sized box cannot exhaust memory. */
        const val MAX_NATIONAL_MAP_RESULTS = 500
        private const val NATIONAL_MAP_PAGE_SIZE = 100
        private const val NATIONAL_MAP_PROJECT_ID = "NY_SouthEast4County_A22"
        private const val QUERY_PADDING_DEGREES = 0.001
    }
}
