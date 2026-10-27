package com.example.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.basemap.OfflineBasemapStatus
import com.example.data.download.LazDownloadQueue
import com.example.data.field.FieldSessionStatsCalculator
import com.example.update.AppUpdateButtons
import com.example.update.rememberAppUpdateController

/**
 * Home hub in an iOS grouped-list layout: a large title, the active project, then one inset
 * group per topic (workspaces, session, app) so every tool is one tap away without a wall of cards.
 */
@Composable
fun HomeHubScreen(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    onOpen: (AppDestination) -> Unit,
) {
    val context = LocalContext.current
    val summary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val isDetailed by viewModel.isDetailedTerrain.collectAsStateWithLifecycle()
    val finds by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val digs by viewModel.excavationLogs.collectAsStateWithLifecycle()
    val boundaries by viewModel.surveyBoundaries.collectAsStateWithLifecycle()
    val pendingSync by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    val offlineRegions by viewModel.offlineBasemapRegions.collectAsStateWithLifecycle()
    val terrainQuality by viewModel.terrainQuality.collectAsStateWithLifecycle()
    val breadcrumbTracks by viewModel.breadcrumbTracks.collectAsStateWithLifecycle()

    val sessionStats = remember(finds, breadcrumbTracks) {
        FieldSessionStatsCalculator.compute(finds, breadcrumbTracks)
    }
    val recentLidar = remember(metadata.siteName, summary) {
        runCatching { LazDownloadQueue.store(context).list().take(6) }.getOrDefault(emptyList())
    }

    val basemapStatusText = remember(offlineRegions) {
        val downloading = offlineRegions.count { it.status == OfflineBasemapStatus.DOWNLOADING }
        val ready = offlineRegions.count { it.status == OfflineBasemapStatus.READY }
        when {
            downloading > 0 -> "Downloading offline basemap…"
            ready > 0 -> "$ready ready region${if (ready == 1) "" else "s"}"
            else -> "No offline basemap"
        }
    }

    val updateController = rememberAppUpdateController(checkOnLaunch = false)

    val workspaceCards = listOf(
        AppDestination.TERRAIN,
        AppDestination.FIELD,
        AppDestination.THERMALS,
        AppDestination.AI,
        AppDestination.LIBRARY,
        AppDestination.MAP,
        AppDestination.MAPBOX,
        AppDestination.LIDAR,
        AppDestination.COMPARE,
        AppDestination.TOOLS,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("home_hub"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // Large title, iOS-style: the page names itself instead of a toolbar.
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ThermalClaude", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Historic terrain · field verification",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Image(
                    painter = painterResource(R.drawable.findit_emblem),
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_project_card"),
            ) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "ACTIVE PROJECT",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        metadata.siteName.ifBlank { "No terrain loaded" },
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    terrainQuality?.let { quality ->
                        Text(
                            quality.bannerLine(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("home_ground_quality"),
                        )
                    }
                    Text(
                        buildString {
                            append(finds.size).append(" finds · ")
                            append(digs.size).append(" digs · ")
                            append(boundaries.size).append(" boundaries")
                            if (pendingSync > 0) append(" · ").append(pendingSync).append(" queued")
                            if (canRefine) append(if (isDetailed) " · refined detail" else " · source LAZ ready")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onOpen(AppDestination.TERRAIN) },
                            modifier = Modifier.weight(1f).testTag("home_open_terrain"),
                        ) { Text("Open terrain") }
                        FilledTonalButton(
                            onClick = { onOpen(AppDestination.LIBRARY) },
                            modifier = Modifier.weight(1f).testTag("home_open_library"),
                        ) { Text("Import") }
                    }
                }
            }
        }

        // Feature 20 — last-opened / recent LAZ projects strip
        if (recentLidar.isNotEmpty()) {
            item {
                GroupedSection(
                    header = "Recent LiDAR",
                    footer = "Tap a file to decode and open on Terrain.",
                    modifier = Modifier.testTag("home_recent_projects"),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        recentLidar.forEach { dataset ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.reopenSavedLidar(dataset.displayName)
                                        onOpen(AppDestination.TERRAIN)
                                    }
                                    .testTag("home_recent_${dataset.file.nameWithoutExtension}"),
                            ) {
                                Text(
                                    dataset.displayName,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    GroupedRow(
                        title = "Open library",
                        onClick = { onOpen(AppDestination.LIBRARY) },
                        modifier = Modifier.testTag("home_recent_open_library"),
                    )
                }
            }
        }

        item {
            GroupedSection(header = "Workspaces") {
                workspaceCards.forEachIndexed { index, dest ->
                    GroupedRow(
                        title = dest.label,
                        subtitle = dest.subtitle,
                        icon = dest.icon,
                        showDivider = index > 0,
                        onClick = { onOpen(dest) },
                        modifier = Modifier.testTag("home_card_${dest.name.lowercase()}"),
                    )
                }
            }
        }

        // Feature 4 — offline basemap status · Feature 5 — session end debrief + share
        item {
            GroupedSection(header = "Session") {
                GroupedRow(
                    title = "Offline basemap",
                    subtitle = basemapStatusText,
                    icon = Icons.Default.Map,
                    showDivider = false,
                    modifier = Modifier.testTag("home_basemap_status"),
                    trailing = {
                        TextButton(
                            onClick = { onOpen(AppDestination.LIBRARY) },
                            modifier = Modifier.testTag("home_basemap_open_library"),
                        ) { Text("Library") }
                    },
                )
                GroupedRow(
                    title = "End session debrief",
                    subtitle = buildString {
                        if (sessionStats.totalFinds == 0 && sessionStats.distanceMeters < 1.0) {
                            append("Nothing logged yet this session.")
                        } else {
                            append(sessionStats.totalFinds).append(" find(s)")
                            sessionStats.confirmRate?.let {
                                append(" · ").append((it * 100).toInt()).append("% confirmed")
                            }
                            append(" · ")
                            append(
                                if (sessionStats.distanceMeters >= 1000.0) {
                                    String.format("%.2f km walked", sessionStats.distanceMeters / 1000.0)
                                } else {
                                    String.format("%.0f m walked", sessionStats.distanceMeters)
                                },
                            )
                            sessionStats.findsPerHour?.let {
                                append(" · ").append(String.format("%.1f", it)).append(" finds/h")
                            }
                            sessionStats.topFindType?.let {
                                append(" · top: ").append(it)
                            }
                        }
                    },
                    icon = Icons.Default.Share,
                    modifier = Modifier.testTag("home_session_debrief"),
                    trailing = {
                        TextButton(
                            onClick = {
                                val text = sessionStats.toShareText(
                                    siteName = metadata.siteName.takeIf { it.isNotBlank() },
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Find It field debrief")
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(send, "Share session debrief"))
                            },
                            enabled = sessionStats.totalFinds > 0 || sessionStats.distanceMeters >= 1.0,
                            modifier = Modifier.testTag("home_session_debrief_share"),
                        ) { Text("Share") }
                    },
                )
            }
        }

        item {
            GroupedSection(
                header = "App",
                footer = "LiDAR ranks surface morphology and historic context — not buried metal, age, or dig depth.",
                modifier = Modifier.testTag("home_updates_card"),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Updates & backup", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        updateController.status.ifBlank { "GitHub Releases · findit-v* builds" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AppUpdateButtons(controller = updateController)
                }
            }
        }
    }
}
