package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.DemGenerator
import com.example.data.GroundSurfaceMode
import com.example.data.LazDataset
import com.example.data.LazDatasetStore
import com.example.data.LazDownloadManager
import com.example.data.LazImportRepository
import com.example.data.LidarImportOptions
import com.example.data.NoaaLidarCatalog
import com.example.data.TerrainImportSource
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_LOCAL_IMPORT_BYTES = LazDownloadManager.MAX_IMPORT_BYTES
private val supportedExtensions = setOf("las", "laz", "tif", "tiff", "asc", "xyz", "csv", "txt", "dem")

@Composable
fun CustomFileLoader(
    onCustomTerrainLoaded: (DemGenerator.TerrainLoadResult, TerrainImportSource?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val datasetStore = remember(context) {
        val baseDirectory = context.getExternalFilesDir(null) ?: context.filesDir
        LazDatasetStore(File(baseDirectory, "lidar"))
    }
    val importRepository = remember { LazImportRepository(LazDownloadManager()) }

    var mode by remember { mutableStateOf(0) }
    var matrix by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var groundMode by remember { mutableStateOf(GroundSurfaceMode.SOURCE_CLASSIFIED) }
    var rasterResolution by remember { mutableStateOf(512) }
    var smoothingRadius by remember { mutableStateOf(0) }
    var savedDatasets by remember { mutableStateOf(datasetStore.list()) }

    fun importOptions() = LidarImportOptions(
        groundMode = groundMode,
        rasterResolution = rasterResolution,
        smoothingRadius = smoothingRadius,
    )

    fun showResult(
        result: DemGenerator.TerrainLoadResult?,
        name: String,
        source: TerrainImportSource? = null,
        successMessage: String? = null,
    ) {
        isWorking = false
        progress = null
        progressText = null
        if (result == null) {
            isError = true
            message = "Could not parse $name. Supported: LAZ, LAS, GeoTIFF, ASC, XYZ, CSV/text matrices, or ZIP."
        } else {
            onCustomTerrainLoaded(result, source)
            isError = false
            message = successMessage ?: "$name loaded as ${result.grid.width} × ${result.grid.height}. ${result.summary}"
        }
    }

    fun renderSavedDataset(dataset: LazDataset) {
        if (isWorking) return
        val options = importOptions()
        isWorking = true
        progress = null
        progressText = null
        message = "Reading ${dataset.displayName}…"
        isError = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    FileInputStream(dataset.file).buffered().use { input ->
                        DemGenerator.parseFromStreamDetailed(dataset.displayName, input, options)
                    }
                }.getOrNull()
            }
            val source = TerrainImportSource(
                uri = Uri.fromFile(dataset.file).toString(),
                displayName = dataset.displayName,
                options = options,
            )
            showResult(result, dataset.displayName, source)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri)
        val options = importOptions()
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        isWorking = true
        progress = null
        progressText = null
        message = "Reading $name…"
        isError = false
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { parseContentUri(context, uri, name, options) }.getOrNull()
            }
            val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
            val source = if (result != null && extension in setOf("las", "laz")) {
                TerrainImportSource(uri.toString(), name, options)
            } else {
                null
            }
            showResult(result, name, source)
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Import terrain", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Load a local terrain file or download a direct NOAA LAZ/LAS file. Remote point clouds are saved in app storage and remain available in the dataset library.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LiDAR ground processing", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose how returns become a raster. The original LAZ/LAS file is never rewritten or reclassified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GroundModeButton(
                    title = "Source ground classes",
                    subtitle = "Prefer ASPRS class 2 and legacy class 8; fall back when coverage is sparse.",
                    selected = groundMode == GroundSurfaceMode.SOURCE_CLASSIFIED,
                    onClick = { groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED },
                )
                GroundModeButton(
                    title = "Automatic ground estimate",
                    subtitle = "Use lowest returns and reject isolated low noise when classes are missing.",
                    selected = groundMode == GroundSurfaceMode.AUTO_LOWEST,
                    onClick = { groundMode = GroundSurfaceMode.AUTO_LOWEST },
                )
                GroundModeButton(
                    title = "Highest-return surface (DSM)",
                    subtitle = "Keep trees and structures for comparison with the bare-earth model.",
                    selected = groundMode == GroundSurfaceMode.SURFACE_MODEL,
                    onClick = { groundMode = GroundSurfaceMode.SURFACE_MODEL },
                )

                Text("Raster detail", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = listOf(256 to "Overview", 512 to "Balanced", 1_024 to "Fine"),
                    selected = rasterResolution,
                    onSelected = { rasterResolution = it },
                )

                Text("Ground smoothing", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = listOf(0 to "None", 1 to "Light", 2 to "Medium", 4 to "Strong"),
                    selected = smoothingRadius,
                    onSelected = { smoothingRadius = it },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Local / matrix", "LAZ URL").forEachIndexed { index, label ->
                if (mode == index) {
                    Button(
                        onClick = { mode = index },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { mode = index },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text(label) }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (mode == 0) {
                    Button(
                        onClick = {
                            picker.launch(arrayOf("application/octet-stream", "image/tiff", "text/*", "application/zip"))
                        },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("choose_lidar_file_button"),
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Choose terrain file")
                    }
                    OutlinedTextField(
                        value = matrix,
                        onValueChange = { matrix = it.take(1_000_000); message = null },
                        label = { Text("Elevation matrix") },
                        placeholder = { Text("10.1 10.5 11.2\n10.3 10.2 11.5") },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth().testTag("custom_grid_input"),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                matrix = "12 12 12 12 12\n12 9 9 9 12\n12 9 14 9 12\n12 9 9 9 12\n12 12 12 12 12"
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("Load sample") }
                        Button(
                            onClick = {
                                isWorking = true
                                scope.launch {
                                    val result = withContext(Dispatchers.Default) { DemGenerator.parseCustomGrid(matrix) }
                                    showResult(
                                        result?.let { DemGenerator.TerrainLoadResult(it, "Local elevation matrix", true) },
                                        "Matrix",
                                    )
                                }
                            },
                            enabled = matrix.isNotBlank() && !isWorking,
                            modifier = Modifier.weight(1f).height(48.dp).testTag("parse_custom_grid_button"),
                        ) { Text("Render matrix") }
                    }
                } else {
                    Text(
                        "Find a dataset in NOAA's LiDAR viewer, copy the direct HTTPS .laz or .las download URL, then paste it below.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NoaaLidarCatalog.DATA_VIEWER_URL)))
                            }.onFailure {
                                isError = true
                                message = "No browser is available to open the NOAA LiDAR viewer."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("open_noaa_lidar_button"),
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open NOAA LiDAR search")
                    }
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it.trim(); message = null },
                        label = { Text("Direct LAZ/LAS HTTPS URL") },
                        placeholder = { Text("https://…/tile.laz") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("remote_url_input"),
                    )
                    Button(
                        onClick = {
                            val url = remoteUrl.trim()
                            if (!importRepository.isSupportedRemoteUrl(url)) {
                                isError = true
                                message = "Enter a valid direct HTTPS LAZ or LAS URL."
                                return@Button
                            }

                            isWorking = true
                            progress = null
                            progressText = "Connecting…"
                            message = null
                            isError = false
                            scope.launch {
                                runCatching {
                                    importRepository.importFromUrl(
                                        url = url,
                                        store = datasetStore,
                                        onProgress = { downloaded, total ->
                                            scope.launch {
                                                progress = if (total > 0L) downloaded.toFloat() / total.toFloat() else null
                                                progressText = if (total > 0L) {
                                                    "${formatBytes(downloaded)} of ${formatBytes(total)}"
                                                } else {
                                                    "${formatBytes(downloaded)} downloaded"
                                                }
                                            }
                                        },
                                    )
                                }.onSuccess { file ->
                                    savedDatasets = datasetStore.list()
                                    val options = importOptions()
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            FileInputStream(file).buffered().use { input ->
                                                DemGenerator.parseFromStreamDetailed(file.name, input, options)
                                            }
                                        }.getOrNull()
                                    }
                                    val source = TerrainImportSource(
                                        uri = Uri.fromFile(file).toString(),
                                        displayName = file.name,
                                        options = options,
                                    )
                                    showResult(
                                        result = result,
                                        name = file.name,
                                        source = source,
                                        successMessage = "Saved ${file.name} and opened it in the terrain engine.",
                                    )
                                }.onFailure { error ->
                                    isWorking = false
                                    progress = null
                                    progressText = null
                                    isError = true
                                    message = error.localizedMessage ?: "Download failed"
                                }
                            }
                        },
                        enabled = remoteUrl.isNotBlank() && !isWorking,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("download_lidar_url_button"),
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download, save, and render")
                    }
                }
            }
        }

        if (isWorking) {
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress!!.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            progressText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SavedDatasetLibrary(
            datasets = savedDatasets,
            enabled = !isWorking,
            onOpen = ::renderSavedDataset,
        )
    }
}

