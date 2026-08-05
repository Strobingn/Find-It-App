package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * Screen `1d`: the app's entry point. Shows the active project's real state — site, extent, finds
 * logged, GPS — instead of a marketing hero, then lets the user either jump straight into the Map
 * workspace or switch to one of the other built-in terrain sources first.
 *
 * Every number here reads off [HillshadeViewModel] directly; there is no separate "coverage swept"
 * or "ranked target" tracking in this codebase (that would need a survey-sweep and a scored-target
 * pipeline neither of which exist yet), so the stat grid reports what the app actually knows: finds
 * logged/unverified, terrain source, grid extent, and live GPS state.
 */
@Composable
fun HomeScreen(
    viewModel: HillshadeViewModel,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    val elevationGrid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val terrainSummary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val gpsEnabled by viewModel.gpsEnabled.collectAsStateWithLifecycle()
    val hasLocationPermission by viewModel.hasLocationPermission.collectAsStateWithLifecycle()
    val devicePosition by viewModel.deviceGridPosition.collectAsStateWithLifecycle()
    val currentSiteIndex by viewModel.currentSiteIndex.collectAsStateWithLifecycle()

    val unverified = signals.count { it.status == "Logged" }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("home_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Find It", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "LiDAR field survey",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            ActiveProjectCard(
                siteName = metadata.siteName,
                extentLabel = "${elevationGrid.width}×${elevationGrid.height} · " +
                    "${elevationGrid.cellSizeMeters.toDouble().format(2)} m/cell · " +
                    extentLabel(elevationGrid.width, elevationGrid.height, elevationGrid.cellSizeMeters),
                onOpenWorkspace = onOpenWorkspace,
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Finds logged",
                    value = "${signals.size}",
                    caption = if (signals.isEmpty()) "None yet" else "$unverified unverified",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Terrain source",
                    value = if (metadata.isGeoreferenced) "Georeferenced" else "Local grid",
                    caption = terrainSummary,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "GPS",
                    value = if (gpsEnabled && hasLocationPermission) "Tracking" else "Off",
                    caption = devicePosition?.let { (x, y) -> "Grid ${x.toInt()}, ${y.toInt()}" } ?: "No fix",
                    icon = if (gpsEnabled && hasLocationPermission) Icons.Default.GpsFixed else Icons.Default.GpsNotFixed,
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "Grid resolution",
                    value = "${elevationGrid.cellSizeMeters.toDouble().format(2)} m",
                    caption = "${elevationGrid.width}×${elevationGrid.height} cells",
                    icon = Icons.Default.Landscape,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item {
            Text(
                "TERRAIN SOURCES",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(HomeSiteOptions) { option ->
            SiteRow(
                title = option.title,
                subtitle = option.subtitle,
                active = currentSiteIndex == option.index,
                enabled = option.index != 3 || currentSiteIndex == 3,
                onClick = {
                    viewModel.selectSite(option.index)
                    onOpenWorkspace()
                },
            )
        }
    }
}

private data class HomeSiteOption(val index: Int, val title: String, val subtitle: String)

private val HomeSiteOptions = listOf(
    HomeSiteOption(0, "Homestead", "Cellar, well and wall · simulated"),
    HomeSiteOption(1, "Fort", "Ramparts and trench · simulated"),
    HomeSiteOption(2, "Villa", "Foundations and road · simulated"),
    HomeSiteOption(3, "Imported", "Load a file from Data first"),
)

@Composable
private fun ActiveProjectCard(
    siteName: String,
    extentLabel: String,
    onOpenWorkspace: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.BottomStart,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "ACTIVE PROJECT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        siteName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        extentLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = onOpenWorkspace,
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(46.dp).testTag("open_workspace_button"),
            ) {
                Text("Open workspace", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                icon?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(5.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SiteRow(
    title: String,
    subtitle: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.06f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp)),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Double.format(places: Int) = String.format(Locale.US, "%.${places}f", this)
