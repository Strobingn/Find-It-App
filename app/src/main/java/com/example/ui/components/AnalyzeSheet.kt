package com.example.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** The four groupings the Analyze sheet splits the terrain controls into. */
enum class AnalyzeSegment(val label: String) {
    RELIEF("Relief"),
    LIGHTING("Lighting"),
    SCREENING("Screening"),
    OVERLAYS("Overlays"),
}

/**
 * The Map workspace's bottom sheet. Collapsed it is one row of segment tabs over a scrollable row
 * of the active segment's choices, so the map is never covered by more than ~140dp of chrome.
 * Dragging the handle up (or tapping it) expands it to the full [LidarControlPanel] card set, with
 * the segment tabs acting as jump targets into that list.
 */
@Composable
fun AnalyzeSheet(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    segment: AnalyzeSegment,
    onSegmentChange: (AnalyzeSegment) -> Unit,
    extentLabel: String,
    maxExpandedHeight: Dp,
    selectedSiteIndex: Int,
    onSiteSelected: (Int) -> Unit,
    sunAzimuth: Float,
    onSunAzimuthChanged: (Float) -> Unit,
    sunAltitude: Float,
    onSunAltitudeChanged: (Float) -> Unit,
    vegetationFilter: Float,
    onVegetationFilterChanged: (Float) -> Unit,
    paletteType: Int,
    onPaletteTypeChanged: (Int) -> Unit,
    contrast: Float,
    onContrastChanged: (Float) -> Unit,
    visualizationMode: Int,
    onVisualizationModeChanged: (Int) -> Unit,
    overlayType: Int,
    onOverlayTypeChanged: (Int) -> Unit,
    overlayOpacity: Float,
    onOverlayOpacityChanged: (Float) -> Unit,
    gridSpacing: Float,
    onGridSpacingChanged: (Float) -> Unit,
    zScale: Float,
    onZScaleChanged: (Float) -> Unit,
    featureScaleMeters: Float,
    onFeatureScaleChanged: (Float) -> Unit,
    analysisSensitivity: Float,
    onAnalysisSensitivityChanged: (Float) -> Unit,
    contourIntervalMeters: Float,
    onContourIntervalChanged: (Float) -> Unit,
    heatmapEnabled: Boolean,
    onHeatmapEnabledChanged: (Boolean) -> Unit,
    basemapEnabled: Boolean,
    onBasemapEnabledChanged: (Boolean) -> Unit,
    basemapOpacity: Float,
    onBasemapOpacityChanged: (Float) -> Unit,
    basemapStatus: String?,
    modifier: Modifier = Modifier,
) {
    // Every card, in segment order, so the segment tabs can scroll the expanded list to the right
    // place. Kept as a flat list of (segment, card) so the index lookup stays a single indexOfFirst.
    val cards: List<Pair<AnalyzeSegment, @Composable () -> Unit>> = listOf(
        AnalyzeSegment.RELIEF to { ReliefStyleCard(visualizationMode, onVisualizationModeChanged) },
        AnalyzeSegment.RELIEF to { SurfaceModelCard(vegetationFilter, onVegetationFilterChanged) },
        AnalyzeSegment.RELIEF to { ElevationPaletteCard(paletteType, onPaletteTypeChanged) },
        AnalyzeSegment.RELIEF to { TerrainSourceCard(selectedSiteIndex, onSiteSelected) },
        AnalyzeSegment.LIGHTING to {
            LightingCard(
                sunAzimuth = sunAzimuth,
                onSunAzimuthChanged = onSunAzimuthChanged,
                sunAltitude = sunAltitude,
                onSunAltitudeChanged = onSunAltitudeChanged,
                contrast = contrast,
                onContrastChanged = onContrastChanged,
                zScale = zScale,
                onZScaleChanged = onZScaleChanged,
            )
        },
        AnalyzeSegment.SCREENING to {
            FeatureScreeningCard(
                featureScaleMeters = featureScaleMeters,
                onFeatureScaleChanged = onFeatureScaleChanged,
                analysisSensitivity = analysisSensitivity,
                onAnalysisSensitivityChanged = onAnalysisSensitivityChanged,
                contourIntervalMeters = contourIntervalMeters,
                onContourIntervalChanged = onContourIntervalChanged,
            )
        },
        AnalyzeSegment.OVERLAYS to {
            LiveOverlaysCard(
                heatmapEnabled = heatmapEnabled,
                onHeatmapEnabledChanged = onHeatmapEnabledChanged,
                basemapEnabled = basemapEnabled,
                onBasemapEnabledChanged = onBasemapEnabledChanged,
                basemapOpacity = basemapOpacity,
                onBasemapOpacityChanged = onBasemapOpacityChanged,
                basemapStatus = basemapStatus,
            )
        },
        AnalyzeSegment.OVERLAYS to { HistoricalOverlayCard(overlayType, onOverlayTypeChanged, overlayOpacity, onOverlayOpacityChanged) },
        AnalyzeSegment.OVERLAYS to { SurveyGridCard(gridSpacing, onGridSpacingChanged) },
    )

    val listState = rememberLazyListState()

    // Expanding straight from a segment tab should land on that segment's first card, not wherever
    // the list happened to be left.
    LaunchedEffect(expanded, segment) {
        if (expanded) {
            val index = cards.indexOfFirst { it.first == segment }
            if (index >= 0) listState.animateScrollToItem(index)
        }
    }

    Surface(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.11f)),
        modifier = modifier.fillMaxWidth().testTag("analyze_sheet"),
    ) {
        Column(
            modifier = Modifier
                .animateContentSize()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp, top = 8.dp),
        ) {
            DragHandle(expanded = expanded, onExpandedChange = onExpandedChange)

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Analyze",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    extentLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(AnalyzeSegment.entries.size) { index ->
                    val entry = AnalyzeSegment.entries[index]
                    SegmentChip(
                        label = entry.label,
                        selected = entry == segment,
                        onClick = { onSegmentChange(entry) },
                    )
                }
            }

            if (expanded) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = maxExpandedHeight),
                ) {
                    items(cards.size) { index -> cards[index].second() }
                }
            } else {
                PeekRow(
                    segment = segment,
                    visualizationMode = visualizationMode,
                    onVisualizationModeChanged = onVisualizationModeChanged,
                    sunAzimuth = sunAzimuth,
                    onSunAzimuthChanged = onSunAzimuthChanged,
                    featureScaleMeters = featureScaleMeters,
                    analysisSensitivity = analysisSensitivity,
                    contourIntervalMeters = contourIntervalMeters,
                    overlayType = overlayType,
                    onOverlayTypeChanged = onOverlayTypeChanged,
                    gridSpacing = gridSpacing,
                    onGridSpacingChanged = onGridSpacingChanged,
                    heatmapEnabled = heatmapEnabled,
                    onHeatmapEnabledChanged = onHeatmapEnabledChanged,
                    basemapEnabled = basemapEnabled,
                    onBasemapEnabledChanged = onBasemapEnabledChanged,
                    onExpand = { onExpandedChange(true) },
                    modifier = Modifier.padding(top = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun DragHandle(expanded: Boolean, onExpandedChange: (Boolean) -> Unit) {
    // A plain float box rather than mutableFloatStateOf — the accumulator is only read inside the
    // drag callbacks, so making it snapshot state would recompose the sheet on every drag frame.
    val dragged = remember { floatArrayOf(0f) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> dragged[0] += delta },
                onDragStarted = { dragged[0] = 0f },
                onDragStopped = {
                    if (dragged[0] < -40f) onExpandedChange(true)
                    if (dragged[0] > 40f) onExpandedChange(false)
                },
            )
            .clickable(
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse terrain controls" else "Expand terrain controls",
                onClick = { onExpandedChange(!expanded) },
            )
            .testTag("analyze_sheet_handle"),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
            shape = RoundedCornerShape(2.dp),
        ) {
            Box(Modifier.size(width = 36.dp, height = 4.dp))
        }
    }
}

