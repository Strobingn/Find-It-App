package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiConversationTurn
import com.example.ai.GeminiTerrainImageEncoder
import com.example.ai.TerrainVisionSession
import com.example.ai.TerrainVisionSnapshot
import com.example.data.ElevationGrid
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GeminiMessageRole { USER, MODEL }

data class GeminiMessage(
    val id: Long,
    val role: GeminiMessageRole,
    val text: String,
    val usedViewportImage: Boolean = false,
)

data class GeminiAssistantState(
    val messages: List<GeminiMessage> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null,
    val isConfigured: Boolean = GeminiApiClient.isConfigured(),
)

class GeminiAssistantViewModel : ViewModel() {
    private val client = GeminiApiClient()
    private val ids = AtomicLong(1L)
    private val _state = MutableStateFlow(
        GeminiAssistantState(
            messages = listOf(
                GeminiMessage(
                    id = ids.getAndIncrement(),
                    role = GeminiMessageRole.MODEL,
                    text = "Gemini is ready. Attach the visible terrain viewport so I can inspect the rendered LiDAR image as well as its terrain metadata.",
                ),
            ),
        ),
    )
    val state: StateFlow<GeminiAssistantState> = _state.asStateFlow()

    fun send(
        prompt: String,
        terrainContext: String,
        viewport: TerrainVisionSnapshot,
        attachViewportImage: Boolean,
    ) {
        val cleaned = prompt.trim()
        if (cleaned.isBlank() || _state.value.isSending) return
        if (!GeminiApiClient.isConfigured()) {
            _state.value = _state.value.copy(
                error = "Add a real GEMINI_API_KEY to .env or local.properties, then rebuild the app.",
                isConfigured = false,
            )
            return
        }
        if (attachViewportImage && viewport.bitmap == null) {
            _state.value = _state.value.copy(
                error = "Open the Terrain page and render a layer before attaching the current viewport.",
            )
            return
        }

        val userMessage = GeminiMessage(
            id = ids.getAndIncrement(),
            role = GeminiMessageRole.USER,
            text = cleaned,
            usedViewportImage = attachViewportImage,
        )
        val withUser = _state.value.messages + userMessage
        _state.value = _state.value.copy(messages = withUser, isSending = true, error = null)

        viewModelScope.launch {
            try {
                val image = if (attachViewportImage) {
                    withContext(Dispatchers.Default) { GeminiTerrainImageEncoder.encode(viewport) }
                        ?: error("The current terrain viewport could not be encoded for Gemini.")
                } else {
                    null
                }
                val answer = client.generate(
                    conversation = withUser.map {
                        GeminiConversationTurn(
                            role = if (it.role == GeminiMessageRole.MODEL) "model" else "user",
                            text = it.text,
                        )
                    },
                    systemContext = terrainContext,
                    image = image,
                )
                _state.value = _state.value.copy(
                    messages = _state.value.messages + GeminiMessage(
                        id = ids.getAndIncrement(),
                        role = GeminiMessageRole.MODEL,
                        text = answer,
                        usedViewportImage = image != null,
                    ),
                    isSending = false,
                    error = null,
                    isConfigured = true,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    isSending = false,
                    error = error.localizedMessage ?: "Gemini request failed",
                )
            }
        }
    }

    fun clearConversation() {
        _state.value = GeminiAssistantState(
            messages = listOf(
                GeminiMessage(
                    id = ids.getAndIncrement(),
                    role = GeminiMessageRole.MODEL,
                    text = "Conversation cleared. Ask me about the active terrain or attach the visible viewport for image analysis.",
                ),
            ),
            isConfigured = GeminiApiClient.isConfigured(),
        )
    }
}

