package com.example.data

import android.content.Context
import com.example.geospatial.GeoSpatialLibrary
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable state for the Public LiDAR picker.
 *
 * The picker is only composed while the Import tab is selected, so remember-state alone loses the
 * result list whenever the user opens the map. Keeping the small search/result manifest in app
 * preferences lets the next composition restore the same files and checked selections without
 * downloading or querying again.
 */
data class LazPickerSession(
    val latitude: String = "",
    val longitude: String = "",
    val west: String = "",
    val south: String = "",
    val east: String = "",
    val north: String = "",
    val areaSelectionMode: String = "RECTANGLE",
    val radiusLatitude: String = "",
    val radiusLongitude: String = "",
    val radiusMiles: String = "",
    val polygonVertices: String = "",
    val selectedRegion: String? = null,
    val mosaicProjectName: String = "",
    val tiles: List<NysHistoricLazTileCatalog.Tile> = emptyList(),
    val copcAssets: List<CopcAsset> = emptyList(),
    val selectedUrls: Set<String> = emptySet(),
    val selectedAreaDescription: String? = null,
    val lastSearchBounds: GeoSpatialLibrary.GeographicBounds? = null,
)

object LazPickerSessionStore {
    private const val PREFERENCES = "public_lidar_picker"
    private const val SESSION_KEY = "session_v1"

    fun load(context: Context): LazPickerSession {
        val raw = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(SESSION_KEY, null)
            ?: return LazPickerSession()
        return runCatching { decode(JSONObject(raw)) }.getOrDefault(LazPickerSession())
    }

    fun save(context: Context, session: LazPickerSession) {
        runCatching {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(SESSION_KEY, encode(session).toString())
                .apply()
        }
    }

    private fun encode(session: LazPickerSession): JSONObject = JSONObject().apply {
        put("latitude", session.latitude)
        put("longitude", session.longitude)
        put("west", session.west)
        put("south", session.south)
        put("east", session.east)
        put("north", session.north)
        put("areaSelectionMode", session.areaSelectionMode)
        put("radiusLatitude", session.radiusLatitude)
        put("radiusLongitude", session.radiusLongitude)
        put("radiusMiles", session.radiusMiles)
        put("polygonVertices", session.polygonVertices)
        putNullable("selectedRegion", session.selectedRegion)
        put("mosaicProjectName", session.mosaicProjectName)
        put("tiles", JSONArray().apply { session.tiles.forEach { put(encode(it)) } })
        put("copcAssets", JSONArray().apply { session.copcAssets.forEach { put(encode(it)) } })
        put("selectedUrls", JSONArray().apply { session.selectedUrls.forEach(::put) })
        putNullable("selectedAreaDescription", session.selectedAreaDescription)
        session.lastSearchBounds?.let { bounds ->
            put("lastSearchBounds", encode(bounds))
        }
    }

    private fun decode(json: JSONObject): LazPickerSession = LazPickerSession(
        latitude = json.optString("latitude"),
        longitude = json.optString("longitude"),
        west = json.optString("west"),
        south = json.optString("south"),
        east = json.optString("east"),
        north = json.optString("north"),
        areaSelectionMode = json.optString("areaSelectionMode", "RECTANGLE"),
        radiusLatitude = json.optString("radiusLatitude"),
        radiusLongitude = json.optString("radiusLongitude"),
        radiusMiles = json.optString("radiusMiles"),
        polygonVertices = json.optString("polygonVertices"),
        selectedRegion = json.optString("selectedRegion").takeIf(String::isNotBlank),
        mosaicProjectName = json.optString("mosaicProjectName"),
        tiles = json.optJSONArray("tiles")?.let(::decodeTiles).orEmpty(),
        copcAssets = json.optJSONArray("copcAssets")?.let(::decodeCopcAssets).orEmpty(),
        selectedUrls = json.optJSONArray("selectedUrls")?.let { values ->
            buildSet { for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add) }
        }.orEmpty(),
        selectedAreaDescription = json.optString("selectedAreaDescription").takeIf(String::isNotBlank),
        lastSearchBounds = json.optJSONObject("lastSearchBounds")?.let(::decodeBounds),
    )

    private fun encode(tile: NysHistoricLazTileCatalog.Tile): JSONObject = JSONObject().apply {
        put("objectId", tile.objectId)
        put("name", tile.name)
        put("downloadUrl", tile.downloadUrl)
        putNullable("minLongitude", tile.minLongitude)
        putNullable("minLatitude", tile.minLatitude)
        putNullable("maxLongitude", tile.maxLongitude)
        putNullable("maxLatitude", tile.maxLatitude)
        put("project", tile.project)
    }

    private fun decodeTiles(values: JSONArray): List<NysHistoricLazTileCatalog.Tile> = buildList {
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val name = item.optString("name")
            val url = item.optString("downloadUrl")
            if (name.isBlank() || url.isBlank()) continue
            add(
                NysHistoricLazTileCatalog.Tile(
                    objectId = item.optLong("objectId"),
                    name = name,
                    downloadUrl = url,
                    minLongitude = item.optDoubleOrNull("minLongitude"),
                    minLatitude = item.optDoubleOrNull("minLatitude"),
                    maxLongitude = item.optDoubleOrNull("maxLongitude"),
                    maxLatitude = item.optDoubleOrNull("maxLatitude"),
                    project = item.optString("project"),
                ),
            )
        }
    }

    private fun encode(asset: CopcAsset): JSONObject = JSONObject().apply {
        put("id", asset.id)
        put("title", asset.title)
        put("href", asset.href)
        asset.bounds?.let { put("bounds", encode(it)) }
    }

    private fun decodeCopcAssets(values: JSONArray): List<CopcAsset> = buildList {
        for (index in 0 until values.length()) {
            val item = values.optJSONObject(index) ?: continue
            val href = item.optString("href")
            if (href.isBlank()) continue
            add(
                CopcAsset(
                    id = item.optString("id").ifBlank { href },
                    title = item.optString("title").ifBlank { href.substringAfterLast('/') },
                    href = href,
                    bounds = item.optJSONObject("bounds")?.let(::decodeBounds),
                ),
            )
        }
    }

    private fun encode(bounds: GeoSpatialLibrary.GeographicBounds): JSONObject = JSONObject().apply {
        put("minLat", bounds.minLat)
        put("maxLat", bounds.maxLat)
        put("minLon", bounds.minLon)
        put("maxLon", bounds.maxLon)
    }

    private fun decodeBounds(json: JSONObject): GeoSpatialLibrary.GeographicBounds? {
        val minLat = json.optDoubleOrNull("minLat") ?: return null
        val maxLat = json.optDoubleOrNull("maxLat") ?: return null
        val minLon = json.optDoubleOrNull("minLon") ?: return null
        val maxLon = json.optDoubleOrNull("maxLon") ?: return null
        return GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon)
            .takeIf { minLat < maxLat && minLon < maxLon }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf(Double::isFinite)
}
