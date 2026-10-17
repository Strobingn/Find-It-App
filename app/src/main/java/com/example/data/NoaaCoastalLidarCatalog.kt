package com.example.data

import com.example.geospatial.GeoSpatialLibrary
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
 * NOAA Digital Coast COPC on the public AWS bucket.
 *
 * Dataset footprints come from the DAV map service. Individual 1500 m COPC tiles live next to
 * each collection and are filtered geographically from two sampled tile boxes plus the filename
 * grid (`yyyyMMdd_eeeeeennnnn.copc`).
 */
class NoaaCoastalLidarCatalog(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun tilesInBounds(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<NysHistoricLazTileCatalog.Tile> = withContext(Dispatchers.IO) {
        val datasets = queryDatasets(west, south, east, north)
        buildList {
            for (dataset in datasets.take(MAX_DATASETS)) {
                val collection = openCollection(dataset.id) ?: continue
                addAll(
                    tilesFromCollection(
                        dataset = dataset,
                        collectionJson = collection.body,
                        collectionUrl = collection.url,
                        west = west,
                        south = south,
                        east = east,
                        north = north,
                    ),
                )
            }
        }.distinctBy(NysHistoricLazTileCatalog.Tile::downloadUrl).let(::rankLidarTiles)
    }

    internal fun queryDatasets(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<NoaaDataset> {
        val url = buildDavQueryUrl(west, south, east, north)
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("NOAA lidar lookup failed with HTTP ${response.code}")
            }
            return parseDavDatasets(body)
        }
    }

    internal fun buildDavQueryUrl(
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): String {
        fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        return buildString {
            append(DAV_FOOTPRINTS_QUERY)
            append("?f=json")
            append("&where=").append(encoded("data_type='Lidar'"))
            append("&geometry=").append(encoded("$west,$south,$east,$north"))
            append("&geometryType=esriGeometryEnvelope")
            append("&inSR=4326")
            append("&spatialRel=esriSpatialRelIntersects")
            append("&outFields=").append(encoded("id,title,data_type"))
            append("&returnGeometry=false")
            append("&resultRecordCount=").append(MAX_DATASETS)
        }
    }

    internal fun parseDavDatasets(json: String): List<NoaaDataset> {
        val features = JSONObject(json).optJSONArray("features") ?: JSONArray()
        return buildList {
            for (index in 0 until features.length()) {
                val attributes = features.optJSONObject(index)?.optJSONObject("attributes") ?: continue
                val id = attributes.optInt("id", -1).takeIf { it > 0 } ?: continue
                val title = attributes.optString("title").ifBlank { "NOAA dataset $id" }
                add(NoaaDataset(id = id, title = title))
            }
        }
    }

    private data class OpenedCollection(val url: String, val body: String)

    private fun openCollection(datasetId: Int): OpenedCollection? {
        for (datum in DATUMS) {
            val url = collectionUrl(datum, datasetId)
            val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
            val body = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string().orEmpty().takeIf { it.startsWith("{") }
            }
            if (!body.isNullOrBlank()) return OpenedCollection(url, body)
        }
        return null
    }

    internal fun tilesFromCollection(
        dataset: NoaaDataset,
        collectionJson: String,
        collectionUrl: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): List<NysHistoricLazTileCatalog.Tile> {
        val items = parseItemHrefs(collectionJson, collectionUrl)
        if (items.isEmpty()) return emptyList()
        val gridItems = items.mapNotNull { href ->
            val fileName = href.substringAfterLast('/').removeSuffix(".json")
            val codes = parseGridName(fileName) ?: return@mapNotNull null
            Triple(href, fileName, codes)
        }
        val samplePairs = pickGridSamples(gridItems).mapNotNull { (href, fileName, _) ->
            fetchItemBounds(href)?.let { fileName to it }
        }
        if (samplePairs.size >= 2) {
            val estimator = NoaaCopcGrid.fit(samplePairs)
            return gridItems.mapNotNull { (href, fileName, _) ->
                val bounds = estimator.boundsFor(fileName) ?: return@mapNotNull null
                if (bounds.maxLon < west || bounds.minLon > east ||
                    bounds.maxLat < south || bounds.minLat > north
                ) {
                    return@mapNotNull null
                }
                tileFor(
                    dataset = dataset,
                    fileName = fileName,
                    lazUrl = lazUrlFor(href),
                    bounds = bounds,
                )
            }
        }
        if (items.size > MAX_ITEM_FETCH) return emptyList()
        return items.mapNotNull { href -> fetchIntersectingItem(dataset, href, west, south, east, north) }
    }

    private fun pickGridSamples(
        gridItems: List<Triple<String, String, NoaaCopcGrid.GridName>>,
    ): List<Triple<String, String, NoaaCopcGrid.GridName>> {
        if (gridItems.size < 2) return emptyList()
        val first = gridItems.first()
        val second = gridItems.firstOrNull {
            it.third.eastingCode != first.third.eastingCode ||
                it.third.northingCode != first.third.northingCode
        } ?: return emptyList()
        return listOf(first, second)
    }

    private fun fetchIntersectingItem(
        dataset: NoaaDataset,
        href: String,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
    ): NysHistoricLazTileCatalog.Tile? {
        val request = Request.Builder().url(href).header("Accept", "application/json").get().build()
        val json = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string().orEmpty()
        }
        val item = JSONObject(json)
        val bbox = item.optJSONArray("bbox") ?: return null
        val bounds = parseItemBounds(bbox) ?: return null
        if (bounds.maxLon < west || bounds.minLon > east ||
            bounds.maxLat < south || bounds.minLat > north
        ) {
            return null
        }
        val fileName = item.optString("id").ifBlank { href.substringAfterLast('/') }.removeSuffix(".json")
        return tileFor(dataset, fileName, lazUrlFor(href), bounds)
    }

    private fun fetchItemBounds(href: String): GeoSpatialLibrary.GeographicBounds? {
        val request = Request.Builder().url(href).header("Accept", "application/json").get().build()
        val json = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string().orEmpty()
        }
        val bbox = JSONObject(json).optJSONArray("bbox") ?: return null
        return parseItemBounds(bbox)
    }

    internal fun parseItemHrefs(collectionJson: String, collectionUrl: String): List<String> {
        val links = JSONObject(collectionJson).optJSONArray("links") ?: return emptyList()
        return buildList {
            for (index in 0 until links.length()) {
                val link = links.optJSONObject(index) ?: continue
                if (link.optString("rel") != "item") continue
                val href = link.optString("href")
                if (href.isBlank()) continue
                add(resolveUrl(collectionUrl, href))
            }
        }
    }

    private fun tileFor(
        dataset: NoaaDataset,
        fileName: String,
        lazUrl: String,
        bounds: GeoSpatialLibrary.GeographicBounds,
    ): NysHistoricLazTileCatalog.Tile {
        val year = Regex("(20\\d{2}|19\\d{2})").find(dataset.title)?.value?.toIntOrNull()
            ?: parseGridName(fileName)?.year
        return NysHistoricLazTileCatalog.Tile(
            objectId = lazUrl.hashCode().toLong(),
            name = if (fileName.endsWith(".copc")) "$fileName.laz" else "$fileName.copc.laz",
            downloadUrl = lazUrl,
            minLongitude = bounds.minLon,
            minLatitude = bounds.minLat,
            maxLongitude = bounds.maxLon,
            maxLatitude = bounds.maxLat,
            project = dataset.title,
            source = LidarDownloadSource.NOAA_COAST,
            hostLabel = LidarDownloadSource.NOAA_COAST.shortHost,
            acquiredYear = year,
        )
    }

    private fun lazUrlFor(itemHref: String): String = lazUrlForItem(itemHref)

    private fun parseItemBounds(bbox: JSONArray): GeoSpatialLibrary.GeographicBounds? {
        if (bbox.length() < 4) return null
        val maxOffset = if (bbox.length() >= 6) 3 else 2
        val minLon = bbox.optDouble(0)
        val minLat = bbox.optDouble(1)
        val maxLon = bbox.optDouble(maxOffset)
        val maxLat = bbox.optDouble(maxOffset + 1)
        return GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon).takeIf {
            minLat.isFinite() && maxLat.isFinite() && minLon.isFinite() && maxLon.isFinite() &&
                minLat < maxLat && minLon < maxLon
        }
    }

    internal data class NoaaDataset(val id: Int, val title: String)

    companion object {
        const val DAV_FOOTPRINTS_QUERY =
            "https://www.coast.noaa.gov/arcgis/rest/services/DAV/DAV_footprints/MapServer/0/query"
        internal val DATUMS = listOf("geoid18", "geoid12b", "egm08", "mllw", "msl", "igld85")
        private const val MAX_DATASETS = 12
        private const val MAX_ITEM_FETCH = 80

        internal fun collectionUrl(datum: String, datasetId: Int): String =
            "https://noaa-nos-coastal-lidar-pds.s3.amazonaws.com/laz/$datum/$datasetId/stac/collection.json"

        internal fun parseGridName(fileName: String): NoaaCopcGrid.GridName? {
            val match = Regex("^(\\d{8})_(\\d{5})(\\d{5})(?:\\.copc)?$").find(fileName.removeSuffix(".laz"))
                ?: return null
            return NoaaCopcGrid.GridName(
                year = match.groupValues[1].take(4).toIntOrNull(),
                eastingCode = match.groupValues[2].toInt(),
                northingCode = match.groupValues[3].toInt(),
            )
        }

        internal fun resolveUrl(base: String, href: String): String {
            if (href.startsWith("https://", ignoreCase = true)) return href
            val directory = base.substringBeforeLast('/') + "/"
            return java.net.URI(directory).resolve(href).toString()
        }

        internal fun lazUrlForItem(itemHref: String): String {
            val fileName = itemHref.substringAfterLast('/').removeSuffix(".json")
            val stacDir = itemHref.substringBeforeLast('/')
            val datasetDir = stacDir.removeSuffix("/stac")
            val lazName = if (fileName.endsWith(".copc")) "$fileName.laz" else "$fileName.copc.laz"
            return "$datasetDir/$lazName"
        }
    }
}

