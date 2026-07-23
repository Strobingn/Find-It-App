package com.example.geospatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.example.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TILE_SIZE = 256
private const val DEFAULT_MAX_TILES = 36
private const val DEFAULT_MAX_ZOOM = 17

/** Result of loading a basemap: the stitched tiles (if any loaded) and whether the tile server
 * actively rejected the request rather than just being unreachable — worth telling the user
 * apart, since a reject means retrying won't help without changing tile provider/User-Agent. */
data class BasemapResult(val bitmap: Bitmap?, val blockedByServer: Boolean)

private sealed interface TileFetch {
    data class Loaded(val bitmap: Bitmap) : TileFetch
    data object Blocked : TileFetch
    data object Unavailable : TileFetch
}

/**
 * Fetches OpenStreetMap raster tiles (https://tile.openstreetmap.org) covering a bounded area and
 * caches each tile to disk, so a field session that already viewed a site keeps its basemap even
 * without a connection. Only the small tile set needed for the current site is ever requested —
 * this is not a bulk region downloader — and requests are capped at 2 concurrent per the OSM tile
 * usage policy, identified with a descriptive User-Agent.
 *
 * OSM's tile servers can reject a request with an HTTP 200 and a valid `image/png` body (a
 * "blocked" graphic) rather than an error status, marked only by an `x-blocked` response header —
 * confirmed by hitting the live endpoint during development. Those responses are detected and
 * discarded rather than cached, so a rejection never gets mistaken for real map data.
 */
class OsmTileRepository(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "osm_tiles").apply { mkdirs() }
    private val client = OkHttpClient.Builder().build()
    private val fetchLimiter = Semaphore(2)

    suspend fun loadBasemap(
        bounds: GeoSpatialLibrary.GeographicBounds,
        maxTiles: Int = DEFAULT_MAX_TILES,
        maxZoom: Int = DEFAULT_MAX_ZOOM,
    ): BasemapResult = withContext(Dispatchers.IO) {
        val zoom = SlippyTileMath.chooseZoomForBounds(bounds, maxTiles, maxZoom)
        val range = SlippyTileMath.boundsToTileRange(bounds, zoom)
        val stitched = Bitmap.createBitmap(
            (range.maxX - range.minX + 1) * TILE_SIZE,
            (range.maxY - range.minY + 1) * TILE_SIZE,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(stitched)
        val tileJobs = coroutineScope {
            (range.minY..range.maxY).flatMap { tileY ->
                (range.minX..range.maxX).map { tileX ->
                    async { Triple(tileX, tileY, fetchLimiter.withPermit { loadTile(zoom, tileX, tileY) }) }
                }
            }
        }
        var anyTileLoaded = false
        var anyBlocked = false
        for (job in tileJobs) {
            val (tileX, tileY, result) = job.await()
            when (result) {
                is TileFetch.Loaded -> {
                    anyTileLoaded = true
                    canvas.drawBitmap(
                        result.bitmap,
                        ((tileX - range.minX) * TILE_SIZE).toFloat(),
                        ((tileY - range.minY) * TILE_SIZE).toFloat(),
                        null,
                    )
                }
                TileFetch.Blocked -> anyBlocked = true
                TileFetch.Unavailable -> Unit
            }
        }
        BasemapResult(bitmap = stitched.takeIf { anyTileLoaded }, blockedByServer = anyBlocked && !anyTileLoaded)
    }

    private fun loadTile(zoom: Int, x: Int, y: Int): TileFetch {
        val file = File(cacheDir, "$zoom/$x/$y.png")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return TileFetch.Loaded(it) }
        }
        return runCatching {
            val request = Request.Builder()
                .url("https://tile.openstreetmap.org/$zoom/$x/$y.png")
                .header("User-Agent", "FindIt-LidarSurveyApp/${BuildConfig.VERSION_NAME} (Android; offline field use)")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.header("x-blocked") != null) return@use TileFetch.Blocked
                if (!response.isSuccessful) return@use TileFetch.Unavailable
                val bytes = response.body?.bytes() ?: return@use TileFetch.Unavailable
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@use TileFetch.Unavailable
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                TileFetch.Loaded(bitmap)
            }
        }.getOrDefault(TileFetch.Unavailable)
    }
}
