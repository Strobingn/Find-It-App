package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.OfflineMapCache
import com.example.data.OfflineMapCache.CacheRegion
import com.example.ui.OfflineMapsViewModel

@Composable
fun OfflineMapsPanel(
    viewModel: OfflineMapsViewModel,
    modifier: Modifier = Modifier,
) {
    val cacheStats by viewModel.cacheStats.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val cachedRegions by viewModel.cachedRegions.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val currentRegion by viewModel.currentRegion.collectAsStateWithLifecycle()
    
    var showClearDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showRegionDetail by remember { mutableStateOf<String?>(null) }
    
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Offline Maps", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Download and cache map tiles for field use without connectivity. Cached maps are stored locally and available in airplane mode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Cache Statistics Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Cache Status", style = MaterialTheme.typography.titleMedium)
                
                cacheStats?.let { stats ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Tiles Cached", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${stats.tileCount}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Cache Size", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${"%.1f".format(stats.sizeInMB())} MB",
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    LinearProgressIndicator(
                        progress = { (stats.percentUsed() / 100).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(8.dp)
                    )
                    
                    Text(
                        "${"%.0f".format(stats.percentUsed())}% of ${stats.maxCacheSizeBytes / (1024 * 1024)} MB used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                } ?: run {
                    Text("Loading cache statistics...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // Action Buttons
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showDownloadDialog = true },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Download Region")
            }
            
            OutlinedButton(
                onClick = { viewModel.refreshCacheStats() },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Refresh")
            }
        }

        // Download Progress
        downloadProgress?.let { (processed, total) ->
            if (total > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Downloading Map Tiles", style = MaterialTheme.typography.titleMedium)
                        
                        currentRegion?.let { region ->
                            Text(
                                "Region: ${"%.4f".format(region.minLat)}, ${"%.4f".format(region.minLon)} to ${"%.4f".format(region.maxLat)}, ${"%.4f".format(region.maxLon)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        
                        LinearProgressIndicator(
                            progress = { processed.toFloat() / total },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Text(
                            "$processed of $total tiles",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Cached Regions
        if (cachedRegions.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Cached Regions", style = MaterialTheme.typography.titleMedium)
                    
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(cachedRegions) { key ->
                            val region = viewModel.loadRegionDefinition(key)
                            RegionItem(
                                key = key,
                                region = region,
                                onClick = { showRegionDetail = key },
                                onRemove = { viewModel.removeCachedRegion(key) }
                            )
                        }
                    }
                }
            }
        }

        // Clear Cache Button
        Button(
            onClick = { showClearDialog = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Default.CleaningServices, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Clear All Cache")
        }

        // Status Message
        statusMessage?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    
    // Download Region Dialog
    if (showDownloadDialog) {
        var minLat by remember { mutableStateOf("0.0") }
        var maxLat by remember { mutableStateOf("0.0") }
        var minLon by remember { mutableStateOf("0.0") }
        var maxLon by remember { mutableStateOf("0.0") }
        var minZoom by remember { mutableStateOf("10") }
        var maxZoom by remember { mutableStateOf("15") }
        var selectedProvider by remember { mutableStateOf(OfflineMapCache.OSM_STANDARD) }
        
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("Download Map Region") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Enter the geographic bounds for the region to cache:")
                    
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = minLat,
                            onValueChange = { minLat = it },
                            label = { Text("Min Latitude") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxLat,
                            onValueChange = { maxLat = it },
                            label = { Text("Max Latitude") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = minLon,
                            onValueChange = { minLon = it },
                            label = { Text("Min Longitude") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxLon,
                            onValueChange = { maxLon = it },
                            label = { Text("Max Longitude") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = minZoom,
                            onValueChange = { minZoom = it },
                            label = { Text("Min Zoom") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxZoom,
                            onValueChange = { maxZoom = it },
                            label = { Text("Max Zoom") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    
                    Text("Tile Provider:", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "OSM Standard" to OfflineMapCache.OSM_STANDARD,
                            "OSM Humanitarian" to OfflineMapCache.OSM_HUMANITARIAN,
                            "OSM Outdoors" to OfflineMapCache.OSM_OUTDOORS
                        ).forEach { (name, url) ->
                            val isSelected = selectedProvider == url
                            if (isSelected) {
                                Button(
                                    onClick = { selectedProvider = url },
                                    modifier = Modifier.weight(1f)
                                ) { Text(name) }
                            } else {
                                OutlinedButton(
                                    onClick = { selectedProvider = url },
                                    modifier = Modifier.weight(1f)
                                ) { Text(name) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDownloadDialog = false
                        try {
                            val region = CacheRegion(
                                minLat = minLat.toDouble(),
                                maxLat = maxLat.toDouble(),
                                minLon = minLon.toDouble(),
                                maxLon = maxLon.toDouble(),
                                minZoom = minZoom.toInt(),
                                maxZoom = maxZoom.toInt()
                            )
                            viewModel.preloadRegion(region, selectedProvider)
                        } catch (e: NumberFormatException) {
                            viewModel.reportValidationError("Invalid coordinates or zoom range")
                        }
                    }
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Clear Cache Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Cache") },
            text = { Text("Are you sure you want to clear all cached map tiles? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearAllCache()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Region Detail Dialog
    showRegionDetail?.let { key ->
        val region = viewModel.loadRegionDefinition(key)
        AlertDialog(
            onDismissRequest = { showRegionDetail = null },
            title = { Text("Region Details") },
            text = {
                region?.let {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Bounds: ${"%.4f".format(it.minLat)}, ${"%.4f".format(it.minLon)} to ${"%.4f".format(it.maxLat)}, ${"%.4f".format(it.maxLon)}")
                        Text("Zoom Range: ${it.minZoom} to ${it.maxZoom}")
                        Text("Key: $key")
                    }
                } ?: run {
                    Text("Region details not available")
                }
            },
            confirmButton = {
                TextButton(onClick = { showRegionDetail = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun RegionItem(
    key: String,
    region: CacheRegion?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                key.take(30),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            region?.let {
                Text(
                    "${"%.3f".format(it.minLat)}, ${"%.3f".format(it.minLon)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Row {
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Info, contentDescription = "Details")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }
}
