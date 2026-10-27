package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.data.ai.AiAnalysisState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiTerrainAnalysisSheet(
    state: AiAnalysisState,
    onDismiss: () -> Unit,
    onReanalyze: () -> Unit,
    onPinFinding: (String) -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .heightIn(min = 200.dp, max = 600.dp),
        ) {
            Text(
                "🤖 AI Terrain Analyst",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            when (state) {
                is AiAnalysisState.Idle -> {
                    Text(
                        "Tap \"Re-analyze\" to analyze the current terrain view.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is AiAnalysisState.Analyzing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                        Text("Analyzing terrain…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is AiAnalysisState.Streaming -> {
                    AnalysisContent(
                        text = state.text,
                        isComplete = false,
                        onReanalyze = onReanalyze,
                        onPinFinding = onPinFinding,
                    )
                }
                is AiAnalysisState.Complete -> {
                    AnalysisContent(
                        text = state.text,
                        isComplete = true,
                        onReanalyze = onReanalyze,
                        onPinFinding = onPinFinding,
                    )
                }
                is AiAnalysisState.Error -> {
                    Text(
                        "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisContent(
    text: String,
    isComplete: Boolean,
    onReanalyze: () -> Unit,
    onPinFinding: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp),
    ) {
        item {
            Text(
                text = text + if (!isComplete) " ▌" else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onReanalyze, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text("Re-analyze", modifier = Modifier.padding(start = 8.dp))
        }
        if (isComplete && text.isNotBlank()) {
            IconButton(onClick = { onPinFinding(text) }) {
                Icon(Icons.Default.PushPin, contentDescription = "Pin finding to target list")
            }
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy analysis text")
            }
        }
    }
}
