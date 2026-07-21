package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NormalizedRasterBounds
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
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // Always reserve status + nav bar space so system bars never cover UI.
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            if (!terrainFocusMode.value) {
                TopAppBar(
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    windowInsets = WindowInsets.safeDrawing,
                )
            }
        },
        bottomBar = {
            if (!terrainFocusMode.value) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    windowInsets = NavigationBarDefaults.windowInsets,
                ) {
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
    val autoRefine by viewModel.autoRefineTerrain.collectAsStateWithLifecycle()
    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val showControls = rememberSaveable { mutableStateOf(false) }
    val viewportResetKey = rememberSaveable { mutableIntStateOf(0) }

    // Always honor Scaffold safe-drawing padding (status + nav bars), even in focus mode.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
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
            viewportResetKey = viewportResetKey.intValue,
            showSurveyCursor = false,
            showCoordinateHud = false,
            contentBounds = remember(elevationGrid) { elevationGrid.validContentBounds() },
            onViewportChanged = { bounds, zoom ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
                // Debounced auto re-rasterize of LAZ/LAS/etc. for this viewport.
                viewModel.onExploreViewportChanged(bounds, zoom)
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(if (focusMode) 4.dp else 8.dp)
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
                IconButton(onClick = { viewportResetKey.intValue++ }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit terrain to screen")
                }
                IconButton(onClick = { showControls.value = !showControls.value }) {
                    Icon(Icons.Default.Tune, contentDescription = "Show terrain controls")
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
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, end = if (canRefine) 168.dp else 14.dp, bottom = 14.dp)
                .fillMaxWidth(if (canRefine) 0.58f else 0.92f),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                val widthMeters = (elevationGrid.width - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
                val heightMeters = (elevationGrid.height - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
                Text(
                    "${elevationGrid.width}×${elevationGrid.height} · ${widthMeters.format(0)}×${heightMeters.format(0)} m · ${elevationGrid.cellSizeMeters.toDouble().format(2)} m/cell",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        showControls.value -> "Controls open"
                        detailMessage != null -> detailMessage!!
                        else -> "Pinch to zoom · drag to pan · Tune for analysis"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (canRefine) Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(14.dp)
                .widthIn(max = 168.dp),
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "${zoomLevel.value.format(1)}× · pan to target",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Auto detail",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Switch(
                        checked = autoRefine,
                        onCheckedChange = viewModel::setAutoRefineTerrain,
                        enabled = canRefine,
                    )
                }
                Button(
                    onClick = { viewModel.refineTerrain(visibleBounds.value) },
                    enabled = zoomLevel.value >= 1.5f && !isRefining,
                ) {
                    Text(
                        when {
                            isRefining -> "Reading file…"
                            autoRefine -> "Refresh detail"
                            else -> "Load detail here"
                        },
                    )
                }
                if (isDetailed) TextButton(onClick = viewModel::showWholeTerrain) {
                    Text("Whole file")
                }
            }
        }

        AnimatedVisibility(
            visible = showControls.value,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.72f),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            terrainSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    item {
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FindsTab(viewModel: HillshadeViewModel, padding: PaddingValues) {
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val x by viewModel.sweepX.collectAsStateWithLifecycle()
    val y by viewModel.sweepY.collectAsStateWithLifecycle()
    TargetLoggerPanel(
        loggedSignals = signals,
        currentSweepX = x,
        currentSweepY = y,
        onLogSignal = viewModel::logCurrentSignal,
        onDeleteSignal = viewModel::deleteLoggedSignal,
        onUpdateSignal = viewModel::updateLoggedSignal,
        onClearAll = viewModel::clearLoggedSignals,
        modifier = Modifier.fillMaxSize().padding(padding),
    )
}

@Composable
private fun ImportTab(viewModel: HillshadeViewModel, padding: PaddingValues, onImported: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
        item {
            CustomFileLoader(
                onCustomTerrainLoaded = { result, source ->
                    viewModel.setCustomTerrain(result, source)
                    onImported()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Double.format(places: Int) = String.format(Locale.US, "%.${places}f", this)

private fun Float.format(places: Int) = toDouble().format(places)

private fun compassLabel(azimuth: Float): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((azimuth % 360f) + 360f) % 360f
    return directions[((normalized / 45f).roundToInt()).mod(directions.size)]
}
