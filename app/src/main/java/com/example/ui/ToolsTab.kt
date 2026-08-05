package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.platform.LocalContext
import com.example.data.download.LazDownloadQueue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.field.FindSiteClusterer
import java.util.Locale

/**
 * Home for the field-workflow features that otherwise only surface deep inside another tab:
 * every card shows live status and jumps straight to where the feature is used.
 */
@Composable
fun ToolsTab(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    onNavigateToTab: (Int) -> Unit,
) {
    val context = LocalContext.current
    val loggedSignals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val plannedRoute by viewModel.plannedRoute.collectAsStateWithLifecycle()
    val breadcrumbTracks by viewModel.breadcrumbTracks.collectAsStateWithLifecycle()
    val excavationLogs by viewModel.excavationLogs.collectAsStateWithLifecycle()
    val surveyBoundaries by viewModel.surveyBoundaries.collectAsStateWithLifecycle()
    val pendingSyncCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()

    val sites = remember(loggedSignals) { FindSiteClusterer.cluster(loggedSignals) }
    val terrainReady = grid.width > 2 && grid.height > 2
    val recordedPoints = breadcrumbTracks.sumOf { it.points.size }
    val positionedFinds = loggedSignals.count { it.latitude != null && it.longitude != null }
    val openDigs = excavationLogs.count { !it.isComplete }
    val completedDigs = excavationLogs.count { it.isComplete }
    val savedLidarCount = remember(context) { LazDownloadQueue.store(context).list().size }
    val lidarFolderPath = remember(context) { LazDownloadQueue.store(context).directory.absolutePath }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("tools_tab"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Every field feature, one card each — status plus a shortcut to where it runs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ToolCard(
                icon = Icons.Default.Folder,
                title = "Saved LiDAR downloads",
                status = if (savedLidarCount == 0) {
                    "No files yet"
                } else {
                    "$savedLidarCount file(s) · rename on Import"
                },
                statusActive = savedLidarCount > 0,
                description = "App-private folder for every downloaded or imported LAZ/LAS. " +
                    "Path: $lidarFolderPath. Open the Import tab to reopen, rename for future reuse, or delete.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_IMPORT) },
                    modifier = Modifier.testTag("tool_open_saved_lidar"),
                ) { Text("Open Import library") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.GridOn,
                title = "Search coverage",
                status = if (recordedPoints > 0) {
                    "${breadcrumbTracks.size} trail(s) · $recordedPoints GPS points recorded"
                } else {
                    "No GPS trails yet"
                },
                statusActive = recordedPoints > 0,
                description = "Paints a sweep-coverage grid over the site map from your GPS " +
                    "breadcrumb trails, with an adjustable sweep width (1–4 m) to match your coil.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_MAP) },
                    modifier = Modifier.testTag("tool_open_coverage"),
                ) { Text("Open map") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Route,
                title = "Optimal target route",
                status = plannedRoute?.let {
                    "Active · ${it.waypoints.size} stops · ${routeDistanceText(it.totalDistanceMeters)}"
                } ?: "No route planned",
                statusActive = plannedRoute != null,
                description = "Orders your GPS-logged targets into the shortest walking route " +
                    "(nearest-neighbor + 2-opt) and draws it as a polyline on the map.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_FINDS) },
                    modifier = Modifier.testTag("tool_open_route"),
                ) { Text("Plan in targets") }
                if (plannedRoute != null) {
                    TextButton(
                        onClick = { viewModel.setPlannedRoute(null) },
                        modifier = Modifier.testTag("tool_clear_route"),
                    ) { Text("Clear route") }
                }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Hub,
                title = "Site clustering",
                status = if (sites.isNotEmpty()) {
                    "${sites.size} site(s) from $positionedFinds positioned finds"
                } else {
                    "No sites yet"
                },
                statusActive = sites.isNotEmpty(),
                description = "Groups finds within 50 m of each other into named sites " +
                    "(cellar-hole scatter, home lot, campsite) with confirmed/rejected counts " +
                    "and the most common find types.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_FINDS) },
                    modifier = Modifier.testTag("tool_open_sites"),
                ) { Text("Open finds") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.WbSunny,
                title = "Real-sun lighting",
                status = if (terrainReady && metadata.isGeoreferenced) {
                    "Ready · ${metadata.siteName}"
                } else {
                    "Needs a georeferenced terrain"
                },
                statusActive = terrainReady && metadata.isGeoreferenced,
                description = "Renders the hillshade with the true sun azimuth and altitude for " +
                    "any date and time at the site's real coordinates — preview how shadows fall " +
                    "before you're on site.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_TERRAIN) },
                    modifier = Modifier.testTag("tool_open_sun"),
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Visibility,
                title = "Viewshed",
                status = if (terrainReady) {
                    "Ready · tap any terrain cell to analyze"
                } else {
                    "Load a terrain first"
                },
                statusActive = terrainReady,
                description = "Computes everything visible from a tapped cell — visible areas " +
                    "burn green into the hillshade, blocked areas go dark, with the observer " +
                    "marked in blue.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_TERRAIN) },
                    modifier = Modifier.testTag("tool_open_viewshed"),
                ) { Text("Open terrain") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Edit,
                title = "Excavation logs",
                status = when {
                    excavationLogs.isEmpty() -> "No dig logs yet"
                    openDigs > 0 -> "$openDigs open · $completedDigs completed"
                    else -> "$completedDigs completed dig(s)"
                },
                statusActive = excavationLogs.isNotEmpty(),
                description = "Per-target dig records with depth, soil notes, and finds. " +
                    "Open a find on the Targets tab, then start a dig log. Survives offline restarts.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_FINDS) },
                    modifier = Modifier.testTag("tool_open_excavation"),
                ) { Text("Open targets") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.CropFree,
                title = "Survey boundaries",
                status = if (surveyBoundaries.isEmpty()) {
                    "No boundaries on this project"
                } else {
                    "${surveyBoundaries.size} boundary polygon(s)"
                },
                statusActive = surveyBoundaries.isNotEmpty(),
                description = "Project search areas from a GPS trail (≥3 points) or a 100 m box " +
                    "around your current fix. Used to keep field work inside permitted ground.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_FINDS) },
                    modifier = Modifier.testTag("tool_open_boundaries"),
                ) { Text("Open targets") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.Sync,
                title = "Offline sync queue",
                status = if (pendingSyncCount == 0) {
                    "Queue empty · local only"
                } else {
                    "$pendingSyncCount change(s) waiting"
                },
                statusActive = pendingSyncCount > 0,
                description = "Every target, dig log, trail, and boundary change is coalesced " +
                    "into an ordered offline queue (delete-wins, no silent drops). Cloud delivery " +
                    "is Phase 9; the queue is durable today.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_FINDS) },
                    modifier = Modifier.testTag("tool_open_sync"),
                ) { Text("Open targets") }
            }
        }
        item {
            ToolCard(
                icon = Icons.Default.History,
                title = "Historic map georeference",
                status = "Map tab · control points + swipe + side-by-side",
                statusActive = true,
                description = "Import a scanned plat, set control points (image crosshair + map tap), " +
                    "fit an affine transform with visible confidence/RMSE, blend with swipe, or " +
                    "compare side-by-side. Fits persist to Room historic_maps.",
            ) {
                TextButton(
                    onClick = { onNavigateToTab(TAB_MAP) },
                    modifier = Modifier.testTag("tool_open_historic_georef"),
                ) { Text("Open map") }
            }
        }
    }
}

@Composable
private fun ToolCard(
    icon: ImageVector,
    title: String,
    status: String,
    statusActive: Boolean,
    description: String,
    actions: @Composable RowScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (statusActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

private fun routeDistanceText(totalMeters: Double): String =
    if (totalMeters >= 1000.0) {
        String.format(Locale.US, "%.2f km", totalMeters / 1000.0)
    } else {
        "${totalMeters.toInt()} m"
    }

// Tab indices in MainScreen's tab list.
private const val TAB_TERRAIN = 0
private const val TAB_MAP = 1
private const val TAB_FINDS = 5
private const val TAB_IMPORT = 6
