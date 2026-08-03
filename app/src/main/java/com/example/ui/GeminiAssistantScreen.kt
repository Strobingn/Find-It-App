package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Memory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.GeminiApiClient
import com.example.ai.GeminiConversationTurn
import com.example.ai.GeminiTerrainImageEncoder
import com.example.ai.OpenAiApiClient
import com.example.ai.TerrainAiGateway
import com.example.ai.TerrainAiProvider
import com.example.ai.TerrainVisionSession
import com.example.ai.TerrainVisionSnapshot
import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.TerrainDerivedLayerCache
import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainIntelligenceEngine
import com.example.analysis.TerrainIntelligenceRenderer
import com.example.analysis.TerrainIntelligenceResult
import com.example.analysis.VerifiedFeedback
import com.example.analysis.VerifiedFeedbackPoint
import com.example.data.AppMemoryBudget
import com.example.data.ElevationGrid
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AiMessageRole { USER, MODEL }

data class AiMessage(
    val id: Long,
    val role: AiMessageRole,
    val text: String,
    val provider: TerrainAiProvider? = null,
    val usedViewportImage: Boolean = false,
)

data class CloudMapTarget(
    val xPercent: Float,
    val yPercent: Float,
    val label: String,
    val confidence: Float,
)

private val cloudMapTargetPattern = Regex(
    """\[MAP_TARGET\s+x=([0-9]+(?:\.[0-9]+)?)\s+y=([0-9]+(?:\.[0-9]+)?)\s+confidence=([0-9]+(?:\.[0-9]+)?)\s+label=([^\]]+)]""",
    RegexOption.IGNORE_CASE,
)

internal fun parseCloudMapTargets(
    response: String,
    viewportBounds: NormalizedRasterBounds,
): List<CloudMapTarget> {
    val bounds = viewportBounds.sanitized()
    return cloudMapTargetPattern.findAll(response).mapNotNull { match ->
        val localX = match.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: return@mapNotNull null
        val localY = match.groupValues[2].toFloatOrNull()?.coerceIn(0f, 100f) ?: return@mapNotNull null
        val confidence = match.groupValues[3].toFloatOrNull()?.let {
            if (it > 1f) it / 100f else it
        }?.coerceIn(0f, 1f) ?: return@mapNotNull null
        val label = match.groupValues[4].trim().take(80).ifBlank { "AI target" }
        CloudMapTarget(
            xPercent = ((bounds.left + (localX.toDouble() / 100.0) * (bounds.right - bounds.left)) * 100.0).toFloat(),
            yPercent = ((bounds.top + (localY.toDouble() / 100.0) * (bounds.bottom - bounds.top)) * 100.0).toFloat(),
            label = label,
            confidence = confidence,
        )
    }.distinctBy { "${it.xPercent.toInt()}:${it.yPercent.toInt()}:${it.label}" }.take(12).toList()
}

private fun removeCloudMapTargetTags(response: String): String =
    response.replace(cloudMapTargetPattern, "").replace(Regex("\n{3,}"), "\n\n").trim()

data class AiTerrainState(
    val messages: List<AiMessage> = emptyList(),
    val isSending: Boolean = false,
    val cloudError: String? = null,
    val cloudStage: String = "Cloud AI ready",
    val openAiConfigured: Boolean = false,
    val geminiConfigured: Boolean = false,
    val hasDeviceOpenAiKey: Boolean = false,
    val hasDeviceGeminiKey: Boolean = false,
    val activeProvider: TerrainAiProvider? = null,
    val providerPreference: TerrainAiProvider? = null,
    val isLocalAnalyzing: Boolean = false,
    val isLocalRestoring: Boolean = false,
    val localStage: String = "Ready for offline terrain analysis",
    val localError: String? = null,
    val localResult: TerrainIntelligenceResult? = null,
    val selectedLayer: TerrainDerivedLayer = TerrainDerivedLayer.LOCAL_RELIEF,
    val showSourceHillshade: Boolean = true,
    val localLayerBitmap: Bitmap? = null,
    val cloudMapTargets: List<CloudMapTarget> = emptyList(),
    val cloudTerrainKey: String? = null,
    /** Field-verified points for the current dataset, derived from logged finds - see [VerifiedFeedback]. */
    val verifiedFeedback: List<VerifiedFeedbackPoint> = emptyList(),
)

class AiTerrainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val gateway = TerrainAiGateway(appContext)
    private val localEngine = TerrainIntelligenceEngine(
        TerrainDerivedLayerCache(File(application.cacheDir, "terrain-intelligence-v2")),
    )
    private val ids = AtomicLong(1L)
    private var localAnalysisJob: Job? = null
    private val _state = MutableStateFlow(
        AiTerrainState(
            messages = listOf(
                AiMessage(
                    id = ids.getAndIncrement(),
                    role = AiMessageRole.MODEL,
                    text = "OpenAI is the primary cloud analyst, Gemini is the automatic fallback, and local terrain intelligence runs without either provider.",
                ),
            ),
        ).withProviderStatus(),
    )
    val state: StateFlow<AiTerrainState> = _state.asStateFlow()

    fun saveOpenAiKey(value: String) {
        val saved = OpenAiApiClient.saveDeviceApiKey(appContext, value)
        _state.value = _state.value.withProviderStatus().copy(
            cloudError = if (saved) null else "Enter a valid OpenAI API key.",
        )
    }

    fun clearOpenAiKey() {
        OpenAiApiClient.clearDeviceApiKey(appContext)
        _state.value = _state.value.withProviderStatus().copy(cloudError = null)
    }

    fun saveGeminiKey(value: String) {
        val saved = GeminiApiClient.saveDeviceApiKey(appContext, value)
        _state.value = _state.value.withProviderStatus().copy(
            cloudError = if (saved) null else "Enter a valid Gemini API key.",
        )
    }

    fun clearGeminiKey() {
        GeminiApiClient.clearDeviceApiKey(appContext)
        _state.value = _state.value.withProviderStatus().copy(cloudError = null)
    }

    fun selectCloudProvider(provider: TerrainAiProvider?) {
        val unavailable = when (provider) {
            TerrainAiProvider.OPENAI -> !OpenAiApiClient.isConfigured(appContext)
            TerrainAiProvider.GEMINI -> !GeminiApiClient.isConfigured(appContext)
            null -> false
        }
        _state.value = _state.value.copy(
            providerPreference = provider.takeUnless { unavailable },
            cloudError = if (unavailable) "${provider?.label} is not configured on this device." else null,
        )
    }

    /**
     * Runs the offline detectors, feeding in any field-verified outcomes logged for this exact
     * dataset so both [TerrainIntelligenceEngine]'s candidates and [MetalDetectingTargetRefiner]'s
     * targets (via [AiTerrainState.verifiedFeedback], applied by the caller) benefit from real
     * confirmed/rejected field checks instead of only ever seeing an empty feedback list.
     */
    fun runLocalAnalysis(grid: ElevationGrid, terrainSummary: String, loggedSignals: List<TargetSignal> = emptyList()) {
        if (_state.value.isLocalAnalyzing) return
        localAnalysisJob?.cancel()
        _state.value = _state.value.copy(
            isLocalAnalyzing = true,
            isLocalRestoring = false,
            localError = null,
            localStage = "Starting offline terrain-feature extraction…",
        )
        localAnalysisJob = viewModelScope.launch {
            try {
                val datasetKey = TerrainIntelligenceEngine.terrainSignature(grid)
                val verifiedPoints = VerifiedFeedback.derive(loggedSignals, datasetKey)
                val result = localEngine.analyze(
                    grid = grid,
                    terrainSummary = terrainSummary,
                    feedback = VerifiedFeedback.toTerrainFeedbackRecords(datasetKey, verifiedPoints),
                    onStage = { stage -> _state.value = _state.value.copy(localStage = stage) },
                )
                val layer = _state.value.selectedLayer
                val bitmap = withContext(Dispatchers.Default) {
                    TerrainIntelligenceRenderer.renderLayer(result, layer)
                }
                _state.value = _state.value.copy(
                    isLocalAnalyzing = false,
                    localStage = "Offline analysis complete · ${result.candidates.size} candidates",
                    localResult = result,
                    localLayerBitmap = bitmap,
                    localError = null,
                    verifiedFeedback = verifiedPoints,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    isLocalAnalyzing = false,
                    localError = error.localizedMessage ?: "Local terrain analysis failed",
                    localStage = "Offline analysis stopped",
                )
            }
        }
    }

    /**
     * Rehydrates the AI workspace after process death. Derived layers are already stored in the
     * disk cache, so reopening the same terrain restores the result without repeating extraction.
     */
    fun restoreLocalAnalysis(grid: ElevationGrid, terrainSummary: String) {
        val datasetKey = TerrainIntelligenceEngine.terrainSignature(grid)
        if (_state.value.localResult?.datasetKey == datasetKey) return
        localAnalysisJob?.cancel()
        _state.value = _state.value.copy(
            isLocalAnalyzing = false,
            isLocalRestoring = true,
            localResult = null,
            localLayerBitmap = null,
            localError = null,
            localStage = "Checking for saved analysis…",
        )
        localAnalysisJob = viewModelScope.launch {
            try {
                val result = localEngine.restoreCached(
                    grid = grid,
                    terrainSummary = terrainSummary,
                    onStage = { stage -> _state.value = _state.value.copy(localStage = stage) },
                )
                if (result == null) {
                    _state.value = _state.value.copy(
                        isLocalRestoring = false,
                        localStage = "Source detail ready · tap Analyze to update derived layers",
                    )
                    return@launch
                }
                val bitmap = withContext(Dispatchers.Default) {
                    TerrainIntelligenceRenderer.renderLayer(result, _state.value.selectedLayer)
                }
                _state.value = _state.value.copy(
                    isLocalRestoring = false,
                    localStage = "Saved analysis restored · ${result.candidates.size} candidates",
                    localResult = result,
                    localLayerBitmap = bitmap,
                    localError = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    isLocalRestoring = false,
                    localError = error.localizedMessage ?: "Could not restore saved analysis",
                    localStage = "Tap Analyze to rebuild derived layers",
                )
            }
        }
    }

    fun selectLocalLayer(layer: TerrainDerivedLayer) {
        val result = _state.value.localResult ?: return
        _state.value = _state.value.copy(selectedLayer = layer, showSourceHillshade = false)
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                TerrainIntelligenceRenderer.renderLayer(result, layer)
            }
            _state.value = _state.value.copy(localLayerBitmap = bitmap)
        }
    }

    fun selectSourceHillshade() {
        _state.value = _state.value.copy(showSourceHillshade = true)
    }

    fun send(
        prompt: String,
        terrainContext: String,
        viewport: TerrainVisionSnapshot,
        attachViewportImage: Boolean,
        terrainKey: String? = null,
    ) {
        val cleaned = prompt.trim()
        if (cleaned.isBlank() || _state.value.isSending) return
        val preference = _state.value.providerPreference
        if (TerrainAiGateway.preferredProvider(appContext) == null) {
            _state.value = _state.value.copy(
                cloudError = "Add an OpenAI key for the primary provider or a Gemini key for fallback. Offline analysis still works without a key.",
            )
            return
        }
        if (attachViewportImage && viewport.bitmap == null) {
            _state.value = _state.value.copy(
                cloudError = "Open Terrain and render a layer before attaching the current viewport.",
            )
            return
        }

        val userMessage = AiMessage(
            id = ids.getAndIncrement(),
            role = AiMessageRole.USER,
            text = cleaned,
            usedViewportImage = attachViewportImage,
        )
        val withUser = _state.value.messages + userMessage
        _state.value = _state.value.copy(
            messages = withUser,
            isSending = true,
            cloudError = null,
            cloudStage = "Preparing the terrain image…",
        )

        viewModelScope.launch {
            try {
                val image = if (attachViewportImage) {
                    withContext(Dispatchers.Default) { GeminiTerrainImageEncoder.encode(viewport) }
                        ?: error("The current terrain viewport could not be encoded.")
                } else {
                    null
                }
                val answer = gateway.generate(
                    conversation = withUser.map {
                        GeminiConversationTurn(
                            role = if (it.role == AiMessageRole.MODEL) "model" else "user",
                            text = it.text,
                        )
                    },
                    systemContext = if (image != null) {
                        terrainContext + """

                            MAP DRAWING PROTOCOL:
                            Inspect the attached terrain image and identify up to 8 worthwhile field-check targets.
                            The image coordinates are 0..100 from left to right and 0..100 from top to bottom.
                            After the written explanation, emit one exact marker line per target:
                            [MAP_TARGET x=42.0 y=61.0 confidence=0.82 label=possible cellar rim]
                            Only mark visible evidence. Do not claim buried metal, age, or depth as fact.
                        """.trimIndent()
                    } else {
                        terrainContext
                    },
                    image = image,
                    requestedProvider = preference,
                    onProviderStage = { stage ->
                        _state.value = _state.value.copy(cloudStage = stage)
                    },
                )
                val cloudTargets = if (image != null) {
                    parseCloudMapTargets(answer.text, viewport.bounds)
                } else {
                    emptyList()
                }
                val fallbackNote = answer.fallbackReason?.let {
                    "\n\nFallback used because OpenAI returned: $it"
                }.orEmpty()
                _state.value = _state.value.copy(
                    messages = _state.value.messages + AiMessage(
                        id = ids.getAndIncrement(),
                        role = AiMessageRole.MODEL,
                        text = removeCloudMapTargetTags(answer.text) + fallbackNote,
                        provider = answer.provider,
                        usedViewportImage = image != null,
                    ),
                    isSending = false,
                    cloudError = null,
                    cloudStage = "Completed with ${answer.provider.label}",
                    cloudMapTargets = if (cloudTargets.isNotEmpty()) {
                        cloudTargets
                    } else {
                        _state.value.cloudMapTargets
                    },
                    cloudTerrainKey = if (cloudTargets.isNotEmpty()) terrainKey else _state.value.cloudTerrainKey,
                ).withProviderStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    isSending = false,
                    cloudError = error.localizedMessage ?: "Cloud AI request failed",
                    cloudStage = "Cloud AI request stopped",
                ).withProviderStatus()
            }
        }
    }

    fun clearConversation() {
        _state.value = _state.value.copy(
            messages = listOf(
                AiMessage(
                    id = ids.getAndIncrement(),
                    role = AiMessageRole.MODEL,
                    text = "Conversation cleared. OpenAI remains primary, Gemini remains fallback, and local analysis is independent.",
                ),
            ),
            cloudError = null,
            cloudStage = "Cloud AI ready",
            cloudMapTargets = emptyList(),
            cloudTerrainKey = null,
        ).withProviderStatus()
    }

    private fun AiTerrainState.withProviderStatus(): AiTerrainState = copy(
        openAiConfigured = OpenAiApiClient.isConfigured(appContext),
        geminiConfigured = GeminiApiClient.isConfigured(appContext),
        hasDeviceOpenAiKey = OpenAiApiClient.hasDeviceApiKey(appContext),
        hasDeviceGeminiKey = GeminiApiClient.hasDeviceApiKey(appContext),
        activeProvider = TerrainAiGateway.preferredProvider(appContext),
    )
}

