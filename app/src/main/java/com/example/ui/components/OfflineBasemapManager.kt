package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.data.basemap.OfflineBasemapRegion
import com.example.data.basemap.OfflineBasemapStatus
import com.example.geospatial.BasemapDownloadProgress
import com.example.geospatial.BasemapPlan
import java.util.Locale

@Composable
fun OfflineBasemapManager(
    suggestedName: String,
    regions: List<OfflineBasemapRegion>,
    plan: BasemapPlan?,
    progress: BasemapDownloadProgress?,
    isDownloading: Boolean,
    message: String?,
    onEstimate: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit,
    onOpen: (OfflineBasemapRegion) -> Unit,
    onRetry: (OfflineBasemapRegion) -> Unit,
    onDelete: (OfflineBasemapRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    var regionName by rememberSaveable(suggestedName) { mutableStateOf("$suggestedName offline map") }
    var pendingDelete by remember { mutableStateOf<OfflineBasemapRegion?>(null) }
    pendingDelete?.let { region ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete offline region?") },
            text = {
                Text(
                    "${region.displayName} and tiles not shared by another saved region will be removed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(region)
                        pendingDelete = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep") }
            },
        )
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Map, contentDescription = null)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Offline field map", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Save USGS Topo tiles for the active terrain extent and reopen them without service.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = regionName,
                onValueChange = { regionName = it.take(80) },
                label = { Text("Region name") },
                enabled = !isDownloading,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = onEstimate,
                enabled = !isDownloading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Estimate current terrain")
            }

            plan?.let {
                Text(
                    "Zoom ${it.zoom} · ${it.tileCount} tiles · ${it.cachedTiles} already saved",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (it.missingTiles == 0) {
                        "All required tiles are already on this device."
                    } else {
                        "Estimated new download: ${formatBytes(it.estimatedDownloadBytes)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { onDownload(regionName) },
                    enabled = !isDownloading && regionName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (it.missingTiles == 0) "Save region record" else "Download for offline use")
                }
            }

            if (isDownloading) {
                val fraction = progress?.let {
                    it.completedTiles.toFloat() / it.totalTiles.coerceAtLeast(1)
                } ?: 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${progress?.completedTiles ?: 0} / ${progress?.totalTiles ?: 0} tiles · " +
                        "${formatBytes(progress?.downloadedBytes ?: 0L)} downloaded",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel download")
                }
            }

            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Text("Saved for this terrain", style = MaterialTheme.typography.titleSmall)
            if (regions.isEmpty()) {
                Text(
                    "No offline regions saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                regions.forEach { region ->
                    OfflineRegionRow(
                        region = region,
                        enabled = !isDownloading,
                        onOpen = { onOpen(region) },
                        onRetry = { onRetry(region) },
                        onDelete = { pendingDelete = region },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineRegionRow(
    region: OfflineBasemapRegion,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(region.displayName, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${region.status.displayLabel()} · ${region.completedTiles}/${region.tileCount} tiles · " +
                formatBytes(region.storedBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        region.lastError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (region.status == OfflineBasemapStatus.READY) {
                TextButton(onClick = onOpen, enabled = enabled) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Open offline")
                }
            }
            if (region.status in setOf(
                    OfflineBasemapStatus.FAILED,
                    OfflineBasemapStatus.CANCELED,
                    OfflineBasemapStatus.PLANNED,
                )
            ) {
                TextButton(onClick = onRetry, enabled = enabled) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete, enabled = enabled) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Delete")
            }
        }
    }
}

private fun OfflineBasemapStatus.displayLabel(): String = when (this) {
    OfflineBasemapStatus.PLANNED -> "Planned"
    OfflineBasemapStatus.DOWNLOADING -> "Downloading"
    OfflineBasemapStatus.READY -> "Ready offline"
    OfflineBasemapStatus.FAILED -> "Needs retry"
    OfflineBasemapStatus.CANCELED -> "Canceled"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
