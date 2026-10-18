package com.example.geospatial

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import com.example.BuildConfig
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val TILE_SIZE = 256
private const val DEFAULT_MAX_TILES = 36
private const val DEFAULT_MAX_ZOOM = 16
private const val OFFLINE_MAX_TILES = 128
private const val ESTIMATED_TILE_BYTES = 24L * 1024L

/** Result of loading a basemap: the stitched tiles (if any loaded) and whether the tile server
 * actively rejected the request rather than just being unreachable — worth telling the user
 * apart, since a reject means retrying won't help without changing tile provider/User-Agent. */
data class BasemapResult(
    val bitmap: Bitmap?,
    val blockedByServer: Boolean,
    val loadedTiles: Int = 0,
    val expectedTiles: Int = 0,
)

data class BasemapPlan(
    val bounds: GeoSpatialLibrary.GeographicBounds,
    val zoom: Int,
    val range: SlippyTileMath.TileRange,
    val cachedTiles: Int,
    val cachedBytes: Long,
) {
    val tileCount: Int get() = range.tileCount
    val missingTiles: Int get() = (tileCount - cachedTiles).coerceAtLeast(0)
    val estimatedDownloadBytes: Long get() = missingTiles * ESTIMATED_TILE_BYTES
}

data class BasemapDownloadProgress(
    val completedTiles: Int,
    val totalTiles: Int,
    val downloadedBytes: Long,
)

data class BasemapDownloadResult(
    val completedTiles: Int,
    val failedTiles: Int,
    val downloadedBytes: Long,
    val storedBytes: Long,
    val blockedByServer: Boolean,
)

private sealed interface TileFetch {
    data class Loaded(val bitmap: Bitmap, val byteCount: Long, val downloaded: Boolean) : TileFetch
    data object Blocked : TileFetch
    data object Unavailable : TileFetch
}

/**
 * Fetches the USGS National Map's public USGS Topo tile-cache service for a bounded project area.
 * The service advertises both cached tiles and an ExportTiles operation. Files are stored in
 * app-private durable storage so explicitly saved field regions remain available offline.
 */
class BasemapTileRepository(context: Context) {
    // Offline regions live in filesDir, not cacheDir, so Android cannot evict them behind the
    // user's back.
    private val tileDir = File(context.applicationContext.filesDir, "offline_usgs_topo_tiles").apply { mkdirs() }
    private val client = OkHttpClient.Builder().build()
    private val fetchLimiter = Semaphore(2)

