package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.ImagePart
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Google AI (Gemini) service for terrain analysis, anomaly classification, and chat.
 *
 * Uses the Google AI SDK directly with an API key to avoid Firebase App Check requirements.
 * The API key is read from BuildConfig (populated via local.properties or CI secret).
 * Prefer [com.example.ai.GeminiApiClient] for chat — this path shares the same BuildConfig key.
 */
object GeminiRepository {

    private const val TAG = "GeminiRepository"
    private const val DEFAULT_MODEL = "gemini-2.0-flash"

    private var initialized = false
    private var isAvailable = false
    private var model: GenerativeModel? = null

    private fun buildConfigApiKey(): String {
        val cleaned = BuildConfig.GEMINI_API_KEY.trim()
        val upper = cleaned.uppercase()
        return cleaned.takeUnless {
            it.length < 20 ||
                upper.startsWith("YOUR_") ||
                upper.startsWith("MY_") ||
                upper.contains("PLACEHOLDER")
        }.orEmpty()
    }

    /** Initialize Gemini. Call once from Application.onCreate. */
    fun initialize() {
        if (initialized) return
        initialized = true

        val apiKey = buildConfigApiKey()
        if (apiKey.isBlank()) {
            isAvailable = false
            Log.w(
                TAG,
                "GEMINI_API_KEY not set — SDK path unavailable. " +
                    "Add GEMINI_API_KEY=… to local.properties and rebuild.",
            )
            return
        }

        val modelName = BuildConfig.GEMINI_MODEL.trim().ifBlank { DEFAULT_MODEL }
        try {
            model = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.4f
                    topK = 32
                    topP = 0.95f
                    maxOutputTokens = 2048
                },
            )
            isAvailable = true
            Log.i(TAG, "Gemini initialized (model=$modelName, available=true)")
        } catch (e: Exception) {
            isAvailable = false
            Log.e(TAG, "Failed to initialize Gemini", e)
        }
    }

    /** True if Gemini is ready for use. */
    val available: Boolean get() = isAvailable

    // ------------------------------------------------------------------
    // Terrain Analyst
    // ------------------------------------------------------------------

    /**
     * Analyze a terrain image with Gemini and stream the response.
     *
     * @param bitmap The hillshade/terrain bitmap to analyze.
     * @param context Snapshot of current terrain settings for the prompt.
     */
    fun analyzeTerrainImage(
        bitmap: Bitmap,
        context: TerrainSessionContext,
    ): Flow<String> = geminiFlow {
        val prompt = buildString {
            appendLine(TERRAIN_SYSTEM_PROMPT)
            appendLine()
            appendLine("--- Terrain Context ---")
            appendLine("Grid: ${context.gridWidth}x${context.gridHeight} cells")
            appendLine("Resolution: ${context.cellSizeMeters} m/cell")
            appendLine("Visualization mode: ${vizModeName(context.visualizationMode)}")
            appendLine("Sun: azimuth ${context.sunAzimuth} degrees, altitude ${context.sunAltitude} degrees")
            appendLine("Vegetation filter: ${(context.vegetationFilter * 100).toInt()}%")
            appendLine("Contrast: ${context.contrast}")
            appendLine("Z-scale: ${context.zScale}")
            appendLine("Terrain: ${context.terrainSummary}")
            if (context.hasCoordinates) appendLine("Georeferenced: yes")
            appendLine("Logged signals: ${context.signalCount}")
            if (context.signalSummary.isNotBlank()) appendLine("Signal summary: ${context.signalSummary}")
            appendLine()
            appendLine("Analyze the attached terrain image. Describe any visible features, classify them, and suggest where to investigate next. Use specific archaeological remote sensing terminology.")
        }

        val content = content {
            image(bitmap)
            text(prompt)
        }

        sendAndStream(content)
    }

    // ------------------------------------------------------------------
    // Anomaly Classifier
    // ------------------------------------------------------------------

    /**
     * Classify a batch of anomaly regions with Gemini.
     *
     * @param regions Anomaly regions with cropped bitmaps.
     * @param context Terrain session context.
     * @return Classifications for each region, or empty list on error.
     */
    suspend fun classifyAnomalyRegions(
        regions: List<AnomalyRegion>,
        context: TerrainSessionContext,
    ): List<AnomalyClassification> = withContext(Dispatchers.IO) {
        if (regions.isEmpty()) return@withContext emptyList()

        val m = model
        if (m == null) {
            Log.w(TAG, "Model not available for anomaly classification")
            return@withContext regions.map { region ->
                AnomalyClassification(
                    region = region,
                    label = "Unavailable",
                    confidence = 0f,
                    description = "AI not configured",
                )
            }
        }

        runCatching {
            val prompt = buildString {
                appendLine(ANOMALY_SYSTEM_PROMPT)
                appendLine()
                appendLine("--- Terrain Context ---")
                appendLine("Grid: ${context.gridWidth}x${context.gridHeight} · ${context.cellSizeMeters} m/cell")
                appendLine("Visualization: disturbance-candidate mode (residual + curvature + roughness)")
                appendLine()
                appendLine("You will receive ${regions.size} anomaly region images. For each one, classify it using ONLY one of these labels: Foundation, Ditch/Trench, Pit/Well, Mound, Road/Path, Natural erosion, Modern disturbance, Uncertain.")
                appendLine()
                appendLine("Respond in this exact format for each region (one per line):")
                appendLine("REGION_N|LABEL|CONFIDENCE_0_TO_1|BRIEF_DESCRIPTION")
                appendLine()
                appendLine("Example:")
                appendLine("1|Foundation|0.82|Rectangular platform with sharp slope breaks, consistent with stone wall footing.")
                appendLine("2|Ditch/Trench|0.71|Linear depression with parallel edges, ~2 m wide.")
                appendLine()
                appendLine("Be concise. Confidence must be a number 0.0-1.0. If uncertain, label Uncertain with lower confidence.")
            }

            val content = content {
                text(prompt)
                regions.forEachIndexed { index, region ->
                    region.croppedBitmap?.let { bmp ->
                        text("--- REGION_${index + 1} ---")
                        image(bmp)
                    }
                }
            }

            val response = m.generateContent(content)
            val text = response.text ?: ""
            parseAnomalyResponse(text, regions)
        }.getOrElse { e ->
            Log.e(TAG, "Anomaly classification failed", e)
            regions.map { region ->
                AnomalyClassification(
                    region = region,
                    label = "Error",
                    confidence = 0f,
                    description = "Classification failed: ${e.message}",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Field Assistant Chat
    // ------------------------------------------------------------------

    /**
     * Multi-turn chat with session context.
     *
     * @param history Previous messages (excluding the current user message).
     * @param userMessage The new user message.
     * @param context Current terrain session context.
     */
    fun chat(
        history: List<ChatMessage>,
        userMessage: String,
        context: TerrainSessionContext,
    ): Flow<String> = geminiFlow {
        val systemPrompt = buildString {
            appendLine(CHAT_SYSTEM_PROMPT)
            appendLine()
            appendLine("--- Current Session ---")
            appendLine("Grid: ${context.gridWidth}x${context.gridHeight}")
            appendLine("Resolution: ${context.cellSizeMeters} m/cell")
            appendLine("Mode: ${vizModeName(context.visualizationMode)}")
            appendLine("Sun: ${context.sunAzimuth} az, ${context.sunAltitude} alt")
            appendLine("Vegetation: ${(context.vegetationFilter * 100).toInt()}%")
            appendLine("Contrast: ${context.contrast}, Z-scale: ${context.zScale}")
            appendLine("Terrain: ${context.terrainSummary}")
            appendLine("Signals logged: ${context.signalCount}")
            if (context.signalSummary.isNotBlank()) appendLine("Signals: ${context.signalSummary}")
        }

        val content = content {
            text(systemPrompt)
            history.forEach { msg ->
                when (msg.role) {
                    ChatRole.USER -> text("User: ${msg.content}")
                    ChatRole.ASSISTANT -> text("Assistant: ${msg.content}")
                }
            }
            text("User: $userMessage")
        }
        sendAndStream(content)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun geminiFlow(block: suspend GeminiScope.() -> Unit): Flow<String> = flow {
        if (!isAvailable || model == null) {
            emit("AI is not configured. Add GEMINI_API_KEY=<your Google AI API key> to local.properties and rebuild.")
            return@flow
        }
        val scope = GeminiScope { text -> emit(text) }
        try {
            scope.block()
        } catch (e: Exception) {
            Log.e(TAG, "Gemini flow error", e)
            emit("\n\n[Error: ${e.message}]")
        }
    }.flowOn(Dispatchers.IO)

    private class GeminiScope(private val onText: suspend (String) -> Unit) {
        suspend fun sendAndStream(content: Content) {
            val m = model ?: return
            val response = m.generateContentStream(content)
            response.collect { chunk ->
                chunk.text?.let { onText(it) }
            }
        }
    }

    private fun parseAnomalyResponse(text: String, regions: List<AnomalyRegion>): List<AnomalyClassification> {
        val lines = text.lines().filter { it.contains("|") }
        return regions.mapIndexed { index, region ->
            val line = lines.getOrNull(index) ?: "${index + 1}|Uncertain|0.0|No classification received"
            val parts = line.split("|")
            if (parts.size >= 4) {
                AnomalyClassification(
                    region = region,
                    label = parts[1].trim(),
                    confidence = parts[2].trim().toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f,
                    description = parts[3].trim(),
                )
            } else {
                AnomalyClassification(
                    region = region,
                    label = "Uncertain",
                    confidence = 0f,
                    description = line,
                )
            }
        }
    }

    private fun vizModeName(mode: Int): String = when (mode) {
        0 -> "Hillshade"
        1 -> "Multi-directional hillshade"
        2 -> "Slope"
        3 -> "Local relief"
        4 -> "Curvature"
        5 -> "Disturbance candidates"
        6 -> "Aspect"
        7 -> "Elevation"
        8 -> "Canopy height"
        else -> "Unknown"
    }

    // ------------------------------------------------------------------
    // System prompts
    // ------------------------------------------------------------------

    private val TERRAIN_SYSTEM_PROMPT = """
You are an archaeological remote sensing specialist analyzing LiDAR-derived terrain visualizations. You have deep expertise in:
- Local Relief Models (LRM) and micro-topography
- Slope-break detection and residual anomaly mapping
- Archaeological feature morphology (foundations, walls, ditches, mounds, roads)
- Distinguishing natural geomorphology from anthropogenic modification

Analyze the provided terrain image systematically:
1. Identify linear features (walls, foundations, roads, ditches, trenches)
2. Identify circular/oval anomalies (wells, pits, burial mounds, gun emplacements)
3. Note geometric patterns suggesting human modification
4. Classify as natural vs. anthropogenic where possible
5. Provide confidence levels
6. Recommend next steps (where to dig, what to look for)

Use domain-specific terminology. Be specific about dimensions and orientations when visible.
""".trimIndent()

    private val ANOMALY_SYSTEM_PROMPT = """
You are an archaeological remote sensing AI. Classify terrain anomaly regions from LiDAR disturbance-candidate analysis.

Classification labels (use ONLY these):
- Foundation
- Ditch/Trench
- Pit/Well
- Mound
- Road/Path
- Natural erosion
- Modern disturbance
- Uncertain

For each region, provide:
1. Label (from list above)
2. Confidence 0.0-1.0
3. Brief description (1-2 sentences)

Consider: slope breaks, edge geometry, size relative to cell resolution, pattern consistency with known archaeological features.
""".trimIndent()

    private val CHAT_SYSTEM_PROMPT = """
You are an AI field assistant for an archaeological LiDAR analysis app. You help users interpret terrain data, find features, and plan fieldwork.

Your knowledge includes:
- LiDAR visualization techniques (hillshade, slope, local relief, curvature)
- Archaeological feature recognition in DEMs
- Survey and excavation planning
- Historical context for common site types

Guidelines:
- Be concise but informative
- Use archaeological terminology correctly
- When suggesting locations, reference grid coordinates or relative positions
- For lighting suggestions, explain WHY a certain azimuth/altitude helps
- If asked about historical periods, qualify with uncertainty ranges
- Never claim certainty — use confidence language

Current session context is provided. The user may ask about the terrain image, logged signals, or general methodology.
""".trimIndent()
}
