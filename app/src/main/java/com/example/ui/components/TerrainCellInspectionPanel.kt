package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.analysis.TerrainCellInspection
import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.MeasurementFormat
import java.util.Locale

/**
 * Compact cell-metrics card. Viewshed is intentionally not embedded here — it has its own
 * [ViewshedCard] so the terrain canvas stays readable.
 */
@Composable
fun TerrainCellInspectionPanel(
    inspection: TerrainCellInspection,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isComputingViewshed: Boolean = false,
    canComputeViewshed: Boolean = true,
    onComputeViewshed: () -> Unit = {},
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = modifier
            .widthIn(max = 300.dp)
            .testTag("terrain_cell_inspection_panel"),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Exact terrain cell", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Col ${inspection.column}, row ${inspection.row}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close cell inspection")
                }
            }
            Text(
                if (inspection.valid) "Valid source cell" else "No-data source cell",
                color = if (inspection.valid) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            if (inspection.valid) {
                InspectionValue("Elevation", MeasurementFormat.feet(inspection.elevationMeters, 2))
                InspectionValue("Bare earth", MeasurementFormat.feet(inspection.bareEarthMeters, 2))
                InspectionValue("Canopy height", MeasurementFormat.length(inspection.canopyHeightMeters))
                InspectionValue("Slope", decimal(inspection.slopeDegrees, "°"))
                InspectionValue(
                    "Aspect",
                    inspection.aspectDegrees?.let {
                        "${decimal(it, "°")} ${compassDirection(it)}"
                    } ?: "Flat",
                )
                InspectionValue("Local relief", MeasurementFormat.signedLength(inspection.localReliefMeters))
                InspectionValue("Depression depth", MeasurementFormat.length(inspection.depressionDepthMeters))
            } else {
                Text(
                    "No valid source measurement at this cell.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            InspectionValue("Resolution", MeasurementFormat.resolution(inspection.cellSizeMeters))
            val latitude = inspection.latitude
            val longitude = inspection.longitude
            if (latitude != null && longitude != null) {
                InspectionValue(
                    "Coordinate",
                    "${GeoSpatialLibrary.formatDms(latitude, true)}\n" +
                        GeoSpatialLibrary.formatDms(longitude, false),
                )
            } else {
                InspectionValue("Coordinate", "Local grid")
            }
            // Compact action only — results + map overlay live on ViewshedCard / canvas.
            OutlinedButton(
                onClick = onComputeViewshed,
                enabled = inspection.valid && canComputeViewshed && !isComputingViewshed,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("compute_viewshed_button"),
            ) {
                if (isComputingViewshed) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Computing…")
                } else {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Viewshed from here")
                }
            }
            Text(
                "Result draws on the map; status opens in a small card.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InspectionValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.44f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.56f),
        )
    }
}

private fun decimal(value: Float, suffix: String, digits: Int = 2): String =
    String.format(Locale.US, "%.${digits}f%s", value, suffix)

private fun compassDirection(degrees: Float): String {
    val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return directions[((degrees + 22.5f) / 45f).toInt() % directions.size]
}
