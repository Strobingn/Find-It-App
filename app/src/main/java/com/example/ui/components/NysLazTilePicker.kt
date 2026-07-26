package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.DemGenerator
import com.example.data.GroundSurfaceMode
import com.example.data.LazDatasetStore
import com.example.data.LazDownloadManager
import com.example.data.LazImportRepository
import com.example.data.LazTerrainCache
import com.example.data.LazTerrainDiskCache
import com.example.data.LazTerrainMemoryCache
import com.example.data.LidarImportOptions
import com.example.data.NysHistoricLazTileCatalog
import com.example.data.TerrainDecodeCoordinator
import com.example.data.TerrainImportSource
import com.example.data.TerrainPerformanceSession
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Visible NYS Southeast 4 County tile resolver and downloader for historic-site work. */
@Composable
fun NysLazTilePicker(
    onCustomTerrainLoaded: (DemGenerator.TerrainLoadResult, TerrainImportSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = remember { NysHistoricLazTileCatalog() }
    val downloader = remember { LazImportRepository(LazDownloadManager()) }
    val store = remember(context) {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        LazDatasetStore(File(base, "lidar"))
    }
    val diskCache = remember(context) { LazTerrainDiskCache(File(context.cacheDir, "decoded-terrain")) }
    val terrainCache = remember(diskCache) { LazTerrainCache(LazTerrainMemoryCache(), diskCache) }
    val decodeCoordinator = remember(terrainCache) { TerrainDecodeCoordinator(terrainCache) }

    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var tiles by remember { mutableStateOf<List<NysHistoricLazTileCatalog.Tile>>(emptyList()) }
    var isLookingUp by remember { mutableStateOf(false) }
    var downloadingUrl by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun lookup() {
        val lat = latitude.trim().toDoubleOrNull()
        val lon = longitude.trim().toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            error = "Enter valid latitude and longitude coordinates."
            return
        }
        isLookingUp = true
        error = null
        status = "Finding the exact NYS LiDAR tile…"
        scope.launch {
            try {
                tiles = catalog.tilesAt(lon, lat)
                status = if (tiles.isEmpty()) {
                    "No Southeast 4 County 2022 tile covers that coordinate."
                } else {
                    "Found ${tiles.size} matching tile${if (tiles.size == 1) "" else "s"}."
                }
            } catch (t: Throwable) {
                tiles = emptyList()
                error = t.localizedMessage ?: "NYS tile lookup failed."
                status = null
            } finally {
                isLookingUp = false
            }
        }
    }

    fun downloadAndOpen(tile: NysHistoricLazTileCatalog.Tile) {
        if (downloadingUrl != null) return
        downloadingUrl = tile.downloadUrl
        error = null
        status = "Downloading ${tile.name}…"
        scope.launch {
            try {
                val file = downloader.importFromUrl(
                    url = tile.downloadUrl,
                    store = store,
                    onProgress = { downloaded, total ->
                        withContext(Dispatchers.Main.immediate) {
                            status = if (total > 0L) {
                                "Downloading ${tile.name}: ${percent(downloaded, total)}%"
                            } else {
                                "Downloading ${tile.name}: ${formatBytesCompact(downloaded)}"
                            }
                        }
                    },
                )
                status = "Building bare-earth terrain from source ground classes…"
                val options = LidarImportOptions(
                    groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
                    rasterResolution = 1_024,
                    smoothingRadius = 0,
                )
                val outcome = decodeCoordinator.decode(
                    file = file,
                    displayName = tile.name,
                    options = options,
                    onStage = { stage ->
                        withContext(Dispatchers.Main.immediate) { status = stage }
                    },
                )
                TerrainPerformanceSession.publish(outcome.gpuScene)
                onCustomTerrainLoaded(
                    outcome.terrain,
                    TerrainImportSource(
                        uri = Uri.fromFile(file).toString(),
                        displayName = tile.name,
                        options = options,
                    ),
                )
                status = "Opened ${tile.name} using ASPRS ground class 2 with class 8 fallback."
            } catch (_: CancellationException) {
                status = "Tile download cancelled."
            } catch (t: Throwable) {
                error = t.localizedMessage ?: "Tile download or decode failed."
                status = null
            } finally {
                downloadingUrl = null
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth().testTag("nys_laz_tile_picker"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("NYS historic LAZ tiles", style = MaterialTheme.typography.titleLarge)
                    Text(
                        NysHistoricLazTileCatalog.PROJECT_NAME,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "Enter a coordinate in the woods. The app checks the official NYS tile polygons, identifies the exact file, downloads it, and opens the source-classified bare-earth surface.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latitude,
                    onValueChange = { latitude = it.take(16); error = null },
                    label = { Text("Latitude") },
                    placeholder = { Text("41.43") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("nys_tile_latitude"),
                )
                OutlinedTextField(
                    value = longitude,
                    onValueChange = { longitude = it.take(17); error = null },
                    label = { Text("Longitude") },
                    placeholder = { Text("-74.04") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).testTag("nys_tile_longitude"),
                )
            }
            Button(
                onClick = ::lookup,
                enabled = !isLookingUp && downloadingUrl == null,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("find_nys_laz_tiles"),
            ) {
                if (isLookingUp) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text("Find exact LAZ tile")
            }

            tiles.forEach { tile ->
                OutlinedButton(
                    onClick = { downloadAndOpen(tile) },
                    enabled = downloadingUrl == null,
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(tile.name, maxLines = 1)
                        Text("Download and render ground class", style = MaterialTheme.typography.bodySmall)
                    }
                    if (downloadingUrl == tile.downloadUrl) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    }
                }
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun percent(downloaded: Long, total: Long): Int =
    ((downloaded.coerceAtLeast(0L) * 100L) / total.coerceAtLeast(1L)).toInt().coerceIn(0, 100)

private fun formatBytesCompact(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MiB", mib)
}
