package com.example.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the downloaded Gemma 4 model on the phone with LiteRT-LM — no network needed. The engine
 * takes several seconds and a few GB of memory to load, so it is kept warm between questions and
 * released after [IDLE_RELEASE_MS] of no use. GPU first, CPU if the GPU path won't load.
 */
internal object OnDeviceGemma {
    private const val TAG = "OnDeviceGemma"
    private const val IDLE_RELEASE_MS = 3 * 60_000L
    /** Prompt + answer budget. Stand and hunt-plan prompts run ~2–4K tokens. */
    private const val MAX_TOKENS = 6_144
    /** Rough char budget for history so prompt + answer stay inside [MAX_TOKENS]. */
    private const val MAX_PROMPT_CHARS = 14_000

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var enginePath: String? = null
    private var engineBackend: String? = null
    private var engineHasVision = false
    /** Model path whose vision encoder wouldn't load; text-only from then on (no reload per image). */
    private var visionFailedPath: String? = null
    private var idleJob: Job? = null

    /** "GPU" / "CPU" for the loaded engine, null when nothing is loaded. */
    val loadedBackend: String? get() = engineBackend

    fun isConfigured(context: Context): Boolean = OnDeviceModelStore(context).activeModel() != null

    fun modelLabel(context: Context): String = OnDeviceModelStore(context).activeModel()?.label ?: "Gemma 4 (not downloaded)"

    suspend fun generate(
        context: Context,
        conversation: List<GeminiConversationTurn>,
        systemContext: String,
        image: GeminiImageInput?,
        onDelta: ((String) -> Unit)?,
        onStage: (String) -> Unit = {},
    ): String {
        val store = OnDeviceModelStore(context)
        val model = store.activeModel() ?: throw IOException("Offline AI isn't downloaded yet — get it in AI settings.")
        return mutex.withLock {
            idleJob?.cancel()
            try {
                val eng = ensureEngine(context, store.fileFor(model).path, wantVision = image != null, onStage = onStage, label = model.label)
                onStage("${model.label} is thinking on this phone…")
                runConversation(eng, conversation, systemContext, image?.takeIf { engineHasVision }, onDelta)
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    /** Frees the model (e.g. before deleting its file). */
    fun release(path: String? = null) {
        scope.launch {
            mutex.withLock {
                if (path == null || path == enginePath) closeEngine()
            }
        }
    }

    private suspend fun ensureEngine(context: Context, path: String, wantVision: Boolean, onStage: (String) -> Unit, label: String): Engine {
        engine?.let { current ->
            if (enginePath == path && (!wantVision || engineHasVision || visionFailedPath == path)) return current
            closeEngine()
        }
        onStage("Loading $label on this phone…")
        return withContext(Dispatchers.Default) {
            val cacheDir = context.cacheDir.path
            val attempts = listOf(
                Triple("GPU", Backend.GPU(), true),
                Triple("GPU", Backend.GPU(), false),
                Triple("CPU", Backend.CPU(), true),
                Triple("CPU", Backend.CPU(), false),
            ).filter { wantVision || !it.third }
            var lastError: Throwable? = null
            for ((name, backend, vision) in attempts) {
                val config = EngineConfig(
                    modelPath = path,
                    backend = backend,
                    visionBackend = if (vision) (if (name == "GPU") Backend.GPU() else Backend.CPU()) else null,
                    audioBackend = null,
                    maxNumTokens = MAX_TOKENS,
                    maxNumImages = if (vision) 1 else null,
                    cacheDir = cacheDir,
                )
                val candidate = Engine(config)
                try {
                    candidate.initialize()
                    engine = candidate
                    enginePath = path
                    engineBackend = name
                    engineHasVision = vision
                    if (wantVision && !vision) visionFailedPath = path
                    Log.i(TAG, "Loaded $label on $name (vision=$vision)")
                    return@withContext candidate
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastError = error
                    Log.w(TAG, "Couldn't load on $name (vision=$vision): ${error.message}")
                    runCatching { candidate.close() }
                }
            }
            throw IOException(
                when (lastError) {
                    is UnsatisfiedLinkError -> "This phone's processor can't run the offline model (needs a 64-bit ARM phone)."
                    is OutOfMemoryError -> "Not enough memory to load $label — try Gemma 4 E2B or close other apps."
                    else -> "Couldn't load $label: ${lastError?.message ?: "unknown error"}"
                },
            )
        }
    }

    private suspend fun runConversation(
        engine: Engine,
        turns: List<GeminiConversationTurn>,
        systemContext: String,
        image: GeminiImageInput?,
        onDelta: ((String) -> Unit)?,
    ): String {
        val (history, last) = splitForPrompt(turns, systemContext.length)
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemContext),
            initialMessages = history.map { if (it.role == "model") Message.model(it.text) else Message.user(it.text) },
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7, seed = 0),
        )
        val parts = buildList<Content> {
            image?.let { add(Content.ImageBytes(it.bytes)) }
            add(Content.Text(last))
        }
        val text = StringBuilder()
        engine.createConversation(config).use { conversation ->
            try {
                conversation.sendMessageAsync(Contents.of(parts)).collect { chunk ->
                    val piece = chunk.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (piece.isNotEmpty()) {
                        text.append(piece)
                        onDelta?.invoke(piece)
                    }
                }
            } catch (cancelled: CancellationException) {
                runCatching { conversation.cancelProcess() }
                throw cancelled
            }
        }
        return text.toString().trim().ifBlank { throw IOException("The offline model returned an empty answer") }
    }

    /**
     * Keeps the newest turns that fit the on-device context: everything but the last user turn
     * becomes history (oldest dropped first), and the last user turn is what gets sent.
     */
    internal fun splitForPrompt(turns: List<GeminiConversationTurn>, systemChars: Int): Pair<List<GeminiConversationTurn>, String> {
        val lastIndex = turns.indexOfLast { it.role != "model" }
        val last = if (lastIndex >= 0) turns[lastIndex].text else ""
        val budget = (MAX_PROMPT_CHARS - systemChars - last.length).coerceAtLeast(0)
        val earlier = if (lastIndex > 0) turns.subList(0, lastIndex) else emptyList()
        val kept = ArrayDeque<GeminiConversationTurn>()
        var used = 0
        for (turn in earlier.asReversed()) {
            if (used + turn.text.length > budget) break
            kept.addFirst(turn)
            used += turn.text.length
        }
        // Conversations must start with the user.
        while (kept.isNotEmpty() && kept.first().role == "model") kept.removeFirst()
        return kept.toList() to last.take(MAX_PROMPT_CHARS)
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock { closeEngine() }
        }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        enginePath = null
        engineBackend = null
        engineHasVision = false
    }
}
