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
import androidx.compose.material.icons.filled.Share
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
import com.example.ai.parseFindSuggestions
import com.example.ai.resolveMetalType
import com.example.ai.resolveOutcome
import com.example.data.ElevationGrid
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata

/**
 * Built-in AI field prompts for metal-detecting / historic-site work.
 * [label] is the short chip text; [prompt] is the full question sent to the model.
 * Prompts stay honest: LiDAR ranks surface morphology and context, not buried metal or age.
 */
data class AiBuiltInPrompt(
    val label: String,
    val prompt: String,
)

internal val AI_BUILT_IN_PROMPTS: List<AiBuiltInPrompt> = listOf(
    AiBuiltInPrompt(
        label = "Analyze viewport",
        prompt = "Analyze the visible viewport image. Describe the strongest terrain anomalies " +
            "that could relate to historic human activity (foundations, roads, walls, depressions). " +
            "Note uncertainty and natural alternatives. Do not claim buried metal, age, or dig depth.",
    ),
    AiBuiltInPrompt(
        label = "Best dig spots",
        prompt = "Rank the best places in this viewport to field-check for historic artifacts " +
            "(coins, buttons, household goods) based on terrain morphology only: flats near " +
            "possible structures, yard zones, road edges, and refuse-context depressions. " +
            "List 5–10 ordered targets with grid/relative location, why each ranks high, " +
            "and what to verify on the ground. LiDAR does not identify metal or depth.",
    ),
    AiBuiltInPrompt(
        label = "Homesites",
        prompt = "Locate the strongest old homesite / house-lot candidates: building platforms, " +
            "rectangular foundations, cellar-hole depressions with rims, yard flats, and " +
            "clustered small anomalies. Explain supporting and opposing evidence for each. " +
            "Do not invent ownership history or construction dates as fact.",
    ),
    AiBuiltInPrompt(
        label = "Foundations",
        prompt = "Identify possible old foundations and building platforms: rectangular edges, " +
            "corner angles, raised pads, and stone-line footprints. Distinguish from natural " +
            "benches, root throws, and modern grading. Rank by geometric clarity and size.",
    ),
    AiBuiltInPrompt(
        label = "Cellar holes",
        prompt = "Find cellar-hole style depressions: roughly square or rectangular bowls, " +
            "rim raised relative to interior, typical homesite scale. Flag lookalikes " +
            "(tree throws, drainage sinks, modern pits). Give approximate size and search radius.",
    ),
    AiBuiltInPrompt(
        label = "Wagon roads",
        prompt = "Trace old wagon roads, cart paths, and abandoned lanes: linear hollows, " +
            "parallel banks, continuous faint cuts across the terrain. Note where they meet " +
            "possible homesites or field openings. Mark segments that may be modern trails or drainage.",
    ),
    AiBuiltInPrompt(
        label = "Stone walls",
        prompt = "Detect stone-wall and old field-boundary lines: thin linear ridges, " +
            "property-edge continuity, and corners. Separate from natural rock outcrops " +
            "and modern fence grades. Describe how walls may frame a homesite lot.",
    ),
    AiBuiltInPrompt(
        label = "Trash / refuse",
        prompt = "Suggest refuse-pit or trash-scatter zones near likely homesites: small " +
            "depressions, disturbed ground, downslope dumps, and yard edges. These are " +
            "screening hints only—not proof of bottles, iron, or other artifacts.",
    ),
    AiBuiltInPrompt(
        label = "Camp flats",
        prompt = "Find small use-flats or possible camp/occupation pads: level benches above " +
            "wet ground, slight clearings, and clusters of subtle anomalies away from modern roads. " +
            "Prioritize pre-modern appearance; flag modern clearings and logging landings.",
    ),
    AiBuiltInPrompt(
        label = "Civil War era?",
        prompt = "Using terrain context only, highlight locations that could relate to " +
            "19th-century (including Civil War–period) activity: road junctions, camps-like flats, " +
            "linear earthworks, and homesite clusters. Be explicit that terrain cannot date " +
            "features or identify military relics as fact—only suggest field-check priorities.",
    ),
    AiBuiltInPrompt(
        label = "False positives",
        prompt = "List terrain anomalies that look human-made but are likely natural or modern: " +
            "drainage, wetlands, root throws, erosion, logging scars, septic, driveways, " +
            "and low-density LiDAR artifacts. Explain how to reject them in the field.",
    ),
    AiBuiltInPrompt(
        label = "Vs local AI",
        prompt = "Compare the local detector results with the visible viewport image. " +
            "Where do they agree, where do they disagree, and which targets deserve field checks first?",
    ),
    AiBuiltInPrompt(
        label = "Roads & walls",
        prompt = "Identify road traces, stone walls, foundations, and depressions in the " +
            "current view. Rank the clearest historic-looking features and note natural alternatives.",
    ),
    AiBuiltInPrompt(
        label = "Field checklist",
        prompt = "Explain what should be verified on site for the top targets: approach path, " +
            "search radius, photos to take, notes to log, and ethics (permission, modern disturbance, " +
            "avoid cemeteries and restricted ground). No metal/depth claims from LiDAR alone.",
    ),
    AiBuiltInPrompt(
        label = "Sweep plan",
        prompt = "Propose a practical metal-detecting sweep plan for this site: order of " +
            "targets, walking route that reduces backtracking, coil-friendly open ground vs " +
            "brush, and estimated time. Start from high-value homesite/road edges first.",
    ),
    AiBuiltInPrompt(
        label = "Water & lot layout",
        prompt = "Infer a possible historic lot layout from terrain: house platform, yard, " +
            "lane, and relationship to drainage or water. Where would front-yard and side-yard " +
            "artifact scatter often be if this were an old homesite? Label uncertainty clearly.",
    ),
)

