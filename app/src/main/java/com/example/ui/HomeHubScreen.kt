package com.example.ui

import com.example.update.CrashLog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.ui.graphics.Color
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosIconRowDividerInset
import com.example.ui.ios.IosRow
import com.example.ui.ios.IosSection
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
import com.example.update.AppUpdateButtons
import com.example.update.rememberAppUpdateController

/**
 * iOS-style home hub: large title, then inset grouped sections — active project, recent LiDAR,
 * one row per workspace, field status, and app updates.
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
    var crashReport by remember { mutableStateOf(CrashLog.read(context)) }

    // Derived, not hand-listed: screens that aren't in the tab bar are reachable only from here,
    // so a destination added to the enum must never be able to go missing from this list.
    val workspaceCards = AppDestination.workspaceDestinations

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .testTag("home_hub"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // iOS large title.
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Find It", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Historic terrain · field verification",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Image(
                    painter = painterResource(R.drawable.findit_emblem),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        crashReport?.let { report ->
            item {
                IosSection(
                    header = "Last crash",
                    footer = "Share sends the error report (no location or personal data) so it can be fixed.",
                    modifier = Modifier.testTag("home_last_crash"),
                ) {
                    IosRow(
                        title = "The app crashed last time",
                        subtitle = CrashLog.summary(report),
                        titleColor = MaterialTheme.colorScheme.error,
                    )
                    IosDivider(startInset = 16.dp)
                    IosRow(
                        title = "Share crash report",
                        icon = Icons.Default.Share,
                        showChevron = true,
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Find It crash report")
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(send, "Share crash report"))
                        },
                        modifier = Modifier.testTag("home_share_crash"),
                    )
                    IosDivider(startInset = IosIconRowDividerInset)
                    IosRow(
                        title = "Dismiss",
                        titleColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            CrashLog.clear(context)
                            crashReport = null
                        },
                    )
                }
            }
        }

        item {
            IosSection(header = "Active project", modifier = Modifier.testTag("home_project_card")) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        metadata.siteName.ifBlank { "No terrain loaded" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
                IosDivider(startInset = 16.dp)
                IosRow(
                    title = "Open terrain",
                    icon = Icons.Default.Landscape,
                    showChevron = true,
                    onClick = { onOpen(AppDestination.TERRAIN) },
                    modifier = Modifier.testTag("home_open_terrain"),
                )
                IosDivider(startInset = IosIconRowDividerInset)
                IosRow(
                    title = "Import / library",
                    icon = Icons.Default.UploadFile,
                    showChevron = true,
                    onClick = { onOpen(AppDestination.LIBRARY) },
                    modifier = Modifier.testTag("home_open_library"),
                )
            }
        }

        // Feature 20 — last-opened / recent LAZ projects strip
        if (recentLidar.isNotEmpty()) {
            item {
                IosSection(
                    header = "Recent LiDAR",
                    footer = "Tap a file to decode and open it on Terrain.",
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
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .clickable {
                                        viewModel.reopenSavedLidar(dataset.displayName)
                                        onOpen(AppDestination.TERRAIN)
                                    }
                                    .testTag("home_recent_${dataset.file.nameWithoutExtension}"),
                            ) {
                                Text(
                                    dataset.displayName,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    IosDivider(startInset = 16.dp)
                    IosRow(
                        title = "Open library",
                        showChevron = true,
                        onClick = { onOpen(AppDestination.LIBRARY) },
                        modifier = Modifier.testTag("home_recent_open_library"),
                    )
                }
            }
        }

        item {
            IosSection(header = "Workspaces") {
                workspaceCards.forEachIndexed { index, dest ->
                    if (index > 0) IosDivider(startInset = IosIconRowDividerInset)
                    IosRow(
                        title = dest.label,
                        subtitle = dest.subtitle,
                        icon = dest.icon,
                        showChevron = true,
                        onClick = { onOpen(dest) },
                        modifier = Modifier.testTag("home_card_${dest.name.lowercase()}"),
                    )
                }
            }
        }

        item {
            val canShare = sessionStats.totalFinds > 0 || sessionStats.distanceMeters >= 1.0
            IosSection(header = "Field status") {
                // Feature 4 — offline basemap status
                IosRow(
                    title = "Offline basemap",
                    subtitle = basemapStatusText,
                    icon = Icons.Default.Map,
                    showChevron = true,
                    onClick = { onOpen(AppDestination.LIBRARY) },
                    modifier = Modifier.testTag("home_basemap_open_library"),
                )
                IosDivider(startInset = IosIconRowDividerInset)
                OfflineAiHomeRow()
                IosDivider(startInset = IosIconRowDividerInset)
                // Feature 5 — session end debrief + share
                IosRow(
                    title = "Session debrief",
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
                    titleColor = if (canShare) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                    showChevron = canShare,
                    onClick = if (canShare) {
                        {
                            val text = sessionStats.toShareText(
                                siteName = metadata.siteName.takeIf { it.isNotBlank() },
                            )
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Find It field debrief")
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "Share session debrief"))
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.testTag("home_session_debrief_share"),
                )
            }
        }

        item {
            IosSection(
                header = "App updates",
                footer = "LiDAR ranks surface morphology and historic context — not buried metal, age, or dig depth.",
                modifier = Modifier.testTag("home_updates_card"),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

/** Home → Field status → "Offline AI": shows the download state and opens the download page. */
@Composable
private fun OfflineAiHomeRow() {
    val context = LocalContext.current
    val store = remember(context) { com.example.ai.OnDeviceModelStore(context) }
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val subtitle = remember(tick, open) {
        val states = com.example.ai.OnDeviceModel.entries.associateWith { store.state(it) }
        val downloading = states.entries.firstOrNull { it.value is com.example.ai.ModelDownloadState.Downloading }
        val active = store.activeModel()
        when {
            downloading != null -> {
                val d = downloading.value as com.example.ai.ModelDownloadState.Downloading
                if (d.waitingForWifi) "${downloading.key.label}: waiting for Wi-Fi" else "${downloading.key.label}: downloading ${(d.fraction * 100).toInt()}%"
            }
            active != null -> "${active.label} ready — works with no signal"
            else -> "Download Gemma 4 to use AI with no signal"
        }
    }
    // Keep the percentage moving while a download runs.
    androidx.compose.runtime.LaunchedEffect(subtitle) {
        if (subtitle.contains("downloading") || subtitle.contains("waiting")) {
            kotlinx.coroutines.delay(2_000)
            tick++
        }
    }
    IosRow(
        title = "Offline AI",
        subtitle = subtitle,
        icon = Icons.Default.Memory,
        showChevron = true,
        onClick = { open = true },
        modifier = Modifier.testTag("home_offline_ai"),
    )
    if (open) {
        com.example.ui.ios.IosSheet(title = "Offline AI", onDismiss = { open = false; tick++ }) {
            OfflineAiSection(onChanged = { tick++ })
        }
    }
}
