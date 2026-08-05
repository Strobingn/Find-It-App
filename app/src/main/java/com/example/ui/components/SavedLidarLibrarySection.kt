package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DemGenerator
import com.example.data.LazDataset
import com.example.data.LazDatasetStore
import com.example.data.LazTerrainCache
import com.example.data.LazTerrainDiskCache
import com.example.data.LazTerrainMemoryCache
import com.example.data.LidarImportOptions
import com.example.data.TerrainDecodeCoordinator
import com.example.data.TerrainImportSource
import com.example.data.TerrainPerformanceSession
import com.example.data.download.LazDownloadQueue
import com.example.data.download.LazDownloadState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Persistent library of every LAZ/LAS the app has downloaded or copied into
 * [LazDatasetStore] (`Android/data/<app>/files/lidar`). Open, rename (for reuse), or delete.
 */
@Composable
fun SavedLidarLibrarySection(
    onTerrainLoaded: (DemGenerator.TerrainLoadResult, TerrainImportSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val datasetStore = remember(context) { LazDownloadQueue.store(context) }
    val diskCache = remember(context) { LazTerrainDiskCache(File(context.cacheDir, "decoded-terrain")) }
    val terrainCache = remember(diskCache) { LazTerrainCache(LazTerrainMemoryCache(), diskCache) }
    val decodeCoordinator = remember(terrainCache) { TerrainDecodeCoordinator(terrainCache) }

    var datasets by remember { mutableStateOf(datasetStore.list()) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var isOpening by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    var renameDataset by remember { mutableStateOf<LazDataset?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        datasets = datasetStore.list()
    }

    // Refresh when returning to Import and when background tile downloads finish.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val downloadTasks by LazDownloadQueue.tasks.collectAsStateWithLifecycle()
    LaunchedEffect(downloadTasks) {
        if (downloadTasks.any { it.state == LazDownloadState.COMPLETED }) {
            refresh()
        }
    }

    fun openDataset(dataset: LazDataset) {
        if (isOpening) return
        openJob?.cancel()
        isOpening = true
        progressText = "Preparing ${dataset.displayName}…"
        message = null
        isError = false
        openJob = scope.launch {
            try {
                val options = LidarImportOptions()
                val source = TerrainImportSource(
                    uri = Uri.fromFile(dataset.file).toString(),
                    displayName = dataset.displayName,
                    options = options,
                )
                var paintedEarly = false
                val outcome = decodeCoordinator.decode(
                    file = dataset.file,
                    displayName = dataset.displayName,
                    options = options,
                    onPreview = { preview ->
                        withContext(Dispatchers.Main.immediate) {
                            TerrainPerformanceSession.publish(preview.gpuScene)
                            if (!paintedEarly) {
                                paintedEarly = true
                                onTerrainLoaded(preview.terrain, source)
                                progressText = if (preview.isPreview) {
                                    "Sparse preview visible — exact terrain decoding…"
                                } else {
                                    "Terrain visible — finishing GPU mesh…"
                                }
                            }
                        }
                    },
                    onStage = { stage ->
                        withContext(Dispatchers.Main.immediate) { progressText = stage }
                    },
                )
                TerrainPerformanceSession.publish(outcome.gpuScene)
                if (!paintedEarly) {
                    onTerrainLoaded(outcome.terrain, source)
                }
                isOpening = false
                progressText = null
                message = buildString {
                    append("Opened ${dataset.displayName} · ${outcome.terrain.grid.width}×${outcome.terrain.grid.height}")
                    if (outcome.isPreview) append(" (preview; exact still loading)")
                }
                isError = false
                val exactJob = outcome.exactOutcome
                if (exactJob != null) {
                    scope.launch {
                        val exact = runCatching { exactJob.await() }.getOrNull() ?: return@launch
                        withContext(Dispatchers.Main.immediate) {
                            TerrainPerformanceSession.publish(exact.gpuScene)
                            onTerrainLoaded(exact.terrain, source)
                            message = "Exact terrain ready · ${exact.terrain.grid.width}×${exact.terrain.grid.height}"
                            isError = false
                        }
                    }
                }
            } catch (_: CancellationException) {
                isOpening = false
                progressText = null
                message = "Open cancelled."
                isError = false
            } catch (error: Throwable) {
                isOpening = false
                progressText = null
                message = error.localizedMessage ?: "Could not open this LAZ file"
                isError = true
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth().testTag("saved_lidar_library"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Previous LiDAR downloads",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${datasets.size} file${if (datasets.size == 1) "" else "s"} · rename for reuse",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { refresh() },
                    enabled = !isOpening,
                    modifier = Modifier.testTag("refresh_saved_lidar_library"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Refresh")
                }
            }

            Text(
                "Storage folder (app-private):",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                datasetStore.directory.absolutePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("saved_lidar_storage_path"),
            )
            Text(
                "NYS/USGS tile downloads, HTTPS imports, and file-picker LAZ/LAS copies all land here. " +
                    "Renames stick and keep download reuse (same source URL maps to the renamed file).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isOpening) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                progressText?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }

            message?.let {
                Text(
                    it,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (datasets.isEmpty()) {
                Text(
                    "No saved LAZ/LAS yet. Download tiles from the LiDAR picker above, or import a " +
                        "file/URL below — they will show up here for reopen and rename.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                datasets.forEach { dataset ->
                    SavedLidarRow(
                        dataset = dataset,
                        enabled = !isOpening,
                        onOpen = { openDataset(dataset) },
                        onRename = {
                            renameDataset = dataset
                            renameText = dataset.file.nameWithoutExtension
                            renameError = null
                        },
                        onDelete = {
                            terrainCache.remove(dataset.file)
                            if (datasetStore.delete(dataset)) {
                                TerrainPerformanceSession.clear()
                                refresh()
                                message = "Deleted ${dataset.displayName}."
                                isError = false
                            } else {
                                message = "Could not delete ${dataset.displayName}."
                                isError = true
                            }
                        },
                    )
                }
            }
        }
    }

    renameDataset?.let { dataset ->
        AlertDialog(
            onDismissRequest = { renameDataset = null },
            title = { Text("Rename saved LAZ file") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Use a short project name so you can find this tile later. " +
                            "The .laz/.las extension is kept or added automatically.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = {
                            renameText = it
                            renameError = null
                        },
                        singleLine = true,
                        label = { Text("File name") },
                        supportingText = { Text("Example: homestead-ridge-2021") },
                        modifier = Modifier.fillMaxWidth().testTag("rename_saved_dataset_field"),
                    )
                    renameError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { datasetStore.rename(dataset, renameText) }
                            .onSuccess { renamed ->
                                refresh()
                                renameDataset = null
                                message = "Renamed to ${renamed.displayName}. Future downloads of " +
                                    "the same source URL will reuse this file."
                                isError = false
                            }
                            .onFailure {
                                renameError = it.localizedMessage ?: "Could not rename the dataset"
                            }
                    },
                    modifier = Modifier.testTag("confirm_rename_saved_dataset"),
                ) { Text("Save name") }
            },
            dismissButton = {
                TextButton(onClick = { renameDataset = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SavedLidarRow(
    dataset: LazDataset,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedButton(
            onClick = onOpen,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(64.dp).testTag("open_saved_lidar_${dataset.displayName}"),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    dataset.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${formatLidarBytes(dataset.sizeBytes)} · tap to open",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        TextButton(
            onClick = onRename,
            enabled = enabled,
            modifier = Modifier.testTag("rename_saved_dataset"),
        ) { Text("Rename") }
        TextButton(
            onClick = onDelete,
            enabled = enabled,
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${dataset.displayName}")
        }
    }
}

private fun formatLidarBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
