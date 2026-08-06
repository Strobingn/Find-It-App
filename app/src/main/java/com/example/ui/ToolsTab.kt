package com.example.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.FieldOfflineAssist
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.data.download.LazDownloadQueue
import com.example.data.export.ProjectArchiveImport
import com.example.data.export.QrSharePayload
import com.example.data.field.BoundaryProximityLevel
import com.example.data.field.FieldSessionStats
import com.example.data.field.FieldSessionStatsCalculator
import com.example.geospatial.DaylightPlanner
import com.example.geospatial.GeoSpatialLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.field.FindSiteClusterer
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home for the field-workflow features that otherwise only surface deep inside another tab:
 * every card shows live status and jumps straight to where the feature is used.
 */
@Composable
fun ToolsTab(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    aiViewModel: AiTerrainViewModel = viewModel(key = "ai_analysis_workspace"),
    onNavigate: (AppDestination) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedSignals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val plannedRoute by viewModel.plannedRoute.collectAsStateWithLifecycle()
    val breadcrumbTracks by viewModel.breadcrumbTracks.collectAsStateWithLifecycle()
    val excavationLogs by viewModel.excavationLogs.collectAsStateWithLifecycle()
    val surveyBoundaries by viewModel.surveyBoundaries.collectAsStateWithLifecycle()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    val activeTerrainKey by viewModel.activeTerrainKey.collectAsStateWithLifecycle()
    val activeGroundMode by viewModel.activeGroundMode.collectAsStateWithLifecycle()
    val activeClassPreset by viewModel.activeClassPreset.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val isReloadingSurface by viewModel.isReloadingSurface.collectAsStateWithLifecycle()
    val boundaryProximityAlert by viewModel.boundaryProximityAlert.collectAsStateWithLifecycle()
    val lastExportMessage by viewModel.lastExportMessage.collectAsStateWithLifecycle()
    val aiState by aiViewModel.state.collectAsStateWithLifecycle()

    val deviceLat by viewModel.deviceLatitude.collectAsStateWithLifecycle()
    val deviceLon by viewModel.deviceLongitude.collectAsStateWithLifecycle()
    val sites = remember(loggedSignals) { FindSiteClusterer.cluster(loggedSignals) }
    val sessionStats = remember(loggedSignals, breadcrumbTracks) {
        FieldSessionStatsCalculator.compute(loggedSignals, breadcrumbTracks)
    }

    var pendingSitePackageBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingClippedLasBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingGeoPackageBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingAnnotatedMapBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingQgisBundleBytes by remember { mutableStateOf(ByteArray(0)) }
    var pendingGeoTiffBytes by remember { mutableStateOf(ByteArray(0)) }
    var isExportingSitePackage by remember { mutableStateOf(false) }
    var sitePackageStatus by remember { mutableStateOf<String?>(null) }
    var geoPackageStatus by remember { mutableStateOf<String?>(null) }
    var annotatedMapStatus by remember { mutableStateOf<String?>(null) }
    var qgisExportStatus by remember { mutableStateOf<String?>(null) }
    var coverageGapStatus by remember { mutableStateOf<String?>(null) }
    var shareStatus by remember { mutableStateOf<String?>(null) }
    var qrPayloadText by remember { mutableStateOf<String?>(null) }
    var archiveInspectStatus by remember { mutableStateOf<String?>(null) }
    var archiveImportDialog by remember { mutableStateOf<String?>(null) }

    val sitePackageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            isExportingSitePackage = false
            sitePackageStatus = "Site package export canceled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingSitePackageBytes) }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            sitePackageStatus = lastExportMessage ?: "Site package zip saved"
        }.onFailure {
            sitePackageStatus = "Site package failed: ${it.localizedMessage}"
        }
        isExportingSitePackage = false
    }
    val clippedLasLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            sitePackageStatus = "Clipped LAS export canceled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingClippedLasBytes) }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            sitePackageStatus = "Clipped LAS surface sample saved"
        }.onFailure {
            sitePackageStatus = "Clipped LAS failed: ${it.localizedMessage}"
        }
    }
    val geoPackageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geopackage+sqlite"),
    ) { uri ->
        if (uri == null) {
            geoPackageStatus = "GeoPackage export canceled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingGeoPackageBytes) }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            geoPackageStatus = "GeoPackage saved (${pendingGeoPackageBytes.size} bytes)"
        }.onFailure {
            geoPackageStatus = "GeoPackage failed: ${it.localizedMessage}"
        }
    }
    val annotatedMapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            annotatedMapStatus = "Annotated map export canceled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingAnnotatedMapBytes) }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            annotatedMapStatus = "Annotated map bundle saved"
        }.onFailure {
            annotatedMapStatus = "Annotated map failed: ${it.localizedMessage}"
        }
    }
    val archiveInspectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            archiveInspectStatus = "Inspect canceled"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            archiveInspectStatus = "Inspecting archive…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val declaredLength = context.contentResolver
                        .openAssetFileDescriptor(uri, "r")
                        ?.use { it.declaredLength }
                        ?: -1L
                    if (ProjectArchiveImport.exceedsSizeCap(declaredLength)) {
                        return@runCatching ProjectArchiveImport.Result(
                            ok = false,
                            message = "Archive too large (max ${ProjectArchiveImport.MAX_ARCHIVE_BYTES / (1024 * 1024)} MB)",
                            manifestName = null,
                        )
                    }
                    val read = context.contentResolver.openInputStream(uri)?.use { input ->
                        ProjectArchiveImport.readBytesCapped(input, declaredLength)
                    } ?: return@runCatching ProjectArchiveImport.Result(
                        ok = false,
                        message = "Could not read the selected file",
                        manifestName = null,
                    )
                    val bytes = read.bytes
                        ?: return@runCatching ProjectArchiveImport.Result(
                            ok = false,
                            message = read.error ?: "Could not read the selected file",
                            manifestName = null,
                        )
                    ProjectArchiveImport.inspect(bytes)
                }
            }
            result.onSuccess { inspectResult ->
                archiveInspectStatus = inspectResult.message
            }.onFailure {
                archiveInspectStatus = "Inspect failed: ${it.localizedMessage}"
            }
        }
    }
    val archiveImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) {
            archiveInspectStatus = "Import canceled"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            archiveInspectStatus = "Extracting archive…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val declaredLength = context.contentResolver
                        .openAssetFileDescriptor(uri, "r")
                        ?.use { it.declaredLength }
                        ?: -1L
                    if (ProjectArchiveImport.exceedsSizeCap(declaredLength)) {
                        return@runCatching ProjectArchiveImport.Result(
                            ok = false,
                            message = "Archive too large (max ${ProjectArchiveImport.MAX_ARCHIVE_BYTES / (1024 * 1024)} MB)",
                            manifestName = null,
                        )
                    }
                    val read = context.contentResolver.openInputStream(uri)?.use { input ->
                        ProjectArchiveImport.readBytesCapped(input, declaredLength)
                    } ?: return@runCatching ProjectArchiveImport.Result(
                        ok = false,
                        message = "Could not read the selected file",
                        manifestName = null,
                    )
                    val bytes = read.bytes
                        ?: return@runCatching ProjectArchiveImport.Result(
                            ok = false,
                            message = read.error ?: "Could not read the selected file",
                            manifestName = null,
                        )
                    ProjectArchiveImport.applyIfSafe(bytes, context.filesDir)
                }
            }
            result.onSuccess { importResult ->
                archiveInspectStatus = importResult.message
                if (importResult.ok) {
                    archiveImportDialog =
                        "Extracted ${importResult.fileCount} file(s) to ${importResult.extractDirPath}. " +
                            "Open Library for LAZ. CSV/find merge is not automatic."
                }
            }.onFailure {
                archiveInspectStatus = "Import failed: ${it.localizedMessage}"
            }
        }
    }
    val qgisBundleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) {
            qgisExportStatus = "QGIS bundle export canceled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingQgisBundleBytes) }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            qgisExportStatus = "QGIS bundle saved (${pendingQgisBundleBytes.size} bytes)"
        }.onFailure {
            qgisExportStatus = "QGIS bundle failed: ${it.localizedMessage}"
        }
    }
    val geoTiffLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/tiff"),
    ) { uri ->
        if (uri == null) {
            qgisExportStatus = "GeoTIFF export canceled"
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(pendingGeoTiffBytes) }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            qgisExportStatus = "GeoTIFF saved (${pendingGeoTiffBytes.size} bytes)"
        }.onFailure {
            qgisExportStatus = "GeoTIFF failed: ${it.localizedMessage}"
        }
    }
    // Site center when georeferenced, live GPS fix otherwise; null hides the times gracefully.
    val daylightToday = remember(metadata, deviceLat, deviceLon) {
        val latLon = metadata.bounds?.let {
            ((it.minLat + it.maxLat) / 2.0) to ((it.minLon + it.maxLon) / 2.0)
        } ?: (deviceLat?.let { lat -> deviceLon?.let { lon -> lat to lon } })
        latLon?.let { (lat, lon) ->
            val now = Calendar.getInstance()
            val offsetMinutes = TimeZone.getDefault().getOffset(now.timeInMillis) / 60_000
            Triple(
                DaylightPlanner.compute(lat, lon, now.get(Calendar.DAY_OF_YEAR)),
                offsetMinutes,
                metadata.bounds != null,
            )
        }
    }
    val terrainReady = grid.width > 2 && grid.height > 2
    val recordedPoints = breadcrumbTracks.sumOf { it.points.size }
    val positionedFinds = loggedSignals.count { it.latitude != null && it.longitude != null }
    val openDigs = excavationLogs.count { !it.isComplete }
    val completedDigs = excavationLogs.count { it.isComplete }
    val savedLidarCount = remember(context) { LazDownloadQueue.store(context).list().size }
    val lidarFolderPath = remember(context) { LazDownloadQueue.store(context).directory.absolutePath }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("tools_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Every field feature, one card each — status plus a shortcut to where it runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ToolCard(
                icon = Icons.Default.Folder,
                title = "Saved LiDAR downloads",
                status = if (savedLidarCount == 0) {
                    "No files yet"
                } else {
                    "$savedLidarCount file(s) · rename on Import"
                },
                statusActive = savedLidarCount > 0,
                description = "App-private folder for every downloaded or imported LAZ/LAS. " +
                    "Path: $lidarFolderPath. Open the Import tab to reopen, rename for future reuse, or delete.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.LIBRARY) },
                    modifier = Modifier.testTag("tool_open_saved_lidar"),
                ) { Text("Open Import library") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.GridOn,
                title = "Search coverage",
                status = if (recordedPoints > 0) {
                    "${breadcrumbTracks.size} trail(s) · $recordedPoints GPS points recorded"
                } else {
                    "No GPS trails yet"
                },
                statusActive = recordedPoints > 0,
                description = "Paints a sweep-coverage grid over the site map from your GPS " +
                    "breadcrumb trails, with an adjustable sweep width (1–4 m) to match your coil.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.MAP) },
                    modifier = Modifier.testTag("tool_open_coverage"),
                ) { Text("Open map") }
            }
        }
        item {
            val candidateCount = aiState.localResult?.candidates?.size ?: 0
            ToolCard(
                icon = Icons.Default.MyLocation,
                title = "Coverage gap targets",
                status = coverageGapStatus ?: when {
                    candidateCount > 0 ->
                        "$candidateCount local candidate(s) · $recordedPoints trail pts · offline gaps"
                    recordedPoints > 0 ->
                        "No local analysis yet · $recordedPoints trail pts on map"
                    else ->
                        "Run AI local analysis or record GPS trails"
                },
                statusActive = candidateCount > 0 || recordedPoints > 0,
                description = "Offline draft of unverified gap targets from high-score terrain " +
                    "candidates away from logged finds (FieldOfflineAssist). Creates map markers " +
                    "when candidates exist; otherwise opens the map sweep-coverage view from trail density. " +
                    "LiDAR does not prove buried metal.",
            ) {
                TextButton(
                    onClick = {
                        val candidates = aiState.localResult?.candidates.orEmpty()
                        if (candidates.isEmpty()) {
                            coverageGapStatus = if (recordedPoints > 0) {
                                "No candidates — open map for trail density coverage"
                            } else {
                                "Run local analysis on the AI tab first"
                            }
                            onNavigate(AppDestination.MAP)
                            return@TextButton
                        }
                        val (text, gaps) = FieldOfflineAssist.coverageGapTargets(
                            candidates = candidates,
                            breadcrumbTracks = breadcrumbTracks,
                            signals = loggedSignals,
                        )
                        if (gaps.isEmpty()) {
                            coverageGapStatus = text.lineSequence().firstOrNull()
                                ?: "No gap targets selected"
                            return@TextButton
                        }
                        gaps.forEach { gap ->
                            val coordinate = GeoSpatialLibrary.gridToGeographic(
                                gap.xPercent,
                                gap.yPercent,
                                metadata,
                            )
                            viewModel.updateLoggedSignal(
                                TargetSignal(
                                    gridX = gap.xPercent.coerceIn(0f, 100f),
                                    gridY = gap.yPercent.coerceIn(0f, 100f),
                                    metalType = MetalType.MANUAL_MARKER,
                                    signalStrength = (gap.confidence * 100f).coerceIn(0f, 100f),
                                    latitude = coordinate?.first,
                                    longitude = coordinate?.second,
                                    source = DetectionSource.AI_ANALYSIS,
                                    notes = gap.label,
                                    status = "Coverage gap",
                                    datasetKey = aiState.localResult?.datasetKey,
                                    terrainKey = activeTerrainKey,
                                ),
                            )
                        }
                        coverageGapStatus = "Placed ${gaps.size} gap marker(s) on map"
                        onNavigate(AppDestination.MAP)
                    },
                    modifier = Modifier.testTag("tool_coverage_gap_targets"),
                ) { Text("Coverage gap targets") }
                TextButton(
                    onClick = { onNavigate(AppDestination.MAP) },
                    modifier = Modifier.testTag("tool_coverage_gap_open_map"),
                ) { Text("Open map") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Route,
                title = "Optimal target route",
                status = plannedRoute?.let {
                    "Active · ${it.waypoints.size} stops · ${routeDistanceText(it.totalDistanceMeters)}"
                } ?: "No route planned",
                statusActive = plannedRoute != null,
                description = "Orders your GPS-logged targets into the shortest walking route " +
                    "(nearest-neighbor + 2-opt) and draws it as a polyline on the map.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                    modifier = Modifier.testTag("tool_open_route"),
                ) { Text("Plan in targets") }
                if (plannedRoute != null) {
                    TextButton(
                        onClick = { viewModel.setPlannedRoute(null) },
                        modifier = Modifier.testTag("tool_clear_route"),
                    ) { Text("Clear route") }
                }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Hub,
                title = "Site clustering",
                status = if (sites.isNotEmpty()) {
                    "${sites.size} site(s) from $positionedFinds positioned finds"
                } else {
                    "No sites yet"
                },
                statusActive = sites.isNotEmpty(),
                description = "Groups finds within 50 m of each other into named sites " +
                    "(cellar-hole scatter, home lot, campsite) with confirmed/rejected counts " +
                    "and the most common find types.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                    modifier = Modifier.testTag("tool_open_sites"),
                ) { Text("Open finds") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Insights,
                title = "Field session stats",
                status = if (sessionStats.totalFinds == 0 && sessionStats.distanceMeters < 1.0) {
                    "Nothing logged yet"
                } else {
                    buildString {
                        append("${sessionStats.totalFinds} find(s)")
                        sessionStats.confirmRate?.let {
                            append(" · ${(it * 100).toInt()}% confirmed")
                        }
                        append(" · ${routeDistanceText(sessionStats.distanceMeters)} walked")
                        sessionStats.findsPerHour?.let { append(" · ${"%.1f".format(it)} finds/h") }
                    }
                },
                statusActive = sessionStats.totalFinds > 0,
                description = "Live dig-day scoreboard from your logged finds and GPS trails: " +
                    "totals, confirm/reject split, distance covered, logging pace" +
                    (sessionStats.topFindType?.let { ", and your most common find ($it)." } ?: ".") +
                    " Share builds a plain-text day debrief for the system share sheet.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                    modifier = Modifier.testTag("tool_open_stats"),
                ) { Text("Open targets") }
                TextButton(
                    onClick = {
                        val text = buildSessionDebriefText(
                            projectName = metadata.siteName,
                            stats = sessionStats,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Find It session debrief")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share session debrief"))
                    },
                    modifier = Modifier.testTag("tool_session_debrief_share"),
                ) { Text("Share debrief") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.WbSunny,
                title = "Real-sun lighting",
                status = if (terrainReady && metadata.isGeoreferenced) {
                    "Ready · ${metadata.siteName}"
                } else {
                    "Needs a georeferenced terrain"
                },
                statusActive = terrainReady && metadata.isGeoreferenced,
                description = "Renders the hillshade with the true sun azimuth and altitude for " +
                    "any date and time at the site's real coordinates — preview how shadows fall " +
                    "before you're on site.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.TERRAIN) },
                    modifier = Modifier.testTag("tool_open_sun"),
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.WbTwilight,
                title = "Daylight planner",
                status = daylightToday?.let { (window, offset, _) ->
                    if (window.isPolar) {
                        "Polar day/night at this location"
                    } else {
                        val rise = DaylightPlanner.formatLocal(window.sunriseUtcMinutes!!, offset)
                        val set = DaylightPlanner.formatLocal(window.sunsetUtcMinutes!!, offset)
                        val hours = window.dayLengthMinutes!! / 60f
                        "Up $rise · down $set · ${"%.1f".format(hours)} h of light"
                    }
                } ?: "Needs a georeferenced terrain or GPS fix",
                statusActive = daylightToday != null,
                description = "Sunrise, sunset, and total usable light for today at the site " +
                    (if (daylightToday?.third == true) "center (from the terrain georeference). " else "position (from your GPS fix). ") +
                    "NOAA solar math, fully offline — plan the hunt around the light, not the clock.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.TERRAIN) },
                    modifier = Modifier.testTag("tool_open_daylight"),
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Visibility,
                title = "Viewshed",
                status = if (terrainReady) {
                    "Ready · tap any terrain cell to analyze"
                } else {
                    "Load a terrain first"
                },
                statusActive = terrainReady,
                description = "Computes everything visible from a tapped cell — visible areas " +
                    "burn green into the hillshade, blocked areas go dark, with the observer " +
                    "marked in blue.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.TERRAIN) },
                    modifier = Modifier.testTag("tool_open_viewshed"),
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Edit,
                title = "Excavation logs",
                status = when {
                    excavationLogs.isEmpty() -> "No dig logs yet"
                    openDigs > 0 -> "$openDigs open · $completedDigs completed"
                    else -> "$completedDigs completed dig(s)"
                },
                statusActive = excavationLogs.isNotEmpty(),
                description = "Per-target dig records with depth, soil notes, and finds. " +
                    "Open a find on the Targets tab, then start a dig log. Survives offline restarts.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                    modifier = Modifier.testTag("tool_open_excavation"),
                ) { Text("Open targets") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.CropFree,
                title = "Survey boundaries",
                status = if (surveyBoundaries.isEmpty()) {
                    "No boundaries on this project"
                } else {
                    "${surveyBoundaries.size} boundary polygon(s)"
                },
                statusActive = surveyBoundaries.isNotEmpty(),
                description = "Project search areas from a GPS trail (≥3 points) or a 100 m box " +
                    "around your current fix. Used to keep field work inside permitted ground.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                    modifier = Modifier.testTag("tool_open_boundaries"),
                ) { Text("Open targets") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Sync,
                title = "Offline sync queue",
                status = if (pendingSyncCount == 0) {
                    "Queue empty · local only"
                } else {
                    "$pendingSyncCount change(s) waiting"
                },
                statusActive = pendingSyncCount > 0,
                description = "Every target, dig log, trail, and boundary change is coalesced " +
                    "into an ordered offline queue (delete-wins, no silent drops). Conflict " +
                    "resolver ready · cloud sync not started (Phase 9).",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                    modifier = Modifier.testTag("tool_open_sync"),
                ) { Text("Open targets") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.History,
                title = "Historic map georeference",
                status = "Map tab · control points + swipe + side-by-side",
                statusActive = true,
                description = "Import a scanned plat, set control points (image crosshair + map tap), " +
                    "fit an affine transform with visible confidence/RMSE, blend with swipe, or " +
                    "compare side-by-side. Fits persist to Room historic_maps.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.MAP) },
                    modifier = Modifier.testTag("tool_open_historic_georef"),
                ) { Text("Open map") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Layers,
                title = "Dual surface / class filter",
                status = "${activeGroundMode.name} · ${activeClassPreset.label}",
                statusActive = canRefine,
                description = "Classified ground vs auto-lowest vs highest-return DSM, plus ASPRS class " +
                    "filter chips. Re-decodes the open LAZ — terrain geometry only, not metal.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.TERRAIN) },
                    modifier = Modifier.testTag("tool_dual_surface"),
                ) { Text("Open terrain controls") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.CropFree,
                title = "Clip refine to boundary",
                status = when {
                    surveyBoundaries.isEmpty() -> "No survey boundary yet"
                    isRefining -> "Refining…"
                    else -> "${surveyBoundaries.size} boundary polygon(s)"
                },
                statusActive = surveyBoundaries.isNotEmpty() && canRefine,
                description = "Re-rasterize the open LiDAR into the survey boundary footprint so detail " +
                    "stays on permitted ground. Requires a georeferenced LAZ and a boundary.",
            ) {
                TextButton(
                    onClick = { viewModel.refineToSurveyBoundary() },
                    enabled = canRefine && surveyBoundaries.isNotEmpty() && !isRefining && !isReloadingSurface,
                    modifier = Modifier.testTag("tool_refine_boundary"),
                ) { Text("Refine to boundary") }
                TextButton(
                    onClick = { onNavigate(AppDestination.TERRAIN) },
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Archive,
                title = "Site package export",
                status = when {
                    isExportingSitePackage -> "Building package…"
                    shareStatus != null -> shareStatus!!
                    sitePackageStatus != null -> sitePackageStatus!!
                    else -> "Export zip with finds, digs, trails, PDF, clipped LAS"
                },
                statusActive = !isExportingSitePackage,
                description = "One offline zip: summary, targets (CSV/GPX/GeoJSON), digs, boundaries, " +
                    "trails, annotated PNG/PDF when available, and optional clipped LAS surface sample. " +
                    "LAS is a surface sample, not original pulse returns. Share uses the system sheet " +
                    "(FileProvider) without auto-import on the other device.",
            ) {
                TextButton(
                    onClick = {
                        if (isExportingSitePackage) return@TextButton
                        isExportingSitePackage = true
                        sitePackageStatus = "Building site package…"
                        scope.launch {
                            runCatching { viewModel.buildSitePackageBytes(includeClippedLas = true) }
                                .onSuccess { bytes ->
                                    pendingSitePackageBytes = bytes
                                    sitePackageLauncher.launch(
                                        "find-it-site-package-${System.currentTimeMillis()}.zip",
                                    )
                                }
                                .onFailure {
                                    isExportingSitePackage = false
                                    sitePackageStatus = "Export failed: ${it.localizedMessage}"
                                }
                        }
                    },
                    enabled = !isExportingSitePackage,
                    modifier = Modifier.testTag("tool_site_package_export"),
                ) { Text("Export site package") }
                TextButton(
                    onClick = {
                        if (isExportingSitePackage) return@TextButton
                        isExportingSitePackage = true
                        shareStatus = "Building package for share…"
                        scope.launch {
                            runCatching { viewModel.buildSitePackageBytes(includeClippedLas = true) }
                                .onSuccess { bytes ->
                                    pendingSitePackageBytes = bytes
                                    runCatching {
                                        shareBytesViaFileProvider(
                                            context = context,
                                            bytes = bytes,
                                            fileName = "find-it-site-package-${System.currentTimeMillis()}.zip",
                                            mimeType = "application/zip",
                                            chooserTitle = "Share site package",
                                        )
                                    }.onSuccess {
                                        shareStatus = "Share sheet opened (${bytes.size} bytes)"
                                    }.onFailure {
                                        shareStatus = "Share failed: ${it.localizedMessage}"
                                    }
                                }
                                .onFailure {
                                    shareStatus = "Share build failed: ${it.localizedMessage}"
                                }
                            isExportingSitePackage = false
                        }
                    },
                    enabled = !isExportingSitePackage,
                    modifier = Modifier.testTag("tool_site_package_share"),
                ) { Text("Share package") }
                TextButton(
                    onClick = {
                        runCatching { viewModel.buildClippedLasBytes() }
                            .onSuccess { bytes ->
                                pendingClippedLasBytes = bytes
                                clippedLasLauncher.launch(
                                    "find-it-clipped-${System.currentTimeMillis()}.las",
                                )
                            }
                            .onFailure {
                                sitePackageStatus = "Clipped LAS failed: ${it.localizedMessage}"
                            }
                    },
                ) { Text("Clipped LAS only") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Layers,
                title = "GeoPackage export",
                status = geoPackageStatus
                    ?: if (loggedSignals.isEmpty()) {
                        "No finds yet · empty .gpkg still valid"
                    } else {
                        "${loggedSignals.size} find(s) ready"
                    },
                statusActive = true,
                description = "Minimal SQLite GeoPackage with a finds table (id, lat, lon, metal, " +
                    "status, notes) plus gpkg_contents. Attribute handoff only — not metal proof " +
                    "from LiDAR.",
            ) {
                TextButton(
                    onClick = {
                        geoPackageStatus = "Building GeoPackage…"
                        scope.launch {
                            runCatching { viewModel.buildGeoPackageBytes() }
                                .onSuccess { bytes ->
                                    pendingGeoPackageBytes = bytes
                                    geoPackageLauncher.launch(
                                        "find-it-finds-${System.currentTimeMillis()}.gpkg",
                                    )
                                }
                                .onFailure {
                                    geoPackageStatus = "GeoPackage failed: ${it.localizedMessage}"
                                }
                        }
                    },
                    modifier = Modifier.testTag("tool_geopackage_export"),
                ) { Text("Export GeoPackage") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Map,
                title = "Annotated map bundle",
                status = annotatedMapStatus
                    ?: if (terrainReady) "Ready · PNG + README zip" else "Load terrain first",
                statusActive = terrainReady,
                description = "Zip of the annotated terrain PNG (targets overlaid) plus README with " +
                    "ethics and LiDAR honesty. Built from the same project export renderer.",
            ) {
                TextButton(
                    onClick = {
                        if (!terrainReady) {
                            annotatedMapStatus = "Load a terrain first"
                            return@TextButton
                        }
                        annotatedMapStatus = "Building annotated map…"
                        scope.launch {
                            runCatching { viewModel.buildAnnotatedMapBundleBytes() }
                                .onSuccess { bytes ->
                                    pendingAnnotatedMapBytes = bytes
                                    annotatedMapLauncher.launch(
                                        "find-it-annotated-map-${System.currentTimeMillis()}.zip",
                                    )
                                }
                                .onFailure {
                                    annotatedMapStatus = "Failed: ${it.localizedMessage}"
                                }
                        }
                    },
                    enabled = terrainReady,
                    modifier = Modifier.testTag("tool_annotated_map_export"),
                ) { Text("Export map zip") }
            }
        }
        item {
            val georefReady = terrainReady && metadata.isGeoreferenced
            ToolCard(
                icon = Icons.Default.Layers,
                title = "QGIS / GeoTIFF export",
                status = qgisExportStatus ?: when {
                    !terrainReady -> "Load a terrain first"
                    !metadata.isGeoreferenced -> "Needs georeferenced terrain (real bounds)"
                    else -> "Ready · GeoTIFF + shapefile targets + .qgs"
                },
                statusActive = georefReady,
                description = "QGIS-ready zip (bare-earth GeoTIFF, find shapefile, project.qgs) for " +
                    "desktop GIS. Standalone GeoTIFF is the same elevation raster without the " +
                    "vector/project extras. Local grids without bounds cannot be exported safely.",
            ) {
                TextButton(
                    onClick = {
                        if (!georefReady) {
                            qgisExportStatus = "Needs a georeferenced terrain with bounds"
                            return@TextButton
                        }
                        qgisExportStatus = "Building QGIS bundle…"
                        scope.launch {
                            runCatching { viewModel.buildQgisBundleBytes() }
                                .onSuccess { bytes ->
                                    if (bytes == null) {
                                        qgisExportStatus =
                                            "This terrain has no geographic bounds, so a GeoTIFF/QGIS bundle cannot be placed safely."
                                    } else {
                                        pendingQgisBundleBytes = bytes
                                        qgisBundleLauncher.launch(
                                            "find-it-qgis-bundle-${System.currentTimeMillis()}.zip",
                                        )
                                    }
                                }
                                .onFailure {
                                    qgisExportStatus = "QGIS bundle failed: ${it.localizedMessage}"
                                }
                        }
                    },
                    enabled = georefReady,
                    modifier = Modifier.testTag("tool_qgis_bundle_export"),
                ) { Text("Export QGIS bundle") }
                TextButton(
                    onClick = {
                        if (!georefReady) {
                            qgisExportStatus = "Needs a georeferenced terrain with bounds"
                            return@TextButton
                        }
                        qgisExportStatus = "Building GeoTIFF…"
                        scope.launch {
                            runCatching { viewModel.buildGeoTiffBytes() }
                                .onSuccess { bytes ->
                                    if (bytes == null) {
                                        qgisExportStatus =
                                            "This terrain has no geographic bounds, so a GeoTIFF cannot be placed safely."
                                    } else {
                                        pendingGeoTiffBytes = bytes
                                        geoTiffLauncher.launch(
                                            "find-it-terrain-${System.currentTimeMillis()}.tif",
                                        )
                                    }
                                }
                                .onFailure {
                                    qgisExportStatus = "GeoTIFF failed: ${it.localizedMessage}"
                                }
                        }
                    },
                    enabled = georefReady,
                    modifier = Modifier.testTag("tool_geotiff_export"),
                ) { Text("Export GeoTIFF") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.QrCode,
                title = "QR / archive handoff",
                status = qrPayloadText?.lines()?.take(2)?.joinToString(" · ")
                    ?: archiveInspectStatus
                    ?: "Build QR text for package hash, or inspect a zip",
                statusActive = qrPayloadText != null || archiveInspectStatus != null,
                description = "QR payloads never embed the full zip — large archives emit SHARE_FILE " +
                    "with name/size/sha256. Inspect validates a Find It manifest; import extracts " +
                    "files only (no auto Room merge).",
            ) {
                TextButton(
                    onClick = {
                        shareStatus = "Building package for QR meta…"
                        scope.launch {
                            runCatching { viewModel.buildSitePackageBytes(includeClippedLas = false) }
                                .onSuccess { bytes ->
                                    val hash = withContext(Dispatchers.Default) {
                                        QrSharePayload.sha256Hex(bytes)
                                    }
                                    qrPayloadText = QrSharePayload.forProject(
                                        projectName = metadata.siteName,
                                        archiveByteSize = bytes.size,
                                        contentHash = hash,
                                    )
                                    shareStatus = "QR payload ready (${bytes.size} bytes)"
                                }
                                .onFailure {
                                    shareStatus = "QR build failed: ${it.localizedMessage}"
                                }
                        }
                    },
                    modifier = Modifier.testTag("tool_qr_payload"),
                ) { Text("Build QR text") }
                qrPayloadText?.let { payload ->
                    // ZXing not on classpath — large monospace card for external QR apps.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("tool_qr_payload_display"),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "Scan/share text as QR via any QR app",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                payload,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, payload)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share QR payload"))
                        },
                        modifier = Modifier.testTag("tool_qr_payload_share"),
                    ) { Text("Share text") }
                }
                TextButton(
                    onClick = {
                        archiveInspectLauncher.launch(
                            arrayOf("application/zip", "application/octet-stream", "*/*"),
                        )
                    },
                    modifier = Modifier.testTag("tool_archive_inspect"),
                ) { Text("Inspect archive") }
                TextButton(
                    onClick = { archiveImportLauncher.launch("application/zip") },
                    modifier = Modifier.testTag("tool_archive_import"),
                ) { Text("Import archive (files only)") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Warning,
                title = "Boundary GPS alert",
                status = boundaryProximityAlert?.message
                    ?: "Enable GPS near a survey boundary for edge alerts",
                statusActive = boundaryProximityAlert?.level == BoundaryProximityLevel.NEAR_EDGE ||
                    boundaryProximityAlert?.level == BoundaryProximityLevel.OUTSIDE,
                description = "Live GPS vs survey polygons: inside, near edge (~25 m), or outside. " +
                    "Helps keep field work on permitted ground — not legal ownership proof.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.TERRAIN) },
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Terrain,
                title = "Surface Z under finds",
                status = "Open finds to see relative Z under each georeferenced find",
                statusActive = terrainReady && metadata.isGeoreferenced,
                description = "Relative bare-earth ΔZ and slope bucket under a find’s lat/lon. " +
                    "Relative surface context only — not buried-object or dig depth.",
            ) {
                TextButton(
                    onClick = { onNavigate(AppDestination.FIELD) },
                ) { Text("Open finds") }
            }
        }
    }

    archiveImportDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { archiveImportDialog = null },
            title = { Text("Archive extracted") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        archiveImportDialog = null
                        onNavigate(AppDestination.LIBRARY)
                    },
                    modifier = Modifier.testTag("tool_archive_import_open_library"),
                ) { Text("Open Library for LAZ") }
            },
            dismissButton = {
                TextButton(onClick = { archiveImportDialog = null }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: String,
    status: String,
    statusActive: Boolean,
    description: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (statusActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

private fun routeDistanceText(totalMeters: Double): String =
    if (totalMeters >= 1000.0) {
        String.format(Locale.US, "%.2f km", totalMeters / 1000.0)
    } else {
        "${totalMeters.toInt()} m"
    }

private fun buildSessionDebriefText(projectName: String, stats: FieldSessionStats): String =
    buildString {
        appendLine("Find It session debrief")
        appendLine("Project: ${projectName.ifBlank { "(unnamed)" }}")
        appendLine()
        appendLine("Finds: ${stats.totalFinds} (${stats.positionedFinds} with GPS)")
        appendLine("Confirmed: ${stats.confirmedFinds}")
        appendLine("Rejected: ${stats.rejectedFinds}")
        stats.confirmRate?.let {
            appendLine("Confirm rate: ${"%.0f".format(Locale.US, it * 100)}%")
        }
        appendLine("Distance walked: ${routeDistanceText(stats.distanceMeters)}")
        stats.activeMinutes?.let { appendLine("Active span: ${it} min") }
        stats.findsPerHour?.let {
            appendLine("Pace: ${"%.1f".format(Locale.US, it)} finds/h")
        }
        stats.topFindType?.let { appendLine("Top type: $it") }
        appendLine()
        appendLine(
            "LiDAR is terrain context only — not metal identity or dig depth. " +
                "Only detect on land you have permission to search.",
        )
    }

private fun shareBytesViaFileProvider(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mimeType: String,
    chooserTitle: String,
) {
    val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
    val outFile = File(shareDir, fileName)
    outFile.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        outFile,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, chooserTitle))
}

// Tab indices in MainScreen's tab list.
