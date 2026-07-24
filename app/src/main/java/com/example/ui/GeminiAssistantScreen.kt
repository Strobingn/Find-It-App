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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import com.example.BuildConfig
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiConversationTurn
import com.example.data.ElevationGrid
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class GeminiMessageRole { USER, MODEL }

data class GeminiMessage(
    val id: Long,
    val role: GeminiMessageRole,
    val text: String,
)

data class GeminiAssistantState(
    val messages: List<GeminiMessage> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null,
    val isConfigured: Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank(),
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
                    text = "Gemini is ready to explain terrain features, suggest search patterns, and review the active LiDAR layer.",
                ),
            ),
        ),
    )
    val state: StateFlow<GeminiAssistantState> = _state.asStateFlow()

    fun send(prompt: String, terrainContext: String) {
        val cleaned = prompt.trim()
        if (cleaned.isBlank() || _state.value.isSending) return
        if (!BuildConfig.GEMINI_API_KEY.isNotBlank()) {
            _state.value = _state.value.copy(
                error = "Add GEMINI_API_KEY to .env, then rebuild the app.",
            )
            return
        }

        val userMessage = GeminiMessage(ids.getAndIncrement(), GeminiMessageRole.USER, cleaned)
        val withUser = _state.value.messages + userMessage
        _state.value = _state.value.copy(messages = withUser, isSending = true, error = null)

        viewModelScope.launch {
            try {
                val answer = client.generate(
                    conversation = withUser.map {
                        GeminiConversationTurn(
                            role = if (it.role == GeminiMessageRole.MODEL) "model" else "user",
                            text = it.text,
                        )
                    },
                    systemContext = buildSystemPrompt(terrainContext),
                )
                _state.value = _state.value.copy(
                    messages = _state.value.messages + GeminiMessage(
                        id = ids.getAndIncrement(),
                        role = GeminiMessageRole.MODEL,
                        text = answer,
                    ),
                    isSending = false,
                    error = null,
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
                    text = "Conversation cleared. Ask me about the active terrain layer.",
                ),
            ),
        )
    }

    private fun buildSystemPrompt(terrainContext: String): String = """
        You are the Find It field assistant for LiDAR terrain analysis and responsible metal-detecting survey planning.
        Give direct, practical answers. Distinguish measured terrain evidence from speculation. Never claim that LiDAR proves
        buried metal exists. Explain visible terrain signatures, likely historical land use, access/safety considerations,
        and efficient survey patterns. Use metric and US customary units when helpful.

        Active app context:
        $terrainContext
    """.trimIndent()
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
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val contextText = remember(terrainSummary, grid, metadata) {
        buildTerrainContext(terrainSummary, grid, metadata)
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
                            "Using ${BuildConfig.GEMINI_MODEL} · active layer context included"
                        } else {
                            "API key missing · add GEMINI_API_KEY to .env"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isConfigured) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                IconButton(onClick = assistantViewModel::clearConversation) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Gemini conversation")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "What terrain features stand out?",
                "Plan a systematic search grid",
                "Explain likely old roads or foundations",
                "What should I verify in the field?",
            ).forEach { suggestion ->
                AssistChip(
                    onClick = { draft = suggestion },
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
            items(state.messages, key = GeminiMessage::id) { message ->
                GeminiMessageBubble(message)
            }
            if (state.isSending) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                        Text("Gemini is analyzing the active terrain…")
                    }
                }
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

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
                    assistantViewModel.send(draft, contextText)
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(
                    if (isUser) "You" else "Gemini",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
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
    """.trimIndent()
}
