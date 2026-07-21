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

        ControlCard("Terrain source") {
            OptionGrid(
                options = listOf(
                    Option(0, "Homestead", "Cellar, well and wall"),
                    Option(1, "Fort", "Ramparts and trench"),
                    Option(2, "Villa", "Foundations and road"),
                    Option(3, "Imported", "Load it from Import"),
                ),
                selected = selectedSiteIndex,
                onSelected = onSiteSelected,
                tagPrefix = "site_tab",
            )
        }

        ControlCard("Relief style") {
            OptionGrid(
                options = listOf(
                    Option(0, "Standard", "Single light source"),
                    Option(1, "Multi-direction", "Balanced opposing light"),
                    Option(2, "Slope", "Highlight steep ground"),
                    Option(3, "Foundations", "Local-relief filter"),
                ),
                selected = visualizationMode,
                onSelected = onVisualizationModeChanged,
                tagPrefix = "visualization",
            )
        }

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

        ControlCard("Historical overlay") {
            OptionGrid(
                options = listOf(
                    Option(0, "None", "Terrain only"),
                    Option(1, "1880s plat", "Illustrative site layer"),
                    Option(2, "1940s contours", "Illustrative contour layer"),
                ),
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

        ControlCard("Survey grid") {
            OptionGrid(
                options = listOf(
                    Option(0, "Off", "No planning grid"),
                    Option(20, "5 × 5", "Broad coverage"),
                    Option(10, "10 × 10", "Standard coverage"),
                    Option(5, "20 × 20", "Detailed coverage"),
                ),
                selected = gridSpacing.toInt(),
                onSelected = { onGridSpacingChanged(it.toFloat()) },
                tagPrefix = "grid",
            )
        }

        ControlCard("Lighting and relief") {
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

        ControlCard("Elevation palette") {
            OptionGrid(
                options = listOf(
                    Option(0, "Clay", "Neutral relief"),
                    Option(1, "Copper", "Warm LiDAR tint"),
                    Option(2, "Terrain", "Hypsometric colors"),
                ),
                selected = paletteType,
                onSelected = onPaletteTypeChanged,
                tagPrefix = "palette",
            )
        }
    }
}

private data class Option(val value: Int, val title: String, val subtitle: String)

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
    options: List<Option>,
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
