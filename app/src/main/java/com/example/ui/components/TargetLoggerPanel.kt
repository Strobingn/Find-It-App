package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.LogSignalResult
import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import com.example.data.field.BoundaryVertex
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.ExcavationLogEntry
import com.example.data.field.FieldNavigation
import com.example.data.field.FieldWaypoint
import com.example.data.field.FindSiteClusterer
import com.example.data.field.OptimizedFieldRoute
import com.example.data.field.PendingSyncEntry
import com.example.data.field.SurveyBoundary
import com.example.data.field.TargetRouteOptimizer
import com.example.data.field.VoiceNoteRecorder
import com.example.data.field.createVoiceNoteFile
import com.example.data.field.deleteVoiceNoteFile
import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.trueToMagneticBearingDegrees
import com.example.data.export.buildCsv
import com.example.data.export.buildGeoJson
import com.example.data.export.buildGpx
import com.example.data.export.buildKml
import com.example.data.export.buildKmz
import com.example.data.export.buildShapefileZip
import com.example.data.export.ProjectExportFiles
import com.example.geospatial.MeasurementFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.cos

@Composable
fun TargetLoggerPanel(
    loggedSignals: List<TargetSignal>,
    currentSweepX: Float,
    currentSweepY: Float,
    breadcrumbTracks: List<BreadcrumbTrack>,
    isBreadcrumbRecording: Boolean,
    excavationLogs: List<ExcavationLogEntry> = emptyList(),
    surveyBoundaries: List<SurveyBoundary> = emptyList(),
    pendingSyncEntries: List<PendingSyncEntry> = emptyList(),
    gpsEnabled: Boolean,
    deviceLatitude: Double?,
    deviceLongitude: Double?,
    deviceAccuracyMeters: Float?,
    compassHeadingDegrees: Float?,
    onEnableGps: () -> Unit,
    onSetCompassNavigationActive: (Boolean) -> Unit,
    onStartBreadcrumb: () -> Unit,
    onPauseBreadcrumb: () -> Unit,
    onClearBreadcrumbs: () -> Unit,
    /** Logs current sweep; pass force=true after user confirms a proximity warning. */
    onLogSignal: (forceDespiteProximity: Boolean) -> LogSignalResult,
    onDeleteSignal: (TargetSignal) -> Unit,
    onUpdateSignal: (TargetSignal) -> Unit,
    onToggleStarred: (TargetSignal) -> Unit = {},
    onClearAll: () -> Unit,
    onBuildProjectExport: suspend () -> ProjectExportFiles,
    onBuildQgisBundle: suspend () -> ByteArray? = { null },
    onBuildProjectArchive: suspend () -> ByteArray = { ByteArray(0) },
    onRoutePlanned: (OptimizedFieldRoute?) -> Unit = {},
    onSaveExcavationLog: (ExcavationLogEntry) -> Unit = {},
    onDeleteExcavationLog: (ExcavationLogEntry) -> Unit = {},
    onStartExcavationLog: (Long) -> ExcavationLogEntry? = { null },
    onCreateBoundaryFromTrail: (BreadcrumbTrack) -> Unit = {},
    onCreateBoundaryAroundGps: () -> Unit = {},
    onDeleteSurveyBoundary: (SurveyBoundary) -> Unit = {},
    onMarkSyncSent: (Long) -> Unit = {},
    onClearSyncQueue: () -> Unit = {},
    /** Ordered signal ids from AI NAV_TARGET tags (shared AiTerrainViewModel). */
    pendingNavTargetIds: List<Long> = emptyList(),
    /** Clears pending NAV_TARGET (and other structured tags) after navigation starts. */
    onConsumeNavTargets: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingSignal by remember { mutableStateOf<TargetSignal?>(null) }
    var digLogSignal by remember { mutableStateOf<TargetSignal?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var showProjectExport by remember { mutableStateOf(false) }
    var isBuildingProjectExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearBreadcrumbs by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf("") }
    var pendingGpx by remember { mutableStateOf("") }
    var pendingKml by remember { mutableStateOf("") }
    var pendingGeoJson by remember { mutableStateOf("") }
    var pendingBinaryBytes by remember { mutableStateOf(ByteArray(0)) }
    var binarySuccessMessage by remember { mutableStateOf("") }
    var pendingProjectBytes by remember { mutableStateOf(ByteArray(0)) }
    var navigationTarget by remember { mutableStateOf<TargetSignal?>(null) }
    var plannedRoute by remember { mutableStateOf<OptimizedFieldRoute?>(null) }
    var pendingProximity by remember { mutableStateOf<LogSignalResult?>(null) }
    var logMessage by remember { mutableStateOf<String?>(null) }
    val routeStopCount = loggedSignals.count { it.latitude != null && it.longitude != null }

    fun attemptLog(force: Boolean = false) {
        val result = onLogSignal(force)
        if (result.nearbyFind != null && !force) {
            pendingProximity = result
            return
        }
        pendingProximity = null
        logMessage = "Logged ${result.signal.metalType.label}"
    }
    val planRoute: () -> Unit = {
        scope.launch {
            val waypoints = loggedSignals.mapNotNull { signal ->
                val latitude = signal.latitude ?: return@mapNotNull null
                val longitude = signal.longitude ?: return@mapNotNull null
                FieldWaypoint(signal.id.toString(), latitude, longitude, signal.metalType.label)
            }
            plannedRoute = withContext(Dispatchers.Default) {
                TargetRouteOptimizer.optimize(waypoints, deviceLatitude, deviceLongitude)
            }
            onRoutePlanned(plannedRoute)
        }
    }

    LaunchedEffect(navigationTarget?.id) {
        onSetCompassNavigationActive(navigationTarget != null)
    }
    LaunchedEffect(loggedSignals) {
        navigationTarget?.let { active ->
            if (loggedSignals.none { it.id == active.id }) navigationTarget = null
        }
    }
    // Apply first AI NAV_TARGET that matches a geo-located find; consume so it is not re-applied.
    LaunchedEffect(pendingNavTargetIds, loggedSignals) {
        val firstId = pendingNavTargetIds.firstOrNull() ?: return@LaunchedEffect
        val match = loggedSignals.firstOrNull { it.id == firstId }
        if (match != null && match.latitude != null && match.longitude != null) {
            navigationTarget = match
            onConsumeNavTargets()
        }
    }
    DisposableEffect(Unit) {
        onDispose { onSetCompassNavigationActive(false) }
    }

    val terrainImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingProjectBytes) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "Full terrain image saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val projectPdfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingProjectBytes) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "PDF field report saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val projectZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingProjectBytes) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "Archive saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }

    val buildProjectExport: (Boolean) -> Unit = { pdf ->
        showProjectExport = false
        isBuildingProjectExport = true
        exportMessage = if (pdf) "Building full PDF report…" else "Building full terrain image…"
        scope.launch {
            runCatching { onBuildProjectExport() }
                .onSuccess { files ->
                    pendingProjectBytes = if (pdf) files.reportPdf else files.terrainPng
                    if (pdf) {
                        projectPdfLauncher.launch("${files.fileStem}-field-report.pdf")
                    } else {
                        terrainImageLauncher.launch("${files.fileStem}-terrain.png")
                    }
                }
                .onFailure { exportMessage = "Export failed: ${it.localizedMessage}" }
            isBuildingProjectExport = false
        }
    }

    val buildQgisBundleExport: () -> Unit = {
        showProjectExport = false
        isBuildingProjectExport = true
        exportMessage = "Building QGIS bundle..."
        scope.launch {
            runCatching { onBuildQgisBundle() }
                .onSuccess { bytes ->
                    if (bytes == null) {
                        exportMessage = "This terrain has no geographic bounds, so a GeoTIFF/QGIS bundle cannot be placed safely."
                    } else {
                        pendingProjectBytes = bytes
                        projectZipLauncher.launch("find-it-qgis-bundle.zip")
                    }
                }
                .onFailure { exportMessage = "Export failed: ${it.localizedMessage}" }
            isBuildingProjectExport = false
        }
    }

    val buildArchiveExport: () -> Unit = {
        showProjectExport = false
        isBuildingProjectExport = true
        exportMessage = "Building portable project archive..."
        scope.launch {
            runCatching { onBuildProjectArchive() }
                .onSuccess { bytes ->
                    pendingProjectBytes = bytes
                    projectZipLauncher.launch("find-it-project-archive.zip")
                }
                .onFailure { exportMessage = "Export failed: ${it.localizedMessage}" }
            isBuildingProjectExport = false
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingCsv) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "CSV saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingGpx) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "GPX saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val kmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingKml) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "KML saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val shapefileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingBinaryBytes) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = binarySuccessMessage }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val kmzLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kmz"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingBinaryBytes) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = binarySuccessMessage }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val geoJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingGeoJson) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "GeoJSON saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Field finds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Current grid position: ${currentSweepX.toInt()}, ${currentSweepY.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ethics_dig_disclaimer"),
                ) {
                    Text(
                        "Ethics: only detect on land you have permission to search. " +
                            "LiDAR does not prove buried metal or ownership. " +
                            "Verify laws and access before digging.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                if (pendingNavTargetIds.isNotEmpty()) {
                    val matched = loggedSignals.any { it.id == pendingNavTargetIds.first() && it.latitude != null }
                    if (!matched) {
                        Text(
                            "AI navigation pending: ${pendingNavTargetIds.size} stop(s) — " +
                                "no matching geo-located find yet for id ${pendingNavTargetIds.first()}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("ai_nav_pending_unmatched"),
                        )
                    }
                }
                Button(
                    onClick = { attemptLog(force = false) },
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("log_signal_button"),
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Log current position")
                }
                logMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(
                    onClick = { showProjectExport = true },
                    enabled = !isBuildingProjectExport,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("project_export_button"),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isBuildingProjectExport) "Building export…" else "Export terrain and report")
                }
                OutlinedButton(
                    onClick = planRoute,
                    enabled = routeStopCount >= 2,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("plan_route_button"),
                ) {
                    Icon(Icons.Default.Route, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Plan target route · $routeStopCount stops")
                }
            }
        }

        SitesCard(loggedSignals)

        plannedRoute?.let { route ->
            PlannedRouteCard(
                route = route,
                signals = loggedSignals,
                onNavigate = { navigationTarget = it },
                onDismiss = {
                    plannedRoute = null
                    onRoutePlanned(null)
                },
            )
        }

        val recordedBreadcrumbPoints = breadcrumbTracks.sumOf { it.points.size }
        val trailDistanceMeters = remember(breadcrumbTracks) {
            breadcrumbTracks.sumOf { track ->
                track.points.zipWithNext { a, b ->
                    FieldNavigation.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
                }.sum()
            }
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("GPS breadcrumb", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        isBreadcrumbRecording -> "Recording ${recordedBreadcrumbPoints} GPS fix${if (recordedBreadcrumbPoints == 1) "" else "es"}. Trails are saved with this terrain project."
                        recordedBreadcrumbPoints > 0 -> {
                            val dist = MeasurementFormat.length(trailDistanceMeters.toFloat())
                            "$recordedBreadcrumbPoints GPS fix${if (recordedBreadcrumbPoints == 1) "" else "es"} · " +
                                "${breadcrumbTracks.size} trail${if (breadcrumbTracks.size == 1) "" else "s"} · $dist walked"
                        }
                        else -> "Record a project-scoped path for offline field checking. GPS jitter is filtered automatically."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = if (isBreadcrumbRecording) onPauseBreadcrumb else onStartBreadcrumb,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("breadcrumb_record_button"),
                    ) {
                        Icon(Icons.Default.Flag, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isBreadcrumbRecording) "Pause trail" else "Start trail")
                    }
                    OutlinedButton(
                        onClick = { confirmClearBreadcrumbs = true },
                        enabled = breadcrumbTracks.isNotEmpty() && !isBreadcrumbRecording,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear trails")
                    }
                }
            }
        }

        SurveyBoundaryCard(
            boundaries = surveyBoundaries,
            breadcrumbTracks = breadcrumbTracks,
            hasGpsFix = deviceLatitude != null && deviceLongitude != null,
            onCreateFromTrail = onCreateBoundaryFromTrail,
            onCreateAroundGps = {
                if (deviceLatitude == null || deviceLongitude == null) onEnableGps()
                onCreateBoundaryAroundGps()
            },
            onDelete = onDeleteSurveyBoundary,
        )

        OfflineSyncQueueCard(
            entries = pendingSyncEntries,
            onMarkSent = onMarkSyncSent,
            onClear = onClearSyncQueue,
        )

        navigationTarget?.let { target ->
            FieldNavigationCard(
                target = target,
                currentLatitude = deviceLatitude,
                currentLongitude = deviceLongitude,
                currentAccuracyMeters = deviceAccuracyMeters,
                headingDegrees = compassHeadingDegrees,
                gpsEnabled = gpsEnabled,
                onEnableGps = onEnableGps,
                onStop = { navigationTarget = null },
            )
        }

        if (exportMessage != null) {
            Text(
                exportMessage.orEmpty(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        var outcomeFilter by remember { mutableStateOf<VerificationOutcome?>(null) }
        var typeFilter by remember { mutableStateOf<String?>(null) }
        var starredOnly by remember { mutableStateOf(false) }
        var sortMode by remember { mutableStateOf(FindSortMode.STARRED_FIRST) }
        val typeOptions = remember(loggedSignals) {
            loggedSignals.map { it.metalType.label }.distinct().sorted()
        }
        val filteredSignals = remember(
            loggedSignals,
            outcomeFilter,
            typeFilter,
            starredOnly,
            sortMode,
            deviceLatitude,
            deviceLongitude,
        ) {
            val filtered = loggedSignals.filter { signal ->
                (outcomeFilter == null || signal.outcome == outcomeFilter) &&
                    (typeFilter == null || signal.metalType.label == typeFilter) &&
                    (!starredOnly || signal.starred)
            }
            when (sortMode) {
                FindSortMode.STARRED_FIRST -> filtered.sortedWith(
                    compareByDescending<TargetSignal> { it.starred }
                        .thenByDescending { it.timestamp },
                )
                FindSortMode.NEWEST -> filtered.sortedByDescending { it.timestamp }
                FindSortMode.NEAREST -> {
                    val lat = deviceLatitude
                    val lon = deviceLongitude
                    if (lat == null || lon == null) {
                        filtered.sortedByDescending { it.timestamp }
                    } else {
                        filtered.sortedBy { signal ->
                            val sLat = signal.latitude
                            val sLon = signal.longitude
                            if (sLat == null || sLon == null) {
                                Double.MAX_VALUE
                            } else {
                                FieldNavigation.distanceMeters(lat, lon, sLat, sLon)
                            }
                        }
                    }
                }
            }
        }

        if (loggedSignals.isNotEmpty()) {
            Text("Filter finds", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = starredOnly,
                    onClick = { starredOnly = !starredOnly },
                    label = { Text("Starred only") },
                    leadingIcon = {
                        Icon(
                            if (starredOnly) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.testTag("filter_starred_only"),
                )
                FilterChip(
                    selected = outcomeFilter == null,
                    onClick = { outcomeFilter = null },
                    label = { Text("All outcomes") },
                )
                VerificationOutcome.entries.forEach { outcome ->
                    FilterChip(
                        selected = outcomeFilter == outcome,
                        onClick = { outcomeFilter = if (outcomeFilter == outcome) null else outcome },
                        label = { Text(outcome.label) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Sort",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                FindSortMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sortMode == mode,
                        onClick = { sortMode = mode },
                        label = { Text(mode.label) },
                        modifier = Modifier.testTag("sort_${mode.name}"),
                    )
                }
            }
            if (typeOptions.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = typeFilter == null,
                        onClick = { typeFilter = null },
                        label = { Text("All types") },
                    )
                    typeOptions.forEach { label ->
                        FilterChip(
                            selected = typeFilter == label,
                            onClick = { typeFilter = if (typeFilter == label) null else label },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        if (loggedSignals.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No finds logged yet. Sweep the map, then log the current position.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            if (filteredSignals.isEmpty()) {
                Text(
                    "No finds match the current filters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
                Button(
                    onClick = { showExport = true },
                    modifier = Modifier.weight(1.5f).height(48.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export GIS data")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("logged_signals_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredSignals, key = { it.id }) { signal ->
                    SignalCard(
                        signal = signal,
                        onEdit = { editingSignal = signal },
                        onDelete = {
                            signal.voiceNoteUris.forEach { deleteVoiceNoteFile(context, it) }
                            onDeleteSignal(signal)
                        },
                        onNavigate = { navigationTarget = signal },
                        onToggleStarred = { onToggleStarred(signal) },
                        digLogCount = excavationLogs.count { it.targetId == signal.id },
                        onOpenDigLogs = { digLogSignal = signal },
                        onShare = {
                            val lat = signal.latitude
                            val lon = signal.longitude
                            val text = if (lat != null && lon != null) {
                                "${signal.metalType.label}\n${String.format(Locale.US, "%.6f, %.6f", lat, lon)}\n" +
                                    "Grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()}"
                            } else {
                                "${signal.metalType.label}\nLocal grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()}\n(no geographic coordinates)"
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_SUBJECT, "Find-It target")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share coordinates"))
                        },
                    )
                }
            }
        }
    }

    pendingProximity?.let { pending ->
        val nearby = pending.nearbyFind
        val distance = pending.nearbyDistanceMeters
        AlertDialog(
            onDismissRequest = { pendingProximity = null },
            title = { Text("Nearby find") },
            text = {
                Text(
                    if (nearby != null && distance != null) {
                        "Another find (${nearby.metalType.label}) is only " +
                            "${MeasurementFormat.length(distance)} away. Log this position anyway?"
                    } else {
                        "Another find is nearby. Log this position anyway?"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { attemptLog(force = true) },
                    modifier = Modifier.testTag("proximity_log_anyway"),
                ) {
                    Text("Log anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingProximity = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    editingSignal?.let { signal ->
        EditSignalDialog(
            signal = signal,
            excavationLogs = excavationLogs.filter { it.targetId == signal.id },
            onDismiss = { editingSignal = null },
            onSave = {
                onUpdateSignal(it)
                editingSignal = null
            },
            onSaveExcavationLog = onSaveExcavationLog,
            onDeleteExcavationLog = onDeleteExcavationLog,
            onStartExcavationLog = { onStartExcavationLog(signal.id) },
        )
    }

    digLogSignal?.let { signal ->
        ExcavationLogsDialog(
            signal = signal,
            logs = excavationLogs.filter { it.targetId == signal.id },
            onSave = onSaveExcavationLog,
            onDelete = onDeleteExcavationLog,
            onDismiss = { digLogSignal = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all finds?") },
            text = { Text("This permanently removes ${loggedSignals.size} saved record(s).") },
            confirmButton = {
                TextButton(
                    onClick = {
                        loggedSignals.flatMap { it.voiceNoteUris }.forEach { deleteVoiceNoteFile(context, it) }
                        confirmClear = false
                        onClearAll()
                    },
                ) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (showExport) {
        ExportGisDialog(
            signals = loggedSignals,
            onDismiss = { showExport = false },
            onSaveCsv = {
                pendingCsv = buildCsv(loggedSignals)
                showExport = false
                csvLauncher.launch("find-it-targets.csv")
            },
            onSaveGpx = {
                pendingGpx = buildGpx(loggedSignals)
                showExport = false
                gpxLauncher.launch("find-it-targets.gpx")
            },
            onSaveKml = {
                pendingKml = buildKml(loggedSignals)
                showExport = false
                kmlLauncher.launch("find-it-targets.kml")
            },
            onSaveGeoJson = {
                pendingGeoJson = buildGeoJson(loggedSignals)
                showExport = false
                geoJsonLauncher.launch("find-it-targets.geojson")
            },
            onSaveShapefile = {
                pendingBinaryBytes = buildShapefileZip(loggedSignals)
                binarySuccessMessage = "Shapefile bundle saved"
                showExport = false
                shapefileLauncher.launch("find-it-targets-shp.zip")
            },
            onSaveKmz = {
                pendingBinaryBytes = buildKmz(loggedSignals)
                binarySuccessMessage = "KMZ saved"
                showExport = false
                kmzLauncher.launch("find-it-targets.kmz")
            },
        )
    }

    if (confirmClearBreadcrumbs) {
        AlertDialog(
            onDismissRequest = { confirmClearBreadcrumbs = false },
            title = { Text("Clear saved trails?") },
            text = { Text("This removes ${breadcrumbTracks.size} breadcrumb trail(s) from this terrain project.") },
            confirmButton = {
                TextButton(onClick = { confirmClearBreadcrumbs = false; onClearBreadcrumbs() }) {
                    Text("Clear trails")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClearBreadcrumbs = false }) { Text("Cancel") } },
        )
    }

    if (showProjectExport) {
        AlertDialog(
            onDismissRequest = { showProjectExport = false },
            title = { Text("Export this terrain project") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Exports rebuild the complete source footprint, not the current screen crop.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("PNG includes saved targets, survey layers, legend, scale, and source coordinates.")
                    Text("PDF includes the annotated map, metadata, target records, survey provenance, and integrity notes.")
                    Text("QGIS bundle packs a GeoTIFF terrain raster, a shapefile of your targets, and a ready-to-open .qgs project.")
                    Text("Portable archive moves the whole project (targets in every format, PNG, PDF, QGIS bundle) between devices as one zip.")
                    TextButton(
                        onClick = { buildQgisBundleExport() },
                        modifier = Modifier.fillMaxWidth().testTag("export_qgis_bundle"),
                    ) { Text("Save QGIS bundle (.zip)") }
                    TextButton(
                        onClick = { buildArchiveExport() },
                        modifier = Modifier.fillMaxWidth().testTag("export_project_archive"),
                    ) { Text("Save portable archive (.zip)") }
                }
            },
            confirmButton = {
                Button(onClick = { buildProjectExport(true) }) { Text("Save PDF") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { showProjectExport = false }) { Text("Cancel") }
                    TextButton(onClick = { buildProjectExport(false) }) { Text("Save PNG") }
                }
            },
        )
    }
}

@Composable
private fun FieldNavigationCard(
    target: TargetSignal,
    currentLatitude: Double?,
    currentLongitude: Double?,
    currentAccuracyMeters: Float?,
    headingDegrees: Float?,
    gpsEnabled: Boolean,
    onEnableGps: () -> Unit,
    onStop: () -> Unit,
) {
    val targetLatitude = target.latitude
    val targetLongitude = target.longitude
    val solution = remember(currentLatitude, currentLongitude, targetLatitude, targetLongitude, headingDegrees) {
        if (currentLatitude == null || currentLongitude == null || targetLatitude == null || targetLongitude == null) {
            null
        } else {
            FieldNavigation.solve(
                currentLatitude = currentLatitude,
                currentLongitude = currentLongitude,
                targetLatitude = targetLatitude,
                targetLongitude = targetLongitude,
                headingDegrees = headingDegrees,
            )
        }
    }
    val magneticTargetBearing = remember(solution, currentLatitude, currentLongitude) {
        if (solution == null || currentLatitude == null || currentLongitude == null) {
            null
        } else {
            trueToMagneticBearingDegrees(
                trueBearingDegrees = solution.targetBearingDegrees,
                latitude = currentLatitude,
                longitude = currentLongitude,
            )
        }
    }
    val compassTurn = remember(headingDegrees, magneticTargetBearing) {
        if (headingDegrees == null || magneticTargetBearing == null) null
        else FieldNavigation.signedTurnDegrees(headingDegrees, magneticTargetBearing)
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("field_navigation_card"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Navigation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Field navigation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${target.metalType.label} · ${target.status}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                TextButton(onClick = onStop) { Text("Stop") }
            }

            if (solution == null) {
                Text(
                    when {
                        targetLatitude == null || targetLongitude == null ->
                            "This saved target has no geographic coordinate, so it cannot be routed in the field."
                        !gpsEnabled -> "Start GPS to calculate distance and bearing to this target."
                        else -> "Waiting for a current GPS fix…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!gpsEnabled) {
                    Button(onClick = onEnableGps, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Text("Start GPS navigation")
                    }
                }
                return@Column
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    Icons.Default.Navigation,
                    contentDescription = "Direction to target",
                    modifier = Modifier
                        .size(62.dp)
                        .rotate(compassTurn ?: 0f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        formatNavigationDistance(solution.distanceMeters),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Target true ${FieldNavigation.compassDirection(solution.targetBearingDegrees)} · ${solution.targetBearingDegrees.toInt()}°",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    compassTurn?.let { turn ->
                        Text(
                            FieldNavigation.turnInstruction(turn),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } ?: Text(
                        "Hold the phone flat while the compass initializes.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            headingDegrees?.let { heading ->
                Text(
                    "Compass heading ${FieldNavigation.compassDirection(heading)} · ${heading.toInt()}° magnetic",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            currentAccuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.let { accuracy ->
                Text(
                    "Current GPS accuracy ±${MeasurementFormat.length(accuracy)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Compass guidance is for field checking. Calibrate the phone and verify the target against the terrain before excavating.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private fun formatNavigationDistance(meters: Double): String =
    "${MeasurementFormat.length(meters)} away"

private enum class FindSortMode(val label: String) {
    STARRED_FIRST("Starred first"),
    NEWEST("Newest"),
    NEAREST("Nearest"),
}

@Composable
private fun SignalCard(
    signal: TargetSignal,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onNavigate: () -> Unit,
    onToggleStarred: () -> Unit = {},
    digLogCount: Int = 0,
    onOpenDigLogs: () -> Unit = {},
    onShare: () -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                tint = Color(signal.metalType.colorHex),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(signal.metalType.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                    if (signal.starred) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Starred",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                val depth = signal.depthCm?.let { "$it cm" } ?: "depth unknown"
                Text(
                    "Grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()} · $depth · ${signal.signalStrength.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${signal.source.name.lowercase().replaceFirstChar { it.uppercase() }} · ${signal.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                signal.gpsAccuracyMeters?.let { accuracy ->
                    Text(
                        "GPS captured ${MeasurementFormat.length(accuracy)} accuracy",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (signal.outcome != VerificationOutcome.UNVERIFIED) {
                    Text(
                        signal.outcome.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = when (signal.outcome) {
                            VerificationOutcome.CONFIRMED_FEATURE -> MaterialTheme.colorScheme.tertiary
                            VerificationOutcome.REJECTED_FALSE_POSITIVE -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (signal.notes.isNotBlank()) {
                    Text(signal.notes, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (signal.photoUris.isNotEmpty()) {
                    Text(
                        "${signal.photoUris.size} photo attachment${if (signal.photoUris.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (signal.voiceNoteUris.isNotEmpty()) {
                    Text(
                        "${signal.voiceNoteUris.size} voice note${if (signal.voiceNoteUris.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (digLogCount > 0) {
                    Text(
                        "$digLogCount dig log${if (digLogCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            IconButton(
                onClick = onToggleStarred,
                modifier = Modifier.size(48.dp).testTag("toggle_star_find"),
            ) {
                Icon(
                    if (signal.starred) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (signal.starred) "Unstar find" else "Star find",
                    tint = if (signal.starred) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit find")
            }
            IconButton(
                onClick = onNavigate,
                enabled = signal.latitude != null && signal.longitude != null,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Navigation, contentDescription = "Navigate to find")
            }
            IconButton(onClick = onOpenDigLogs, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Construction, contentDescription = "Dig logs")
            }
            IconButton(onClick = onShare, modifier = Modifier.size(48.dp).testTag("share_find_coords")) {
                Icon(Icons.Default.Share, contentDescription = "Share coordinates")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete find")
            }
        }
    }
}

@Composable
private fun EditSignalDialog(
    signal: TargetSignal,
    excavationLogs: List<ExcavationLogEntry> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (TargetSignal) -> Unit,
    onSaveExcavationLog: (ExcavationLogEntry) -> Unit = {},
    onDeleteExcavationLog: (ExcavationLogEntry) -> Unit = {},
    onStartExcavationLog: () -> ExcavationLogEntry? = { null },
) {
    val context = LocalContext.current
    var photoUris by remember(signal.id) { mutableStateOf(signal.photoUris) }
    var voiceNoteUris by remember(signal.id) { mutableStateOf(signal.voiceNoteUris) }
    var recorder by remember(signal.id) { mutableStateOf<VoiceNoteRecorder?>(null) }
    var isRecordingVoiceNote by remember(signal.id) { mutableStateOf(false) }
    var recordingMessage by remember(signal.id) { mutableStateOf<String?>(null) }
    var mediaPlayer by remember(signal.id) { mutableStateOf<MediaPlayer?>(null) }
    var playingVoiceNoteUri by remember(signal.id) { mutableStateOf<String?>(null) }

    fun stopPlayback() {
        mediaPlayer?.let { player ->
            runCatching { player.stop() }
            player.release()
        }
        mediaPlayer = null
        playingVoiceNoteUri = null
    }

    fun startVoiceRecording() {
        if (isRecordingVoiceNote || voiceNoteUris.size >= 10) return
        val active = VoiceNoteRecorder(context, createVoiceNoteFile(context, signal))
        runCatching { active.start() }
            .onSuccess {
                recorder = active
                isRecordingVoiceNote = true
                recordingMessage = null
            }
            .onFailure {
                active.cancel()
                recordingMessage = it.localizedMessage ?: "Could not start the voice-note recorder."
            }
    }

    fun stopVoiceRecording() {
        val file = recorder?.stop()
        recorder = null
        isRecordingVoiceNote = false
        if (file == null) {
            recordingMessage = "The voice note was too short or could not be saved."
        } else {
            voiceNoteUris = (voiceNoteUris + Uri.fromFile(file).toString()).distinct().take(10)
            recordingMessage = "Voice note saved offline."
        }
    }

    fun playVoiceNote(uriText: String) {
        if (playingVoiceNoteUri == uriText) {
            stopPlayback()
            return
        }
        stopPlayback()
        val player = MediaPlayer()
        runCatching {
            player.setDataSource(context, Uri.parse(uriText))
            player.setOnCompletionListener {
                it.release()
                if (mediaPlayer === it) {
                    mediaPlayer = null
                    playingVoiceNoteUri = null
                }
            }
            player.prepare()
            player.start()
        }.onSuccess {
            mediaPlayer = player
            playingVoiceNoteUri = uriText
            recordingMessage = null
        }.onFailure {
            player.release()
            recordingMessage = it.localizedMessage ?: "Could not play this voice note."
        }
    }

    DisposableEffect(signal.id) {
        onDispose {
            recorder?.cancel()
            mediaPlayer?.release()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startVoiceRecording() else {
            recordingMessage = "Microphone permission is required to record a voice note."
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            photoUris = (photoUris + uri.toString()).distinct().take(10)
        }
    }
    var notes by remember(signal.id) { mutableStateOf(signal.notes) }
    var status by remember(signal.id) { mutableStateOf(signal.status) }
    var outcome by remember(signal.id) { mutableStateOf(signal.outcome) }
    val statuses = listOf(
        "AI suggested",
        "Selected",
        "Approaching",
        "Checked",
        "Productive",
        "Rejected",
        "Inconclusive",
        "Follow up",
    )
    AlertDialog(
        onDismissRequest = {
            recorder?.cancel()
            stopPlayback()
            onDismiss()
        },
        title = { Text("Edit find") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${signal.metalType.label} at grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()}")
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(500) },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Photos (${photoUris.size}/10)", style = MaterialTheme.typography.titleSmall)
                photoUris.forEach { photoUri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            photoUri.substringAfterLast('/').takeLast(32),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { photoUris = photoUris - photoUri }) { Text("Remove") }
                    }
                }
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = photoUris.size < 10,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add photo")
                }
                Text("Voice notes (${voiceNoteUris.size}/10)", style = MaterialTheme.typography.titleSmall)
                voiceNoteUris.forEachIndexed { index, voiceUri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Voice note ${index + 1}",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { playVoiceNote(voiceUri) }) {
                            Icon(
                                if (playingVoiceNoteUri == voiceUri) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = if (playingVoiceNoteUri == voiceUri) "Stop voice note" else "Play voice note",
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(if (playingVoiceNoteUri == voiceUri) "Stop" else "Play")
                        }
                        TextButton(
                            onClick = {
                                if (playingVoiceNoteUri == voiceUri) stopPlayback()
                                deleteVoiceNoteFile(context, voiceUri)
                                voiceNoteUris = voiceNoteUris - voiceUri
                            },
                        ) { Text("Remove") }
                    }
                }
                Button(
                    onClick = {
                        if (isRecordingVoiceNote) {
                            stopVoiceRecording()
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) startVoiceRecording() else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    enabled = isRecordingVoiceNote || voiceNoteUris.size < 10,
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("voice_note_record_button"),
                ) {
                    Icon(if (isRecordingVoiceNote) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRecordingVoiceNote) "Stop and save voice note" else "Record voice note")
                }
                recordingMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message.startsWith("Could") || message.startsWith("Microphone") || message.startsWith("The voice")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                statuses.chunked(2).forEach { rowStatuses ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowStatuses.forEach { item ->
                            val selected = status == item
                            if (selected) {
                                Button(
                                    onClick = { status = item },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text(item) }
                            } else {
                                OutlinedButton(
                                    onClick = { status = item },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text(item) }
                            }
                        }
                    }
                }
                Text("Field verification", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Checked this location? Your answer feeds back into how future terrain analysis of this dataset scores similar candidates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                VerificationOutcome.entries.chunked(2).forEach { rowOutcomes ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowOutcomes.forEach { item ->
                            val selected = outcome == item
                            if (selected) {
                                Button(
                                    onClick = { outcome = item },
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("outcome_${item.name}"),
                                ) { Text(item.label, maxLines = 2, style = MaterialTheme.typography.labelMedium) }
                            } else {
                                OutlinedButton(
                                    onClick = { outcome = item },
                                    modifier = Modifier.weight(1f).height(48.dp).testTag("outcome_${item.name}"),
                                ) { Text(item.label, maxLines = 2, style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
                if (signal.datasetKey == null && outcome != VerificationOutcome.UNVERIFIED) {
                    Text(
                        "This find isn't linked to a specific analyzed dataset, so this verification won't influence future scoring.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ExcavationLogSection(
                    logs = excavationLogs,
                    onStart = onStartExcavationLog,
                    onSave = onSaveExcavationLog,
                    onDelete = onDeleteExcavationLog,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    stopPlayback()
                    onSave(
                        signal.copy(
                            notes = notes.trim(),
                            photoUris = photoUris,
                            voiceNoteUris = voiceNoteUris,
                            status = status,
                            outcome = outcome,
                        ),
                    )
                },
                enabled = !isRecordingVoiceNote,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    recorder?.cancel()
                    stopPlayback()
                    onDismiss()
                },
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExcavationLogSection(
    logs: List<ExcavationLogEntry>,
    onStart: () -> ExcavationLogEntry?,
    onSave: (ExcavationLogEntry) -> Unit,
    onDelete: (ExcavationLogEntry) -> Unit,
) {
    var editingLog by remember { mutableStateOf<ExcavationLogEntry?>(null) }
    var soilNotes by remember { mutableStateOf("") }
    var findsDescription by remember { mutableStateOf("") }
    var depthText by remember { mutableStateOf("") }
    var findsCountText by remember { mutableStateOf("0") }

    fun openEditor(entry: ExcavationLogEntry) {
        editingLog = entry
        soilNotes = entry.soilNotes
        findsDescription = entry.findsDescription
        depthText = entry.depthCentimeters?.toString().orEmpty()
        findsCountText = entry.findsCount.toString()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Excavation log", style = MaterialTheme.typography.titleSmall)
        Text(
            "Record depth, soil, and finds for this target. Open digs survive app restarts offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Ethics: only dig where you have permission. LiDAR is not ownership or metal proof.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (logs.isEmpty()) {
            Text(
                "No dig logs yet for this target.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            logs.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (entry.isComplete) "Completed dig" else "Open dig",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                entry.depthCentimeters?.let { append("${it} cm · ") }
                                if (entry.findsCount > 0) append("${entry.findsCount} find(s)")
                                else if (entry.soilNotes.isNotBlank()) append(entry.soilNotes.take(40))
                                else append("No notes yet")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { openEditor(entry) }) { Text("Edit") }
                    TextButton(onClick = { onDelete(entry) }) { Text("Delete") }
                }
            }
        }
        OutlinedButton(
            onClick = {
                val started = onStart()
                if (started != null) openEditor(started)
            },
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("start_excavation_log_button"),
        ) {
            Text("Start dig log")
        }
        editingLog?.let { entry ->
            OutlinedTextField(
                value = depthText,
                onValueChange = { depthText = it.filter { ch -> ch.isDigit() }.take(4) },
                label = { Text("Depth (cm)") },
                modifier = Modifier.fillMaxWidth().testTag("excavation_depth_field"),
            )
            OutlinedTextField(
                value = soilNotes,
                onValueChange = { soilNotes = it.take(400) },
                label = { Text("Soil notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = findsDescription,
                onValueChange = { findsDescription = it.take(400) },
                label = { Text("Finds description") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = findsCountText,
                onValueChange = { findsCountText = it.filter { ch -> ch.isDigit() }.take(3) },
                label = { Text("Find count") },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val updated = entry.copy(
                            depthCentimeters = depthText.toIntOrNull(),
                            soilNotes = soilNotes.trim(),
                            findsDescription = findsDescription.trim(),
                            findsCount = findsCountText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            updatedAtMillis = now,
                        )
                        onSave(updated)
                        editingLog = null
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("save_excavation_log_button"),
                ) { Text("Save dig") }
                OutlinedButton(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val completed = entry.copy(
                            depthCentimeters = depthText.toIntOrNull(),
                            soilNotes = soilNotes.trim(),
                            findsDescription = findsDescription.trim(),
                            findsCount = findsCountText.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                            completedAtMillis = now,
                            updatedAtMillis = now,
                        )
                        onSave(completed)
                        editingLog = null
                    },
                    modifier = Modifier.weight(1f).height(48.dp).testTag("complete_excavation_log_button"),
                ) { Text("Complete dig") }
            }
            TextButton(onClick = { editingLog = null }) { Text("Close dig editor") }
        }
    }
}

@Composable
private fun SurveyBoundaryCard(
    boundaries: List<SurveyBoundary>,
    breadcrumbTracks: List<BreadcrumbTrack>,
    hasGpsFix: Boolean,
    onCreateFromTrail: (BreadcrumbTrack) -> Unit,
    onCreateAroundGps: () -> Unit,
    onDelete: (SurveyBoundary) -> Unit,
) {
    val trailWithEnoughPoints = breadcrumbTracks.firstOrNull { it.points.size >= 3 }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().testTag("survey_boundary_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Survey boundary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Keep field work inside a permitted search area. Create from a GPS trail (≥3 points) " +
                    "or a 100 m box around your current fix.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (boundaries.isEmpty()) {
                Text(
                    "No boundaries on this project yet.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                boundaries.forEach { boundary ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(boundary.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${boundary.vertices.size} vertices",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDelete(boundary) }) { Text("Delete") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { trailWithEnoughPoints?.let(onCreateFromTrail) },
                    enabled = trailWithEnoughPoints != null,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("boundary_from_trail_button"),
                ) { Text("From trail") }
                OutlinedButton(
                    onClick = onCreateAroundGps,
                    enabled = hasGpsFix,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("boundary_from_gps_button"),
                ) { Text("Around GPS") }
            }
        }
    }
}

@Composable
private fun OfflineSyncQueueCard(
    entries: List<PendingSyncEntry>,
    onMarkSent: (Long) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().testTag("offline_sync_queue_card"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Offline sync queue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (entries.isEmpty()) {
                    "Local field changes stay on-device. When a cloud endpoint is available (Phase 9), " +
                        "queued upserts and deletes replay in order without duplicates."
                } else {
                    "${entries.size} change(s) waiting to sync. Oldest first; failed sends keep their attempt count."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            entries.take(5).forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${entry.operation.name} · ${entry.entityType.name}",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            buildString {
                                append(entry.entityId.take(12))
                                if (entry.attemptCount > 0) append(" · attempts ${entry.attemptCount}")
                                entry.lastError?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(
                        onClick = { onMarkSent(entry.id) },
                        modifier = Modifier.testTag("sync_mark_sent_${entry.id}"),
                    ) { Text("Sent") }
                }
            }
            if (entries.size > 5) {
                Text(
                    "+${entries.size - 5} more queued…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isNotEmpty()) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("clear_sync_queue_button"),
                ) { Text("Clear queue") }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear offline sync queue?") },
            text = { Text("This drops ${entries.size} pending change(s) without delivering them.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExportGisDialog(
    signals: List<TargetSignal>,
    onDismiss: () -> Unit,
    onSaveCsv: () -> Unit,
    onSaveGpx: () -> Unit,
    onSaveKml: () -> Unit,
    onSaveGeoJson: () -> Unit,
    onSaveShapefile: () -> Unit,
    onSaveKmz: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var format by remember { mutableStateOf(0) }
    val georeferenced = signals.count { it.latitude != null && it.longitude != null }
    val labels = listOf("CSV", "GPX", "KML", "GeoJSON", "Shapefile", "KMZ")
    val content = remember(signals, format) {
        when (format) {
            0 -> buildCsv(signals)
            1 -> buildGpx(signals)
            2 -> buildKml(signals)
            3 -> buildGeoJson(signals)
            4 -> "Binary shapefile bundle — .shp, .shx, and .dbf in one zip. Opens in QGIS and other GIS tools."
            else -> "KMZ packages the KML export as a single compressed file for Google Earth."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export field data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.chunked(2).forEachIndexed { rowIndex, rowLabels ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowLabels.forEachIndexed { columnIndex, label ->
                                val value = rowIndex * 2 + columnIndex
                                if (format == value) {
                                    Button(onClick = { format = value }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { format = value }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                                }
                            }
                        }
                    }
                }
                Text(
                    if (format == 0) {
                        "CSV includes all ${signals.size} records. Coordinates remain blank when the source grid has no CRS."
                    } else {
                        "${labels[format]} includes $georeferenced of ${signals.size} records with real WGS84 coordinates."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(content)) },
                    enabled = format <= 3,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy ${labels[format]}")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = when (format) {
                    0 -> onSaveCsv
                    1 -> onSaveGpx
                    2 -> onSaveKml
                    3 -> onSaveGeoJson
                    4 -> onSaveShapefile
                    else -> onSaveKmz
                },
                enabled = format == 0 || georeferenced > 0,
            ) { Text("Save file") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/**
 * Groups logged finds into proximity sites (see [FindSiteClusterer]) and summarizes each one:
 * find count, dominant types, field-check outcomes, and centroid. Hidden until at least one
 * find has real coordinates.
 */
@Composable
private fun SitesCard(signals: List<TargetSignal>) {
    val sites = remember(signals) { FindSiteClusterer.cluster(signals) }
    if (sites.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Detected sites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Finds within 50 m of each other are grouped automatically. Mark outcomes on your finds to see which sites are producing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sites.take(8).forEach { site ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${site.label} — ${site.signals.size} find${if (site.signals.size == 1) "" else "s"}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            buildString {
                                append(site.topTypes.take(2).joinToString(" · "))
                                if (site.confirmedCount > 0) append(" · ${site.confirmedCount} confirmed")
                                if (site.rejectedCount > 0) append(" · ${site.rejectedCount} rejected")
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            String.format(Locale.US, "%.5f, %.5f", site.centerLatitude, site.centerLongitude),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (sites.size > 8) {
                Text(
                    "+${sites.size - 8} more site${if (sites.size - 8 == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/**
 * The optimized walking order across all georeferenced targets, built by [TargetRouteOptimizer].
 * Each stop shows its leg distance from the previous one; tapping a stop hands it to the
 * compass navigation flow.
 */
@Composable
private fun PlannedRouteCard(
    route: OptimizedFieldRoute,
    signals: List<TargetSignal>,
    onNavigate: (TargetSignal) -> Unit,
    onDismiss: () -> Unit,
) {
    val signalsById = remember(signals) { signals.associateBy { it.id.toString() } }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Planned route",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Clear") }
            }
            Text(
                "Shortest walking order across ${route.waypoints.size} stops · " +
                    "${MeasurementFormat.length(route.totalDistanceMeters.toFloat())} total · tap a stop to navigate to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var previous: FieldWaypoint? = null
            route.waypoints.forEachIndexed { index, waypoint ->
                val legMeters = previous?.let {
                    FieldNavigation.distanceMeters(it.latitude, it.longitude, waypoint.latitude, waypoint.longitude)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { signalsById[waypoint.id]?.let(onNavigate) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            "${index + 1}.",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(30.dp),
                        )
                        Column {
                            Text(waypoint.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (legMeters != null) {
                                    "+${MeasurementFormat.length(legMeters.toFloat())} from previous stop"
                                } else {
                                    "First stop"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                previous = waypoint
            }
        }
    }
}

@Composable
private fun BoundariesCard(
    boundaries: List<SurveyBoundary>,
    terrainBounds: GeoSpatialLibrary.GeographicBounds?,
    deviceLatitude: Double?,
    deviceLongitude: Double?,
    onCreateBoundary: (String, List<BoundaryVertex>) -> Unit,
    onDeleteBoundary: (SurveyBoundary) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Survey boundaries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Boundaries record the permitted search area for this project. They persist " +
                    "offline and are queued for sync with the rest of your field data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            boundaries.forEach { boundary ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(boundary.displayName, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${boundary.vertices.size} vertices",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onDeleteBoundary(boundary) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete boundary")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        terrainBounds?.let { bounds ->
                            onCreateBoundary(
                                "Terrain extent",
                                listOf(
                                    BoundaryVertex(bounds.minLat, bounds.minLon),
                                    BoundaryVertex(bounds.minLat, bounds.maxLon),
                                    BoundaryVertex(bounds.maxLat, bounds.maxLon),
                                    BoundaryVertex(bounds.maxLat, bounds.minLon),
                                ),
                            )
                        }
                    },
                    enabled = terrainBounds != null,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("boundary_from_terrain"),
                ) {
                    Text("From terrain extent")
                }
                OutlinedButton(
                    onClick = {
                        val lat = deviceLatitude
                        val lon = deviceLongitude
                        if (lat != null && lon != null) {
                            onCreateBoundary("Around me (100 m)", boundarySquareAround(lat, lon, 50.0))
                        }
                    },
                    enabled = deviceLatitude != null && deviceLongitude != null,
                    modifier = Modifier.weight(1f).height(48.dp).testTag("boundary_around_me"),
                ) {
                    Text("Around me")
                }
            }
        }
    }
}

private fun boundarySquareAround(
    latitude: Double,
    longitude: Double,
    radiusMeters: Double,
): List<BoundaryVertex> {
    val latDelta = radiusMeters / 111_320.0
    val lonDelta = radiusMeters / (111_320.0 * cos(Math.toRadians(latitude)).coerceAtLeast(0.01))
    return listOf(
        BoundaryVertex(latitude - latDelta, longitude - lonDelta),
        BoundaryVertex(latitude - latDelta, longitude + lonDelta),
        BoundaryVertex(latitude + latDelta, longitude + lonDelta),
        BoundaryVertex(latitude + latDelta, longitude - lonDelta),
    )
}

@Composable
private fun ExcavationLogsDialog(
    signal: TargetSignal,
    logs: List<ExcavationLogEntry>,
    onSave: (ExcavationLogEntry) -> Unit,
    onDelete: (ExcavationLogEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var showForm by remember(signal.id) { mutableStateOf(false) }
    var depthText by remember(signal.id) { mutableStateOf("") }
    var soilNotes by remember(signal.id) { mutableStateOf("") }
    var findsDescription by remember(signal.id) { mutableStateOf("") }
    var findsCountText by remember(signal.id) { mutableStateOf("0") }
    var markComplete by remember(signal.id) { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dig logs · ${signal.metalType.label}") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Ethics: only dig where you have permission. LiDAR is not ownership or metal proof.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                if (logs.isEmpty() && !showForm) {
                    Text(
                        "No dig logs yet. Record each check or excavation so the full visit " +
                            "history stays with this target, offline.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                logs.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatDigLogTime(entry.startedAtMillis) +
                                    if (entry.isComplete) " · complete" else " · open",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val detail = buildString {
                                entry.depthCentimeters?.let { append("Depth $it cm") }
                                if (entry.findsCount > 0) {
                                    if (isNotEmpty()) append(" · ")
                                    append("${entry.findsCount} find(s)")
                                }
                                if (entry.soilNotes.isNotBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(entry.soilNotes)
                                }
                                if (entry.findsDescription.isNotBlank()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(entry.findsDescription)
                                }
                            }
                            if (detail.isNotBlank()) {
                                Text(
                                    detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(entry) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete dig log")
                        }
                    }
                }
                if (showForm) {
                    OutlinedTextField(
                        value = depthText,
                        onValueChange = { depthText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Depth (cm)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("diglog_depth"),
                    )
                    OutlinedTextField(
                        value = soilNotes,
                        onValueChange = { soilNotes = it.take(200) },
                        label = { Text("Soil notes") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = findsDescription,
                        onValueChange = { findsDescription = it.take(200) },
                        label = { Text("What came out") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = findsCountText,
                        onValueChange = { findsCountText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Finds count") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilterChip(
                        selected = markComplete,
                        onClick = { markComplete = !markComplete },
                        label = { Text(if (markComplete) "Dig complete" else "Still open") },
                    )
                }
            }
        },
        confirmButton = {
            if (showForm) {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        onSave(
                            ExcavationLogEntry(
                                id = UUID.randomUUID().toString(),
                                targetId = signal.id,
                                terrainKey = signal.terrainKey,
                                startedAtMillis = now,
                                completedAtMillis = if (markComplete) now else null,
                                depthCentimeters = depthText.toIntOrNull(),
                                soilNotes = soilNotes.trim(),
                                findsDescription = findsDescription.trim(),
                                findsCount = findsCountText.toIntOrNull() ?: 0,
                                photoUris = emptyList(),
                                voiceNoteUris = emptyList(),
                                createdAtMillis = now,
                                updatedAtMillis = now,
                            ),
                        )
                        showForm = false
                        depthText = ""
                        soilNotes = ""
                        findsDescription = ""
                        findsCountText = "0"
                    },
                    modifier = Modifier.testTag("diglog_save"),
                ) { Text("Save log") }
            } else {
                Button(
                    onClick = { showForm = true },
                    modifier = Modifier.testTag("diglog_new"),
                ) { Text("New dig log") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (showForm) showForm = false else onDismiss() }) {
                Text(if (showForm) "Back" else "Close")
            }
        },
    )
}

private fun formatDigLogTime(millis: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(millis))