@Composable
private fun SavedDatasetLibrary(
    datasets: List<LazDataset>,
    enabled: Boolean,
    onOpen: (LazDataset) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().testTag("saved_laz_dataset_list"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Saved LAZ datasets", style = MaterialTheme.typography.titleMedium)
            if (datasets.isEmpty()) {
                Text(
                    "Downloaded LAZ/LAS files will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                datasets.forEach { dataset ->
                    OutlinedButton(
                        onClick = { onOpen(dataset) },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            Text(dataset.displayName, maxLines = 1)
                            Text(
                                "${formatBytes(dataset.sizeBytes)} · Tap to render",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroundModeButton(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (selected) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp)) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp)) { content() }
    }
}

@Composable
private fun ChoiceRow(
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, label) ->
            if (value == selected) {
                Button(
                    onClick = { onSelected(value) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) { Text(label, maxLines = 1) }
            } else {
                OutlinedButton(
                    onClick = { onSelected(value) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                ) { Text(label, maxLines = 1) }
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "terrain-file"
}

private fun parseContentUri(
    context: Context,
    uri: Uri,
    name: String,
    options: LidarImportOptions,
): DemGenerator.TerrainLoadResult? {
    if (name.lowercase(Locale.US).endsWith(".zip")) {
        val temp = File.createTempFile("find-it-import-", ".zip", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> copyLimited(input, output, MAX_LOCAL_IMPORT_BYTES) }
            } ?: return null
            parseTerrainFile(context, temp, name, options).first
        } finally {
            temp.delete()
        }
    }
    return context.contentResolver.openInputStream(uri)?.buffered()?.use {
        DemGenerator.parseFromStreamDetailed(name, it, options)
    }
}

private fun parseTerrainFile(
    context: Context,
    file: File,
    suggestedName: String,
    options: LidarImportOptions,
): Pair<DemGenerator.TerrainLoadResult?, String> {
    val isZip = suggestedName.lowercase(Locale.US).endsWith(".zip") || FileInputStream(file).use { input ->
        val signature = ByteArray(4)
        input.read(signature) == 4 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()
    }
    if (!isZip) {
        return FileInputStream(file).buffered().use {
            DemGenerator.parseFromStreamDetailed(suggestedName, it, options) to suggestedName
        }
    }

    val extracted = File.createTempFile("find-it-unzip-", ".terrain", context.cacheDir)
    return try {
        var entryName: String? = null
        ZipInputStream(BufferedInputStream(FileInputStream(file))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val extension = entry.name.substringAfterLast('.', "").lowercase(Locale.US)
                if (!entry.isDirectory && extension in supportedExtensions) {
                    entryName = entry.name.substringAfterLast('/')
                    FileOutputStream(extracted).use { output -> copyLimited(zip, output, MAX_LOCAL_IMPORT_BYTES) }
                    break
                }
                zip.closeEntry()
            }
        }
        val selectedName = entryName ?: error("ZIP contains no supported terrain file")
        FileInputStream(extracted).buffered().use {
            DemGenerator.parseFromStreamDetailed(selectedName, it, options) to selectedName
        }
    } finally {
        extracted.delete()
    }
}

private inline fun copyLimited(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
    onProgress: (Long) -> Unit = {},
) {
    val buffer = ByteArray(1024 * 1024)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        copied += count
        require(copied <= maxBytes) { "File exceeds the 10 GB safety limit" }
        output.write(buffer, 0, count)
        onProgress(copied)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.1f MiB", mib)
    val gib = mib / 1024.0
    return String.format(Locale.US, "%.2f GiB", gib)
}
