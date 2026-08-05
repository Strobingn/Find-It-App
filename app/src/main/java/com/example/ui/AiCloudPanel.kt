package com.example.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.FieldAiFeature
import com.example.ai.FieldAiSessionPack
import com.example.ai.FieldOfflineAssist
import com.example.ai.GeminiApiClient
import com.example.ai.OpenAiApiClient
import com.example.ai.TerrainAiProvider
import com.example.ai.TerrainVisionSession
import com.example.data.ElevationGrid
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata

internal val AI_BUILT_IN_QUESTIONS = listOf(
    "Analyze the visible viewport image",
    "Compare the local detector results with the image",
    "Identify road traces, walls, foundations, and depressions",
    "Rank the strongest field-check locations",
    "Explain what should be verified on site",
)

private val CompactButtonHeight = 32.dp
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/** Which AI field-pack chip strip to show. Pack1 = ordinals 0..9, Pack3 = 10..19. */
private enum class AiPackFilter {
    ALL,
    PACK1,
    PACK3,
}

private fun FieldAiFeature.matchesPackFilter(filter: AiPackFilter): Boolean = when (filter) {
    AiPackFilter.ALL -> true
    AiPackFilter.PACK1 -> ordinal < 10
    AiPackFilter.PACK3 -> ordinal >= 10
}

