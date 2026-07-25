package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NormalizedRasterBounds
import com.example.ui.components.CustomFileLoader
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarControlPanel
import com.example.ui.components.LidarMapCanvas
import com.example.ui.components.TargetLoggerPanel
import com.example.ui.components.TerrainGoogleMapScreen
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private data class AppTab(
    val label: String,
    val title: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    AppTab("Terrain", "Terrain workspace", Icons.Default.Landscape),
    AppTab("Map", "Google Maps overlay", Icons.Default.Layers),
    AppTab("Gemini", "Gemini field assistant", Icons.Default.AutoAwesome),
    AppTab("Finds", "Field finds", Icons.Default.Flag),
    AppTab("Import", "Terrain library", Icons.Default.UploadFile),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HillshadeViewModel, modifier: Modifier = Modifier) {
    val selectedTab = rememberSaveable { mutableIntStateOf(0) }
    val terrainFocusMode = rememberSaveable { mutableStateOf(false) }
    val terrainSelected = selectedTab.intValue == 0
    val activeTab = tabs[selectedTab.intValue]

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!terrainSelected && !terrainFocusMode.value) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            activeTab.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
            }
        },
        bottomBar = {
            if (!terrainFocusMode.value) {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                    shadowElevation = 10.dp,
                    tonalElevation = 3.dp,
                ) {
                    NavigationBar(
                        modifier = Modifier.height(68.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTab.intValue == index,
                                onClick = {
                                    selectedTab.intValue = index
                                    terrainFocusMode.value = false
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label, maxLines = 1) },
                                alwaysShowLabel = false,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        when (selectedTab.intValue) {
            0 -> TerrainTab(
                viewModel = viewModel,
                focusMode = terrainFocusMode.value,
                onFocusModeChanged = { terrainFocusMode.value = it },
            )
            1 -> GoogleMapTab(viewModel, padding)
            2 -> GeminiTab(viewModel, padding)
            3 -> FindsTab(viewModel, padding)
            else -> ImportTab(viewModel, padding) {
                selectedTab.intValue = 0
                terrainFocusMode.value = false
            }
        }
    }
}

@Composable
private fun TerrainTab(
    viewModel: HillshadeViewModel,
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
    val gpsEnabled by viewModel.gpsEnabled.collectAsStateWithLifecycle()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val devicePosition by viewModel.deviceGridPosition.collectAsStateWithLifecycle()
    val heatmapEnabled by viewModel.heatmapEnabled.collectAsStateWithLifecycle()
    val basemapEnabled by viewModel.basemapEnabled.collectAsStateWithLifecycle()
    val basemapOpacity by viewModel.basemapOpacity.collectAsStateWithLifecycle()
    val basemapBitmap by viewModel.basemapBitmap.collectAsStateWithLifecycle()
    val basemapStatus by viewModel.basemapStatus.collectAsStateWithLifecycle()
    val vmViewportReset by viewModel.viewportResetKey.collectAsStateWithLifecycle()
    val viewportPanX by viewModel.viewportPanX.collectAsStateWithLifecycle()
    val viewportPanY by viewModel.viewportPanY.collectAsStateWithLifecycle()

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val showControls = rememberSaveable { mutableStateOf(false) }
    val localViewportResetKey = rememberSaveable { mutableIntStateOf(0) }
    val viewportResetKey = vmViewportReset + localViewportResetKey.intValue
    val context = LocalContext.current
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocationPermissionResult(granted) }

    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine) {
        if (canRefine && zoomLevel.value >= 2.5f) {
            delay(600)
            if (!isRefining) viewModel.refineTerrain(visibleBounds.value)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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
                .padding(top = 76.dp, bottom = 58.dp)
                .testTag("terrain_workspace"),
        )

        Surface(
            shape = RoundedCornerShape(15.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 4.dp,
            shadowElevation = 7.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 5.dp, vertical = 3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 7.dp),
                ) {
                    Icon(
                        Icons.Default.WbSunny,
                        contentDescription = "Sun direction",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(19.dp).rotate(azimuth),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${compassLabel(azimuth)} ${azimuth.roundToInt()}°",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TerrainQuickAction("Light -", Icons.Default.RotateLeft) { viewModel.rotateSunAzimuth(-45f) }
                TerrainQuickAction("Light +", Icons.Default.RotateRight) { viewModel.rotateSunAzimuth(45f) }
                TerrainQuickAction("Fit", Icons.Default.CenterFocusStrong) { localViewportResetKey.intValue++ }
                if (canRefine) {
                    TerrainQuickAction(
                        if (isRefining) "Loading" else if (isDetailed) "Refresh" else "Detail",
                        Icons.Default.ZoomInMap,
                        active = isDetailed,
                        enabled = !isRefining,
                    ) { viewModel.refineTerrain(visibleBounds.value) }
                    if (isDetailed) {
                        TerrainQuickAction("Whole", Icons.Default.ZoomOutMap) { viewModel.showWholeTerrain() }
                    }
                }
                TerrainQuickAction(
                    if (showControls.value) "Close" else "Analyze",
                    Icons.Default.Tune,
                    active = showControls.value,
                ) { showControls.value = !showControls.value }
                TerrainQuickAction(
                    if (gpsEnabled) "GPS on" else "GPS",
                    if (gpsEnabled && hasLocationPermission) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                    active = gpsEnabled && hasLocationPermission,
                ) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted && !gpsEnabled) {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                    viewModel.toggleGpsTracking(!gpsEnabled)
                }
                TerrainQuickAction(
                    if (focusMode) "Exit" else "Full",
                    if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                ) { onFocusModeChanged(!focusMode) }
            }
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 10.dp, bottom = 76.dp),
        ) {
            val widthMeters = (elevationGrid.width - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
            val heightMeters = (elevationGrid.height - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
            Text(
                String.format(
                    Locale.US,
                    "%d×%d · %.0f×%.0f m · %.2f m/cell · %s",
                    elevationGrid.width,
                    elevationGrid.height,
                    widthMeters,
                    heightMeters,
                    elevationGrid.cellSizeMeters,
                    if (showControls.value) "tools open" else "pinch / drag",
                ),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
        }

        AnimatedVisibility(
            visible = showControls.value,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(10.dp),
        ) {
            LidarControlPanel(
                selectedSiteIndex = site,
                onSiteSelected = viewModel::selectSite,
                sunAzimuth = azimuth,
                onSunAzimuthChanged = viewModel::updateSunAzimuth,
                sunAltitude = altitude,
                onSunAltitudeChanged = viewModel::updateSunAltitude,
                vegetationFilter = vegetation,
                onVegetationFilterChanged = viewModel::updateVegetationFilter,
                paletteType = palette,
                onPaletteTypeChanged = viewModel::updatePalette,
                contrast = contrast,
                onContrastChanged = viewModel::updateContrast,
                visualizationMode = visualization,
                onVisualizationModeChanged = viewModel::updateVisualizationMode,
                overlayType = overlay,
                onOverlayTypeChanged = viewModel::updateOverlayType,
                overlayOpacity = overlayOpacity,
                onOverlayOpacityChanged = viewModel::updateOverlayOpacity,
                gridSpacing = grid,
                onGridSpacingChanged = viewModel::updateGridSpacing,
                zScale = zScale,
                onZScaleChanged = viewModel::updateZScale,
                featureScaleMeters = featureScale,
                onFeatureScaleChanged = viewModel::updateFeatureScale,
                analysisSensitivity = sensitivity,
                onAnalysisSensitivityChanged = viewModel::updateAnalysisSensitivity,
                contourIntervalMeters = contourInterval,
                onContourIntervalChanged = viewModel::updateContourInterval,
                heatmapEnabled = heatmapEnabled,
                onHeatmapEnabledChanged = viewModel::setHeatmapEnabled,
                basemapEnabled = basemapEnabled,
                onBasemapEnabledChanged = viewModel::setBasemapEnabled,
                basemapOpacity = basemapOpacity,
                onBasemapOpacityChanged = viewModel::setBasemapOpacity,
                basemapStatus = basemapStatus,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = maxHeight * 0.76f)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun TerrainQuickAction(
    label: String,
    icon: ImageVector,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        val contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.width(19.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun GoogleMapTab(viewModel: HillshadeViewModel, padding: PaddingValues) {
    val bitmap by viewModel.hillshadeBitmap.collectAsStateWithLifecycle()
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    TerrainGoogleMapScreen(
        terrainBitmap = bitmap,
        grid = grid,
        metadata = metadata,
        modifier = Modifier.fillMaxSize().padding(padding),
    )
}

@Composable
private fun GeminiTab(viewModel: HillshadeViewModel, padding: PaddingValues) {
    AiAnalysisWorkspace(viewModel = viewModel, padding = padding)
}

@Composable
private fun FindsTab(viewModel: HillshadeViewModel, padding: PaddingValues) {
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val sweepX by viewModel.sweepX.collectAsStateWithLifecycle()
    val sweepY by viewModel.sweepY.collectAsStateWithLifecycle()
    TargetLoggerPanel(
        loggedSignals = signals,
        currentSweepX = sweepX,
        currentSweepY = sweepY,
        onLogSignal = viewModel::logCurrentSignal,
        onDeleteSignal = viewModel::deleteLoggedSignal,
        onUpdateSignal = viewModel::updateLoggedSignal,
        onClearAll = viewModel::clearLoggedSignals,
        modifier = Modifier.fillMaxSize().padding(padding),
    )
}

@Composable
private fun ImportTab(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    onImported: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CustomFileLoader(
            onCustomTerrainLoaded = { result, source ->
                viewModel.setCustomTerrain(result, source)
                onImported()
            },
        )
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
