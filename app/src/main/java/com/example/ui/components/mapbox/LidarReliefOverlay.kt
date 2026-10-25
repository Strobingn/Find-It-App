package com.example.ui.components.mapbox

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.example.data.relief.ElevationChunk
import com.example.data.relief.ReliefRenderer
import com.example.data.relief.ReliefSettings
import com.example.data.relief.ThreeDepElevationClient
import com.example.data.relief.TileKey
import com.mapbox.maps.CanonicalTileID
import com.mapbox.maps.CustomRasterSourceClient
import com.mapbox.maps.CustomRasterSourceTileData
import com.mapbox.maps.CustomRasterSourceTileStatus
import com.mapbox.maps.MapboxDelicateApi
import com.mapbox.maps.MapboxExperimental
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.sources.CustomRasterSource
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.customRasterSource
import com.mapbox.maps.toMapboxImage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Live LiDAR relief over the Mapbox map: as tiles come into view it fetches USGS 3DEP elevation
 * blocks, renders them with [ReliefRenderer] in the current look, and hands Mapbox the tiles.
 * All state lives on the main thread; fetching and rendering run on background dispatchers.
 */
@OptIn(MapboxExperimental::class, MapboxDelicateApi::class, ExperimentalCoroutinesApi::class)
class LidarReliefOverlay(context: Context, private val scope: CoroutineScope) {
    private val client = ThreeDepElevationClient(File(context.applicationContext.cacheDir, "3dep-elevation"))
    private val chunks = LruCache<TileKey, ElevationChunk>(6)
    private val rendered = LruCache<TileKey, Bitmap>(10)
    private val empty = HashSet<TileKey>()
    private val inflight = HashMap<TileKey, Job>()
    private val required = LinkedHashSet<CanonicalTileID>()
    private val io = Dispatchers.IO.limitedParallelism(3)
    private val cpu = Dispatchers.Default.limitedParallelism(2)
    private var source: CustomRasterSource? = null
    private var style: Style? = null
    private var version = 0

    var settings: ReliefSettings = ReliefSettings()
        private set

    private val _loading = MutableStateFlow(0)
    val loading: StateFlow<Int> = _loading

    /** Ground metres per pixel of the most recent block, for the "≈1 m" readout. */
    private val _resolutionMeters = MutableStateFlow<Double?>(null)
    val resolutionMeters: StateFlow<Double?> = _resolutionMeters

    /** Adds the source and layer to a freshly loaded style, under [belowLayerId] when present. */
    fun install(style: Style, belowLayerId: String?, opacity: Double, visible: Boolean) {
        this.style = style
        required.clear()
        val client = CustomRasterSourceClient { tileId, status ->
            scope.launch(Dispatchers.Main) { onTileStatus(tileId, status) }
        }
        val created = customRasterSource(SOURCE_ID) {
            tileSize(TILE.toShort())
            minZoom(MIN_ZOOM.toByte())
            maxZoom(MAX_ZOOM.toByte())
            clientCallback(client)
        }
        // A fresh source object must be the one attached to the style, or setTileData has nowhere to go.
        if (style.styleLayerExists(LAYER_ID)) style.removeStyleLayer(LAYER_ID)
        if (style.styleSourceExists(SOURCE_ID)) style.removeStyleSource(SOURCE_ID)
        style.addSource(created)
        source = created
        style.addLayerBelow(rasterLayer(LAYER_ID, SOURCE_ID) { rasterOpacity(opacity) }, belowLayerId)
        setAppearance(opacity, visible)
    }

    fun setAppearance(opacity: Double, visible: Boolean) {
        val s = style ?: return
        if (!s.styleLayerExists(LAYER_ID)) return
        s.setStyleLayerProperty(LAYER_ID, "raster-opacity", com.mapbox.bindgen.Value(opacity))
        s.setStyleLayerProperty(LAYER_ID, "visibility", com.mapbox.bindgen.Value(if (visible) "visible" else "none"))
    }

    /** Re-renders everything in view with a new look. Elevation stays cached. */
    fun updateSettings(newSettings: ReliefSettings) {
        if (newSettings == settings) return
        settings = newSettings
        version++
        rendered.evictAll()
        inflight.values.forEach { it.cancel() }
        inflight.clear()
        _loading.value = 0
        required.map { chunkFor(it) }.distinct().forEach { ensureRendered(it) }
    }

    private fun onTileStatus(tile: CanonicalTileID, status: CustomRasterSourceTileStatus) {
        when (status) {
            CustomRasterSourceTileStatus.REQUIRED -> {
                required += tile
                val key = chunkFor(tile)
                val bitmap = rendered.get(key)
                if (bitmap != null) push(listOf(tile), key, bitmap) else ensureRendered(key)
            }
            CustomRasterSourceTileStatus.NOT_NEEDED -> required -= tile
            else -> Unit
        }
    }

    private fun ensureRendered(key: TileKey) {
        if (key in inflight || key in empty) return
        val forVersion = version
        val look = settings
        _loading.value = inflight.size + 1
        inflight[key] = scope.launch(Dispatchers.Main) {
            val chunk = chunks.get(key) ?: withContext(io) { client.fetch(key) }?.also { chunks.put(key, it) }
            val bitmap = if (chunk != null && chunk.hasData) {
                withContext(cpu) {
                    val pixels = ReliefRenderer.render(chunk, look)
                    Bitmap.createBitmap(pixels, ElevationChunk.SIZE, ElevationChunk.SIZE, Bitmap.Config.ARGB_8888)
                }
            } else {
                null
            }
            inflight.remove(key)
            _loading.value = inflight.size
            if (forVersion != version) return@launch
            if (chunk == null) return@launch // network failure: retried when the tile is asked for again
            _resolutionMeters.value = chunk.cellMeters
            if (bitmap == null) {
                empty += key
                return@launch
            }
            rendered.put(key, bitmap)
            push(required.filter { chunkFor(it) == key }, key, bitmap)
        }
    }

    private fun push(tiles: List<CanonicalTileID>, key: TileKey, chunkBitmap: Bitmap) {
        val src = source ?: return
        if (tiles.isEmpty()) return
        val data = tiles.map { tile ->
            val shift = tile.z.toInt() - key.z
            val crop = ElevationChunk.SIZE shr shift
            val x0 = (tile.x.toInt() - (key.x shl shift)) * crop
            val y0 = (tile.y.toInt() - (key.y shl shift)) * crop
            val piece = Bitmap.createBitmap(chunkBitmap, x0, y0, crop, crop)
            val tileBitmap = if (crop == TILE) piece else Bitmap.createScaledBitmap(piece, TILE, TILE, crop > TILE)
            val image = tileBitmap.toMapboxImage()
            if (tileBitmap !== piece) tileBitmap.recycle()
            if (piece !== chunkBitmap) piece.recycle()
            CustomRasterSourceTileData(tile, image)
        }
        runCatching { src.setTileData(data) }
    }

    /** Elevation block for a display tile: two zoom levels up (16 tiles per block), capped at z15 ≈ 1 m. */
    private fun chunkFor(tile: CanonicalTileID): TileKey {
        val z = tile.z.toInt()
        val level = minOf(z - 2, BLOCK_MAX_ZOOM).coerceAtLeast(0)
        val shift = z - level
        return TileKey(level, tile.x.toInt() shr shift, tile.y.toInt() shr shift)
    }

    companion object {
        const val SOURCE_ID = "findit-relief-src"
        const val LAYER_ID = "findit-relief"
        private const val TILE = 256
        const val MIN_ZOOM = 12
        private const val MAX_ZOOM = 18
        private const val BLOCK_MAX_ZOOM = 15
    }
}
