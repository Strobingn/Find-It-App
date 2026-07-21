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
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.DemGenerator
import com.example.data.GroundSurfaceMode
import com.example.data.LidarImportOptions
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_DOWNLOAD_BYTES = 250L * 1024L * 1024L
private val supportedExtensions = setOf("las", "laz", "asc", "xyz", "csv", "txt", "dem")

@Composable
fun CustomFileLoader(
    onCustomTerrainLoaded: (DemGenerator.TerrainLoadResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(0) }
    var matrix by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    var isWorking by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var groundMode by remember { mutableStateOf(GroundSurfaceMode.SOURCE_CLASSIFIED) }
    var rasterResolution by remember { mutableStateOf(320) }
    var smoothingRadius by remember { mutableStateOf(0) }

    fun importOptions() = LidarImportOptions(
        groundMode = groundMode,
        rasterResolution = rasterResolution,
        smoothingRadius = smoothingRadius,
    )

    fun showResult(result: DemGenerator.TerrainLoadResult?, name: String) {
        isWorking = false
        progress = null
        if (result == null) {
            isError = true
            message = "Could not parse $name. Supported: LAZ, LAS, ASC, XYZ, CSV/text matrices, or ZIP."
        } else {
            onCustomTerrainLoaded(result)
            isError = false
            message = "$name loaded as ${result.grid.width} × ${result.grid.height}. ${result.summary}"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = displayName(context, uri)
        val options = importOptions()
        isWorking = true
        message = "Reading $name…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { parseContentUri(context, uri, name, options) }.getOrNull()
            }
            showResult(result, name)
        }
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Import terrain", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Load LAZ/LAS directly on-device, use another terrain grid, or download a direct HTTPS file. Imported grids without CRS metadata remain local-only.",
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
                    "Choose how returns become a raster. This is non-destructive: the source LAZ/LAS classifications are never overwritten.",
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
                    options = listOf(192 to "Overview", 320 to "Balanced", 512 to "Fine"),
                    selected = rasterResolution,
                    onSelected = { rasterResolution = it },
                )
                Text(
                    "Fine detail preserves smaller banks and foundation edges but renders more slowly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text("Ground smoothing", style = MaterialTheme.typography.labelLarge)
                ChoiceRow(
                    options = listOf(0 to "None", 1 to "Light", 2 to "Strong"),
                    selected = smoothingRadius,
                    onSelected = { smoothingRadius = it },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Local / matrix", "Remote HTTPS").forEachIndexed { index, label ->
                if (mode == index) {
                    Button(onClick = { mode = index }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                } else {
                    OutlinedButton(onClick = { mode = index }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
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
                        onClick = { picker.launch(arrayOf("application/octet-stream", "text/*", "application/zip")) },
                        enabled = !isWorking,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("choose_lidar_file_button"),
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.UploadFile, contentDescription = null)
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
                        "Use the NOAA Data Access Viewer (or another trusted provider), then paste a direct HTTPS link to a LAZ, LAS, ASC, XYZ, CSV, or ZIP download.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://coast.noaa.gov/dataviewer/")))
                            }.onFailure {
                                isError = true
                                message = "No browser is available to open the NOAA portal."
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Open NOAA Data Access Viewer")
                    }
                    OutlinedTextField(
                        value = remoteUrl,
                        onValueChange = { remoteUrl = it.trim(); message = null },
                        label = { Text("Direct HTTPS download URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("remote_url_input"),
                    )
                    Button(
                        onClick = {
                            isWorking = true
                            progress = null
                            message = "Connecting…"
                            scope.launch {
                                downloadTerrain(
                                    context = context,
                                    urlText = remoteUrl,
                                    options = importOptions(),
                                    onProgress = { progress = it },
                                ).onSuccess { (result, name) -> showResult(result, name) }
                                    .onFailure {
                                        isWorking = false
                                        progress = null
                                        isError = true
                                        message = it.localizedMessage ?: "Download failed"
                                    }
                            }
                        },
                        enabled = remoteUrl.isNotBlank() && !isWorking,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("download_lidar_url_button"),
                    ) {
                        androidx.compose.material3.Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Download and render")
                    }
                }
            }
        }

        if (isWorking) {
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { progress!!.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
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
    if (name.lowercase().endsWith(".zip")) {
        val temp = File.createTempFile("find-it-import-", ".zip", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> copyLimited(input, output, MAX_DOWNLOAD_BYTES) }
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

private suspend fun downloadTerrain(
    context: Context,
    urlText: String,
    options: LidarImportOptions,
    onProgress: (Float?) -> Unit,
): Result<Pair<DemGenerator.TerrainLoadResult?, String>> = withContext(Dispatchers.IO) {
    runCatching {
        var currentUrl = validateRemoteUrl(URL(urlText))
        var connection: HttpURLConnection? = null
        var redirectCount = 0
        while (true) {
            connection?.disconnect()
            connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Find-It-Android/1.0")
            }
            val status = connection!!.responseCode
            if (status in 300..399) {
                check(redirectCount++ < 5) { "Too many redirects" }
                val location = connection!!.getHeaderField("Location") ?: error("Redirect had no destination")
                currentUrl = validateRemoteUrl(URL(currentUrl, location))
            } else {
                check(status == HttpURLConnection.HTTP_OK) { "Server returned HTTP $status" }
                break
            }
        }
        val active = requireNotNull(connection)
        check(active.responseCode == HttpURLConnection.HTTP_OK) { "Download did not reach a file" }
        val declaredSize = active.contentLengthLong
        check(declaredSize <= 0 || declaredSize <= MAX_DOWNLOAD_BYTES) { "Download exceeds the 250 MiB safety limit" }

        val dispositionName = active.getHeaderField("Content-Disposition")
            ?.substringAfter("filename=", "")
            ?.trim(' ', '"', '\'')
            ?.takeIf { it.isNotBlank() }
        val urlName = currentUrl.path.substringAfterLast('/').ifBlank { "terrain-download" }
        val name = dispositionName ?: urlName
        val temp = File.createTempFile("find-it-download-", ".bin", context.cacheDir)
        try {
            active.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    copyLimitedWithProgress(input, output, MAX_DOWNLOAD_BYTES) { copied ->
                        val value = if (declaredSize > 0) copied.toFloat() / declaredSize else null
                        withContext(Dispatchers.Main.immediate) { onProgress(value) }
                    }
                }
            }
            active.disconnect()
            val (result, parsedName) = parseTerrainFile(context, temp, name, options)
            withContext(Dispatchers.Main.immediate) { onProgress(1f) }
            result to parsedName
        } finally {
            active.disconnect()
            temp.delete()
        }
    }
}

