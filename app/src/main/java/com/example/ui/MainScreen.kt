package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NormalizedRasterBounds
import kotlinx.coroutines.delay
import com.example.ui.components.CustomFileLoader
import com.example.ui.components.LidarControlPanel
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarMapCanvas
import com.example.ui.components.TargetLoggerPanel
import java.util.Locale
import kotlin.math.roundToInt

private data class AppTab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    AppTab("Terrain", Icons.Default.Map),
    AppTab("Finds", Icons.Default.Flag),
    AppTab("Import", Icons.Default.UploadFile),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HillshadeViewModel, modifier: Modifier = Modifier) {
    val selectedTab = rememberSaveable { mutableIntStateOf(0) }
    val terrainFocusMode = rememberSaveable { mutableStateOf(true) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!terrainFocusMode.value) TopAppBar(
                title = {
                    Column {
                        Text("Find It", fontWeight = FontWeight.Bold)
                        Text(
                            "LiDAR terrain and field survey",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (!terrainFocusMode.value) NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab.intValue == index,
                        onClick = {
                            selectedTab.intValue = index
                            terrainFocusMode.value = false
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (selectedTab.intValue) {
            0 -> TerrainTab(
                viewModel = viewModel,
                padding = padding,
                focusMode = terrainFocusMode.value,
                onFocusModeChanged = { terrainFocusMode.value = it },
            )
            1 -> FindsTab(viewModel, padding)
            else -> ImportTab(viewModel, padding) {
                selectedTab.intValue = 0
                terrainFocusMode.value = true
            }
        }
    }
}

@Composable
private fun TerrainTab(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    focusMode: Boolean,
    onFocusModeChanged: (Boolean) -> Unit,
) {
    val site by viewModel.currentSiteIndex.collectAsStateWithLifecycle()
    val bitmap by viewModel.hillshadeBitmap.collectAsStateWithLifecycle()
    val isRendering by viewModel.isRendering.collectAsStateWithLifecycle()
    val sweepX by viewModel.sweepX.collectAsStateWithLifecycle()
    val sweepY by viewModel.sweepY.collectAsStateWithLifecycle()
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    val elevationGrid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val terrainSummary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val azimuth by viewModel.sunAzimuth.collectAsStateWithLifecycle()
    val altitude by viewModel.sunAltitude.collectAsStateWithLifecycle()
    val vegetation by viewModel.vegetationFilter.collectAsStateWithLifecycle()
    val palette by viewModel.paletteType.collectAsStateWithLifecycle()
    val contrast by viewModel.contrast.collectAsStateWithLifecycle()
    val visualization by viewModel.visualizationMode.collectAsStateWithLifecycle()
    val overlay by viewModel.overlayType.collectAsStateWithLifecycle()
    val overlayOpacity by viewModel.overlayOpacity.collectAsStateWithLifecycle()
    val grid by viewModel.gridSpacing.collectAsStateWithLifecycle()
    val zScale by viewModel.zScale.collectAsStateWithLifecycle()
    val featureScale by viewModel.featureScaleMeters.collectAsStateWithLifecycle()
    val sensitivity by viewModel.analysisSensitivity.collectAsStateWithLifecycle()
    val contourInterval by viewModel.contourIntervalMeters.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val isDetailed by viewModel.isDetailedTerrain.collectAsStateWithLifecycle()
    val detailMessage by viewModel.terrainDetailMessage.collectAsStateWithLifecycle()
    val gpsEnabled by viewModel.gpsEnabled.collectAsStateWithLifecycle()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val devicePosition by viewModel.deviceGridPosition.collectAsStateWithLifecycle()
    val heatmapEnabled by viewModel.heatmapEnabled.collectAsStateWithLifecycle()
    val basemapEnabled by viewModel.basemapEnabled.collectAsStateWithLifecycle()
    val basemapOpacity by viewModel.basemapOpacity.collectAsStateWithLifecycle()
    val basemapBitmap by viewModel.basemapBitmap.collectAsStateWithLifecycle()
    val basemapStatus by viewModel.basemapStatus.collectAsStateWithLifecycle()
    val vmViewportReset by viewModel.viewportResetKey.collectAsStateWithLifecycle()

    val viewportZoom by viewModel.viewportZoom.collectAsStateWithLifecycle()
    val viewportPanX by viewModel.viewportPanX.collectAsStateWithLifecycle()
    val viewportPanY by viewModel.viewportPanY.collectAsStateWithLifecycle()

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val showControls = rememberSaveable { mutableStateOf(false) }
    val localViewportResetKey = rememberSaveable { mutableIntStateOf(0) }
    val viewportResetKey = vmViewportReset + localViewportResetKey.intValue
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocationPermissionResult(granted) }
    val context = LocalContext.current

    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine) {
        if (canRefine && zoomLevel.value >= 2.5f) {
            delay(600)
            if (!isRefining) {
                viewModel.refineTerrain(visibleBounds.value)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (focusMode) PaddingValues(0.dp) else padding),
    ) {
        LidarMapCanvas(
            bitmap = bitmap,
            isRendering = isRendering,
            sweepX = sweepX,
            sweepY = sweepY,
            loggedSignals = signals,
            onSweepPositionChanged = viewModel::setSweepPosition,
            onStopSweeping = {},
            gridSpacing = grid,
            geoMetadata = metadata,
            currentLat = null,
            currentLon = null,
            mode = LidarCanvasMode.EXPLORE,
            viewportResetKey = viewportResetKey,
            showSurveyCursor = false,
            showCoordinateHud = false,
            onViewportChanged = { bounds, zoom ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
                viewModel.updateViewport(zoom, viewportPanX, viewportPanY)
            },
            showHeatmap = heatmapEnabled,
            basemapBitmap = basemapBitmap,
            showBasemap = basemapEnabled,
            basemapOpacity = basemapOpacity,
            basemapStatus = basemapStatus,
            deviceGridPosition = devicePosition,
            modifier = Modifier
                .fillMaxSize()
                .padding(if (focusMode) 0.dp else 8.dp)
                .testTag("terrain_workspace"),
        )

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 6.dp,
            modifier = Modifier.align(Alignment.TopCenter).padding(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                IconButton(onClick = { viewModel.rotateSunAzimuth(-45f) }) {
                    Icon(Icons.Default.RotateLeft, contentDescription = "Rotate light 45 degrees left")
                }
                Icon(
                    Icons.Default.WbSunny,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(azimuth),
                )
                Text(
                    "${compassLabel(azimuth)} ${azimuth.roundToInt()}°",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = { viewModel.rotateSunAzimuth(45f) }) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate light 45 degrees right")
                }
                IconButton(onClick = { localViewportResetKey.intValue++ }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit terrain to screen")
                }
                IconButton(onClick = { showControls.value = !showControls.value }) {
                    Icon(Icons.Default.Tune, contentDescription = "Show terrain controls")
                }
                IconButton(onClick = {
                    val alreadyGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!alreadyGranted && !gpsEnabled) {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    viewModel.toggleGpsTracking(!gpsEnabled)
                }) {
                    Icon(
                        if (gpsEnabled && hasLocationPermission) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                        contentDescription = if (gpsEnabled) "Stop showing my location" else "Show my location",
                        tint = if (gpsEnabled && hasLocationPermission) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { onFocusModeChanged(!focusMode) }) {
                    Icon(
                        if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (focusMode) "Exit full screen" else "Open full screen",
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                val widthMeters = (elevationGrid.width - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
                val heightMeters = (elevationGrid.height - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
                Text(
                    "${elevationGrid.width}×${elevationGrid.height} · ${widthMeters.format(0)}×${heightMeters.format(0)} m · ${elevationGrid.cellSizeMeters.toDouble().format(2)} m/cell",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    if (showControls.value) "Controls open" else "Pinch to zoom · drag to pan · Tune for analysis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (canRefine) Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 4.dp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(if (isDetailed) "Detailed terrain" else "Detail available", fontWeight = FontWeight.Bold)
                Text(detailMessage, style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = { viewModel.refineTerrain(visibleBounds.value) },
                    enabled = !isRefining,
                ) {
                    Text(if (isRefining) "Loading…" else "Load detail here")
                }
                TextButton(onClick = viewModel::showWholeTerrain) {
                    Text("Show whole terrain")
                }
            }
        }

        AnimatedVisibility(
            visible = showControls.value,
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
        ) {
            LidarControlPanel(
                sunAzimuth = azimuth,
                sunAltitude = altitude,
                vegetationFilter = vegetation,
                paletteType = palette,
                contrast = contrast,
                visualizationMode = visualization,
                overlayType = overlay,
                overlayOpacity = overlayOpacity,
                gridSpacing = grid,
                zScale = zScale,
                featureScaleMeters = featureScale,
                analysisSensitivity = sensitivity,
                contourIntervalMeters = contourInterval,
                heatmapEnabled = heatmapEnabled,
                basemapEnabled = basemapEnabled,
                basemapOpacity = basemapOpacity,
                onSunAzimuthChanged = viewModel::setSunAzimuth,
                onSunAltitudeChanged = viewModel::setSunAltitude,
                onVegetationFilterChanged = viewModel::setVegetationFilter,
                onPaletteTypeChanged = viewModel::setPaletteType,
                onContrastChanged = viewModel::setContrast,
                onVisualizationModeChanged = viewModel::setVisualizationMode,
                onOverlayTypeChanged = viewModel::setOverlayType,
                onOverlayOpacityChanged = viewModel::setOverlayOpacity,
                onGridSpacingChanged = viewModel::setGridSpacing,
                onZScaleChanged = viewModel::setZScale,
                onFeatureScaleMetersChanged = viewModel::setFeatureScaleMeters,
                onAnalysisSensitivityChanged = viewModel::setAnalysisSensitivity,
                onContourIntervalMetersChanged = viewModel::setContourIntervalMeters,
                onHeatmapEnabledChanged = viewModel::setHeatmapEnabled,
                onBasemapEnabledChanged = viewModel::setBasemapEnabled,
                onBasemapOpacityChanged = viewModel::setBasemapOpacity,
                terrainSummary = terrainSummary,
                currentSiteIndex = site,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = maxHeight * 0.88f),
            )
        }
    }
}

@Composable
private fun FindsTab(viewModel: HillshadeViewModel, padding: PaddingValues) {
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Finds", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Logged targets and field observations", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (signals.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                    Text("No finds logged yet.", modifier = Modifier.padding(18.dp))
                }
            }
        } else {
            items(signals) { signal ->
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(signal.label, fontWeight = FontWeight.Bold)
                        Text(
                            "Grid ${signal.x.roundToInt()}, ${signal.y.roundToInt()}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
        item { TargetLoggerPanel(viewModel = viewModel) }
    }
}

@Composable
private fun ImportTab(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    onImported: () -> Unit,
) {
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Import terrain", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Open GeoTIFF, TIFF, LAS, LAZ, or ASCII elevation data from device storage.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CustomFileLoader(
            onFileSelected = { uri ->
                viewModel.importTerrain(uri)
                onImported()
            },
        )
        importStatus?.let { status ->
            Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                Text(
                    status,
                    modifier = Modifier.padding(16.dp),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Button(onClick = viewModel::loadSampleTerrain) {
            Text("Load sample terrain")
        }
    }
}

private fun compassLabel(azimuth: Float): String {
    val normalized = ((azimuth % 360f) + 360f) % 360f
    return when {
        normalized < 22.5f || normalized >= 337.5f -> "N"
        normalized < 67.5f -> "NE"
        normalized < 112.5f -> "E"
        normalized < 157.5f -> "SE"
        normalized < 202.5f -> "S"
        normalized < 247.5f -> "SW"
        normalized < 292.5f -> "W"
        else -> "NW"
    }
}

private fun Double.format(decimals: Int): String = String.format(Locale.US, "%.${decimals}f", this)
private fun Float.format(decimals: Int): String = toDouble().format(decimals)
