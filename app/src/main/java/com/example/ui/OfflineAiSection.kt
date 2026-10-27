package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ai.ModelDownloadState
import com.example.ai.OnDeviceGemma
import com.example.ai.OnDeviceModel
import com.example.ai.OnDeviceModelStore
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosSection
import kotlinx.coroutines.delay

/**
 * "Offline AI" in AI settings: pick Gemma 4 E2B or E4B, download it over Wi-Fi, see progress,
 * cancel or delete. Once downloaded it is the last fallback after the cloud providers, so AI
 * answers keep coming with no signal. [onChanged] fires when availability changes.
 */
@Composable
fun OfflineAiSection(onChanged: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { OnDeviceModelStore(context) }
    var tick by remember { mutableIntStateOf(0) }
    var chosen by remember { mutableStateOf(store.chosenModel()) }
    var message by remember { mutableStateOf<String?>(null) }
    val states = remember(tick) { OnDeviceModel.entries.associateWith { store.state(it) } }
    val active = remember(tick) { store.activeModel() }

    // Poll while anything is downloading (DownloadManager has no push callback worth wiring here).
    val downloading = states.values.any { it is ModelDownloadState.Downloading }
    LaunchedEffect(downloading, tick) {
        if (downloading) {
            delay(1_000)
            tick++
        }
    }
    var lastActive by remember { mutableStateOf(active) }
    LaunchedEffect(active) {
        if (active != lastActive) {
            lastActive = active
            onChanged()
        }
    }

    IosSection(
        header = "Offline AI (on this phone)",
        footer = buildString {
            append("Runs Gemma 4 on your phone with no signal. Used automatically when Grok, OpenAI and Gemini can't be reached. ")
            append("Downloads on Wi-Fi only (${OnDeviceModelStore.formatBytes(store.freeBytes())} free).")
            OnDeviceGemma.loadedBackend?.let { append(" Loaded on $it.") }
        },
    ) {
        OnDeviceModel.entries.forEachIndexed { index, model ->
            if (index > 0) IosDivider(startInset = 16.dp)
            ModelRow(
                model = model,
                state = states.getValue(model),
                selected = chosen == model,
                isActive = active == model,
                onSelect = {
                    chosen = model
                    store.choose(model)
                    tick++
                },
                onDownload = {
                    chosen = model
                    store.choose(model)
                    message = store.startDownload(model)
                    tick++
                },
                onCancel = {
                    store.cancelDownload(model)
                    tick++
                },
                onDelete = {
                    store.delete(model)
                    tick++
                },
            )
        }
        message?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ModelRow(
    model: OnDeviceModel,
    state: ModelDownloadState,
    selected: Boolean,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).testTag("offline_model_${model.name.lowercase()}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(model.label, style = MaterialTheme.typography.bodyLarge)
                    if (isActive) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "In use",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    "${model.sizeLabel} · ${model.blurb}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                ModelDownloadState.NotDownloaded, is ModelDownloadState.Failed ->
                    OutlinedButton(onClick = onDownload, modifier = Modifier.testTag("offline_download_${model.name.lowercase()}")) { Text("Download") }
                is ModelDownloadState.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                ModelDownloadState.Ready -> TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
        when (state) {
            is ModelDownloadState.Downloading -> Column(Modifier.padding(start = 48.dp, top = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (state.waitingForWifi) {
                        "Waiting for Wi-Fi · ${OnDeviceModelStore.formatBytes(state.bytes)} of ${OnDeviceModelStore.formatBytes(state.total)}"
                    } else {
                        "${(state.fraction * 100).toInt()}% · ${OnDeviceModelStore.formatBytes(state.bytes)} of ${OnDeviceModelStore.formatBytes(state.total)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is ModelDownloadState.Failed -> Text(
                state.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 48.dp, top = 2.dp),
            )
            else -> Unit
        }
    }
}