/** @deprecated Prefer [AI_BUILT_IN_PROMPTS]; kept for any callers expecting plain strings. */
internal val AI_BUILT_IN_QUESTIONS: List<String> = AI_BUILT_IN_PROMPTS.map { it.prompt }

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
    loggedSignals: List<TargetSignal> = emptyList(),
    onConfirmAiSuggestions: (
        signalId: Long,
        metal: MetalType?,
        outcome: VerificationOutcome?,
        status: String?,
        notes: String?,
    ) -> Unit = { _, _, _, _, _ -> },
    /** Overrides the session pack built from [terrainSummary]/[grid]/[metadata] below, if supplied. */
    fieldSessionPack: FieldAiSessionPack? = null,
    onApplyLighting: (azimuth: Float, altitude: Float) -> Unit = { _, _ -> },
    onApplyVizMode: (Int) -> Unit = {},
    /** Ids stay on assistantViewModel.state until the Finds tab reads and consumes them — this is
     * only a hook for callers that want to react locally (e.g. switch tabs / set playlist) when applied. */
    onApplyNavTargets: (List<Long>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val state by assistantViewModel.state.collectAsStateWithLifecycle()
    val viewport by TerrainVisionSession.snapshot.collectAsStateWithLifecycle()
    var packFilter by rememberSaveable { mutableStateOf(AiPackFilter.ALL) }
    var draft by rememberSaveable { mutableStateOf("") }
    var openAiKey by rememberSaveable { mutableStateOf("") }
    var geminiKey by rememberSaveable { mutableStateOf("") }
    var showKeys by rememberSaveable { mutableStateOf(!state.openAiConfigured && !state.geminiConfigured) }
    var attachImage by rememberSaveable { mutableStateOf(true) }
    var dismissedSuggestionMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedSuggestionSignalId by rememberSaveable { mutableStateOf<Long?>(null) }
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

    val lastModelMessage = state.messages.lastOrNull { it.role == AiMessageRole.MODEL }
    val findSuggestions = remember(lastModelMessage?.id, lastModelMessage?.text) {
        lastModelMessage?.text?.let { parseFindSuggestions(it) }
    }
    val hasSuggestion = findSuggestions != null && (
        findSuggestions.metalTypeLabel != null ||
            findSuggestions.outcomeLabel != null ||
            findSuggestions.statusLabel != null ||
            findSuggestions.notes != null
        )
    val showConfirmCard = hasSuggestion &&
        lastModelMessage != null &&
        dismissedSuggestionMessageId != lastModelMessage.id

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
                        IconButton(
                            onClick = {
                                val reply = lastModelMessage?.text?.takeIf { it.isNotBlank() } ?: return@IconButton
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Find It AI reply")
                                    putExtra(Intent.EXTRA_TEXT, reply)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share AI reply"))
                            },
                            enabled = lastModelMessage?.text?.isNotBlank() == true,
                            modifier = Modifier
                                .height(CompactButtonHeight)
                                .testTag("ai_share_last_reply"),
                        ) {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Share last AI reply",
                                modifier = Modifier.height(18.dp),
                            )
                        }
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
                            val offlineDraft = FieldOfflineAssist.returnTripDraft(
                                signals = pack.signals,
                                excavationLogs = pack.excavationLogs,
                                deviceLat = pack.deviceLatitude,
                                deviceLon = pack.deviceLongitude,
                            )
                            assistantViewModel.postOfflineAssist(
                                title = "Offline return-trip",
                                body = offlineDraft,
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
                            // Finds tab (shared AiTerrainViewModel) can set navigation playlist
                            // and clear via consumeNavTargets. Clearing here would drop the handoff.
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
                    AI_BUILT_IN_PROMPTS.forEach { item ->
                        AssistChip(
                            onClick = {
                                draft = item.prompt
                                if (imageReady) attachImage = true
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.height(16.dp))
                            },
                            enabled = !state.isSending,
                            modifier = Modifier
                                .height(CompactButtonHeight)
                                .testTag("ai_prompt_${item.label.lowercase().replace(Regex("[^a-z0-9]+"), "_")}"),
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

        if (showConfirmCard && findSuggestions != null && lastModelMessage != null) {
            item {
                val targets = loggedSignals
                val resolvedId = selectedSuggestionSignalId
                    ?: targets.singleOrNull()?.id
                    ?: targets.firstOrNull()?.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_confirm_write_card"),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "AI find suggestions (confirm to write)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Suggestions are not applied until you confirm. LiDAR and AI text are not metal identity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        findSuggestions.metalTypeLabel?.let {
                            Text("Metal: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        findSuggestions.outcomeLabel?.let {
                            Text("Outcome: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        findSuggestions.statusLabel?.let {
                            Text("Status: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        findSuggestions.notes?.let {
                            Text("Notes: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (targets.isEmpty()) {
                            Text(
                                "No logged finds on this terrain — log a find first, then confirm.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else if (targets.size > 1) {
                            Text(
                                "Apply to which find?",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                targets.forEach { signal ->
                                    FilterChip(
                                        selected = (selectedSuggestionSignalId ?: resolvedId) == signal.id,
                                        onClick = { selectedSuggestionSignalId = signal.id },
                                        label = {
                                            Text(
                                                "#${signal.id} ${signal.metalType.label}",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    val id = selectedSuggestionSignalId ?: resolvedId
                                    if (id != null) {
                                        onConfirmAiSuggestions(
                                            id,
                                            findSuggestions.metalTypeLabel?.let { resolveMetalType(it) },
                                            findSuggestions.outcomeLabel?.let { resolveOutcome(it) },
                                            findSuggestions.statusLabel,
                                            findSuggestions.notes,
                                        )
                                        dismissedSuggestionMessageId = lastModelMessage.id
                                    }
                                },
                                enabled = (selectedSuggestionSignalId ?: resolvedId) != null,
                                modifier = Modifier
                                    .height(CompactButtonHeight)
                                    .testTag("ai_confirm_write_button"),
                                contentPadding = CompactButtonPadding,
                            ) {
                                Text("Confirm write", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { dismissedSuggestionMessageId = lastModelMessage.id },
                                modifier = Modifier.height(CompactButtonHeight),
                                contentPadding = CompactButtonPadding,
                            ) {
                                Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                            }
                        }
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
