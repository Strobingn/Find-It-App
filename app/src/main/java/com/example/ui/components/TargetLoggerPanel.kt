package com.example.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.TargetSignal
import com.example.data.export.buildCsv
import com.example.data.export.buildGpx

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
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var pendingCsv by remember { mutableStateOf("") }
    var pendingGpx by remember { mutableStateOf("") }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Field finds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Current grid position: ${currentSweepX.toInt()}, ${currentSweepY.toInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onLogSignal,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("log_signal_button"),
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Log current position")
                }
            }
        }

        if (exportMessage != null) {
            Text(
                exportMessage.orEmpty(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (loggedSignals.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No finds logged yet. Sweep the map, then log the current position.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Clear")
                }
                Button(
                    onClick = { showExport = true },
                    modifier = Modifier.weight(1.5f).height(48.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export CSV / GPX")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("logged_signals_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(loggedSignals, key = { it.id }) { signal ->
                    SignalCard(
                        signal = signal,
                        onEdit = { editingSignal = signal },
                        onDelete = { onDeleteSignal(signal) },
                    )
                }
            }
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
        )
    }
}

@Composable
private fun SignalCard(signal: TargetSignal, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Flag,
                contentDescription = null,
                tint = Color(signal.metalType.colorHex),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(signal.metalType.label, fontWeight = FontWeight.Bold)
                val depth = signal.depthCm?.let { "$it cm" } ?: "depth unknown"
                Text(
                    "Grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()} · $depth · ${signal.signalStrength.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${signal.source.name.lowercase().replaceFirstChar { it.uppercase() }} · ${signal.status}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (signal.notes.isNotBlank()) {
                    Text(signal.notes, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Edit find")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete find")
            }
        }
    }
}

@Composable
private fun EditSignalDialog(
    signal: TargetSignal,
    onDismiss: () -> Unit,
    onSave: (TargetSignal) -> Unit,
) {
    var notes by remember(signal.id) { mutableStateOf(signal.notes) }
    var status by remember(signal.id) { mutableStateOf(signal.status) }
    val statuses = listOf("Logged", "Excavated", "Anomalous", "Trash")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit find") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${signal.metalType.label} at grid ${signal.gridX.toInt()}, ${signal.gridY.toInt()}")
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(500) },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                statuses.chunked(2).forEach { rowStatuses ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowStatuses.forEach { item ->
                            val selected = status == item
                            if (selected) {
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
            }
        },
        confirmButton = {
            Button(onClick = { onSave(signal.copy(notes = notes.trim(), status = status)) }) {
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
) {
    val clipboard = LocalClipboardManager.current
    var format by remember { mutableStateOf(0) }
    val georeferenced = signals.count { it.latitude != null && it.longitude != null }
    val content = remember(signals, format) { if (format == 0) buildCsv(signals) else buildGpx(signals) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export field data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("CSV" to 0, "GPX" to 1).forEach { (label, value) ->
                        if (format == value) {
                            Button(onClick = { format = value }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { format = value }, modifier = Modifier.weight(1f).height(48.dp)) { Text(label) }
                        }
                    }
                }
                Text(
                    if (format == 0) {
                        "CSV includes all ${signals.size} records. Coordinates remain blank when the source grid has no CRS."
                    } else {
                        "GPX includes $georeferenced of ${signals.size} records with real WGS84 coordinates."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(content)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy ${if (format == 0) "CSV" else "GPX"}")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = if (format == 0) onSaveCsv else onSaveGpx,
                enabled = format == 0 || georeferenced > 0,
            ) { Text("Save file") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
