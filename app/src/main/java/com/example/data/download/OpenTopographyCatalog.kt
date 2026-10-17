package com.example.data.download

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * OpenTopography `/API/otCatalog` lookup: which USGS 3DEP point-cloud projects cover a box.
 *
 * OpenTopography federates USGS 3DEP; its `USGS_3DEP_ID` values are exactly the EPT resource
 * names in the public `usgs-lidar-public` bucket, so the points are then fetched from that bucket
 * by [EptTileBuilder] (OpenTopography offers no direct point-cloud download API). The catalog
 * answers without a key; [apiKey] is sent only when configured.
 */
class OpenTopographyCatalog(
    private val apiKey: String = "",
    private val get: (String) -> String? = ::httpGetText,
) {
    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > 64
    }

    /** 3DEP EPT resource names covering (west, south, east, north), catalog order; empty on failure. */
    fun usgs3depResources(box: DoubleArray): List<String> {
        val url = catalogUrl(box, apiKey)
        synchronized(cache) { cache[url]?.let { return it } }
        val body = runCatching { get(url) }.getOrNull() ?: return emptyList()
        val ids = parseUsgs3depIds(body)
        synchronized(cache) { cache[url] = ids }
        return ids
    }

    companion object {
        const val ENDPOINT = "https://portal.opentopography.org/API/otCatalog"
        private val ID = Regex(
            "\"propertyID\"\\s*:\\s*\"USGS_3DEP_ID\"\\s*,\\s*\"value\"\\s*:\\s*\"([A-Za-z0-9_\\-]+)\"",
        )

        fun catalogUrl(box: DoubleArray, apiKey: String = ""): String {
            fun f(v: Double) = String.format(Locale.US, "%.5f", v)
            return "$ENDPOINT?productFormat=PointCloud&minx=${f(box[0])}&miny=${f(box[1])}" +
                "&maxx=${f(box[2])}&maxy=${f(box[3])}&include_federated=true&outputFormat=json" +
                (apiKey.trim().takeIf { it.isNotEmpty() }?.let { "&API_Key=$it" } ?: "")
        }

        fun parseUsgs3depIds(json: String): List<String> =
            ID.findAll(json).map { it.groupValues[1] }.distinct().toList()

        fun httpGetText(url: String): String? {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            return try {
                if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes()) }
            } catch (e: IOException) {
                null
            } finally {
                c.disconnect()
            }
        }
    }
}
