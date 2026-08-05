package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** One selectable choice in a control card. Shared with the Map workspace's layer switcher. */
data class ControlOption(val value: Int, val title: String, val subtitle: String)

/**
 * The nine relief renderings, indexed by [com.example.ui.HillshadeViewModel.visualizationMode].
 * Exposed so the Map header's layer switcher and the Analyze sheet's peek row name them the same
 * way this panel does.
 */
val ReliefStyleOptions = listOf(
    ControlOption(0, "Hillshade", "Single light source"),
    ControlOption(1, "Multi-light", "Four balanced directions"),
    ControlOption(2, "Slope", "Highlight steep ground"),
    ControlOption(3, "Local relief", "Banks, walls and cellars"),
    ControlOption(4, "Curvature", "Breaks and edges in slope"),
    ControlOption(5, "Disturbance", "Screen anomaly candidates"),
    ControlOption(6, "Aspect", "Slope direction by color"),
    ControlOption(7, "Elevation", "Unshaded height colors"),
    ControlOption(8, "Canopy", "DSM minus bare earth"),
)

/** The eight cardinal light directions offered by the Lighting card. */
val SunDirectionOptions = listOf(
    ControlOption(0, "N", "0°"),
    ControlOption(45, "NE", "45°"),
    ControlOption(90, "E", "90°"),
    ControlOption(135, "SE", "135°"),
    ControlOption(180, "S", "180°"),
    ControlOption(225, "SW", "225°"),
    ControlOption(270, "W", "270°"),
    ControlOption(315, "NW", "315°"),
)

/** The three historical overlays offered by the Historical overlay card. */
val HistoricalOverlayOptions = listOf(
    ControlOption(0, "None", "Terrain only"),
    ControlOption(1, "1880s plat", "Illustrative site layer"),
    ControlOption(2, "1940s contours", "Illustrative contour layer"),
)

/** The survey-grid spacings offered by the Survey grid card, keyed by cell spacing. */
val SurveyGridOptions = listOf(
    ControlOption(0, "Off", "No planning grid"),
    ControlOption(20, "5 × 5", "Broad coverage"),
    ControlOption(10, "10 × 10", "Standard coverage"),
    ControlOption(5, "20 × 20", "Detailed coverage"),
)

@Composable
fun LidarControlPanel(
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
    heatmapEnabled: Boolean = false,
    onHeatmapEnabledChanged: (Boolean) -> Unit = {},
    basemapEnabled: Boolean = false,
    onBasemapEnabledChanged: (Boolean) -> Unit = {},
    basemapOpacity: Float = 0.6f,
    onBasemapOpacityChanged: (Float) -> Unit = {},
    basemapStatus: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.testTag("lidar_control_panel"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Terrain controls", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Changes are debounced so dragging a slider renders only the latest frame.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        TerrainSourceCard(selectedSiteIndex, onSiteSelected)
        ReliefStyleCard(visualizationMode, onVisualizationModeChanged)
        SurfaceModelCard(vegetationFilter, onVegetationFilterChanged)
        HistoricalOverlayCard(overlayType, onOverlayTypeChanged, overlayOpacity, onOverlayOpacityChanged)
        SurveyGridCard(gridSpacing, onGridSpacingChanged)
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
        FeatureScreeningCard(
            featureScaleMeters = featureScaleMeters,
            onFeatureScaleChanged = onFeatureScaleChanged,
            analysisSensitivity = analysisSensitivity,
            onAnalysisSensitivityChanged = onAnalysisSensitivityChanged,
            contourIntervalMeters = contourIntervalMeters,
            onContourIntervalChanged = onContourIntervalChanged,
        )
        LiveOverlaysCard(
            heatmapEnabled = heatmapEnabled,
            onHeatmapEnabledChanged = onHeatmapEnabledChanged,
            basemapEnabled = basemapEnabled,
            onBasemapEnabledChanged = onBasemapEnabledChanged,
            basemapOpacity = basemapOpacity,
            onBasemapOpacityChanged = onBasemapOpacityChanged,
            basemapStatus = basemapStatus,
        )
        ElevationPaletteCard(paletteType, onPaletteTypeChanged)
    }
}

@Composable
fun TerrainSourceCard(selectedSiteIndex: Int, onSiteSelected: (Int) -> Unit) {
    ControlCard("Terrain source") {
        OptionGrid(
            options = listOf(
                ControlOption(0, "Homestead", "Cellar, well and wall"),
                ControlOption(1, "Fort", "Ramparts and trench"),
                ControlOption(2, "Villa", "Foundations and road"),
                ControlOption(3, "Imported", "Load it from Data"),
            ),
            selected = selectedSiteIndex,
            onSelected = onSiteSelected,
            tagPrefix = "site_tab",
        )
    }
}

