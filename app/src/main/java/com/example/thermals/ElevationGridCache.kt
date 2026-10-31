package com.example.thermals

import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Elevation grids for a search square, kept in memory and on disk.
 * A repeat Find (or another read) of the same spot does not hit the network.
 * The key is the center rounded to 0.0001° (~11 m), so a few meters of GPS jitter still hits.
 */
internal class ElevationGridCache(
    private val directory: File,
    private val memory: MutableMap<String, ElevationFetch>,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    fun get(lat: Double, lon: Double): ElevationFetch? {
        val key = key(lat, lon)
        touch(key)?.let { return it }
        val file = File(directory, "$key.json")
        if (!file.isFile) return null
        val fetch = runCatching { decode(file.readText()) }.getOrElse {
            file.delete()
            return null
        }
        remember(key, fetch)
        return fetch
    }

    fun put(lat: Double, lon: Double, fetch: ElevationFetch) {
        val key = key(lat, lon)
        remember(key, fetch)
        runCatching {
            directory.mkdirs()
            val dest = File(directory, "$key.json")
            val tmp = File(directory, "$key.json.tmp")
            tmp.writeText(encode(fetch))
            if (!tmp.renameTo(dest)) {
                dest.writeText(encode(fetch))
                tmp.delete()
            }
        }
    }

    private fun touch(key: String): ElevationFetch? = synchronized(memory) {
        val value = memory.remove(key) ?: return null
        memory[key] = value
        value
    }

    private fun remember(key: String, fetch: ElevationFetch) {
        synchronized(memory) {
            memory.remove(key)
            memory[key] = fetch
            while (memory.size > maxEntries) {
                val eldest = memory.keys.firstOrNull() ?: break
                memory.remove(eldest)
            }
        }
    }

    companion object {
        const val DIR_NAME = "stand-elevation-v1"
        private const val MAX_ENTRIES = 8

        /** One memory map for the process, so a new service still hits a grid fetched earlier. */
        private val processMemory = LinkedHashMap<String, ElevationFetch>()

        fun inDirectory(directory: File) = ElevationGridCache(directory, processMemory)

        fun key(lat: Double, lon: Double): String {
            val qLat = Math.round(lat * 10_000.0) / 10_000.0
            val qLon = Math.round(lon * 10_000.0) / 10_000.0
            return String.format(Locale.US, "%.4f_%.4f", qLat, qLon)
        }

        private fun encode(fetch: ElevationFetch): String {
            val grid = fetch.grid
            val meters = JSONArray()
            val covered = JSONArray()
            for (i in grid.meters.indices) {
                val usable = grid.covered[i] && grid.meters[i].isFinite()
                covered.put(if (usable) 1 else 0)
                if (usable) meters.put(grid.meters[i]) else meters.put(JSONObject.NULL)
            }
            return JSONObject()
                .put("v", 1)
                .put("lat", grid.centerLat)
                .put("lon", grid.centerLon)
                .put("size", grid.size)
                .put("spacing", grid.spacingMeters)
                .put("source", fetch.sourceLabel)
                .put("meters", meters)
                .put("covered", covered)
                .toString()
        }

        private fun decode(text: String): ElevationFetch {
            val json = JSONObject(text)
            val size = json.getInt("size")
            val metersJson = json.getJSONArray("meters")
            val coveredJson = json.getJSONArray("covered")
            require(metersJson.length() == size * size && coveredJson.length() == size * size)
            val meters = DoubleArray(size * size)
            val covered = BooleanArray(size * size)
            for (i in meters.indices) {
                val on = coveredJson.optInt(i, 0) == 1 && !metersJson.isNull(i)
                covered[i] = on
                meters[i] = if (on) metersJson.getDouble(i) else Double.NaN
            }
            val grid = ElevationGrid(
                json.getDouble("lat"),
                json.getDouble("lon"),
                size,
                json.getDouble("spacing"),
                meters,
                covered,
            )
            return ElevationFetch(grid, json.optString("source", "Elevation model (90 m)"))
        }
    }
}
