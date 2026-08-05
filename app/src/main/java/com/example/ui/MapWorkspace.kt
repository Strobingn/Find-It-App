package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NormalizedRasterBounds
import com.example.ui.components.AnalyzeSegment
import com.example.ui.components.AnalyzeSheet
import com.example.ui.components.GlassPanel
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarMapCanvas
import com.example.ui.components.PillText
import com.example.ui.components.RailIconButton
import com.example.ui.components.ReliefStyleOptions
import com.example.ui.components.StatusPill
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The merged Map workspace: the terrain canvas is the whole screen and every control floats over it
 * — a glass header with the layer switcher, a right-hand icon rail, status pills top-left, and the
 * Analyze peek sheet along the bottom.
 */
@Composable
fun MapWorkspace(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    focusMode: Boolean,
    onFocusModeChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
    val detailMessage by viewModel.terrainDetailMessage.collectAsStateWithLifecycle()
    val gpsEnabled by viewModel.gpsEnabled.collectAsStateWithLifecycle()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val devicePosition by viewModel.deviceGridPosition.collectAsStateWithLifecycle()
    val heatmapEnabled by viewModel.heatmapEnabled.collectAsStateWithLifecycle()
    val basemapEnabled by viewModel.basemapEnabled.collectAsStateWithLifecycle()
    val basemapOpacity by viewModel.basemapOpacity.collectAsStateWithLifecycle()
    val basemapBitmap by viewModel.basemapBitmap.collectAsStateWithLifecycle()
    val basemapStatus by viewModel.basemapStatus.collectAsStateWithLifecycle()

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val viewportResetKey = rememberSaveable { mutableIntStateOf(0) }
    var sheetExpanded by rememberSaveable { mutableStateOf(false) }
    var sheetSegment by rememberSaveable { mutableStateOf(AnalyzeSegment.RELIEF) }
    var layerMenuOpen by remember { mutableStateOf(false) }
    var overflowMenuOpen by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onLocationPermissionResult(granted) }
    val context = LocalContext.current

    // Auto-load higher-resolution detail from the original LAZ/LAS once the user zooms/pans into
    // a small enough area — debounced so continuous pinch/pan gestures don't each trigger a
    // reparse; only fires once the viewport settles. The rail's "Load detail here" action stays
    // available for an immediate re-trigger.
    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine) {
        if (canRefine && zoomLevel.value >= 1.5f) {
            delay(600)
            if (!isRefining) {
                viewModel.refineTerrain(visibleBounds.value)
            }
        }
    }

    // In full screen the map runs to the bottom edge, but the header still clears the status bar —
    // otherwise the site name sits under the clock.
    val chromePadding = if (focusMode) PaddingValues(top = padding.calculateTopPadding()) else padding

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val sheetMaxHeight = maxHeight * 0.72f

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
            onViewportChanged = { bounds, zoom ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
            },
            showHeatmap = heatmapEnabled,
            basemapBitmap = basemapBitmap,
            showBasemap = basemapEnabled,
            basemapOpacity = basemapOpacity,
            basemapStatus = basemapStatus,
            deviceGridPosition = devicePosition,
            modifier = Modifier.fillMaxSize().testTag("terrain_workspace"),
        )

        Column(modifier = Modifier.fillMaxSize().padding(chromePadding).padding(12.dp)) {
            MapHeader(
                title = metadata.siteName,
                subtitle = "${elevationGrid.width}×${elevationGrid.height} · " +
                    "${elevationGrid.cellSizeMeters.toDouble().format(2)} m/cell",
                layerName = ReliefStyleOptions.firstOrNull { it.value == visualization }?.title.orEmpty(),
                menuOpen = layerMenuOpen,
                onMenuOpenChange = { layerMenuOpen = it },
                onLayerSelected = {
                    viewModel.updateVisualizationMode(it)
                    layerMenuOpen = false
                },
                selectedLayer = visualization,
            )

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                // Weighted so a long refine message wraps inside its pill instead of growing the
                // column and pushing the icon rail off the right edge.
                Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    StatusPill(
                        modifier = Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = "Open lighting controls",
                        ) {
                            sheetSegment = AnalyzeSegment.LIGHTING
                            sheetExpanded = true
                        },
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        ) {
                            Icon(
                                Icons.Default.WbSunny,
                                contentDescription = "Sun direction",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp).rotate(azimuth),
                            )
                            PillText("${compassLabel(azimuth)} ${azimuth.roundToInt()}°", mono = true)
                        }
                    }

                    detailStatus(
                        canRefine = canRefine,
                        isRefining = isRefining,
                        detailMessage = detailMessage,
                        zoomLevel = zoomLevel.value,
                    )?.let { status ->
                        StatusPill {
                            PillText(
                                status,
                                emphasis = false,
                                maxLines = 2,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }
                }

                GlassPanel {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(6.dp),
                    ) {
                        RailIconButton(
                            icon = Icons.Default.WbSunny,
                            contentDescription = "Lighting controls",
                            active = sheetSegment == AnalyzeSegment.LIGHTING,
                            onClick = {
                                sheetSegment = AnalyzeSegment.LIGHTING
                                sheetExpanded = true
                            },
                        )
                        RailIconButton(
                            icon = Icons.Default.CenterFocusStrong,
                            contentDescription = "Fit terrain to screen",
                            onClick = { viewportResetKey.intValue++ },
                        )
                        if (canRefine) {
                            RailIconButton(
                                icon = Icons.Default.ZoomInMap,
                                contentDescription = if (isRefining) {
                                    "Reading original LAZ"
                                } else {
                                    "Load detail for this viewport"
                                },
                                enabled = zoomLevel.value >= 1.5f && !isRefining,
                                onClick = { viewModel.refineTerrain(visibleBounds.value) },
                            )
                            RailIconButton(
                                icon = Icons.Default.ZoomOutMap,
                                contentDescription = "Show the whole file",
                                enabled = isDetailed,
                                onClick = viewModel::showWholeTerrain,
                            )
                        }
                        RailIconButton(
                            icon = if (gpsEnabled && hasLocationPermission) {
                                Icons.Default.GpsFixed
                            } else {
                                Icons.Default.GpsNotFixed
                            },
                            contentDescription = if (gpsEnabled) {
                                "Stop showing my location"
                            } else {
                                "Show my location"
                            },
                            active = gpsEnabled && hasLocationPermission,
                            onClick = {
                                val alreadyGranted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (!alreadyGranted && !gpsEnabled) {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                viewModel.toggleGpsTracking(!gpsEnabled)
                            },
                        )

                        Box(
                            Modifier
                                .padding(horizontal = 6.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.12f)),
                        )

                        Box {
                            RailIconButton(
                                icon = Icons.Default.MoreHoriz,
                                contentDescription = "More map options",
                                onClick = { overflowMenuOpen = true },
                            )
                            DropdownMenu(
                                expanded = overflowMenuOpen,
                                onDismissRequest = { overflowMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (heatmapEnabled) "Hide dig heatmap" else "Show dig heatmap") },
                                    leadingIcon = { Icon(Icons.Default.Thermostat, contentDescription = null) },
                                    onClick = {
                                        viewModel.setHeatmapEnabled(!heatmapEnabled)
                                        overflowMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (basemapEnabled) "Hide basemap" else "Show basemap") },
                                    leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) },
                                    onClick = {
                                        viewModel.setBasemapEnabled(!basemapEnabled)
                                        overflowMenuOpen = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (focusMode) "Exit full screen" else "Full screen") },
                                    leadingIcon = {
                                        Icon(
                                            if (focusMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        onFocusModeChanged(!focusMode)
                                        overflowMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnalyzeSheet(
            expanded = sheetExpanded,
            onExpandedChange = { sheetExpanded = it },
            segment = sheetSegment,
            onSegmentChange = { sheetSegment = it },
            extentLabel = extentLabel(
                elevationGrid.width,
                elevationGrid.height,
                elevationGrid.cellSizeMeters,
            ),
            maxExpandedHeight = sheetMaxHeight,
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
                .align(Alignment.BottomCenter)
                .padding(bottom = chromePadding.calculateBottomPadding()),
        )
    }
}

@Composable
private fun MapHeader(
    title: String,
    subtitle: String,
    layerName: String,
    selectedLayer: Int,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onLayerSelected: (Int) -> Unit,
) {
    GlassPanel(modifier = Modifier.fillMaxWidth().testTag("map_header")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box {
                Surface(
                    color = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
                    modifier = Modifier
                        .clickable(role = Role.DropdownList) { onMenuOpenChange(true) }
                        .testTag("layer_switcher"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text(
                            layerName,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = "Change map layer",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    ReliefStyleOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.title, fontWeight = FontWeight.Medium)
                                    Text(
                                        option.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingIcon = if (option.value == selectedLayer) {
                                { Icon(Icons.Default.Check, contentDescription = "Current layer") }
                            } else {
                                null
                            },
                            onClick = { onLayerSelected(option.value) },
                            modifier = Modifier.width(232.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The one-line refine/detail readout that sits under the sun pill, or null when there's nothing to say. */
private fun detailStatus(
    canRefine: Boolean,
    isRefining: Boolean,
    detailMessage: String?,
    zoomLevel: Float,
): String? = when {
    isRefining -> "Reading original LAZ…"
    detailMessage != null -> detailMessage
    canRefine && zoomLevel < 1.5f -> "${zoomLevel.format(1)}× · zoom in to load detail"
    canRefine -> "${zoomLevel.format(1)}× · detail ready"
    else -> null
}

/** Ground extent of the grid, in the "1.4 km × 1.4 km" form the Analyze sheet header uses. */
internal fun extentLabel(columns: Int, rows: Int, cellSizeMeters: Float): String {
    val width = (columns - 1).coerceAtLeast(1) * cellSizeMeters
    val height = (rows - 1).coerceAtLeast(1) * cellSizeMeters
    fun span(meters: Float) = if (meters >= 1_000f) {
        "${(meters / 1_000f).format(1)} km"
    } else {
        "${meters.roundToInt()} m"
    }
    return "${span(width)} × ${span(height)}"
}

private fun Double.format(places: Int) = String.format(Locale.US, "%.${places}f", this)

private fun Float.format(places: Int) = toDouble().format(places)

internal fun compassLabel(azimuth: Float): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((azimuth % 360f) + 360f) % 360f
    return directions[((normalized / 45f).roundToInt()).mod(directions.size)]
}