    suspend fun loadBasemap(
        bounds: GeoSpatialLibrary.GeographicBounds,
        maxTiles: Int = DEFAULT_MAX_TILES,
        maxZoom: Int = DEFAULT_MAX_ZOOM,
        fixedZoom: Int? = null,
        allowNetwork: Boolean = true,
    ): BasemapResult = withContext(Dispatchers.IO) {
        val zoom = fixedZoom?.coerceIn(1, maxZoom)
            ?: SlippyTileMath.chooseZoomForBounds(bounds, maxTiles, maxZoom)
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
                    async {
                        Triple(
                            tileX,
                            tileY,
                            fetchLimiter.withPermit { loadTile(zoom, tileX, tileY, allowNetwork) },
                        )
                    }
                }
            }
        }
        var anyTileLoaded = false
        var anyBlocked = false
        var loadedTiles = 0
        for (job in tileJobs) {
            val (tileX, tileY, result) = job.await()
            when (result) {
                is TileFetch.Loaded -> {
                    anyTileLoaded = true
                    loadedTiles++
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
        // boundsToTileRange snaps outward to whole tiles, so `stitched` covers more area than the
        // requested bounds and is offset from it — crop to the exact bounds so the caller can draw
        // this 1:1 over the terrain's bounding rect without the two disagreeing on geography.
        val croppedBitmap = stitched.takeIf { anyTileLoaded }?.let { cropToBounds(it, bounds, range, zoom) }
        BasemapResult(
            bitmap = croppedBitmap,
            blockedByServer = anyBlocked && !anyTileLoaded,
            loadedTiles = loadedTiles,
            expectedTiles = range.tileCount,
        )
    }

    fun planOfflineRegion(
        bounds: GeoSpatialLibrary.GeographicBounds,
        maxTiles: Int = OFFLINE_MAX_TILES,
        maxZoom: Int = DEFAULT_MAX_ZOOM,
        fixedZoom: Int? = null,
    ): BasemapPlan {
        val zoom = fixedZoom?.coerceIn(1, maxZoom)
            ?: SlippyTileMath.chooseZoomForBounds(bounds, maxTiles, maxZoom)
        val range = SlippyTileMath.boundsToTileRange(bounds, zoom)
        var cachedTiles = 0
        var cachedBytes = 0L
        for (y in range.minY..range.maxY) {
            for (x in range.minX..range.maxX) {
                existingTileFile(zoom, x, y)?.let {
                    cachedTiles++
                    cachedBytes += it.length()
                }
            }
        }
        return BasemapPlan(bounds, zoom, range, cachedTiles, cachedBytes)
    }

    suspend fun downloadOfflineRegion(
        plan: BasemapPlan,
        onProgress: (BasemapDownloadProgress) -> Unit,
    ): BasemapDownloadResult = withContext(Dispatchers.IO) {
        var completed = 0
        var failed = 0
        var downloadedBytes = 0L
        var blocked = false
        onProgress(BasemapDownloadProgress(0, plan.tileCount, 0L))
        for (y in plan.range.minY..plan.range.maxY) {
            for (x in plan.range.minX..plan.range.maxX) {
                currentCoroutineContext().ensureActive()
                when (val result = fetchLimiter.withPermit { loadTile(plan.zoom, x, y, allowNetwork = true) }) {
                    is TileFetch.Loaded -> {
                        completed++
                        if (result.downloaded) downloadedBytes += result.byteCount
                        result.bitmap.recycle()
                    }
                    TileFetch.Blocked -> {
                        failed++
                        blocked = true
                    }
                    TileFetch.Unavailable -> failed++
                }
                onProgress(BasemapDownloadProgress(completed + failed, plan.tileCount, downloadedBytes))
            }
        }
        BasemapDownloadResult(
            completedTiles = completed,
            failedTiles = failed,
            downloadedBytes = downloadedBytes,
            storedBytes = storedBytes(plan.range),
            blockedByServer = blocked,
        )
    }

    fun deleteTilesUsedOnlyBy(
        deletedRange: SlippyTileMath.TileRange,
        retainedRanges: List<SlippyTileMath.TileRange>,
    ) {
        val relevantRanges = retainedRanges.filter {
            it.zoom == deletedRange.zoom &&
            it.minX <= deletedRange.maxX && it.maxX >= deletedRange.minX &&
            it.minY <= deletedRange.maxY && it.maxY >= deletedRange.minY
        }

        for (y in deletedRange.minY..deletedRange.maxY) {
            val activeRanges = relevantRanges.filter { y in it.minY..it.maxY }
            if (activeRanges.isEmpty()) {
                for (x in deletedRange.minX..deletedRange.maxX) {
                    tileFile(deletedRange.zoom, x, y).delete()
                }
            } else {
                val intervals = activeRanges.map { maxOf(it.minX, deletedRange.minX) to minOf(it.maxX, deletedRange.maxX) }
                    .sortedBy { it.first }

                var currentX = deletedRange.minX
                for (interval in intervals) {
                    while (currentX < interval.first) {
                        tileFile(deletedRange.zoom, currentX, y).delete()
                        currentX++
                    }
                    if (currentX <= interval.second) {
                        currentX = interval.second + 1
                    }
                }
                while (currentX <= deletedRange.maxX) {
                    tileFile(deletedRange.zoom, currentX, y).delete()
                    currentX++
                }
            }
        }
        tileDir.walkBottomUp().filter { it.isDirectory && it != tileDir }.forEach { directory ->
            if (directory.listFiles().isNullOrEmpty()) directory.delete()
        }
    }

    private fun storedBytes(range: SlippyTileMath.TileRange): Long {
        var bytes = 0L
        for (y in range.minY..range.maxY) {
            for (x in range.minX..range.maxX) {
                bytes += existingTileFile(range.zoom, x, y)?.length() ?: 0L
            }
        }
        return bytes
    }

    private fun cropToBounds(
        stitched: Bitmap,
        bounds: GeoSpatialLibrary.GeographicBounds,
        range: SlippyTileMath.TileRange,
        zoom: Int,
    ): Bitmap {
        val xMinFrac = SlippyTileMath.lonToTileXFraction(bounds.minLon, zoom) - range.minX
        val xMaxFrac = SlippyTileMath.lonToTileXFraction(bounds.maxLon, zoom) - range.minX
        // Northern (max) latitude maps to the smaller tile Y, same convention as boundsToTileRange.
        val yMinFrac = SlippyTileMath.latToTileYFraction(bounds.maxLat, zoom) - range.minY
        val yMaxFrac = SlippyTileMath.latToTileYFraction(bounds.minLat, zoom) - range.minY

        val left = (xMinFrac * TILE_SIZE).toInt().coerceIn(0, stitched.width - 1)
        val top = (yMinFrac * TILE_SIZE).toInt().coerceIn(0, stitched.height - 1)
        val right = (xMaxFrac * TILE_SIZE).toInt().coerceIn(left + 1, stitched.width)
        val bottom = (yMaxFrac * TILE_SIZE).toInt().coerceIn(top + 1, stitched.height)
        return Bitmap.createBitmap(stitched, left, top, right - left, bottom - top)
    }

    private fun loadTile(zoom: Int, x: Int, y: Int, allowNetwork: Boolean): TileFetch {
        existingTileFile(zoom, x, y)?.let { file ->
            BitmapFactory.decodeFile(file.absolutePath)?.let {
                return TileFetch.Loaded(it, file.length(), downloaded = false)
            }
        }
        if (!allowNetwork) return TileFetch.Unavailable
        val file = tileFile(zoom, x, y)
        return runCatching {
            val request = Request.Builder()
                .url(
                    "https://basemap.nationalmap.gov/arcgis/rest/services/" +
                        "USGSTopo/MapServer/tile/$zoom/$y/$x",
                )
                .header("User-Agent", "FindIt-LidarSurveyApp/${BuildConfig.VERSION_NAME} (Android; offline field use)")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.header("x-blocked") != null || response.code in setOf(401, 403, 429)) {
                    return@use TileFetch.Blocked
                }
                if (!response.isSuccessful) return@use TileFetch.Unavailable
                val bytes = response.body?.bytes() ?: return@use TileFetch.Unavailable
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: return@use TileFetch.Unavailable
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
                TileFetch.Loaded(bitmap, bytes.size.toLong(), downloaded = true)
            }
        }.getOrDefault(TileFetch.Unavailable)
    }

    private fun tileFile(zoom: Int, x: Int, y: Int): File = File(tileDir, "$zoom/$x/$y.tile")

    private fun existingTileFile(zoom: Int, x: Int, y: Int): File? {
        val persistent = tileFile(zoom, x, y)
        if (persistent.isFile && persistent.length() > 0L) return persistent
        return null
    }
}
