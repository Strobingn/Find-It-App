package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.GeminiApiClient
import com.example.ai.OpenAiApiClient
import com.example.ai.TerrainAiProvider
import com.example.ai.TerrainVisionSession
import com.example.data.ElevationGrid
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata

/** Cloud controls and conversation only. The interactive analysis map lives above this panel. */
@Composable
fun AiCloudPanel(
    terrainSummary: String,
    grid: ElevationGrid,
    metadata: GeoSpatialMetadata,
    assistantViewModel: AiTerrainViewModel,
    modifier: Modifier = Modifier,
) {
    val state by assistantViewModel.state.collectAsStateWithLifecycle()
    val viewport by TerrainVisionSession.snapshot.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var openAiKey by rememberSaveable { mutableStateOf("") }
    var geminiKey by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(!state.openAiConfigured && !state.geminiConfigured) }
    var attachImage by rememberSaveable { mutableStateOf(true) }
    val imageReady = viewport.bitmap?.let { !it.isRecycled && it.width > 0 && it.height > 0 } == true

    LaunchedEffect(imageReady) {
        if (!imageReady) attachImage = false
    }

    val terrainContext = remember(terrainSummary, grid, metadata, viewport.bounds, viewport.zoom, state.localResult) {
        buildString {
            appendLine("Terrain summary: $terrainSummary")
            appendLine("Raster: ${grid.width} x ${grid.height} cells")
            appendLine("Cell size: ${grid.cellSizeMeters} meters")
            appendLine("CRS: ${metadata.crs}")
            appendLine("Visible zoom: ${"%.2f".format(viewport.zoom)}x")
            appendLine("Visible bounds: left=${viewport.bounds.left}, top=${viewport.bounds.top}, right=${viewport.bounds.right}, bottom=${viewport.bounds.bottom}")
            state.localResult?.let { result ->
                appendLine("Local analysis recommendation: ${result.recommendation}")
                appendLine("Strongest candidate locations:")
                result.candidates.take(12).forEach {
                    appendLine("- ${it.type.label}: ${"%.0f".format(it.score * 100f)}%, x=${"%.1f".format(it.xPercent)}%, y=${"%.1f".format(it.yPercent)}%")
                }
            }
            append("Analyze only the visible rendered surface. Treat suggested dig locations as field-check priorities, not proof of a buried object.")
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("AI analysis", fontWeight = FontWeight.Bold)
                        Text(
                            when (state.activeProvider) {
                                TerrainAiProvider.OPENAI -> "OpenAI ${OpenAiApiClient.configuredModel()} primary · Gemini fallback ready"
                                TerrainAiProvider.GEMINI -> "Gemini ${GeminiApiClient.configuredModel()} active"
                                null -> "Add an OpenAI or Gemini key"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilterChip(
                        selected = attachImage && imageReady,
                        onClick = { attachImage = !attachImage },
                        enabled = imageReady && !state.isSending,
                        label = { Text(if (attachImage && imageReady) "Map attached" else "Attach map") },
                        leadingIcon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                    )
                    TextButton(onClick = { showKeys = !showKeys }) { Text("Keys") }
                    IconButton(onClick = assistantViewModel::clearConversation) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear conversation")
                    }
                }
            }
        }

        if (showKeys) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = openAiKey,
                            onValueChange = { openAiKey = it.trim().take(256) },
                            label = { Text("OpenAI API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    assistantViewModel.saveOpenAiKey(openAiKey)
                                    openAiKey = ""
                                },
                                enabled = openAiKey.length >= 20,
                            ) { Text("Save OpenAI") }
                            if (state.hasDeviceOpenAiKey) {
                                OutlinedButton(onClick = assistantViewModel::clearOpenAiKey) { Text("Remove") }
                            }
                        }
                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = { geminiKey = it.trim().take(256) },
                            label = { Text("Gemini API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    assistantViewModel.saveGeminiKey(geminiKey)
                                    geminiKey = ""
                                },
                                enabled = geminiKey.length >= 20,
                            ) { Text("Save Gemini") }
                            if (state.hasDeviceGeminiKey) {
                                OutlinedButton(onClick = assistantViewModel::clearGeminiKey) { Text("Remove") }
                            }
                        }
                    }
                }
            }
        }

        items(state.messages, key = AiMessage::id) { message ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (message.role == AiMessageRole.USER) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (message.role == AiMessageRole.USER) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    modifier = Modifier.fillMaxWidth(0.94f),
                ) {
                    Column(Modifier.padding(11.dp)) {
                        Text(
                            if (message.role == AiMessageRole.USER) "You" else message.provider?.label ?: "Terrain intelligence",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(message.text)
                    }
                }
            }
        }

        if (state.isSending) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Text("Analyzing the visible map…")
                }
            }
        }

        state.cloudError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(4_000) },
                    label = { Text("Ask AI to analyze or mark targets") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !state.isSending,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        assistantViewModel.send(
                            prompt = draft,
                            terrainContext = terrainContext,
                            viewport = viewport,
                            attachViewportImage = attachImage && imageReady,
                        )
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.isSending && state.activeProvider != null,
                    modifier = Modifier.height(56.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Send")
                }
            }
        }
    }
}
