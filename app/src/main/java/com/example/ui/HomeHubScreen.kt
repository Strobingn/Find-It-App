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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.basemap.OfflineBasemapStatus
import com.example.data.download.LazDownloadQueue
import com.example.data.field.FieldSessionStatsCalculator

/**
 * Modern home hub: project status + one card per major workspace so nothing is buried
 * under an 8-item bottom bar.
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

    val workspaceCards = listOf(
        AppDestination.TERRAIN,
        AppDestination.FIELD,
        AppDestination.AI,
        AppDestination.LIBRARY,
        AppDestination.MAP,
        AppDestination.LIDAR,
        AppDestination.COMPARE,
        AppDestination.TOOLS,
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("home_hub"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.findit_emblem),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Find It",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Historic terrain · field verification",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_project_card"),
            ) {
                Column(
                    Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Active project",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                    )
                    Text(
                        metadata.siteName.ifBlank { "No terrain loaded" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                    )
                    terrainQuality?.let { quality ->
                        Text(
                            quality.bannerLine(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
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
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onOpen(AppDestination.TERRAIN) },
                            modifier = Modifier.testTag("home_open_terrain"),
                        ) { Text("Open terrain") }
                        TextButton(
                            onClick = { onOpen(AppDestination.LIBRARY) },
                            modifier = Modifier.testTag("home_open_library"),
                        ) { Text("Import / library") }
                    }
                }
            }
        }

        // Feature 20 — last-opened / recent LAZ projects strip
        if (recentLidar.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_recent_projects"),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Recent LiDAR projects",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Reopen from Library (decode runs there).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recentLidar.forEach { dataset ->
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier
                                        .clickable { onOpen(AppDestination.LIBRARY) }
                                        .testTag("home_recent_${dataset.file.nameWithoutExtension}"),
                                ) {
                                    Text(
                                        dataset.displayName,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = { onOpen(AppDestination.LIBRARY) },
                            modifier = Modifier.testTag("home_recent_open_library"),
                        ) { Text("Open library") }
                    }
                }
            }
        }

        // Feature 4 — offline basemap status
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_basemap_status"),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Offline basemap",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            basemapStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { onOpen(AppDestination.LIBRARY) },
                        modifier = Modifier.testTag("home_basemap_open_library"),
                    ) { Text("Library") }
                }
            }
        }

        // Feature 5 — session end debrief + share
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_session_debrief"),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "End session debrief",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        buildString {
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
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Share text")
                    }
                }
            }
        }

        item {
            Text(
                "Workspaces",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Each tool lives on its own screen — pick a card.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(workspaceCards, key = { it.name }) { dest ->
            DestinationCard(
                destination = dest,
                onClick = { onOpen(dest) },
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "LiDAR ranks surface morphology and historic context — not buried metal, age, or dig depth.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DestinationCard(
    destination: AppDestination,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("home_card_${destination.name.lowercase()}"),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    destination.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    destination.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    destination.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
