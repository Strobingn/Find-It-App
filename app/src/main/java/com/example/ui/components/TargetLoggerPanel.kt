package com.example.ui.components

import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TargetSignal
import com.example.data.export.buildCsv
import com.example.data.export.buildGeoJson
import com.example.data.export.buildGpx
import com.example.data.export.buildKml

/** The statuses a find can carry, in the order the filter chips offer them. */
private val FindStatuses = listOf("Logged", "Excavated", "Anomalous", "Trash")

/**
 * The Finds screen: a list first. The header carries the count summary plus export and overflow,
 * filter chips narrow the list by status, and logging a position is a floating pill rather than a
 * setup card pushing the list below the fold.
 */
@Composable
fun TargetLoggerPanel(
    loggedSignals: List<TargetSignal>,
    currentSweepX: Float,
    currentSweepY: Float,
    onLogSignal: () -> Unit,
    onDeleteSignal: (TargetSignal) -> Unit,
    onUpdateSignal: (TargetSignal) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var editingSignal by remember { mutableStateOf<TargetSignal?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf("") }
    var pendingGpx by remember { mutableStateOf("") }
    var pendingKml by remember { mutableStateOf("") }
    var pendingGeoJson by remember { mutableStateOf("") }

    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingCsv) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "CSV saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val gpxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingGpx) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "GPX saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val kmlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingKml) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "KML saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }
    val geoJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/geo+json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingGeoJson) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "GeoJSON saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }

    val visibleSignals = remember(loggedSignals, statusFilter) {
        statusFilter?.let { status -> loggedSignals.filter { it.status == status } } ?: loggedSignals
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Finds",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        summaryLine(loggedSignals),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HeaderIconButton(
                    icon = Icons.Default.Download,
                    contentDescription = "Export field data",
                    enabled = loggedSignals.isNotEmpty(),
                    onClick = { showExport = true },
                )
                Box {
                    HeaderIconButton(
                        icon = Icons.Default.MoreHoriz,
                        contentDescription = "More find options",
                        onClick = { overflowOpen = true },
                    )
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Current position: ${currentSweepX.toInt()}, ${currentSweepY.toInt()}") },
                            enabled = false,
                            onClick = {},
                        )
                        DropdownMenuItem(
                            text = { Text("Clear all finds") },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            enabled = loggedSignals.isNotEmpty(),
                            onClick = {
                                overflowOpen = false
                                confirmClear = true
                            },
                        )
                    }
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                item {
                    FilterChip(
                        label = "All ${loggedSignals.size}",
                        selected = statusFilter == null,
                        onClick = { statusFilter = null },
                    )
                }
                items(FindStatuses) { status ->
                    val count = loggedSignals.count { it.status == status }
                    FilterChip(
                        label = "$status $count",
                        selected = statusFilter == status,
                        onClick = { statusFilter = if (statusFilter == status) null else status },
                    )
                }
            }

            if (exportMessage != null) {
                Text(
                    exportMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (visibleSignals.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (loggedSignals.isEmpty()) {
                            "No finds logged yet. Sweep the map, then log the current position."
                        } else {
                            "No finds match this filter."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("logged_signals_list"),
                ) {
                    items(visibleSignals, key = { it.id }) { signal ->
                        SignalCard(signal = signal, onEdit = { editingSignal = signal })
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onLogSignal,
            shape = RoundedCornerShape(26.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(18.dp)
                .height(52.dp)
                .testTag("log_signal_button"),
        ) {
            Icon(Icons.Default.AddLocationAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Log position", fontWeight = FontWeight.Medium)
        }
    }

    editingSignal?.let { signal ->
        EditSignalDialog(
            signal = signal,
            onDismiss = { editingSignal = null },
            onSave = {
                onUpdateSignal(it)
                editingSignal = null
            },
            onDelete = {
                onDeleteSignal(signal)
                editingSignal = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all finds?") },
            text = { Text("This permanently removes ${loggedSignals.size} saved record(s).") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearAll() }) { Text("Clear all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    if (showExport) {
        ExportGisDialog(
            signals = loggedSignals,
            onDismiss = { showExport = false },
            onSaveCsv = {
                pendingCsv = buildCsv(loggedSignals)
                showExport = false
                csvLauncher.launch("find-it-targets.csv")
            },
            onSaveGpx = {
                pendingGpx = buildGpx(loggedSignals)
                showExport = false
                gpxLauncher.launch("find-it-targets.gpx")
            },
            onSaveKml = {
                pendingKml = buildKml(loggedSignals)
                showExport = false
                kmlLauncher.launch("find-it-targets.kml")
            },
            onSaveGeoJson = {
                pendingGeoJson = buildGeoJson(loggedSignals)
                showExport = false
                geoJsonLauncher.launch("find-it-targets.geojson")
            },
        )
    }
}

/** "7 logged · 3 excavated · 2 anomalous" — only the non-zero statuses, so it stays one line. */
private fun summaryLine(signals: List<TargetSignal>): String {
    if (signals.isEmpty()) return "Nothing logged yet"
    val parts = mutableListOf("${signals.size} logged")
    FindStatuses.drop(1).forEach { status ->
        val count = signals.count { it.status == status }
        if (count > 0) parts += "$count ${status.lowercase()}"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.15f else 0.07f)),
        modifier = Modifier
            .size(38.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = RoundedCornerShape(8.dp),
        border = if (selected) null else BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
        modifier = Modifier.clickable(role = Role.Tab, onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SignalCard(signal: TargetSignal, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Edit find", onClick = onEdit),
    ) {
        Column(
            Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    tint = Color(signal.metalType.colorHex),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    signal.metalType.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (signal.signalStrength > 0f) "${signal.signalStrength.toInt()}%" else "—",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val depth = signal.depthCm?.let { "$it cm" } ?: "depth unknown"
            val source = signal.source.name.lowercase().replaceFirstChar { it.uppercase() }
            Text(
                "Grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()} · $depth · $source · ${signal.status}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (signal.notes.isNotBlank()) {
                Text(
                    signal.notes,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val tags = buildList {
                if (signal.photoUris.isNotEmpty()) {
                    add("${signal.photoUris.size} photo${if (signal.photoUris.size == 1) "" else "s"}")
                }
                if (signal.latitude != null && signal.longitude != null) add("GPS fix")
            }
            if (tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Surface(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSignalDialog(
    signal: TargetSignal,
    onDismiss: () -> Unit,
    onSave: (TargetSignal) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var photoUris by remember(signal.id) { mutableStateOf(signal.photoUris) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            photoUris = (photoUris + uri.toString()).distinct().take(10)
        }
    }
    var notes by remember(signal.id) { mutableStateOf(signal.notes) }
    var status by remember(signal.id) { mutableStateOf(signal.status) }
    var confirmDelete by remember(signal.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit find") },
        text = {
            // Delete moved in here, so the content can now overflow a short screen — make it scroll.
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text("${signal.metalType.label} at grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()}")
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(500) },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Photos (${photoUris.size}/10)", style = MaterialTheme.typography.titleSmall)
                photoUris.forEach { photoUri ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            photoUri.substringAfterLast('/').takeLast(32),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { photoUris = photoUris - photoUri }) { Text("Remove") }
                    }
                }
                OutlinedButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = photoUris.size < 10,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add photo")
                }
                FindStatuses.chunked(2).forEach { rowStatuses ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowStatuses.forEach { item ->
                            if (status == item) {
                                Button(
                                    onClick = { status = item },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text(item) }
                            } else {
                                OutlinedButton(
                                    onClick = { status = item },
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text(item) }
                            }
                        }
                    }
                }
                // The card itself has no delete affordance any more — the redesign's list rows are
                // content-only — so deleting lives here, behind a confirm tap.
                OutlinedButton(
                    onClick = { if (confirmDelete) onDelete() else confirmDelete = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("delete_find_button"),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (confirmDelete) "Tap again to delete" else "Delete find",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(signal.copy(notes = notes.trim(), photoUris = photoUris, status = status))
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportGisDialog(
    signals: List<TargetSignal>,
    onDismiss: () -> Unit,
    onSaveCsv: () -> Unit,
    onSaveGpx: () -> Unit,
    onSaveKml: () -> Unit,
    onSaveGeoJson: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var format by remember { mutableStateOf(0) }
    val georeferenced = signals.count { it.latitude != null && it.longitude != null }
    val labels = listOf("CSV", "GPX", "KML", "GeoJSON")
    val content = remember(signals, format) {
        when (format) {
            0 -> buildCsv(signals)
            1 -> buildGpx(signals)
            2 -> buildKml(signals)
            else -> buildGeoJson(signals)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export field data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    labels.chunked(2).forEachIndexed { rowIndex, rowLabels ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowLabels.forEachIndexed { columnIndex, label ->
                                val value = rowIndex * 2 + columnIndex
                                if (format == value) {
                                    Button(onClick = { format = value }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                                } else {
                                    OutlinedButton(onClick = { format = value }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                                }
                            }
                        }
                    }
                }
                Text(
                    if (format == 0) {
                        "CSV includes all ${signals.size} records. Coordinates remain blank when the source grid has no CRS."
                    } else {
                        "${labels[format]} includes $georeferenced of ${signals.size} records with real WGS84 coordinates."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(content)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy ${labels[format]}")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = when (format) {
                    0 -> onSaveCsv
                    1 -> onSaveGpx
                    2 -> onSaveKml
                    else -> onSaveGeoJson
                },
                enabled = format == 0 || georeferenced > 0,
            ) { Text("Save file") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
