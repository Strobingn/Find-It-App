package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.download.LazDownloadQueue
import com.example.data.download.LazDownloadSource
import com.example.data.download.LazDownloadSourcePrefs

/** Auto / Amazon / USGS chooser for LAZ tile downloads; the choice is persisted. */
@Composable
fun LazDownloadSourcePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var selected by remember(context) { mutableStateOf(LazDownloadSourcePrefs.load(context)) }
    val notice by LazDownloadQueue.notice.collectAsState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Download source", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazDownloadSource.entries.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = {
                        selected = option
                        LazDownloadSourcePrefs.save(context, option)
                        LazDownloadQueue.postNotice(null)
                    },
                    label = { Text(option.label) },
                    modifier = Modifier.testTag("laz_source_${option.name.lowercase()}"),
                )
            }
        }
        Text(
            selected.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
