package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.example.data.NormalizedRasterBounds
import com.example.ui.components.CustomFileLoader
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarControlPanel
import com.example.ui.components.LidarMapCanvas
import com.example.ui.components.TargetLoggerPanel
import kotlinx.coroutines.delay
import java.util.Locale

private data class AppTab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    AppTab("Terrain", Icons.Default.Map),
    AppTab("Analysis", Icons.Default.Tune),
    AppTab("Finds", Icons.Default.Flag),
    AppTab("Import", Icons.Default.UploadFile),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HillshadeViewModel, modifier: Modifier = Modifier) {
    val selectedTab = rememberSaveable { mutableIntStateOf(0) }
    val fullScreen = rememberSaveable { mutableStateOf(false) }
    val analysisViewModel = composeViewModel<TerrainAnalysisViewModel>()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (!fullScreen.value) {
                TopAppBar(title = { Text("Find It", fontWeight = FontWeight.Bold) })
            }
        },
        bottomBar = {
            if (!fullScreen.value) {
                NavigationBar {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab.intValue == index,
                            onClick = { selectedTab.intValue = index },
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
                fullScreen = fullScreen.value,
                onFullScreenChanged = { fullScreen.value = it },
            )
            1 -> TerrainAnalysisScreen(viewModel, analysisViewModel, padding)
            2 -> FindsTab(viewModel, padding)
            else -> ImportTab(viewModel, padding) {
                selectedTab.intValue = 0
                fullScreen.value = true
            }
        }
    }
}

@Composable
private fun TerrainTab(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    fullScreen: Boolean,
    onFullScreenChanged: (Boolean) -> Unit,
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
    val gridSpacing by viewModel.gridSpacing.collectAsStateWithLifecycle()
    val zScale by viewModel.zScale.collectAsStateWithLifecycle()
    val featureScale by viewModel.featureScaleMeters.collectAsStateWithLifecycle()
    val sensitivity by viewModel.analysisSensitivity.collectAsStateWithLifecycle()
    val contourInterval by viewModel.contourIntervalMeters.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val isDetailed by viewModel.isDetailedTerrain.collectAsStateWithLifecycle()
    val detailMessage by viewModel.terrainDetailMessage.collectAsStateWithLifecycle()
    val devicePosition by viewModel.deviceGridPosition.collectAsStateWithLifecycle()
    val heatmapEnabled by viewModel.heatmapEnabled.collectAsStateWithLifecycle()
    val basemapEnabled by viewModel.basemapEnabled.collectAsStateWithLifecycle()
    val basemapOpacity by viewModel.basemapOpacity.collectAsStateWithLifecycle()
    val basemapBitmap by viewModel.basemapBitmap.collectAsStateWithLifecycle()
    val basemapStatus by viewModel.basemapStatus.collectAsStateWithLifecycle()
    val vmViewportReset by viewModel.viewportResetKey.collectAsStateWithLifecycle()

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoom = rememberSaveable { mutableStateOf(1f) }
    val controlsVisible = rememberSaveable { mutableStateOf(false) }
    val autoRefineArmed = rememberSaveable { mutableStateOf(true) }
    val previousRefining = remember { mutableStateOf(false) }
    val localReset = rememberSaveable { mutableIntStateOf(0) }
    val resetKey = vmViewportReset + localReset.intValue

    LaunchedEffect(isRefining, vmViewportReset) {
        if (previousRefining.value && !isRefining) {
            zoom.value = 1f
            autoRefineArmed.value = true
        }
        previousRefining.value = isRefining
    }

    LaunchedEffect(zoom.value, visibleBounds.value, canRefine, isDetailed, isRefining) {
        val threshold = if (isDetailed) 2f else 1.5f
        if (zoom.value < threshold - 0.05f) autoRefineArmed.value = true
        if (canRefine && !isRefining && autoRefineArmed.value && zoom.value >= threshold) {
            autoRefineArmed.value = false
            delay(250)
            if (!isRefining && zoom.value >= threshold) {
                viewModel.refineTerrain(visibleBounds.value)
            } else {
                autoRefineArmed.value = true
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (fullScreen) PaddingValues(0.dp) else padding),
    ) {
        LidarMapCanvas(
            bitmap = bitmap,
            isRendering = isRendering,
            sweepX = sweepX,
            sweepY = sweepY,
            loggedSignals = signals,
            onSweepPositionChanged = viewModel::setSweepPosition,
            onStopSweeping = {},
            gridSpacing = gridSpacing,
            geoMetadata = metadata,
            currentLat = null,
            currentLon = null,
            mode = LidarCanvasMode.EXPLORE,
            viewportResetKey = resetKey,
            showSurveyCursor = false,
            showCoordinateHud = false,
            onViewportChanged = { bounds, level ->
                visibleBounds.value = bounds
                zoom.value = level
            },
            showHeatmap = heatmapEnabled,
            basemapBitmap = basemapBitmap,
            showBasemap = basemapEnabled,
            basemapOpacity = basemapOpacity,
            basemapStatus = basemapStatus,
            deviceGridPosition = devicePosition,
            modifier = Modifier.fillMaxSize().testTag("terrain_workspace"),
        )

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(10.dp),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp)) {
                IconButton(onClick = { onFullScreenChanged(!fullScreen) }) {
                    Icon(
                        if (fullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = if (fullScreen) "Exit full screen" else "Full screen",
                    )
                }
                IconButton(
                    enabled = canRefine && !isRefining,
                    onClick = {
                        autoRefineArmed.value = false
                        viewModel.refineTerrain(visibleBounds.value)
                    },
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rerender visible area")
                }
                IconButton(onClick = { localReset.intValue++ }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Fit terrain")
                }
                IconButton(onClick = { controlsVisible.value = !controlsVisible.value }) {
                    Icon(Icons.Default.Tune, contentDescription = "Terrain controls")
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ) {
            val widthMeters = (elevationGrid.width - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
            val heightMeters = (elevationGrid.height - 1).coerceAtLeast(1) * elevationGrid.cellSizeMeters
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(
                    String.format(
                        Locale.US,
                        "%d×%d · %.0f×%.0f m · %.2f m/cell",
                        elevationGrid.width,
                        elevationGrid.height,
                        widthMeters,
                        heightMeters,
                        elevationGrid.cellSizeMeters,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    when {
                        isRefining -> "Rendering detailed viewport…"
                        canRefine && !isDetailed -> "First auto-render at 1.5×"
                        canRefine -> "Next auto-render at 2.0×"
                        else -> "Pinch to zoom · drag to pan"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (canRefine && (isDetailed || isRefining || !detailMessage.isNullOrBlank())) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 10.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            isRefining -> "Loading detail…"
                            isDetailed -> "Detail loaded"
                            else -> detailMessage.orEmpty()
                        },
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (isDetailed) {
                        TextButton(onClick = viewModel::showWholeTerrain) { Text("Whole") }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible.value,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
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
                gridSpacing = gridSpacing,
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
                    .heightIn(max = maxHeight * 0.82f)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        CustomFileLoader(
            onCustomTerrainLoaded = { result, source ->
                viewModel.setCustomTerrain(result, source)
                onImported()
            },
        )
    }
}