@Composable
fun ReliefStyleCard(visualizationMode: Int, onVisualizationModeChanged: (Int) -> Unit) {
    ControlCard("Relief style") {
        OptionGrid(
            options = ReliefStyleOptions,
            selected = visualizationMode,
            onSelected = onVisualizationModeChanged,
            tagPrefix = "visualization",
        )
    }
}

@Composable
fun SurfaceModelCard(vegetationFilter: Float, onVegetationFilterChanged: (Float) -> Unit) {
    ControlCard("Surface model") {
        LabeledSlider(
            label = "Canopy removal",
            displayValue = "${(vegetationFilter * 100).toInt()}%",
            value = vegetationFilter,
            range = 0f..1f,
            onValueChange = onVegetationFilterChanged,
            modifier = Modifier.testTag("vegetation_slider"),
        )
        Text(
            if (vegetationFilter >= 0.99f) "Bare-earth DEM" else "Canopy is blended into the surface",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun HistoricalOverlayCard(
    overlayType: Int,
    onOverlayTypeChanged: (Int) -> Unit,
    overlayOpacity: Float,
    onOverlayOpacityChanged: (Float) -> Unit,
) {
    ControlCard("Historical overlay") {
        OptionGrid(
            options = HistoricalOverlayOptions,
            selected = overlayType,
            onSelected = onOverlayTypeChanged,
            tagPrefix = "overlay",
        )
        if (overlayType > 0) {
            Spacer(Modifier.height(4.dp))
            LabeledSlider(
                label = "Overlay opacity",
                displayValue = "${(overlayOpacity * 100).toInt()}%",
                value = overlayOpacity,
                range = 0.1f..0.9f,
                onValueChange = onOverlayOpacityChanged,
            )
        }
    }
}

@Composable
fun SurveyGridCard(gridSpacing: Float, onGridSpacingChanged: (Float) -> Unit) {
    ControlCard("Survey grid") {
        OptionGrid(
            options = SurveyGridOptions,
            selected = gridSpacing.toInt(),
            onSelected = { onGridSpacingChanged(it.toFloat()) },
            tagPrefix = "grid",
        )
    }
}

@Composable
fun LightingCard(
    sunAzimuth: Float,
    onSunAzimuthChanged: (Float) -> Unit,
    sunAltitude: Float,
    onSunAltitudeChanged: (Float) -> Unit,
    contrast: Float,
    onContrastChanged: (Float) -> Unit,
    zScale: Float,
    onZScaleChanged: (Float) -> Unit,
) {
    ControlCard("Lighting and relief") {
        Text(
            "Light direction: ${compassDirection(sunAzimuth)}",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        OptionGrid(
            options = SunDirectionOptions,
            selected = ((sunAzimuth / 45f).roundToInt() * 45).mod(360),
            onSelected = { onSunAzimuthChanged(it.toFloat()) },
            tagPrefix = "sun_direction",
        )
        LabeledSlider(
            label = "Sun azimuth",
            displayValue = "${sunAzimuth.toInt()}°",
            value = sunAzimuth,
            range = 0f..360f,
            onValueChange = onSunAzimuthChanged,
            modifier = Modifier.testTag("sun_azimuth_slider"),
        )
        LabeledSlider(
            label = "Sun altitude",
            displayValue = "${sunAltitude.toInt()}°",
            value = sunAltitude,
            range = 5f..85f,
            onValueChange = onSunAltitudeChanged,
            modifier = Modifier.testTag("sun_altitude_slider"),
        )
        LabeledSlider(
            label = "Shadow contrast",
            displayValue = String.format(Locale.US, "%.1f×", contrast),
            value = contrast,
            range = 1f..2.5f,
            onValueChange = onContrastChanged,
            modifier = Modifier.testTag("contrast_slider"),
        )
        LabeledSlider(
            label = "Vertical exaggeration",
            displayValue = String.format(Locale.US, "%.1f×", zScale),
            value = zScale,
            range = 0.5f..4f,
            onValueChange = onZScaleChanged,
            modifier = Modifier.testTag("z_scale_slider"),
        )
    }
}

@Composable
fun FeatureScreeningCard(
    featureScaleMeters: Float,
    onFeatureScaleChanged: (Float) -> Unit,
    analysisSensitivity: Float,
    onAnalysisSensitivityChanged: (Float) -> Unit,
    contourIntervalMeters: Float,
    onContourIntervalChanged: (Float) -> Unit,
) {
    ControlCard("Feature screening") {
        LabeledSlider(
            label = "Feature scale",
            displayValue = String.format(Locale.US, "%.1f m", featureScaleMeters),
            value = featureScaleMeters,
            range = 1f..40f,
            onValueChange = onFeatureScaleChanged,
            modifier = Modifier.testTag("feature_scale_slider"),
        )
        LabeledSlider(
            label = "Candidate sensitivity",
            displayValue = String.format(Locale.US, "%.1f×", analysisSensitivity),
            value = analysisSensitivity,
            range = 0.4f..2.5f,
            onValueChange = onAnalysisSensitivityChanged,
            modifier = Modifier.testTag("analysis_sensitivity_slider"),
        )
        LabeledSlider(
            label = "Measured contours",
            displayValue = contourLabel(contourIntervalMeters),
            value = contourIntervalMeters,
            range = 0f..5f,
            onValueChange = onContourIntervalChanged,
            modifier = Modifier.testTag("contour_interval_slider"),
        )
        Text(
            "Local relief, curvature, and disturbance views flag terrain shapes for review; they do not identify or date a foundation by themselves.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
fun LiveOverlaysCard(
    heatmapEnabled: Boolean,
    onHeatmapEnabledChanged: (Boolean) -> Unit,
    basemapEnabled: Boolean,
    onBasemapEnabledChanged: (Boolean) -> Unit,
    basemapOpacity: Float,
    onBasemapOpacityChanged: (Float) -> Unit,
    basemapStatus: String?,
) {
    ControlCard("Live overlays") {
        Text(
            "Dig priority heatmap",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        OptionGrid(
            options = listOf(
                ControlOption(0, "Off", "No density layer"),
                ControlOption(1, "On", "Unexcavated finds"),
            ),
            selected = if (heatmapEnabled) 1 else 0,
            onSelected = { onHeatmapEnabledChanged(it == 1) },
            tagPrefix = "heatmap",
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Offline basemap",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        OptionGrid(
            options = listOf(
                ControlOption(0, "Off", "Terrain only"),
                ControlOption(1, "On", "OpenStreetMap tiles"),
            ),
            selected = if (basemapEnabled) 1 else 0,
            onSelected = { onBasemapEnabledChanged(it == 1) },
            tagPrefix = "basemap",
        )
        if (basemapEnabled) {
            Spacer(Modifier.height(4.dp))
            LabeledSlider(
                label = "Basemap opacity",
                displayValue = "${(basemapOpacity * 100).toInt()}%",
                value = basemapOpacity,
                range = 0.1f..1f,
                onValueChange = onBasemapOpacityChanged,
                modifier = Modifier.testTag("basemap_opacity_slider"),
            )
            basemapStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Tiles are fetched once per site and cached on-device — later views work offline.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun ElevationPaletteCard(paletteType: Int, onPaletteTypeChanged: (Int) -> Unit) {
    ControlCard("Elevation palette") {
        OptionGrid(
            options = listOf(
                ControlOption(0, "Clay", "Neutral relief"),
                ControlOption(1, "Copper", "Warm LiDAR tint"),
                ControlOption(2, "Terrain", "Hypsometric colors"),
            ),
            selected = paletteType,
            onSelected = onPaletteTypeChanged,
            tagPrefix = "palette",
        )
    }
}

internal fun contourLabel(intervalMeters: Float): String =
    if (intervalMeters < 0.05f) "Off" else String.format(Locale.US, "%.2f m", intervalMeters)

internal fun compassDirection(azimuth: Float): String {
    val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val normalized = ((azimuth % 360f) + 360f) % 360f
    return directions[((normalized / 45f).roundToInt()).mod(directions.size)]
}

@Composable
private fun ControlCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun OptionGrid(
    options: List<ControlOption>,
    selected: Int,
    onSelected: (Int) -> Unit,
    tagPrefix: String,
) {
    options.chunked(2).forEach { rowOptions ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowOptions.forEach { option ->
                val isSelected = selected == option.value
                Surface(
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(68.dp)
                        .clickable { onSelected(option.value) }
                        .testTag("${tagPrefix}_${option.value}"),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(option.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(
                            option.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (rowOptions.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    displayValue: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                displayValue,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}