@Composable
fun GeminiAssistantScreen(
    terrainSummary: String,
    grid: ElevationGrid,
    metadata: GeoSpatialMetadata,
    modifier: Modifier = Modifier,
    assistantViewModel: GeminiAssistantViewModel = viewModel(),
) {
    val state by assistantViewModel.state.collectAsStateWithLifecycle()
    val viewport by TerrainVisionSession.snapshot.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var attachViewportImage by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val imageReady = viewport.bitmap?.let { !it.isRecycled && it.width > 0 && it.height > 0 } == true
    val contextText = remember(terrainSummary, grid, metadata, viewport.bounds, viewport.zoom) {
        buildTerrainContext(terrainSummary, grid, metadata, viewport)
    }

    LaunchedEffect(imageReady) {
        if (!imageReady) attachViewportImage = false
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Gemini terrain assistant", fontWeight = FontWeight.Bold)
                    Text(
                        if (state.isConfigured) {
                            "Using ${GeminiApiClient.configuredModel()} · text and viewport-image analysis ready"
                        } else {
                            "API key missing · add GEMINI_API_KEY and rebuild"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = assistantViewModel::clearConversation) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Gemini conversation")
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ImageSearch, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Current terrain viewport", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (imageReady) {
                                String.format(
                                    Locale.US,
                                    "%.1fx zoom · L %.3f T %.3f R %.3f B %.3f",
                                    viewport.zoom,
                                    viewport.bounds.left,
                                    viewport.bounds.top,
                                    viewport.bounds.right,
                                    viewport.bounds.bottom,
                                )
                            } else {
                                "Open Terrain and render a layer to enable image analysis"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilterChip(
                        selected = attachViewportImage && imageReady,
                        onClick = { attachViewportImage = !attachViewportImage },
                        enabled = imageReady && !state.isSending,
                        label = { Text(if (attachViewportImage && imageReady) "Attached" else "Attach") },
                        leadingIcon = { Icon(Icons.Default.ImageSearch, contentDescription = null) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Analyze the visible viewport image",
                "Identify linear earthworks or old road traces",
                "Look for depressions, platforms, or foundation shapes",
                "Plan a systematic search grid for this viewport",
                "What should I verify in the field?",
            ).forEach { suggestion ->
                AssistChip(
                    onClick = {
                        draft = suggestion
                        if (imageReady) attachViewportImage = true
                    },
                    label = { Text(suggestion) },
                    leadingIcon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.messages, key = GeminiMessage::id) { message -> GeminiMessageBubble(message) }
            if (state.isSending) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                        Text(if (attachViewportImage) "Gemini is inspecting the current terrain viewport…" else "Gemini is analyzing the terrain metadata…")
                    }
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(4_000) },
                label = { Text("Ask about this terrain") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.weight(1f),
                enabled = !state.isSending,
            )
            Button(
                onClick = {
                    assistantViewModel.send(
                        prompt = draft,
                        terrainContext = contextText,
                        viewport = viewport,
                        attachViewportImage = attachViewportImage && imageReady,
                    )
                    draft = ""
                },
                enabled = draft.isNotBlank() && !state.isSending && state.isConfigured,
                modifier = Modifier.height(56.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Send")
            }
        }
    }
}

@Composable
private fun GeminiMessageBubble(message: GeminiMessage) {
    val isUser = message.role == GeminiMessageRole.USER
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isUser) "You" else "Gemini",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (message.usedViewportImage) {
                        Spacer(Modifier.width(7.dp))
                        Icon(
                            Icons.Default.ImageSearch,
                            contentDescription = "Viewport image included",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(16.dp).height(16.dp),
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun buildTerrainContext(
    summary: String,
    grid: ElevationGrid,
    metadata: GeoSpatialMetadata,
    viewport: TerrainVisionSnapshot,
): String {
    val widthMeters = (grid.width - 1).coerceAtLeast(1) * grid.cellSizeMeters
    val heightMeters = (grid.height - 1).coerceAtLeast(1) * grid.cellSizeMeters
    val boundsText = metadata.bounds?.let {
        "south=${it.minLat}, north=${it.maxLat}, west=${it.minLon}, east=${it.maxLon}"
    } ?: "not georeferenced"
    return """
        Terrain summary: $summary
        Raster: ${grid.width} x ${grid.height} cells
        Cell size: ${grid.cellSizeMeters} meters
        Approximate footprint: ${"%.1f".format(widthMeters)} x ${"%.1f".format(heightMeters)} meters
        CRS: ${metadata.crs}
        Datum: ${metadata.datum}
        Geographic bounds: $boundsText
        Visible viewport zoom: ${"%.2f".format(viewport.zoom)}x
        Visible normalized bounds: left=${viewport.bounds.left}, top=${viewport.bounds.top}, right=${viewport.bounds.right}, bottom=${viewport.bounds.bottom}

        When an image is attached, analyze only visible surface patterns. Distinguish plausible terrain signatures from certainty.
        Do not claim that a rendered anomaly proves a buried object, structure, grave, artifact, or metal target exists.
    """.trimIndent()
}