@Composable
fun GeminiAssistantScreen(
    terrainSummary: String,
    grid: ElevationGrid,
    metadata: GeoSpatialMetadata,
    modifier: Modifier = Modifier,
    assistantViewModel: AiTerrainViewModel = viewModel(key = "gemini_assistant_screen"),
) {
    val state by assistantViewModel.state.collectAsStateWithLifecycle()
    val viewport by TerrainVisionSession.snapshot.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var openAiKeyDraft by rememberSaveable { mutableStateOf("") }
    var geminiKeyDraft by rememberSaveable { mutableStateOf("") }
    var showKeyEditor by rememberSaveable {
        mutableStateOf(!state.openAiConfigured && !state.geminiConfigured)
    }
    var attachViewportImage by rememberSaveable { mutableStateOf(true) }
    val imageReady = viewport.bitmap?.let { !it.isRecycled && it.width > 0 && it.height > 0 } == true
    val contextText = remember(terrainSummary, grid, metadata, viewport.bounds, viewport.zoom) {
        buildTerrainContext(terrainSummary, grid, metadata, viewport)
    }

    LaunchedEffect(imageReady) {
        if (!imageReady) attachViewportImage = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
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
                        Text("Terrain intelligence", fontWeight = FontWeight.Bold)
                        Text(
                            when (state.activeProvider) {
                                TerrainAiProvider.OPENAI -> "OpenAI ${OpenAiApiClient.configuredModel()} primary · Gemini ${GeminiApiClient.configuredModel()} fallback"
                                TerrainAiProvider.GEMINI -> "Gemini ${GeminiApiClient.configuredModel()} fallback active · OpenAI not configured"
                                null -> "Offline analysis ready · add OpenAI or Gemini for cloud analysis"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showKeyEditor = !showKeyEditor }) { Text("Keys") }
                    IconButton(onClick = assistantViewModel::clearConversation) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear AI conversation")
                    }
                }
            }
        }

        if (showKeyEditor) {
            item {
                ProviderKeyEditor(
                    state = state,
                    openAiKeyDraft = openAiKeyDraft,
                    onOpenAiKeyChanged = { openAiKeyDraft = it.trim().take(256) },
                    onSaveOpenAi = {
                        assistantViewModel.saveOpenAiKey(openAiKeyDraft)
                        openAiKeyDraft = ""
                    },
                    onRemoveOpenAi = assistantViewModel::clearOpenAiKey,
                    geminiKeyDraft = geminiKeyDraft,
                    onGeminiKeyChanged = { geminiKeyDraft = it.trim().take(256) },
                    onSaveGemini = {
                        assistantViewModel.saveGeminiKey(geminiKeyDraft)
                        geminiKeyDraft = ""
                    },
                    onRemoveGemini = assistantViewModel::clearGeminiKey,
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Analytics, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Local terrain analysis", fontWeight = FontWeight.Bold)
                            Text(
                                state.localStage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = { assistantViewModel.runLocalAnalysis(grid, terrainSummary) },
                            enabled = !state.isLocalAnalyzing,
                        ) {
                            if (state.isLocalAnalyzing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.width(18.dp).height(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(if (state.localResult == null) "Run local" else "Re-run")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            AppMemoryBudget.describe(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Slope, aspect, curvature, local relief, multi-hillshade, openness, sky-view, depression, ruggedness, linear features, ancient streams, homesites, walls, foundations, cellar holes, roads, charcoal pits, mines, camps, and hotspot scoring run entirely on this device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.localError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        state.localResult?.let { result ->
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    // Only layers this result actually carries. A chip for an absent layer
                    // renders fine and then throws when it is selected.
                    TerrainDerivedLayer.entries.filter { result.layers.values.containsKey(it) }.forEach { layer ->
                        FilterChip(
                            selected = state.selectedLayer == layer,
                            onClick = { assistantViewModel.selectLocalLayer(layer) },
                            label = { Text(layer.label) },
                        )
                    }
                }
            }
            state.localLayerBitmap?.let { bitmap ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(state.selectedLayer.label, fontWeight = FontWeight.Bold)
                            Text(
                                state.selectedLayer.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = state.selectedLayer.label,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 420.dp),
                            )
                        }
                    }
                }
            }
            item {
                LocalCandidateSummary(result.candidates, result.recommendation)
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                                "Open Terrain and render a layer to enable cloud image analysis"
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

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Analyze the visible viewport image",
                    "Compare the local detector results with the image",
                    "Identify road traces, walls, foundations, and depressions",
                    "Rank the strongest field-check locations",
                    "Explain what should be verified on site",
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
        }

        items(state.messages, key = AiMessage::id) { message -> AiMessageBubble(message) }

        if (state.isSending) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
                    Text(state.cloudStage)
                }
            }
        }

        state.cloudError?.let {
            item { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
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
                            terrainContext = contextText + localContext(state.localResult),
                            viewport = viewport,
                            attachViewportImage = attachViewportImage && imageReady,
                        )
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.isSending && state.activeProvider != null,
                    modifier = Modifier.height(56.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun ProviderKeyEditor(
    state: AiTerrainState,
    openAiKeyDraft: String,
    onOpenAiKeyChanged: (String) -> Unit,
    onSaveOpenAi: () -> Unit,
    onRemoveOpenAi: () -> Unit,
    geminiKeyDraft: String,
    onGeminiKeyChanged: (String) -> Unit,
    onSaveGemini: () -> Unit,
    onRemoveGemini: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cloud provider keys", fontWeight = FontWeight.Bold)
            Text(
                "OpenAI is tried first. Gemini ${GeminiApiClient.configuredModel()} is used automatically when OpenAI is unavailable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = openAiKeyDraft,
                onValueChange = onOpenAiKeyChanged,
                label = { Text("OpenAI API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.hasDeviceOpenAiKey) {
                    TextButton(onClick = onRemoveOpenAi) { Text("Remove OpenAI key") }
                }
                Button(onClick = onSaveOpenAi, enabled = openAiKeyDraft.length >= 20) {
                    Text("Save OpenAI")
                }
            }
            OutlinedTextField(
                value = geminiKeyDraft,
                onValueChange = onGeminiKeyChanged,
                label = { Text("Gemini fallback key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.hasDeviceGeminiKey) {
                    TextButton(onClick = onRemoveGemini) { Text("Remove Gemini key") }
                }
                OutlinedButton(onClick = onSaveGemini, enabled = geminiKeyDraft.length >= 20) {
                    Text("Save Gemini")
                }
            }
        }
    }
}

@Composable
private fun LocalCandidateSummary(
    candidates: List<TerrainFeatureCandidate>,
    recommendation: String,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Local detector results", fontWeight = FontWeight.Bold)
            Text(recommendation, style = MaterialTheme.typography.bodySmall)
            candidates.take(12).forEachIndexed { index, candidate ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            "${index + 1}. ${candidate.type.label} · ${(candidate.score * 100f).toInt()}%",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "X ${"%.1f".format(candidate.xPercent)}% · Y ${"%.1f".format(candidate.yPercent)}% · radius ${"%.1f".format(candidate.radiusMeters)} m",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            candidate.evidence.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AiMessageBubble(message: AiMessage) {
    val isUser = message.role == AiMessageRole.USER
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
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isUser) "You" else message.provider?.label ?: "Terrain intelligence",
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

private fun localContext(result: TerrainIntelligenceResult?): String {
    if (result == null) return "\nLocal terrain intelligence has not been run for this dataset."
    val strongest = result.candidates.take(15).joinToString("\n") {
        "${it.type.label}: score=${"%.3f".format(it.score)}, x=${"%.1f".format(it.xPercent)}%, y=${"%.1f".format(it.yPercent)}%, evidence=${it.evidence.joinToString(";")}"
    }
    return """

        Local device analysis (deterministic, non-AI):
        Cache result: ${result.cacheHit}
        Recommendation: ${result.recommendation}
        Strongest candidates:
        $strongest
    """.trimIndent()
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
