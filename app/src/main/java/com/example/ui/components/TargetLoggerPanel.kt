package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TargetSignal

@Composable
fun TargetLoggerPanel(
    loggedSignals: List<TargetSignal>,
    currentSweepX: Float,
    currentSweepY: Float,
    onLogSignal: () -> Unit,
    onDeleteSignal: (TargetSignal) -> Unit,
    onUpdateSignal: (TargetSignal) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editingSignal by remember { mutableStateOf<TargetSignal?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141518))
            .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("target_logger_panel")
    ) {
        // --- Header Section ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.FormatListBulleted,
                    contentDescription = null,
                    tint = Color(0xFFFFD700),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "TARGET SURVEY LOG",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            if (loggedSignals.isNotEmpty()) {
                Text(
                    text = "${loggedSignals.size} marks",
                    color = Color(0xFFFFD700),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // --- Log Marker Button ---
        Button(
            onClick = onLogSignal,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFD700),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("log_current_signal_button")
        ) {
            Icon(
                imageVector = Icons.Default.AddLocationAlt,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "LOG FIND AT COIL (X:${currentSweepX.toInt()}, Y:${currentSweepY.toInt()})",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Logs List ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1E2026))
                .border(1.dp, Color(0xFF2E313D), RoundedCornerShape(10.dp))
        ) {
            if (loggedSignals.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No targets logged yet.\nSweep the coil on the LiDAR Map and tap 'LOG FIND' to mark a discovery.",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.testTag("logged_signals_list")
                ) {
                    items(loggedSignals) { signal ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF141518))
                                .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(8.dp))
                                .clickable { editingSignal = signal }
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Flag,
                                    contentDescription = null,
                                    tint = Color(signal.metalType.colorHex),
                                    modifier = Modifier.size(18.dp).padding(top = 2.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = signal.metalType.label,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        // Status Pill Indicator
                                        val statusBgColor = when(signal.status) {
                                            "Excavated" -> Color(0xFF00E676).copy(alpha = 0.15f)
                                            "Anomalous" -> Color(0xFF29B6F6).copy(alpha = 0.15f)
                                            "Trash" -> Color(0xFFD32F2F).copy(alpha = 0.15f)
                                            else -> Color(0xFFFFD700).copy(alpha = 0.15f)
                                        }
                                        val statusTextColor = when(signal.status) {
                                            "Excavated" -> Color(0xFF00E676)
                                            "Anomalous" -> Color(0xFF29B6F6)
                                            "Trash" -> Color(0xFFEF5350)
                                            else -> Color(0xFFFFD700)
                                        }
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(statusBgColor)
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = signal.status.uppercase(),
                                                color = statusTextColor,
                                                fontSize = 7.5.sp,
                                                fontWeight = FontWeight.Black
                                            )
                                        }
                                    }
                                    Text(
                                        text = "Grid: (${signal.gridX.toInt()}, ${signal.gridY.toInt()})  •  ${signal.depthCm}cm deep  •  Strength: ${signal.signalStrength.toInt()}%",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (signal.notes.isNotBlank()) {
                                        Text(
                                            text = "Note: \"${signal.notes}\"",
                                            color = Color(0xFF81C784),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { editingSignal = signal },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit log details",
                                        tint = Color.LightGray.copy(alpha = 0.7f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSignal(signal) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete log",
                                        tint = Color.Red.copy(alpha = 0.7f),
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (loggedSignals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onClearAll,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.Red
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(36.dp)
                ) {
                    Text(text = "CLEAR ALL", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showExportDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E2026),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1.8f)
                        .height(36.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "EXPORT GIS (CSV/GPX)", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Dialog for Editing Find Details (Priority 3)
    editingSignal?.let { signal ->
        EditSignalDialog(
            signal = signal,
            onDismiss = { editingSignal = null },
            onSave = { updated ->
                onUpdateSignal(updated)
                editingSignal = null
            }
        )
    }

    // Dialog for GPS / GIS Data Export (Priority 9)
    if (showExportDialog) {
        ExportGisDialog(
            signals = loggedSignals,
            onDismiss = { showExportDialog = false }
        )
    }
}

@Composable
fun EditSignalDialog(
    signal: TargetSignal,
    onDismiss: () -> Unit,
    onSave: (TargetSignal) -> Unit
) {
    var notes by remember { mutableStateOf(signal.notes) }
    var status by remember { mutableStateOf(signal.status) }
    val statuses = listOf("Logged", "Excavated", "Anomalous", "Trash")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Edit Find Details",
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Location: (${signal.gridX.toInt()}, ${signal.gridY.toInt()}) • Metal: ${signal.metalType.label}",
                    color = Color.LightGray,
                    fontSize = 11.sp
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Find Notes / Relic Description") },
                    textStyle = TextStyle(color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.LightGray,
                        focusedBorderColor = Color(0xFFFFD700),
                        unfocusedBorderColor = Color.Gray,
                        focusedLabelColor = Color(0xFFFFD700)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Text(
                        text = "Survey Status Flag:",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        statuses.forEach { s ->
                            val isSelected = status == s
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) Color(0xFFFFD700) else Color(0xFF1E2026))
                                    .border(1.dp, if (isSelected) Color(0xFFFFD700) else Color(0xFF2C2E35), RoundedCornerShape(6.dp))
                                    .clickable { status = s }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = s.uppercase(),
                                    color = if (isSelected) Color.Black else Color.LightGray,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(signal.copy(notes = notes, status = status))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black)
            ) {
                Text("Save", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray, fontSize = 11.sp)
            }
        },
        containerColor = Color(0xFF141518)
    )
}

@Composable
fun ExportGisDialog(
    signals: List<TargetSignal>,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = CSV, 1 = GPX
    val clipboardManager = LocalClipboardManager.current

    // Compute mock spatial alignments near old historic areas
    val csvData = remember(signals) {
        val sb = java.lang.StringBuilder()
        sb.append("ID,GridX,GridY,Latitude,Longitude,MetalType,SignalStrength,DepthCm,Status,Notes\n")
        for (sig in signals) {
            val lat = 38.8977 + (sig.gridY - 50.0) * 0.00015
            val lon = -77.0365 + (sig.gridX - 50.0) * 0.00015
            val cleanedNotes = sig.notes.replace("\"", "\"\"")
            sb.append("${sig.id},${sig.gridX.toInt()},${sig.gridY.toInt()},$lat,$lon,${sig.metalType.name},${sig.signalStrength.toInt()},${sig.depthCm},${sig.status},\"$cleanedNotes\"\n")
        }
        sb.toString()
    }

    val gpxData = remember(signals) {
        val sb = java.lang.StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"LidarGroundStack\">\n")
        for (sig in signals) {
            val lat = 38.8977 + (sig.gridY - 50.0) * 0.00015
            val lon = -77.0365 + (sig.gridX - 50.0) * 0.00015
            sb.append("  <wpt lat=\"$lat\" lon=\"$lon\">\n")
            sb.append("    <name>${sig.metalType.label}</name>\n")
            sb.append("    <desc>Strength: ${sig.signalStrength.toInt()}%, Depth: ${sig.depthCm}cm, Status: ${sig.status}, Notes: ${sig.notes}</desc>\n")
            sb.append("  </wpt>\n")
        }
        sb.append("</gpx>")
        sb.toString()
    }

    val activeText = if (selectedTab == 0) csvData else gpxData

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Export GIS Datasets",
                    color = Color(0xFFFFD700),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "Google Earth/QGIS ready",
                    color = Color.Gray,
                    fontSize = 10.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Tab Selection Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2026))
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 0) Color(0xFFFFD700) else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Spreadsheet CSV",
                            color = if (selectedTab == 0) Color.Black else Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selectedTab == 1) Color(0xFFFFD700) else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Handheld GPS GPX",
                            color = if (selectedTab == 1) Color.Black else Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                // Text Display Panel
                OutlinedTextField(
                    value = activeText,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0D0E12),
                        unfocusedContainerColor = Color(0xFF0D0E12),
                        focusedBorderColor = Color(0xFF2C2E35),
                        unfocusedBorderColor = Color(0xFF2C2E35)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )

                Text(
                    text = if (selectedTab == 0) {
                        "CSV files import directly into Excel, Google Sheets, or QGIS vector points. Standard GIS WGS84 coordinates are auto-aligned."
                    } else {
                        "GPX waypoints load directly onto Garmin GPS devices, Google Earth Pro, or hiking tracker apps."
                    },
                    color = Color.Gray,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    clipboardManager.setText(AnnotatedString(activeText))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black)
            ) {
                Text("Copy to Clipboard", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color.Gray, fontSize = 11.sp)
            }
        },
        containerColor = Color(0xFF141518)
    )
}
