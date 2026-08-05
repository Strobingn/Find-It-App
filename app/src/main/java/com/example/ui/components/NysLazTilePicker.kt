package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import com.example.data.CopcAsset
import com.example.data.CopcStacCatalog
import com.example.data.DemGenerator
import com.example.data.GroundSurfaceMode
import com.example.data.LazTerrainCache
import com.example.data.LazTerrainDiskCache
import com.example.data.LazTerrainMemoryCache
import com.example.data.LazPickerSession
import com.example.data.LazPickerSessionStore
import com.example.data.LidarAreaSelection
import com.example.data.LidarImportOptions
import com.example.data.MosaicTerrainBuilder
import com.example.data.MosaicTerrainTile
import com.example.data.LidarSearchRequest
import com.example.data.NormalizedRasterBounds
import com.example.data.NortheastLidarRegion
import com.example.data.NysHistoricLazTileCatalog
import com.example.data.TerrainDecodeCoordinator
import com.example.data.TerrainImportSource
import com.example.data.TerrainPerformanceSession
import com.example.data.local.AppDatabase
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.mosaic.MosaicProject
import com.example.data.mosaic.MosaicProjectResume
import com.example.data.mosaic.MosaicProjectState
import com.example.data.mosaic.MosaicProjectTile
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import com.example.data.download.LazDownloadTask
import com.example.data.download.LazDownloadState
import com.example.data.download.LazDownloadService
import com.example.data.download.LazDownloadQueue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.LinearProgressIndicator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

private enum class AreaSelectionMode { RECTANGLE, RADIUS, POLYGON }

/**
 * Public LiDAR tile resolver and downloader for historic-site work.
 *
 * Lookups run against USGS 3DEP and are not limited to one state; the region chips seed a search
 * box for the Northeast states this app is used in most.
 */
