package com.example.data.historicmap

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Permanent on-device store for automatically downloaded historic maps.
 * Lives under [Context.getFilesDir], never the cache directory, so scanned tiles stay offline.
 */
class HistoricMapArchive(
    context: Context,
    directory: File? = null,
) {
    private val root = directory ?: File(context.applicationContext.filesDir, "historic-map-archive")
    private val indexFile = File(root, "index.json")

    init {
        root.mkdirs()
    }

    fun loadIndex(): HistoricMapIndex {
        if (!indexFile.isFile) return HistoricMapIndex(emptyList())
        return runCatching {
            val array = JSONObject(indexFile.readText()).optJSONArray("records") ?: JSONArray()
            val records = buildList {
                for (i in 0 until array.length()) {
                    add(recordFromJson(array.getJSONObject(i)) ?: continue)
                }
            }
            HistoricMapIndex(records)
        }.getOrDefault(HistoricMapIndex(emptyList()))
    }

    fun saveIndex(index: HistoricMapIndex) {
        root.mkdirs()
        val array = JSONArray()
        index.records.forEach { array.put(recordToJson(it)) }
        indexFile.writeText(JSONObject().put("records", array).toString())
    }

    fun contains(record: HistoricMapRecord): Boolean =
        loadIndex().records.any { it.footprintKey == record.footprintKey && it.localFile?.isFile == true }

    fun destinationFor(record: HistoricMapRecord, extension: String): File {
        root.mkdirs()
        val safe = record.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(root, "$safe.$extension")
    }

    fun upsert(record: HistoricMapRecord) {
        val current = loadIndex().records.toMutableList()
        val existing = current.indexOfFirst { it.footprintKey == record.footprintKey || it.id == record.id }
        if (existing >= 0) current[existing] = record else current += record
        saveIndex(HistoricMapIndex(current))
    }

    fun filesDir(): File = root

    private fun recordToJson(record: HistoricMapRecord) = JSONObject()
        .put("id", record.id)
        .put("source", record.source.name)
        .put("title", record.title)
        .put("editionYear", record.editionYear)
        .put("attribution", record.attribution)
        .put("remoteUrl", record.remoteUrl)
        .put("localFile", record.localFile?.absolutePath)
        .put("minLatitude", record.minLatitude)
        .put("maxLatitude", record.maxLatitude)
        .put("minLongitude", record.minLongitude)
        .put("maxLongitude", record.maxLongitude)
        .put("georeferenced", record.georeferenced)
        .put("residualRmseMeters", record.residualRmseMeters)
        .put("matchRadiusMeters", record.matchRadiusMeters)
        .put("downloadedAtMillis", record.downloadedAtMillis)

    private fun recordFromJson(json: JSONObject): HistoricMapRecord? {
        val source = HistoricMapSource.entries.firstOrNull { it.name == json.optString("source") }
            ?: return null
        val path = json.optString("localFile").takeIf { it.isNotBlank() }
        return HistoricMapRecord(
            id = json.optString("id"),
            source = source,
            title = json.optString("title"),
            editionYear = json.optInt("editionYear", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            attribution = json.optString("attribution"),
            remoteUrl = json.optString("remoteUrl"),
            localFile = path?.let(::File),
            minLatitude = json.optDouble("minLatitude"),
            maxLatitude = json.optDouble("maxLatitude"),
            minLongitude = json.optDouble("minLongitude"),
            maxLongitude = json.optDouble("maxLongitude"),
            georeferenced = json.optBoolean("georeferenced"),
            residualRmseMeters = json.optDouble("residualRmseMeters").takeIf { json.has("residualRmseMeters") },
            matchRadiusMeters = json.optDouble("matchRadiusMeters", 50.0),
            downloadedAtMillis = json.optLong("downloadedAtMillis"),
        )
    }
}