@Composable
private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag("analyze_segment_${label.lowercase()}"),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun PeekRow(
    segment: AnalyzeSegment,
    visualizationMode: Int,
    onVisualizationModeChanged: (Int) -> Unit,
    sunAzimuth: Float,
    onSunAzimuthChanged: (Float) -> Unit,
    featureScaleMeters: Float,
    analysisSensitivity: Float,
    contourIntervalMeters: Float,
    overlayType: Int,
    onOverlayTypeChanged: (Int) -> Unit,
    gridSpacing: Float,
    onGridSpacingChanged: (Float) -> Unit,
    heatmapEnabled: Boolean,
    onHeatmapEnabledChanged: (Boolean) -> Unit,
    basemapEnabled: Boolean,
    onBasemapEnabledChanged: (Boolean) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = modifier.fillMaxWidth().testTag("analyze_peek_row"),
    ) {
        when (segment) {
            AnalyzeSegment.RELIEF -> items(ReliefStyleOptions.size) { index ->
                val option = ReliefStyleOptions[index]
                PeekCard(
                    title = option.title,
                    subtitle = option.subtitle,
                    selected = visualizationMode == option.value,
                    onClick = { onVisualizationModeChanged(option.value) },
                )
            }

            AnalyzeSegment.LIGHTING -> {
                val snapped = ((sunAzimuth / 45f).roundToInt() * 45).mod(360)
                items(SunDirectionOptions.size) { index ->
                    val option = SunDirectionOptions[index]
                    PeekCard(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = snapped == option.value,
                        onClick = { onSunAzimuthChanged(option.value.toFloat()) },
                    )
                }
            }

            // Screening is three sliders with no discrete choices, so its peek reports the current
            // values and hands off to the expanded card rather than inventing presets.
            AnalyzeSegment.SCREENING -> {
                val readouts = listOf(
                    "Feature scale" to String.format(Locale.US, "%.1f m", featureScaleMeters),
                    "Sensitivity" to String.format(Locale.US, "%.1f×", analysisSensitivity),
                    "Contours" to contourLabel(contourIntervalMeters),
                )
                items(readouts.size) { index ->
                    PeekCard(
                        title = readouts[index].first,
                        subtitle = readouts[index].second,
                        selected = false,
                        onClick = onExpand,
                    )
                }
            }

            AnalyzeSegment.OVERLAYS -> {
                item {
                    PeekCard(
                        title = "Heatmap",
                        subtitle = if (heatmapEnabled) "On" else "Off",
                        selected = heatmapEnabled,
                        onClick = { onHeatmapEnabledChanged(!heatmapEnabled) },
                    )
                }
                item {
                    PeekCard(
                        title = "Basemap",
                        subtitle = if (basemapEnabled) "On" else "Off",
                        selected = basemapEnabled,
                        onClick = { onBasemapEnabledChanged(!basemapEnabled) },
                    )
                }
                items(SurveyGridOptions.size) { index ->
                    val option = SurveyGridOptions[index]
                    PeekCard(
                        title = if (option.value == 0) "Grid off" else "Grid ${option.title}",
                        subtitle = option.subtitle,
                        selected = gridSpacing.toInt() == option.value,
                        onClick = { onGridSpacingChanged(option.value.toFloat()) },
                    )
                }
                items(HistoricalOverlayOptions.size) { index ->
                    val option = HistoricalOverlayOptions[index]
                    PeekCard(
                        title = option.title,
                        subtitle = option.subtitle,
                        selected = overlayType == option.value,
                        onClick = { onOverlayTypeChanged(option.value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PeekCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        border = if (selected) BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)) else null,
        modifier = Modifier
            .width(112.dp)
            .clickable(role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (selected) 1f else 0.7f),
                maxLines = 1,
            )
        }
    }
}
