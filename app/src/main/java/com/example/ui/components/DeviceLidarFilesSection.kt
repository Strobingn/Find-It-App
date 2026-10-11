package com.example.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DeviceLidarFile
import com.example.data.DeviceLidarScanner
import com.example.data.LazDataset
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Pull-to-refresh on the Library tab bumps this to rescan the phone. */
object DeviceLidarScanRequests {
    val counter = MutableStateFlow(0)
    fun request() { counter.value = counter.value + 1 }
}

/** "LAZ/LAS on this phone": auto-scanned shared storage, one tap to open at full detail. */
@Composable
fun DeviceLidarFilesSection(
    library: List<LazDataset>,
    enabled: Boolean,
    onOpen: (DeviceLidarFile) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by DeviceLidarScanner.files.collectAsStateWithLifecycle()
    val scanning by DeviceLidarScanner.scanning.collectAsStateWithLifecycle()
    val requests by DeviceLidarScanRequests.counter.collectAsStateWithLifecycle()
    var hasAccess by remember { mutableStateOf(DeviceLidarScanner.hasAllFilesAccess(context)) }
    var permissionReturns by remember { mutableIntStateOf(0) }

    fun rescan() {
        hasAccess = DeviceLidarScanner.hasAllFilesAccess(context)
        scope.launch { DeviceLidarScanner.scan(context, library) }
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionReturns++ }
    val legacyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionReturns++ }

    LaunchedEffect(requests, permissionReturns) { rescan() }
    LaunchedEffect(library) { DeviceLidarScanner.refilter(library) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("device_lidar_files")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("LAZ/LAS on this phone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${files.size} found · tap to open at full detail",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { rescan() },
                enabled = !scanning,
                modifier = Modifier.testTag("rescan_device_lidar"),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text("Scan")
            }
        }
        if (!hasAccess) {
            Text(
                "Allow All files access so Find-It can find every .laz/.las/.copc.laz on your phone and SD card. " +
                    "Without it, only files Android indexes are listed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(
                onClick = {
                    val intent = DeviceLidarScanner.allFilesAccessIntent(context)
                    if (intent != null) {
                        runCatching { settingsLauncher.launch(intent) }
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                },
                modifier = Modifier.testTag("grant_all_files_access"),
            ) { Text("Allow All files access") }
        }
        if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (files.isEmpty() && !scanning) {
            Text(
                "No new LAZ/LAS files found outside the app library.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        files.forEach { file ->
            OutlinedButton(
                onClick = { onOpen(file) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().height(76.dp).testTag("open_device_lidar_${file.name}"),
            ) {
                Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${formatDeviceBytes(file.sizeBytes)} · " +
                            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.modifiedAtMillis)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        file.folder,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatDeviceBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f MB", bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}