/** Maps NOAA `eeeeeennnnn` COPC filenames onto geographic tile boxes from two samples. */
object NoaaCopcGrid {
    data class GridName(val year: Int?, val eastingCode: Int, val northingCode: Int)

    data class Estimator(
        val originEasting: Int,
        val originNorthing: Int,
        val originMinLon: Double,
        val originMinLat: Double,
        val lonPerEasting: Double,
        val latPerNorthing: Double,
        val widthLon: Double,
        val heightLat: Double,
    ) {
        fun boundsFor(fileName: String): GeoSpatialLibrary.GeographicBounds? {
            val codes = NoaaCoastalLidarCatalog.parseGridName(fileName) ?: return null
            val minLon = originMinLon + (codes.eastingCode - originEasting) * lonPerEasting
            val minLat = originMinLat + (codes.northingCode - originNorthing) * latPerNorthing
            return GeoSpatialLibrary.GeographicBounds(
                minLat = minLat,
                maxLat = minLat + heightLat,
                minLon = minLon,
                maxLon = minLon + widthLon,
            ).takeIf { it.minLat < it.maxLat && it.minLon < it.maxLon }
        }
    }

    fun fit(samples: List<Pair<String, GeoSpatialLibrary.GeographicBounds>>): Estimator {
        require(samples.size >= 2) { "Need two NOAA tiles to fit the filename grid" }
        val parsed = samples.map { (name, bounds) ->
            val codes = NoaaCoastalLidarCatalog.parseGridName(name)
                ?: error("NOAA tile name is not a grid key: $name")
            Triple(codes, bounds, name)
        }
        val origin = parsed.first()
        val other = parsed.firstOrNull {
            it.first.eastingCode != origin.first.eastingCode ||
                it.first.northingCode != origin.first.northingCode
        } ?: parsed.last()
        val de = (other.first.eastingCode - origin.first.eastingCode).toDouble()
        val dn = (other.first.northingCode - origin.first.northingCode).toDouble()
        val lonPerEasting = if (de != 0.0) {
            (other.second.minLon - origin.second.minLon) / de
        } else {
            0.0
        }
        val latPerNorthing = if (dn != 0.0) {
            (other.second.minLat - origin.second.minLat) / dn
        } else {
            0.0
        }
        return Estimator(
            originEasting = origin.first.eastingCode,
            originNorthing = origin.first.northingCode,
            originMinLon = origin.second.minLon,
            originMinLat = origin.second.minLat,
            lonPerEasting = lonPerEasting,
            latPerNorthing = latPerNorthing,
            widthLon = origin.second.maxLon - origin.second.minLon,
            heightLat = origin.second.maxLat - origin.second.minLat,
        )
    }
}
