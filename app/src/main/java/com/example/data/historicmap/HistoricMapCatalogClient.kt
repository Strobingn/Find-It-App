package com.example.data.historicmap

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovers and downloads every overlapping historic map edition from USGS TNM,
 * David Rumsey, and Library of Congress (including Sanborn). Requests are paced
 * and identify the app with a descriptive User-Agent.
 */
class HistoricMapCatalogClient(
    private val userAgent: String = USER_AGENT,
    private val pauseMillis: Long = 350L,
    private val opener: suspend (String) -> String? = { url -> getText(url, userAgent) },
    private val downloader: suspend (String, File) -> Boolean = { url, dest -> downloadTo(url, dest, userAgent) },
) {
    suspend fun discover(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
    ): List<HistoricMapRecord> {
        val bbox = listOf(minLongitude, minLatitude, maxLongitude, maxLatitude)
        return buildList {
            addAll(usgsHistorical(bbox))
            addAll(davidRumsey(bbox))
            addAll(libraryOfCongress(bbox, sanbornOnly = false))
            addAll(libraryOfCongress(bbox, sanbornOnly = true))
        }.distinctBy { it.footprintKey }
    }

    suspend fun downloadMissing(records: List<HistoricMapRecord>, archive: HistoricMapArchive): List<HistoricMapRecord> {
        val saved = ArrayList<HistoricMapRecord>()
        for (record in records) {
            if (archive.contains(record)) {
                saved += archive.loadIndex().records.first { it.footprintKey == record.footprintKey }
                continue
            }
            val extension = extensionFor(record.remoteUrl)
            val dest = archive.destinationFor(record, extension)
            val ok = runCatching { downloader(record.remoteUrl, dest) }.getOrDefault(false)
            if (!ok || dest.length() <= 0L) continue
            val stored = record.copy(
                localFile = dest,
                downloadedAtMillis = System.currentTimeMillis(),
            )
            archive.upsert(stored)
            saved += stored
            delay(pauseMillis)
        }
        return saved
    }

    internal suspend fun usgsHistorical(bbox: List<Double>): List<HistoricMapRecord> {
        val records = ArrayList<HistoricMapRecord>()
        var offset = 0
        repeat(MAX_PAGES) { page ->
            if (page > 0) delay(pauseMillis)
            val url = "https://tnmaccess.nationalmap.gov/api/v1/products?" +
                "datasets=" + enc("Historical Topographic Maps") +
                "&bbox=${bbox.joinToString(",")}" +
                "&prodFormats=GeoTIFF&max=200&offset=$offset"
            val body = opener(url) ?: return records
            val items = JSONObject(body).optJSONArray("items") ?: return records
            val pageRecords = parseUsgs(items, bbox)
            records += pageRecords
            if (pageRecords.size < 200) return records
            offset += pageRecords.size
        }
        return records
    }

    internal fun parseUsgs(items: JSONArray, bbox: List<Double>): List<HistoricMapRecord> =
        buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val download = item.optString("downloadURL").ifBlank { item.optString("url") }
                if (download.isBlank()) continue
                val title = item.optString("title").ifBlank { item.optString("sourceId") }
                add(
                    HistoricMapRecord(
                        id = "usgs-" + item.optString("sourceId", title.hashCode().toString()),
                        source = HistoricMapSource.USGS_HISTORICAL,
                        title = title,
                        editionYear = yearOf(item.optString("publicationDate").ifBlank { title }),
                        attribution = "USGS The National Map — Historical Topographic Maps",
                        remoteUrl = download,
                        localFile = null,
                        minLatitude = item.optDouble("minY", bbox[1]),
                        maxLatitude = item.optDouble("maxY", bbox[3]),
                        minLongitude = item.optDouble("minX", bbox[0]),
                        maxLongitude = item.optDouble("maxX", bbox[2]),
                        georeferenced = true,
                    ),
                )
            }
        }

    internal suspend fun davidRumsey(bbox: List<Double>): List<HistoricMapRecord> {
        val q = "georeferenced AND bbox:${bbox[0]},${bbox[1]},${bbox[2]},${bbox[3]}"
        val records = ArrayList<HistoricMapRecord>()
        var offset = 0
        repeat(MAX_PAGES) { page ->
            if (page > 0) delay(pauseMillis)
            val url = "https://www.davidrumsey.com/luna/servlet/as/search?q=${enc(q)}&os=$offset&bs=50&format=json"
            val body = opener(url) ?: return records
            val pageRecords = parseRumsey(body, bbox)
            records += pageRecords
            if (pageRecords.size < 50) return records
            offset += pageRecords.size
        }
        return records
    }

    internal fun parseRumsey(body: String, bbox: List<Double>): List<HistoricMapRecord> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("results")
            ?: root.optJSONObject("results")?.optJSONArray("item")
            ?: JSONArray()
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("iid") }
                val media = item.optJSONObject("media") ?: JSONObject()
                val imageUrl = media.optString("imageUrl").ifBlank {
                    item.optString("url").ifBlank { item.optString("iiif") }
                }
                if (id.isBlank() || imageUrl.isBlank()) continue
                add(
                    HistoricMapRecord(
                        id = "rumsey-$id",
                        source = HistoricMapSource.DAVID_RUMSEY,
                        title = item.optString("title").ifBlank { "David Rumsey $id" },
                        editionYear = yearOf(item.optString("date").ifBlank { item.optString("year") }),
                        attribution = "David Rumsey Map Collection",
                        remoteUrl = imageUrl,
                        localFile = null,
                        minLatitude = bbox[1],
                        maxLatitude = bbox[3],
                        minLongitude = bbox[0],
                        maxLongitude = bbox[2],
                        georeferenced = true,
                    ),
                )
            }
        }
    }

    internal suspend fun libraryOfCongress(bbox: List<Double>, sanbornOnly: Boolean): List<HistoricMapRecord> {
        val centerLat = (bbox[1] + bbox[3]) / 2.0
        val centerLon = (bbox[0] + bbox[2]) / 2.0
        val query = if (sanbornOnly) {
            "sanborn fire insurance $centerLat,$centerLon"
        } else {
            "historic map $centerLat,$centerLon"
        }
        val base = if (sanbornOnly) {
            "https://www.loc.gov/collections/sanborn-maps/?fo=json&c=50&fa=online-format:image&q=${enc(query)}"
        } else {
            "https://www.loc.gov/maps/?fo=json&c=50&fa=online-format:image&q=${enc(query)}"
        }
        val records = ArrayList<HistoricMapRecord>()
        repeat(MAX_PAGES) { page ->
            if (page > 0) delay(pauseMillis)
            val loc = "$base&sp=${page + 1}"
            val body = opener(loc) ?: return records
            val pageRecords = parseLoc(body, bbox, sanbornOnly)
            records += pageRecords
            if (pageRecords.size < 50) return records
        }
        return records
    }

    internal fun parseLoc(body: String, bbox: List<Double>, sanbornOnly: Boolean): List<HistoricMapRecord> {
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        val source = if (sanbornOnly) HistoricMapSource.SANBORN else HistoricMapSource.LIBRARY_OF_CONGRESS
        return buildList {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optString("url") }
                val image = item.optString("image_url").ifBlank {
                    item.optJSONArray("image_url")?.optString(0).orEmpty()
                }.ifBlank {
                    item.optString("url")
                }
                if (id.isBlank() || image.isBlank()) continue
                val latlong = item.optString("latlong").ifBlank { item.optString("coordinates") }
                if (latlong.isNotBlank() && !overlapsBbox(latlong, bbox)) continue
                add(
                    HistoricMapRecord(
                        id = "loc-${id.hashCode()}",
                        source = source,
                        title = item.optString("title").ifBlank { id },
                        editionYear = yearOf(item.optString("date")),
                        attribution = "Library of Congress",
                        remoteUrl = image,
                        localFile = null,
                        minLatitude = bbox[1],
                        maxLatitude = bbox[3],
                        minLongitude = bbox[0],
                        maxLongitude = bbox[2],
                        georeferenced = false,
                    ),
                )
            }
        }
    }

    private fun overlapsBbox(latlong: String, bbox: List<Double>): Boolean {
        val parts = latlong.split(',', ' ', ';').mapNotNull { it.toDoubleOrNull() }
        if (parts.size < 2) return true
        val lat = parts[0]
        val lon = parts[1]
        return lat in bbox[1]..bbox[3] && lon in bbox[0]..bbox[2]
    }

    private fun yearOf(raw: String): Int? =
        Regex("(1[6-9]\\d{2}|20\\d{2})").find(raw)?.value?.toIntOrNull()

    private fun extensionFor(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".tif") || path.endsWith(".tiff") -> "tif"
            path.endsWith(".jp2") -> "jp2"
            path.endsWith(".png") -> "png"
            else -> "jpg"
        }
    }

    companion object {
        private const val MAX_PAGES = 20
        const val USER_AGENT =
            "FindIt-LidarSurveyApp/2.0 (personal historic LiDAR research; Android; polite catalog client)"

        private fun enc(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private fun getText(url: String, userAgent: String): String? {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 20_000
                readTimeout = 40_000
            }
            return try {
                if (connection.responseCode !in 200..299) null
                else connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

        private fun downloadTo(url: String, dest: File, userAgent: String): Boolean {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                connectTimeout = 30_000
                readTimeout = 120_000
            }
            return try {
                if (connection.responseCode !in 200..299) return false
                dest.outputStream().use { output ->
                    connection.inputStream.use { input -> input.copyTo(output) }
                }
                dest.length() > 0L
            } finally {
                connection.disconnect()
            }
        }
    }
}