@Composable
fun NysLazTilePicker(
    onCustomTerrainLoaded: (DemGenerator.TerrainLoadResult, TerrainImportSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { NysHistoricLazTileCatalog() }
    val copcCatalog = remember { CopcStacCatalog() }
    val store = remember(context) { LazDownloadQueue.store(context) }
    val diskCache = remember(context) { LazTerrainDiskCache(File(context.cacheDir, "decoded-terrain")) }
    val terrainCache = remember(diskCache) { LazTerrainCache(LazTerrainMemoryCache(), diskCache) }
    val decodeCoordinator = remember(terrainCache) { TerrainDecodeCoordinator(terrainCache) }
    val mosaicProjectDao = remember(context) { AppDatabase.get(context).mosaicProjectDao() }

    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var west by remember { mutableStateOf("") }
    var south by remember { mutableStateOf("") }
    var east by remember { mutableStateOf("") }
    var north by remember { mutableStateOf("") }
    var areaSelectionMode by rememberSaveable { mutableStateOf(AreaSelectionMode.RECTANGLE) }
    var showAreaMapPicker by remember { mutableStateOf(false) }
    var radiusLatitude by remember { mutableStateOf("") }
    var radiusLongitude by remember { mutableStateOf("") }
    var radiusMiles by remember { mutableStateOf("") }
    var polygonVertices by remember { mutableStateOf("") }
    var selectedRegion by remember { mutableStateOf<NortheastLidarRegion?>(null) }
    var mosaicProjectName by remember { mutableStateOf("") }
    var tiles by remember { mutableStateOf<List<NysHistoricLazTileCatalog.Tile>>(emptyList()) }
    var copcAssets by remember { mutableStateOf<List<CopcAsset>>(emptyList()) }
    var lastSearchBounds by remember {
        mutableStateOf<com.example.geospatial.GeoSpatialLibrary.GeographicBounds?>(null)
    }
    var savedMosaicProjects by remember { mutableStateOf<List<MosaicProject>>(emptyList()) }
    var selectedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedAreaDescription by remember { mutableStateOf<String?>(null) }
    var downloadEstimate by remember { mutableStateOf<NysHistoricLazTileCatalog.DownloadEstimate?>(null) }
    var isLookingUp by remember { mutableStateOf(false) }
    var isEstimatingDownload by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var activeCopcAssetId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sessionRestored by remember { mutableStateOf(false) }
    // Downloads live in LazDownloadService, so they survive leaving this screen. The picker only
    // observes them and opens a tile once its bytes have landed.
    val downloadTasks by LazDownloadQueue.tasks.collectAsStateWithLifecycle()
    var awaitingOpenUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Declined only costs the progress notification; the transfer itself still runs. */ }

    LaunchedEffect(context) {
        val saved = LazPickerSessionStore.load(context)
        latitude = saved.latitude
        longitude = saved.longitude
        west = saved.west
        south = saved.south
        east = saved.east
        north = saved.north
        areaSelectionMode = AreaSelectionMode.entries.firstOrNull {
            it.name == saved.areaSelectionMode
        } ?: AreaSelectionMode.RECTANGLE
        radiusLatitude = saved.radiusLatitude
        radiusLongitude = saved.radiusLongitude
        radiusMiles = saved.radiusMiles
        polygonVertices = saved.polygonVertices
        selectedRegion = saved.selectedRegion?.let { name ->
            NortheastLidarRegion.entries.firstOrNull { it.name == name }
        }
        mosaicProjectName = saved.mosaicProjectName
        tiles = saved.tiles
        copcAssets = saved.copcAssets
        selectedUrls = saved.selectedUrls
        selectedAreaDescription = saved.selectedAreaDescription
        lastSearchBounds = saved.lastSearchBounds
        sessionRestored = true
    }

    LaunchedEffect(
        sessionRestored,
        latitude,
        longitude,
        west,
        south,
        east,
        north,
        areaSelectionMode,
        radiusLatitude,
        radiusLongitude,
        radiusMiles,
        polygonVertices,
        selectedRegion,
        mosaicProjectName,
        tiles,
        copcAssets,
        selectedUrls,
        selectedAreaDescription,
        lastSearchBounds,
    ) {
        if (!sessionRestored) return@LaunchedEffect
        LazPickerSessionStore.save(
            context,
            LazPickerSession(
                latitude = latitude,
                longitude = longitude,
                west = west,
                south = south,
                east = east,
                north = north,
                areaSelectionMode = areaSelectionMode.name,
                radiusLatitude = radiusLatitude,
                radiusLongitude = radiusLongitude,
                radiusMiles = radiusMiles,
                polygonVertices = polygonVertices,
                selectedRegion = selectedRegion?.name,
                mosaicProjectName = mosaicProjectName,
                tiles = tiles,
                copcAssets = copcAssets,
                selectedUrls = selectedUrls,
                selectedAreaDescription = selectedAreaDescription,
                lastSearchBounds = lastSearchBounds,
            ),
        )
    }

    LaunchedEffect(mosaicProjectDao) {
        mosaicProjectDao.observeAll().collect { stored ->
            savedMosaicProjects = stored.map { it.toDomain() }.filter { it.tiles.isNotEmpty() }
        }
    }

    /**
     * Seeds the area box with a state extent. Deliberately does not run the search: a whole state
     * resolves to far more tiles than anyone wants to download, so the box is a starting point the
     * user narrows first.
     */
    fun applyRegion(region: NortheastLidarRegion) {
        selectedRegion = region
        west = region.west.toString()
        south = region.south.toString()
        east = region.east.toString()
        north = region.north.toString()
        error = null
        status = "${region.displayName} bounds loaded. Narrow the box, then find tiles in the area."
    }

    fun lookup() {
        val lat = latitude.trim().toDoubleOrNull()
        val lon = longitude.trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            error = "Enter valid latitude and longitude coordinates."
            return
        }
        isLookingUp = true
        error = null
        downloadEstimate = null
        selectedAreaDescription = null
        copcAssets = emptyList()
        lastSearchBounds = null
        status = "Finding the exact LiDAR tile…"
        scope.launch {
            try {
                tiles = catalog.tilesAt(lon, lat)
                selectedUrls = tiles.map { it.downloadUrl }.toSet()
                status = if (tiles.isEmpty()) {
                    "No published LiDAR tile covers that coordinate."
                } else {
                    "Found ${tiles.size} matching tile${if (tiles.size == 1) "" else "s"}."
                }
            } catch (t: Throwable) {
                tiles = emptyList()
                copcAssets = emptyList()
                error = t.localizedMessage ?: "Tile lookup failed."
                status = null
            } finally {
                isLookingUp = false
            }
        }
    }

    fun tileBounds(tile: NysHistoricLazTileCatalog.Tile): com.example.geospatial.GeoSpatialLibrary.GeographicBounds? {
        val minLon = tile.minLongitude ?: return null
        val minLat = tile.minLatitude ?: return null
        val maxLon = tile.maxLongitude ?: return null
        val maxLat = tile.maxLatitude ?: return null
        return com.example.geospatial.GeoSpatialLibrary.GeographicBounds(
            minLat = minLat,
            maxLat = maxLat,
            minLon = minLon,
            maxLon = maxLon,
        ).takeIf { minLat < maxLat && minLon < maxLon }
    }

    fun lookupArea(selection: LidarAreaSelection, description: String) {
        isLookingUp = true
        error = null
        downloadEstimate = null
        selectedAreaDescription = selection.projectAreaDescription()
        status = "Resolving every LiDAR tile intersecting this $description…"
        scope.launch {
            try {
                val bounds = selection.bounds
                lastSearchBounds = bounds
                val (lazResult, copcResult) = coroutineScope {
                    val laz = async {
                        runCatching {
                            catalog.tilesInBounds(
                                west = bounds.minLon,
                                south = bounds.minLat,
                                east = bounds.maxLon,
                                north = bounds.maxLat,
                            )
                        }
                    }
                    val copc = async { runCatching { copcCatalog.search(bounds) } }
                    laz.await() to copc.await()
                }
                val resolved = lazResult.getOrDefault(emptyList())
                // Providers only accept envelopes. Preserve a tile with no returned footprint so
                // the server's spatial intersection remains authoritative, but precisely filter
                // every tile whose metadata gives us a usable geographic rectangle.
                tiles = resolved.filter { tile -> tileBounds(tile)?.let(selection::intersects) ?: true }
                copcAssets = copcResult.getOrDefault(emptyList()).filter { asset ->
                    asset.bounds?.let(selection::intersects) ?: true
                }
                if (tiles.isEmpty() && copcAssets.isEmpty()) {
                    lazResult.exceptionOrNull()?.let { throw it }
                    copcResult.exceptionOrNull()?.let { throw it }
                }
                selectedUrls = tiles.map { it.downloadUrl }.toSet()
                status = when {
                    tiles.isEmpty() && copcAssets.isEmpty() ->
                        "No published LiDAR tiles intersect that area."
                    resolved.size >= NysHistoricLazTileCatalog.MAX_NATIONAL_MAP_RESULTS ->
                        "Found ${copcAssets.size} streamable COPC and ${tiles.size} downloadable LAZ files — " +
                            "the LAZ search hit its cap. Narrow the box to see the rest."
                    else ->
                        "Found ${copcAssets.size} streamable COPC and ${tiles.size} downloadable LAZ files."
                }
            } catch (t: Throwable) {
                tiles = emptyList()
                copcAssets = emptyList()
                lastSearchBounds = null
                selectedUrls = emptySet()
                error = t.localizedMessage ?: "Area tile lookup failed."
                status = null
            } finally {
                isLookingUp = false
            }
        }
    }

    fun streamCopc(asset: CopcAsset) {
        if (downloadJob?.isActive == true) return
        val query = lastSearchBounds ?: run {
            error = "Select an area before starting a COPC stream."
            return
        }
        val assetBounds = asset.bounds ?: query
        val clippedMinLon = maxOf(query.minLon, assetBounds.minLon)
        val clippedMaxLon = minOf(query.maxLon, assetBounds.maxLon)
        val clippedMinLat = maxOf(query.minLat, assetBounds.minLat)
        val clippedMaxLat = minOf(query.maxLat, assetBounds.maxLat)
        val width = assetBounds.maxLon - assetBounds.minLon
        val height = assetBounds.maxLat - assetBounds.minLat
        if (width <= 0.0 || height <= 0.0 ||
            clippedMinLon >= clippedMaxLon || clippedMinLat >= clippedMaxLat
        ) {
            error = "The selected area does not overlap this COPC source."
            return
        }
        val focus = NormalizedRasterBounds(
            left = (clippedMinLon - assetBounds.minLon) / width,
            top = (assetBounds.maxLat - clippedMaxLat) / height,
            right = (clippedMaxLon - assetBounds.minLon) / width,
            bottom = (assetBounds.maxLat - clippedMinLat) / height,
        ).sanitized()
        val options = LidarImportOptions(
            groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
            rasterResolution = 1_024,
            smoothingRadius = 0,
            focusBounds = focus,
        )
        error = null
        activeCopcAssetId = asset.id
        downloadJob = scope.launch {
            try {
                status = "Authorizing the USGS COPC stream…"
                val signedAsset = copcCatalog.signedAsset(asset)
                val outcome = decodeCoordinator.decodeRemoteCopc(
                    url = signedAsset.href,
                    // COPC is range-streamed rather than fully downloaded. Keep the sparse range
                    // file beside the normal LAZ datasets so it survives cache cleanup and can be
                    // reused when the same source is opened after leaving the map.
                    cacheDirectory = File(store.directory, "copc-range-cache"),
                    options = options,
                    onStage = { status = it },
                )
                TerrainPerformanceSession.publish(outcome.gpuScene)
                onCustomTerrainLoaded(outcome.terrain, null)
                status = "Streamed ${asset.title} for the selected area."
            } catch (_: CancellationException) {
                status = "COPC stream cancelled. Cached byte ranges remain available."
            } catch (t: Throwable) {
                error = t.localizedMessage ?: "COPC streaming failed."
                status = null
            } finally {
                activeCopcAssetId = null
                downloadJob = null
            }
        }
    }

    // A box handed over from the map arrives here after the tab switch. Consuming it means
    // returning to this tab later does not silently repeat the search.
    val mapSearchBounds by LidarSearchRequest.pending.collectAsStateWithLifecycle()
    LaunchedEffect(mapSearchBounds, sessionRestored) {
        if (!sessionRestored) return@LaunchedEffect
        val bounds = LidarSearchRequest.consume() ?: return@LaunchedEffect
        selectedRegion = null
        west = formatDegrees(bounds.minLon)
        south = formatDegrees(bounds.minLat)
        east = formatDegrees(bounds.maxLon)
        north = formatDegrees(bounds.maxLat)
        areaSelectionMode = AreaSelectionMode.RECTANGLE
        lookupArea(LidarAreaSelection.Rectangle(bounds), description = "rectangle")
    }

    fun estimateSelectedDownload() {
        if (isEstimatingDownload || downloadJob?.isActive == true) return
        val selected = tiles.filter { it.downloadUrl in selectedUrls }
        if (selected.isEmpty()) {
            error = "Select at least one tile before estimating storage."
            return
        }
        val existingNames = store.list().mapTo(mutableSetOf()) { it.displayName }
        val needed = selected.filterNot { it.name in existingNames }
        error = null
        isEstimatingDownload = true
        status = if (needed.isEmpty()) {
            "All selected source files are already stored offline."
        } else {
            "Checking source-file sizes without downloading terrain…"
        }
        scope.launch {
            try {
                val estimate = if (needed.isEmpty()) {
                    NysHistoricLazTileCatalog.DownloadEstimate(knownBytes = 0L, unknownTileCount = 0)
                } else {
                    catalog.estimateDownloadBytes(needed)
                }
                downloadEstimate = estimate
                status = when {
                    estimate.unknownTileCount == 0 ->
                        "Storage needed: ${formatBytesCompact(estimate.knownBytes)} for ${needed.size} new file${if (needed.size == 1) "" else "s"}."
                    estimate.knownBytes > 0L ->
                        "Known storage: ${formatBytesCompact(estimate.knownBytes)}. ${estimate.unknownTileCount} file size${if (estimate.unknownTileCount == 1) " is" else "s are"} unavailable."
                    else ->
                        "The server did not report sizes for ${estimate.unknownTileCount} selected file${if (estimate.unknownTileCount == 1) "" else "s"}."
                }
            } catch (t: Throwable) {
                downloadEstimate = null
                error = t.localizedMessage ?: "Could not estimate source-file storage."
                status = null
            } finally {
                isEstimatingDownload = false
            }
        }
    }

    /** Decodes an already-downloaded file and hands the terrain to the workspace. */
    fun openDownloadedFile(file: File, displayName: String) {
        scope.launch {
            try {
                status = "Building bare-earth terrain from source ground classes…"
                val options = LidarImportOptions(
                    groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                    rasterResolution = 1_024,
                    smoothingRadius = 0,
                )
                val source = TerrainImportSource(
                    uri = Uri.fromFile(file).toString(),
                    displayName = displayName,
                    options = options,
                )
                val outcome = decodeCoordinator.decode(
                    file = file,
                    displayName = displayName,
                    options = options,
                    onPreview = { preview ->
                        scope.launch {
                            TerrainPerformanceSession.publish(preview.gpuScene)
                            onCustomTerrainLoaded(preview.terrain, source)
                            status = if (preview.isPreview) {
                                "Dense terrain decoding… (filling holes)"
                            } else {
                                "Terrain visible — finishing detail…"
                            }
                        }
                    },
                    onStage = { stage -> scope.launch { status = stage } },
                )
                TerrainPerformanceSession.publish(outcome.gpuScene)
                onCustomTerrainLoaded(outcome.terrain, source)
                status = "Opened $displayName using ASPRS ground class 2 with class 8 fallback."
                val exactJob = outcome.exactOutcome
                if (exactJob != null) {
                    scope.launch {
                        val exact = runCatching { exactJob.await() }.getOrNull() ?: return@launch
                        TerrainPerformanceSession.publish(exact.gpuScene)
                        onCustomTerrainLoaded(exact.terrain, source)
                        status = "Exact terrain ready · $displayName"
                    }
                }
            } catch (_: CancellationException) {
                status = null
            } catch (t: Throwable) {
                error = t.localizedMessage ?: "Tile decode failed."
                status = null
            }
        }
    }

    /**
     * A copy of this tile already on disk, matched by the URL it came from.
     *
     * Tile names are only unique within a survey, so matching on name alone could hand back an
     * unrelated project's file of the same name. Files stored before provenance was recorded have
     * no index entry; those still fall back to the name, and the match is recorded so the
     * association is explicit from then on.
     */
    fun reusableFile(
        sourceUrl: String,
        displayName: String,
        expectedLocalFileName: String? = null,
    ): File? {
        store.fileForSource(sourceUrl)?.let { return it }
        expectedLocalFileName
            ?.let { File(store.directory, it) }
            ?.takeIf(store::contains)
            ?.let {
                store.recordSource(sourceUrl, it)
                return it
            }
        val byName = store.list().firstOrNull { it.displayName == displayName }?.file ?: return null
        store.recordSource(sourceUrl, byName)
        return byName
    }

    /** A project member can be reopened only from its recorded source URL or saved local file. */
    fun storedProjectFile(tile: MosaicProjectTile): File? =
        store.fileForSource(tile.sourceUrl)
            ?: File(store.directory, tile.localFileName).takeIf(store::contains)

    fun downloadAndOpen(tile: NysHistoricLazTileCatalog.Tile) {
        error = null
        // Already on disk from an earlier background download - skip straight to decoding.
        val existing = reusableFile(tile.downloadUrl, tile.name)
        if (existing != null) {
            openDownloadedFile(existing, existing.name)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Open this one automatically when its bytes land, but only if the user is still here.
        awaitingOpenUrl = tile.downloadUrl
        LazDownloadService.enqueue(context, tile.downloadUrl, tile.name)
        status = "Downloading ${tile.name} in the background. You can leave this screen."
    }

    // The service owns the transfer, so completion can arrive while this screen is composed or
    // long after it was left and re-entered. Either way, act on it exactly once.
    LaunchedEffect(downloadTasks, awaitingOpenUrl) {
        val url = awaitingOpenUrl ?: return@LaunchedEffect
        val task = downloadTasks.firstOrNull { it.url == url } ?: return@LaunchedEffect
        when (task.state) {
            LazDownloadState.COMPLETED -> {
                awaitingOpenUrl = null
                LazDownloadQueue.dismiss(url)
                task.filePath?.let { openDownloadedFile(File(it), task.displayName) }
            }
            LazDownloadState.FAILED -> {
                awaitingOpenUrl = null
                error = task.error ?: "Tile download failed."
                status = null
                // Deliberately left in the queue: dismissing here dropped the only record of the
                // failure, so a part-transferred tile could not be retried without resolving the
                // whole area again. The row below owns retrying and dismissing it.
            }
            LazDownloadState.CANCELLED -> {
                awaitingOpenUrl = null
                status = "Tile download cancelled."
                LazDownloadQueue.dismiss(url)
            }
            LazDownloadState.QUEUED, LazDownloadState.RUNNING -> Unit
        }
    }

    /**
     * Returns the tile's local file, downloading it through the background service first if
     * needed. The transfer itself is owned by the service, so leaving this screen mid-mosaic
     * keeps the bytes coming; returning and tapping again picks up the already-finished files
     * instead of starting over.
     */
    suspend fun awaitDownloadedFile(
        sourceUrl: String,
        displayName: String,
        expectedLocalFileName: String? = null,
        onProgress: (LazDownloadTask) -> Unit,
    ): File {
        reusableFile(sourceUrl, displayName, expectedLocalFileName)?.let { return it }
        LazDownloadService.enqueue(context, sourceUrl, displayName)
        val finished = LazDownloadQueue.tasks
            .map { list -> list.firstOrNull { it.url == sourceUrl } }
            .onEach { task -> task?.let(onProgress) }
            // A null task means something else already dismissed the entry, so fall through to
            // the disk check below rather than waiting on a record that no longer exists.
            .first { it == null || it.isFinished }
        if (finished != null && finished.state == LazDownloadState.COMPLETED) {
            LazDownloadQueue.dismiss(sourceUrl)
            finished.filePath?.let { return File(it) }
        }
        if (finished != null && finished.state == LazDownloadState.CANCELLED) {
            LazDownloadQueue.dismiss(sourceUrl)
            throw CancellationException("Download cancelled")
        }
        reusableFile(sourceUrl, displayName, expectedLocalFileName)?.let { return it }
        // The failed entry stays in the queue so the retry row can resume it. Dismissing here meant
        // one bad tile in a large mosaic discarded its own partial transfer along with any way to
        // resume it, forcing the whole area to be resolved and fetched again.
        error(finished?.error ?: "Download of $displayName did not complete")
    }

    /**
     * Builds a project from every recorded source file, downloading only the members that are
     * still absent. Project state is saved before the first transfer and after each completed
     * source, so cancellation, a failed host, or a process restart can resume the same area.
     */
    fun startOrResumeMosaicProject(initialProject: MosaicProject) {
        if (downloadJob?.isActive == true) return
        error = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        downloadJob = scope.launch {
            var project = initialProject
            try {
                val options = LidarImportOptions(
                    groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                    rasterResolution = 1_024,
                    smoothingRadius = 0,
                )
                val missingBeforeStart = project.tiles.count { storedProjectFile(it) == null }
                project = project.copy(
                    state = MosaicProjectResume.stateWhenStarted(project, missingBeforeStart),
                    recoveryMessage = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                withContext(NonCancellable) { mosaicProjectDao.upsert(project.toEntity()) }
                val decodedTiles = project.tiles.mapIndexed { index, tile ->
                    status = "${index + 1}/${project.tiles.size}: preparing ${tile.displayName}…"
                    val file = awaitDownloadedFile(
                        sourceUrl = tile.sourceUrl,
                        displayName = tile.displayName,
                        expectedLocalFileName = tile.localFileName,
                    ) { task ->
                        status = task.fraction?.let {
                            "${index + 1}/${project.tiles.size}: ${tile.displayName} ${(it * 100).toInt()}%"
                        } ?: "${index + 1}/${project.tiles.size}: ${formatBytesCompact(task.downloadedBytes)} downloaded"
                    }
                    project = project.copy(
                        tiles = project.tiles.map { member ->
                            if (member.sourceUrl == tile.sourceUrl) member.copy(localFileName = file.name) else member
                        },
                        state = MosaicProjectState.DOWNLOADING,
                        recoveryMessage = null,
                        updatedAtMillis = System.currentTimeMillis(),
                    )
                    mosaicProjectDao.upsert(project.toEntity())
                    status = "${index + 1}/${project.tiles.size}: decoding ${tile.displayName}…"
                    val outcome = decodeCoordinator.decode(file, tile.displayName, options) { stage -> status = stage }
                    MosaicTerrainTile(tile.displayName, outcome.terrain, tile.bounds)
                }
                status = "Rebuilding ${project.displayName}…"
                val mosaic = withContext(Dispatchers.Default) {
                    MosaicTerrainBuilder.build(project.displayName, decodedTiles)
                }
                project = project.copy(
                    state = MosaicProjectState.READY,
                    recoveryMessage = null,
                    updatedAtMillis = System.currentTimeMillis(),
                )
                mosaicProjectDao.upsert(project.toEntity())
                TerrainPerformanceSession.publish(com.example.data.TerrainGpuSceneBuilder.build(mosaic.grid))
                onCustomTerrainLoaded(mosaic, null)
                status = "Opened ${project.displayName}. Source files remain offline for reopening."
            } catch (_: CancellationException) {
                val readyCount = project.tiles.count { storedProjectFile(it) != null }
                project = project.copy(
                    state = MosaicProjectState.NEEDS_ATTENTION,
                    recoveryMessage = MosaicProjectResume.pausedMessage(readyCount, project.tiles.size),
                    updatedAtMillis = System.currentTimeMillis(),
                )
                withContext(NonCancellable) { mosaicProjectDao.upsert(project.toEntity()) }
                status = "Project paused. Resume ${project.displayName} when ready."
            } catch (t: Throwable) {
                project = project.copy(
                    state = MosaicProjectState.NEEDS_ATTENTION,
                    recoveryMessage = t.localizedMessage ?: "A source file could not be prepared.",
                    updatedAtMillis = System.currentTimeMillis(),
                )
                mosaicProjectDao.upsert(project.toEntity())
                error = project.recoveryMessage
                status = null
            } finally {
                downloadJob = null
            }
        }
    }

    fun lookupRectangle() {
        val westValue = west.trim().toDoubleOrNull()
        val southValue = south.trim().toDoubleOrNull()
        val eastValue = east.trim().toDoubleOrNull()
        val northValue = north.trim().toDoubleOrNull()
        if (westValue == null || southValue == null || eastValue == null || northValue == null ||
            westValue !in -180.0..180.0 || eastValue !in -180.0..180.0 ||
            southValue !in -90.0..90.0 || northValue !in -90.0..90.0 ||
            westValue >= eastValue || southValue >= northValue
        ) {
            error = "Enter west < east and south < north geographic bounds."
            return
        }
        lookupArea(
            LidarAreaSelection.Rectangle(
                com.example.geospatial.GeoSpatialLibrary.GeographicBounds(
                    minLat = southValue,
                    maxLat = northValue,
                    minLon = westValue,
                    maxLon = eastValue,
                ),
            ),
            description = "rectangle",
        )
    }

    fun lookupRadius() {
        val latitudeValue = radiusLatitude.trim().toDoubleOrNull()
        val longitudeValue = radiusLongitude.trim().toDoubleOrNull()
        val milesValue = radiusMiles.trim().toDoubleOrNull()
        if (latitudeValue == null || longitudeValue == null || milesValue == null ||
            latitudeValue !in -90.0..90.0 || longitudeValue !in -180.0..180.0 ||
            milesValue <= 0.0
        ) {
            error = "Enter a valid center coordinate and radius in miles."
            return
        }
        val selection = runCatching {
            LidarAreaSelection.Radius(
                center = LidarAreaSelection.Point(latitudeValue, longitudeValue),
                radiusMeters = milesValue * METERS_PER_MILE,
            )
        }.getOrElse {
            error = it.message ?: "Radius must be no more than 62.1 miles."
            return
        }
        lookupArea(selection, description = "radius")
    }

    fun lookupPolygon() {
        val points = buildList {
            polygonVertices.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                val values = line.trim().split(Regex("[,\\s]+")).filter(String::isNotBlank)
                if (values.size != 2) {
                    error = "Line ${index + 1} must be latitude, longitude."
                    return
                }
                val latitudeValue = values[0].toDoubleOrNull()
                val longitudeValue = values[1].toDoubleOrNull()
                if (latitudeValue == null || longitudeValue == null ||
                    latitudeValue !in -90.0..90.0 || longitudeValue !in -180.0..180.0
                ) {
                    error = "Line ${index + 1} has an invalid latitude or longitude."
                    return
                }
                add(LidarAreaSelection.Point(latitudeValue, longitudeValue))
            }
        }
        val selection = runCatching { LidarAreaSelection.Polygon(points) }.getOrElse {
            error = it.message ?: "Enter at least three vertices that enclose an area."
            return
        }
        lookupArea(selection, description = "polygon")
    }

    fun downloadSelectedMosaic() {
        if (downloadJob?.isActive == true) return
        val selected = tiles.filter { it.downloadUrl in selectedUrls }
        if (selected.isEmpty()) {
            error = "Select at least one tile for the mosaic."
            return
        }
        val estimate = downloadEstimate
        if (estimate == null || estimate.unknownTileCount != 0) {
            error = "Estimate the selected download before starting the mosaic. Every selected source file must report its size."
            return
        }
        if (selected.any { it.minLongitude == null || it.minLatitude == null || it.maxLongitude == null || it.maxLatitude == null }) {
            error = "One or more selected tiles have no geographic footprint, so they cannot form a safe mosaic."
            return
        }
        val now = System.currentTimeMillis()
        val projectName = mosaicProjectName.trim().ifBlank {
            "USGS ${selected.size}-tile project"
        }
        val project = MosaicProject(
            id = UUID.randomUUID().toString(),
            displayName = projectName,
            tiles = selected.map { tile ->
                MosaicProjectTile(
                    displayName = tile.name,
                    localFileName = reusableFile(tile.downloadUrl, tile.name)?.name ?: tile.name,
                    sourceUrl = tile.downloadUrl,
                    bounds = com.example.geospatial.GeoSpatialLibrary.GeographicBounds(
                        minLat = requireNotNull(tile.minLatitude),
                        maxLat = requireNotNull(tile.maxLatitude),
                        minLon = requireNotNull(tile.minLongitude),
                        maxLon = requireNotNull(tile.maxLongitude),
                    ),
                )
            },
            createdAtMillis = now,
            updatedAtMillis = now,
            state = MosaicProjectState.DOWNLOADING,
            areaSelectionDescription = selectedAreaDescription,
        )
        startOrResumeMosaicProject(project)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth().testTag("nys_laz_tile_picker"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Public LiDAR tiles", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "USGS 3DEP point clouds · nationwide coverage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Enter a coordinate in the woods. The app finds every published LiDAR tile whose footprint covers it, downloads the file, and opens the source-classified bare-earth surface.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Area workflow: select a rectangle, radius, or polygon to resolve every intersecting official tile, choose the files, then open one georeferenced terrain mosaic.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Jump to a state, then narrow the search area — a whole state returns far more tiles than you want to download. The Map tab can send its visible rectangle straight here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                NortheastLidarRegion.entries.forEach { region ->
                    FilterChip(
                        selected = selectedRegion == region,
                        onClick = { applyRegion(region) },
                        label = { Text(region.displayName) },
                        modifier = Modifier.testTag("lidar_region_${region.name}"),
                    )
                }
            }
            OutlinedTextField(
                value = mosaicProjectName,
                onValueChange = { mosaicProjectName = it.take(80) },
                label = { Text("Mosaic project name") },
                placeholder = { Text("North woods survey") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it.take(16); error = null },
                    label = { Text("Latitude") },
                    placeholder = { Text("41.43") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("nys_tile_latitude"),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it.take(17); error = null },
                    label = { Text("Longitude") },
                    placeholder = { Text("-74.04") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("nys_tile_longitude"),
                )
            }
            Button(
                onClick = ::lookup,
                enabled = !isLookingUp,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("find_nys_laz_tiles"),
            ) {
                if (isLookingUp) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Find exact LAZ tile")
            }

            Text("Area search", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { showAreaMapPicker = true },
                enabled = !isLookingUp && downloadJob?.isActive != true,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("pick_lidar_area_on_map"),
            ) {
                Icon(Icons.Default.Map, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Pick area on map")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                AreaSelectionMode.entries.forEach { mode ->
                    FilterChip(
                        selected = areaSelectionMode == mode,
                        onClick = { areaSelectionMode = mode; error = null },
                        label = {
                            Text(
                                when (mode) {
                                    AreaSelectionMode.RECTANGLE -> "Rectangle"
                                    AreaSelectionMode.RADIUS -> "Radius"
                                    AreaSelectionMode.POLYGON -> "Polygon"
                                },
                            )
                        },
                    )
                }
            }
            when (areaSelectionMode) {
                AreaSelectionMode.RECTANGLE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = west,
                            onValueChange = { west = it.take(17); error = null },
                            label = { Text("West lon") },
                            placeholder = { Text("-74.05") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = east,
                            onValueChange = { east = it.take(17); error = null },
                            label = { Text("East lon") },
                            placeholder = { Text("-74.03") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = south,
                            onValueChange = { south = it.take(16); error = null },
                            label = { Text("South lat") },
                            placeholder = { Text("41.42") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = north,
                            onValueChange = { north = it.take(16); error = null },
                            label = { Text("North lat") },
                            placeholder = { Text("41.44") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedButton(
                        onClick = ::lookupRectangle,
                        enabled = !isLookingUp && downloadJob?.isActive != true,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("find_nys_laz_area"),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Find tiles in rectangle")
                    }
                }
                AreaSelectionMode.RADIUS -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = radiusLatitude,
                            onValueChange = { radiusLatitude = it.take(16); error = null },
                            label = { Text("Center latitude") },
                            placeholder = { Text("41.43") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("lidar_radius_latitude"),
                        )
                        OutlinedTextField(
                            value = radiusLongitude,
                            onValueChange = { radiusLongitude = it.take(17); error = null },
                            label = { Text("Center longitude") },
                            placeholder = { Text("-74.04") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("lidar_radius_longitude"),
                        )
                    }
                    OutlinedTextField(
                        value = radiusMiles,
                        onValueChange = { radiusMiles = it.take(8); error = null },
                        label = { Text("Radius (miles, max 62.1)") },
                        placeholder = { Text("0.5") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("lidar_radius_miles"),
                    )
                    OutlinedButton(
                        onClick = ::lookupRadius,
                        enabled = !isLookingUp && downloadJob?.isActive != true,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("find_nys_laz_radius"),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Find tiles in radius")
                    }
                }
                AreaSelectionMode.POLYGON -> {
                    OutlinedTextField(
                        value = polygonVertices,
                        onValueChange = { polygonVertices = it.take(2_000); error = null },
                        label = { Text("Polygon vertices: latitude, longitude per line") },
                        placeholder = { Text("41.4200, -74.0500\n41.4200, -74.0300\n41.4400, -74.0400") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().testTag("lidar_polygon_vertices"),
                    )
                    Text(
                        "Use three or more points around the area. Tiles are checked against the polygon, not just its enclosing box.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = ::lookupPolygon,
                        enabled = !isLookingUp && downloadJob?.isActive != true,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("find_nys_laz_polygon"),
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Find tiles in polygon")
                    }
                }
            }

            if (showAreaMapPicker) {
                Dialog(
                    onDismissRequest = { showAreaMapPicker = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        LidarAreaPickerMapScreen(
                            onAreaSelected = { bounds ->
                                showAreaMapPicker = false
                                selectedRegion = null
                                west = formatDegrees(bounds.minLon)
                                south = formatDegrees(bounds.minLat)
                                east = formatDegrees(bounds.maxLon)
                                north = formatDegrees(bounds.maxLat)
                                areaSelectionMode = AreaSelectionMode.RECTANGLE
                                lookupArea(LidarAreaSelection.Rectangle(bounds), description = "map box")
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            if ((tiles.isNotEmpty() || copcAssets.isNotEmpty()) &&
                !selectedAreaDescription.isNullOrBlank()
            ) {
                Text(
                    "Current tile result: $selectedAreaDescription",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            if (copcAssets.isNotEmpty()) {
                Text("Fast COPC streaming", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Streams the byte ranges needed for the selected area. Downloadable LAZ files remain available below for offline use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                copcAssets.forEachIndexed { index, asset ->
                    Button(
                        onClick = { streamCopc(asset) },
                        enabled = downloadJob?.isActive != true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                            .testTag("stream_copc_asset_$index"),
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text("Stream ${asset.title}", maxLines = 1)
                            Text(
                                "COPC range access · no full-file download",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        if (activeCopcAssetId == asset.id) {
                            CircularProgressIndicator(
                                modifier = Modifier.width(22.dp).height(22.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                if (activeCopcAssetId != null) {
                    OutlinedButton(
                        onClick = { downloadJob?.cancel() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Cancel COPC stream") }
                }
            }
            tiles.forEach { tile ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(
                        checked = tile.downloadUrl in selectedUrls,
                        onCheckedChange = { checked ->
                            selectedUrls = if (checked) selectedUrls + tile.downloadUrl else selectedUrls - tile.downloadUrl
                            downloadEstimate = null
                        },
                    )
                    OutlinedButton(
                        onClick = { downloadAndOpen(tile) },
                        enabled = downloadJob?.isActive != true,
                        modifier = Modifier.weight(1f).height(64.dp),
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(tile.name, maxLines = 1)
                            Text(
                                tile.project.ifBlank { "Open this one tile" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                            )
                        }
                        if (downloadTasks.any { it.url == tile.downloadUrl && !it.isFinished }) {
                            CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            if (tiles.isNotEmpty()) {
                OutlinedButton(
                    onClick = ::estimateSelectedDownload,
                    enabled = downloadJob?.isActive != true &&
                        !isLookingUp && !isEstimatingDownload && selectedUrls.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("estimate_nys_mosaic_download"),
                ) {
                    if (isEstimatingDownload) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            isEstimatingDownload -> "Checking download size…"
                            downloadEstimate?.unknownTileCount == 0 ->
                                "Storage: ${formatBytesCompact(downloadEstimate?.knownBytes ?: 0L)}"
                            else -> "Estimate selected download"
                        },
                    )
                }
                Button(
                    onClick = ::downloadSelectedMosaic,
                    enabled = downloadJob?.isActive != true &&
                        !isEstimatingDownload && selectedUrls.isNotEmpty() &&
                        downloadEstimate?.unknownTileCount == 0,
                    modifier = Modifier.fillMaxWidth().height(54.dp).testTag("download_nys_mosaic"),
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download ${selectedUrls.size} tile${if (selectedUrls.size == 1) "" else "s"} and open mosaic")
                }
                if (downloadJob?.isActive == true) {
                    OutlinedButton(
                        onClick = { downloadJob?.cancel() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("Cancel mosaic download") }
                }
            }

            if (savedMosaicProjects.isNotEmpty()) {
                Text("Saved multi-tile projects", style = MaterialTheme.typography.titleMedium)
                savedMosaicProjects.forEach { project ->
                    val availableSourceCount = project.tiles.count { storedProjectFile(it) != null }
                    val canResume = MosaicProjectResume.canResume(project, availableSourceCount)
                    OutlinedButton(
                        onClick = { startOrResumeMosaicProject(project) },
                        enabled = downloadJob?.isActive != true,
                        modifier = Modifier.fillMaxWidth().height(
                            if (project.areaSelectionDescription.isNullOrBlank()) 76.dp else 96.dp,
                        ),
                    ) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                            Text(project.displayName, maxLines = 1)
                            Text(
                                "${availableSourceCount}/${project.tiles.size} source tile${if (project.tiles.size == 1) "" else "s"} ready · " +
                                    if (canResume) "tap to resume" else "tap to reopen",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (canResume && !project.recoveryMessage.isNullOrBlank()) {
                                Text(
                                    project.recoveryMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                            if (!project.areaSelectionDescription.isNullOrBlank()) {
                                Text(
                                    project.areaSelectionDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = { scope.launch { mosaicProjectDao.deleteById(project.id) } },
                        enabled = downloadJob?.isActive != true,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Remove project entry") }
                }
            }

            val activeDownloads = downloadTasks.filterNot(LazDownloadTask::isFinished)
            if (activeDownloads.isNotEmpty()) {
                Text(
                    "Background downloads",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "These keep running if you leave this screen or close the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                activeDownloads.forEach { task ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(task.displayName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when (task.state) {
                                        LazDownloadState.QUEUED -> "Waiting for the current download to finish"
                                        else -> task.fraction
                                            ?.let { "${(it * 100).toInt()}% of ${formatBytesCompact(task.totalBytes)}" }
                                            ?: "${formatBytesCompact(task.downloadedBytes)} downloaded"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = { LazDownloadService.cancel(context, task.url) },
                            ) { Text("Cancel") }
                        }
                        val fraction = task.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            val failedDownloads = downloadTasks.filter { it.state == LazDownloadState.FAILED }
            if (failedDownloads.isNotEmpty()) {
                Text(
                    "Failed downloads",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Retrying resumes from the bytes already on disk rather than starting the file over.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                failedDownloads.forEach { task ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(task.displayName, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                task.error ?: "Download failed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        TextButton(
                            onClick = {
                                error = null
                                status = "Retrying ${task.displayName}…"
                                LazDownloadService.enqueue(context, task.url, task.displayName)
                            },
                            modifier = Modifier.testTag("retry_failed_download"),
                        ) { Text("Retry") }
                        TextButton(onClick = { LazDownloadQueue.dismiss(task.url) }) { Text("Dismiss") }
                    }
                }
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}


private fun formatBytesCompact(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MiB", mib)
}

private const val METERS_PER_MILE = 1_609.344

/** Six decimals is roughly 0.1 m of longitude, finer than any tile footprint. */
private fun formatDegrees(value: Double): String = String.format(Locale.US, "%.6f", value)