/** Cloud controls and conversation only. The interactive analysis map lives above this panel. */
@Composable
fun AiCloudPanel(
    terrainSummary: String,
    grid: ElevationGrid,
    metadata: GeoSpatialMetadata,
    terrainKey: String,
    assistantViewModel: AiTerrainViewModel,
    fieldSessionPack: FieldAiSessionPack? = null,
    onApplyLighting: (azimuth: Float, altitude: Float) -> Unit = { _, _ -> },
    onApplyVizMode: (Int) -> Unit = {},
    /** User-confirmed NAV_TARGET ids; Finds tab / TargetLoggerPanel consume via shared VM state. */
    onApplyNavTargets: (List<Long>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by assistantViewModel.state.collectAsStateWithLifecycle()
    val viewport by TerrainVisionSession.snapshot.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var openAiKey by rememberSaveable { mutableStateOf("") }
    var geminiKey by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(!state.openAiConfigured && !state.geminiConfigured) }
    var attachImage by rememberSaveable { mutableStateOf(true) }
    var packFilter by rememberSaveable { mutableStateOf(AiPackFilter.ALL) }
    val imageReady = viewport.bitmap?.let { !it.isRecycled && it.width > 0 && it.height > 0 } == true
    val lastAssistantText = remember(state.messages) {
        state.messages.lastOrNull { it.role == AiMessageRole.MODEL }?.text?.takeIf { it.isNotBlank() }
    }
    val filteredFeatures = remember(packFilter) {
        FieldAiFeature.entries.filter { it.matchesPackFilter(packFilter) }
    }
    val hasSuggestedFindFields = state.pendingMetalType != null ||
        state.pendingOutcome != null ||
        state.pendingStatus != null

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

    val baseFieldPack = remember(
        fieldSessionPack,
        terrainSummary,
        terrainContext,
        grid.width,
        grid.height,
        grid.cellSizeMeters,
        state.localResult,
    ) {
        fieldSessionPack ?: FieldAiSessionPack(
            terrainSummary = terrainSummary,
            terrainContext = terrainContext,
            sunAzimuth = 315f,
            sunAltitude = 35f,
            gridWidth = grid.width,
            gridHeight = grid.height,
            cellSizeMeters = grid.cellSizeMeters,
            localResult = state.localResult,
            freeformNotes = "",
        )
    }

    LazyColumn(
        modifier = modifier
            .imePadding()
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
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
                            label = { Text(if (attachImage && imageReady) "Map attached" else "Attach map", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Default.ImageSearch, contentDescription = null, modifier = Modifier.height(16.dp)) },
                            modifier = Modifier.height(CompactButtonHeight),
                        )
                        TextButton(
                            onClick = { showKeys = !showKeys },
                            modifier = Modifier.height(CompactButtonHeight),
                            contentPadding = CompactButtonPadding,
                        ) { Text("Keys", style = MaterialTheme.typography.labelSmall) }
                        IconButton(onClick = assistantViewModel::clearConversation, modifier = Modifier.height(CompactButtonHeight)) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear conversation", modifier = Modifier.height(18.dp))
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = state.providerPreference == null,
                            onClick = { assistantViewModel.selectCloudProvider(null) },
                            enabled = !state.isSending,
                            label = { Text("Auto", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(CompactButtonHeight),
                        )
                        FilterChip(
                            selected = state.providerPreference == TerrainAiProvider.OPENAI,
                            onClick = { assistantViewModel.selectCloudProvider(TerrainAiProvider.OPENAI) },
                            enabled = state.openAiConfigured && !state.isSending,
                            label = { Text("OpenAI", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(CompactButtonHeight),
                        )
                        FilterChip(
                            selected = state.providerPreference == TerrainAiProvider.GEMINI,
                            onClick = { assistantViewModel.selectCloudProvider(TerrainAiProvider.GEMINI) },
                            enabled = state.geminiConfigured && !state.isSending,
                            label = { Text("Gemini", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(CompactButtonHeight),
                        )
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
                                modifier = Modifier.height(CompactButtonHeight),
                                contentPadding = CompactButtonPadding,
                            ) { Text("Save OpenAI", style = MaterialTheme.typography.labelSmall) }
                            if (state.hasDeviceOpenAiKey) {
                                OutlinedButton(
                                    onClick = assistantViewModel::clearOpenAiKey,
                                    modifier = Modifier.height(CompactButtonHeight),
                                    contentPadding = CompactButtonPadding,
                                ) { Text("Remove", style = MaterialTheme.typography.labelSmall) }
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
                                modifier = Modifier.height(CompactButtonHeight),
                                contentPadding = CompactButtonPadding,
                            ) { Text("Save Gemini", style = MaterialTheme.typography.labelSmall) }
                            if (state.hasDeviceGeminiKey) {
                                OutlinedButton(
                                    onClick = assistantViewModel::clearGeminiKey,
                                    modifier = Modifier.height(CompactButtonHeight),
                                    contentPadding = CompactButtonPadding,
                                ) { Text("Remove", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ai_field_pack"),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "AI field pack",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = packFilter == AiPackFilter.ALL,
                        onClick = { packFilter = AiPackFilter.ALL },
                        label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_pack_filter_all"),
                    )
                    FilterChip(
                        selected = packFilter == AiPackFilter.PACK1,
                        onClick = { packFilter = AiPackFilter.PACK1 },
                        label = { Text("Pack 1", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_pack_filter_1"),
                    )
                    FilterChip(
                        selected = packFilter == AiPackFilter.PACK3,
                        onClick = { packFilter = AiPackFilter.PACK3 },
                        label = { Text("Pack 3", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_pack_filter_3"),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filteredFeatures.forEach { feature ->
                        AssistChip(
                            onClick = {
                                val pack = if (draft.isNotBlank()) {
                                    baseFieldPack.copy(freeformNotes = draft)
                                } else {
                                    baseFieldPack
                                }
                                if (feature.prefersViewportImage && imageReady) {
                                    attachImage = true
                                }
                                assistantViewModel.runFieldAiFeature(
                                    feature = feature,
                                    pack = pack,
                                    viewport = viewport,
                                    attachViewportImage = imageReady && (attachImage || feature.prefersViewportImage),
                                    terrainKey = terrainKey,
                                )
                            },
                            label = {
                                Text(feature.shortLabel, style = MaterialTheme.typography.labelSmall)
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.height(16.dp),
                                )
                            },
                            enabled = !state.isSending && state.activeProvider != null,
                            modifier = Modifier
                                .height(CompactButtonHeight)
                                .testTag("ai_feature_${feature.name}"),
                        )
                    }
                }
                if (filteredFeatures.isEmpty()) {
                    Text(
                        when (packFilter) {
                            AiPackFilter.PACK3 -> "Pack 3 features not available yet"
                            AiPackFilter.PACK1 -> "No Pack 1 features"
                            AiPackFilter.ALL -> "No field-pack features"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            val pack = baseFieldPack
                            val draft = FieldOfflineAssist.returnTripDraft(
                                signals = pack.signals,
                                excavationLogs = pack.excavationLogs,
                                deviceLat = pack.deviceLatitude,
                                deviceLon = pack.deviceLongitude,
                            )
                            assistantViewModel.postOfflineAssist(
                                title = "Offline return-trip",
                                body = draft,
                                terrainKey = terrainKey,
                            )
                        },
                        enabled = !state.isSending,
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_offline_return_trip"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Offline return-trip", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            val pack = baseFieldPack
                            val candidates = pack.localResult?.candidates
                                ?: state.localResult?.candidates
                                ?: emptyList()
                            val (text, gaps) = FieldOfflineAssist.coverageGapTargets(
                                candidates = candidates,
                                breadcrumbTracks = pack.breadcrumbTracks,
                                signals = pack.signals,
                            )
                            val mapTargets = gaps.map { gap ->
                                CloudMapTarget(
                                    xPercent = gap.xPercent,
                                    yPercent = gap.yPercent,
                                    label = gap.label,
                                    confidence = gap.confidence,
                                )
                            }
                            assistantViewModel.postOfflineAssist(
                                title = "Offline coverage gaps",
                                body = text,
                                mapTargets = mapTargets,
                                terrainKey = terrainKey,
                            )
                        },
                        enabled = !state.isSending,
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_offline_coverage_gaps"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Offline gaps", style = MaterialTheme.typography.labelSmall)
                    }
                }
                val pendingAz = state.pendingLightingAzimuth
                val pendingAlt = state.pendingLightingAltitude
                if (pendingAz != null && pendingAlt != null) {
                    FilledTonalButton(
                        onClick = {
                            onApplyLighting(pendingAz, pendingAlt)
                            assistantViewModel.clearPendingLighting()
                        },
                        enabled = !state.isSending,
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_apply_lighting"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text(
                            "Apply lighting recommendation",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                state.pendingVizMode?.let { mode ->
                    FilledTonalButton(
                        onClick = {
                            onApplyVizMode(mode)
                            assistantViewModel.clearPendingStructuredActions()
                        },
                        enabled = !state.isSending,
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_apply_viz"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text(
                            "Apply viz mode ($mode)",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (state.pendingNavTargetIds.isNotEmpty()) {
                    val navIds = state.pendingNavTargetIds
                    FilledTonalButton(
                        onClick = {
                            // Confirm apply: callback for local hooks; keep ids on state so the
                            // Finds tab (shared AiTerrainViewModel) can set navigationTarget and
                            // clear via onConsumeNavTargets. Clearing here would drop the handoff.
                            onApplyNavTargets(navIds)
                        },
                        enabled = !state.isSending,
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_apply_nav"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text(
                            "Navigate AI stops (${navIds.size})",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                if (hasSuggestedFindFields) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "AI suggested find fields",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.pendingMetalType?.let {
                                Text("Metal: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            state.pendingOutcome?.let {
                                Text("Outcome: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            state.pendingStatus?.let {
                                Text("Status: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            state.pendingStructuredNotes?.let {
                                Text("Notes: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "Suggestions only — not written to finds until you confirm elsewhere.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            TextButton(
                                onClick = assistantViewModel::clearPendingStructuredActions,
                                modifier = Modifier.height(CompactButtonHeight),
                                contentPadding = CompactButtonPadding,
                            ) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (lastAssistantText != null) {
                    TextButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, lastAssistantText)
                                putExtra(Intent.EXTRA_SUBJECT, "Find-It AI reply")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share reply"))
                        },
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_share_reply"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Share reply", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "Quick questions",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AI_BUILT_IN_QUESTIONS.forEach { question ->
                        AssistChip(
                            onClick = {
                                draft = question
                                if (imageReady) attachImage = true
                            },
                            label = { Text(question, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.height(16.dp))
                            },
                            enabled = !state.isSending,
                            modifier = Modifier.height(CompactButtonHeight),
                        )
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
                    Text(state.cloudStage)
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
                            terrainKey = terrainKey,
                        )
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.isSending && state.activeProvider != null,
                    modifier = Modifier.height(CompactButtonHeight),
                    contentPadding = CompactButtonPadding,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Send", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
