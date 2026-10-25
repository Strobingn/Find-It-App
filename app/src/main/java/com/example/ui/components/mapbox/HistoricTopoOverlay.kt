package com.example.ui.components.mapbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.historicmap.ClippedTopoSheet
import com.example.data.historicmap.HistoricMapArchive
import com.example.data.historicmap.HistoricMapCatalogClient
import com.example.data.historicmap.HistoricMapRecord
import com.example.data.historicmap.HistoricTopoEras
import com.example.data.historicmap.HistoricTopoRectifier
import com.mapbox.bindgen.Value
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.ImageSource
import com.mapbox.maps.extension.style.sources.generated.imageSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.sources.updateImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USGS historical topographic maps laid over the Mapbox map by era. Each sheet is downloaded
 * once, cut to its neatline and placed by its own GeoTIFF georeferencing — no manual alignment.
 */
class HistoricTopoOverlay(context: Context, private val scope: CoroutineScope) {
    private val catalog = HistoricMapCatalogClient()
    private val archive = HistoricMapArchive(context.applicationContext)
    private val rectifier = HistoricTopoRectifier(context.applicationContext)

    private val _records = MutableStateFlow<List<HistoricMapRecord>>(emptyList())
    val records: StateFlow<List<HistoricMapRecord>> = _records

    private val _sheets = MutableStateFlow<List<ClippedTopoSheet>>(emptyList())
    val sheets: StateFlow<List<ClippedTopoSheet>> = _sheets

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private var searchedBox: DoubleArray? = null
    private var searchJob: Job? = null
    private var sheetJob: Job? = null
    private var installedOn: Style? = null
    private val installedIds = LinkedHashSet<String>()

    /** Looks up the editions around the view unless the last search already covered it. */
    fun search(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        val box = searchedBox
        if (box != null && minLat >= box[0] && maxLat <= box[1] && minLon >= box[2] && maxLon <= box[3]) return
        if (searchJob?.isActive == true) return
        val padLat = (maxLat - minLat) * 0.5
        val padLon = (maxLon - minLon) * 0.5
        val wanted = doubleArrayOf(minLat - padLat, maxLat + padLat, minLon - padLon, maxLon + padLon)
        searchJob = scope.launch {
            _status.value = "Finding old topo maps here…"
            val found = withContext(Dispatchers.IO) {
                runCatching { catalog.usgsHistorical(listOf(wanted[2], wanted[0], wanted[3], wanted[1])) }.getOrDefault(emptyList())
            }
            if (found.isEmpty() && _records.value.isEmpty()) {
                _status.value = "No USGS historical topo maps found here."
            } else {
                _status.value = null
                searchedBox = wanted
            }
            _records.value = (_records.value + found).distinctBy { it.id }
        }
    }

    /** Prepares the sheets for [decade] that cover the view, downloading and aligning as needed. */
    fun showEra(decade: Int, minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, centerLat: Double, centerLon: Double) {
        val picks = HistoricTopoEras.sheetsFor(_records.value, decade, minLat, maxLat, minLon, maxLon, centerLat, centerLon, MAX_SHEETS)
        if (picks.map { it.id } == _sheets.value.map { it.recordId }) return
        sheetJob?.cancel()
        sheetJob = scope.launch {
            val ready = ArrayList<ClippedTopoSheet>()
            for (record in picks) {
                val cached = withContext(Dispatchers.IO) { rectifier.cached(record.id) }
                if (cached != null) {
                    ready += cached
                    continue
                }
                _status.value = "Downloading ${label(record)}…"
                val local = withContext(Dispatchers.IO) {
                    runCatching { catalog.downloadMissing(listOf(record), archive).firstOrNull()?.localFile }.getOrNull()
                }
                if (local == null || !local.isFile) {
                    _status.value = "Couldn't download ${label(record)}."
                    continue
                }
                _status.value = "Lining up ${label(record)}…"
                val clipped = withContext(Dispatchers.Default) {
                    runCatching { rectifier.rectify(record, local, MAX_DIMENSION) }.getOrNull()
                }
                if (clipped == null) {
                    _status.value = "${label(record)} uses a layout this app can't align yet."
                    continue
                }
                ready += clipped
            }
            if (ready.size == picks.size) _status.value = null
            _sheets.value = ready
        }
    }

    fun clear() {
        sheetJob?.cancel()
        _sheets.value = emptyList()
    }

    /**
     * Puts the current sheets on [style], coarse scales underneath, all below [belowLayerId].
     * Bitmaps are only uploaded for sheets not already on this style.
     */
    fun apply(style: Style, belowLayerId: String?, opacity: Double, visible: Boolean) {
        if (installedOn !== style) {
            installedOn = style
            installedIds.clear()
        }
        val wanted = _sheets.value.map { it.recordId }.toSet()
        installedIds.filter { it !in wanted }.forEach { id ->
            if (style.styleLayerExists(layerId(id))) style.removeStyleLayer(layerId(id))
            if (style.styleSourceExists(sourceId(id))) style.removeStyleSource(sourceId(id))
            installedIds.remove(id)
        }
        // Sheets arrive coarse-first, so each later one goes on top of the earlier ones.
        for (sheet in _sheets.value) {
            val id = sheet.recordId
            if (id !in installedIds) {
                val bitmap = BitmapFactory.decodeFile(
                    sheet.image.absolutePath,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                ) ?: continue
                if (!style.styleSourceExists(sourceId(id))) {
                    style.addSource(imageSource(sourceId(id)) { coordinates(sheet.corners) })
                }
                style.getSourceAs<ImageSource>(sourceId(id))?.updateImage(bitmap)
                bitmap.recycle()
                if (!style.styleLayerExists(layerId(id))) {
                    style.addLayerBelow(rasterLayer(layerId(id), sourceId(id)) {}, belowLayerId)
                }
                installedIds += id
            }
            style.setStyleLayerProperty(layerId(id), "raster-opacity", Value(opacity))
            style.setStyleLayerProperty(layerId(id), "visibility", Value(if (visible) "visible" else "none"))
        }
    }

    private fun label(r: HistoricMapRecord): String =
        r.title.substringAfter("Quadrangle for ", r.title).trim()

    companion object {
        private const val MAX_SHEETS = 3
        private const val MAX_DIMENSION = 2048
        fun sourceId(id: String) = "findit-topo-src-$id"
        fun layerId(id: String) = "findit-topo-$id"
    }
}