private fun parseTerrainFile(
    context: Context,
    file: File,
    suggestedName: String,
    options: LidarImportOptions,
): Pair<DemGenerator.TerrainLoadResult?, String> {
    val isZip = suggestedName.lowercase().endsWith(".zip") || FileInputStream(file).use { input ->
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
                    FileOutputStream(extracted).use { output -> copyLimited(zip, output, MAX_DOWNLOAD_BYTES) }
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

private fun validateRemoteUrl(url: URL): URL {
    require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS downloads are allowed" }
    require(url.userInfo == null) { "URLs containing credentials are not allowed" }
    val host = url.host.lowercase(Locale.US)
    require(host.isNotBlank() && host != "localhost" && !host.endsWith(".localhost")) { "Invalid download host" }
    InetAddress.getAllByName(host).forEach { address ->
        val bytes = address.address
        val uniqueLocalV6 = bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
        require(
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                !address.isSiteLocalAddress && !address.isMulticastAddress && !uniqueLocalV6,
        ) { "Private and local network downloads are blocked" }
    }
    return url
}

private inline fun copyLimited(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
    onProgress: (Long) -> Unit = {},
) {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        copied += count
        require(copied <= maxBytes) { "File exceeds the 250 MiB safety limit" }
        output.write(buffer, 0, count)
        onProgress(copied)
    }
}

private suspend fun copyLimitedWithProgress(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    maxBytes: Long,
    onProgress: suspend (Long) -> Unit,
) {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    var lastReported = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        copied += count
        require(copied <= maxBytes) { "File exceeds the 250 MiB safety limit" }
        output.write(buffer, 0, count)
        if (copied - lastReported >= 512 * 1024 || lastReported == 0L) {
            lastReported = copied
            onProgress(copied)
        }
    }
    onProgress(copied)
}
