package com.example.data

import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result from the terrain decode path. */
data class TerrainDecodeOutcome(
    val terrain: DemGenerator.TerrainLoadResult,
    val cacheHit: LazTerrainCache.Hit,
    val gpuScene: TerrainGpuScene,
    /** Non-null only when [terrain] is a fast preview and an exact lossless pass is still running. */
    val exactOutcome: Deferred<TerrainDecodeOutcome?>? = null,
    val isPreview: Boolean = false,
)

data class TerrainExactUpdate(
    val decodeKey: String,
    val terrain: DemGenerator.TerrainLoadResult,
    val source: TerrainImportSource,
    val gpuScene: TerrainGpuScene,
)

/**
 * Serializes duplicate work per source/options key while allowing unrelated datasets to decode in
 * parallel. Large full-tile LAZ files first return a uniformly sampled chunk preview. The exact
 * all-return result is then decoded, cached, and prepared for GPU rendering off the visible load
 * path. Focused refinements, mosaics, LAS files, and small LAZ files remain exact-first.
 */
class TerrainDecodeCoordinator(
    private val cache: LazTerrainCache,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val exactJobs = ConcurrentHashMap<String, Deferred<TerrainDecodeOutcome?>>()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Exact-only overload used by positional trailing-lambda calls. The mosaic picker uses this
     * signature, preventing any preview raster from entering a saved multi-tile project.
     *
     * Single-tile loaders use the named `onStage =` parameter on the primary overload below and
     * therefore keep progressive preview behavior.
     */
    suspend fun decode(
        file: File,
        displayName: String,
        options: LidarImportOptions,
        stageCallback: suspend (String) -> Unit,
    ): TerrainDecodeOutcome = decode(
        file = file,
        displayName = displayName,
        options = options,
        allowProgressivePreview = false,
        onStage = stageCallback,
    )

    suspend fun decode(
        file: File,
        displayName: String = file.name,
        options: LidarImportOptions,
        allowProgressivePreview: Boolean = true,
        onStage: suspend (String) -> Unit = {},
    ): TerrainDecodeOutcome {
        val safeOptions = options.sanitized()
        val key = decodeKey(file, safeOptions)
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            return lock.withLock {
                currentCoroutineContext().ensureActive()
                val firstLookup = withContext(Dispatchers.IO) { cache.get(file, safeOptions) }
                firstLookup.result?.let { cached ->
                    onStage(
                        when (firstLookup.hit) {
                            LazTerrainCache.Hit.MEMORY -> "Opening decoded terrain from memory cache…"
                            LazTerrainCache.Hit.DISK -> "Opening decoded terrain from disk cache…"
                            LazTerrainCache.Hit.MISS -> "Reading point cloud…"
                        },
                    )
                    val scene = withContext(Dispatchers.Default) {
                        TerrainGpuSceneBuilder.buildProgressive(cached.grid)
                    }
                    return@withLock TerrainDecodeOutcome(
                        terrain = cached,
                        cacheHit = firstLookup.hit,
                        gpuScene = scene,
                    )
                }

                val canPreview = allowProgressivePreview &&
                    isLazFile(file, displayName) &&
                    safeOptions.focusBounds == null
                if (canPreview) {
                    onStage("Building fast full-tile preview…")
                    val preview = decodePreviewFile(file, safeOptions)
                    if (preview != null) {
                        currentCoroutineContext().ensureActive()
                        val previewScene = withContext(Dispatchers.Default) {
                            TerrainGpuSceneBuilder.buildProgressive(preview.grid)
                        }
                        val source = TerrainImportSource(
                            uri = Uri.fromFile(file).toString(),
                            displayName = displayName,
                            options = safeOptions,
                        )
                        val exact = exactJobs[key] ?: startExactJob(
                            key = key,
                            file = file,
                            displayName = displayName,
                            options = safeOptions,
                            source = source,
                        ).also { exactJobs[key] = it }
                        return@withLock TerrainDecodeOutcome(
                            terrain = preview,
                            cacheHit = LazTerrainCache.Hit.MISS,
                            gpuScene = previewScene,
                            exactOutcome = exact,
                            isPreview = true,
                        )
                    }
                }

                onStage("Decoding exact LAZ/LAS terrain…")
                val decoded = decodeFile(file, displayName, safeOptions)
                    ?: error("Could not decode ${file.name}")
                currentCoroutineContext().ensureActive()
                cache.putMemory(file, safeOptions, decoded)
                maintenanceScope.launch { cache.putDisk(file, safeOptions, decoded) }
                onStage("Preparing terrain preview…")
                val scene = withContext(Dispatchers.Default) {
                    TerrainGpuSceneBuilder.buildProgressive(decoded.grid)
                }
                TerrainDecodeOutcome(
                    terrain = decoded,
                    cacheHit = LazTerrainCache.Hit.MISS,
                    gpuScene = scene,
                )
            }
        } finally {
            if (!lock.isLocked) locks.remove(key, lock)
        }
    }

    private fun startExactJob(
        key: String,
        file: File,
        displayName: String,
        options: LidarImportOptions,
        source: TerrainImportSource,
    ): Deferred<TerrainDecodeOutcome?> = maintenanceScope.async {
        try {
            val decoded = decodeFile(file, displayName, options) ?: return@async null
            cache.putMemory(file, options, decoded)
            maintenanceScope.launch { cache.putDisk(file, options, decoded) }
            val scene = withContext(Dispatchers.Default) {
                TerrainGpuSceneBuilder.buildProgressive(decoded.grid)
            }
            val outcome = TerrainDecodeOutcome(
                terrain = decoded,
                cacheHit = LazTerrainCache.Hit.MISS,
                gpuScene = scene,
            )
            TerrainPerformanceSession.publishExact(
                TerrainExactUpdate(
                    decodeKey = key,
                    terrain = decoded,
                    source = source,
                    gpuScene = scene,
                ),
            )
            outcome
        } finally {
            exactJobs.remove(key)
        }
    }

    private suspend fun decodePreviewFile(
        file: File,
        options: LidarImportOptions,
    ): DemGenerator.TerrainLoadResult? = withContext(Dispatchers.IO) {
        val decodeContext = currentCoroutineContext()
        val preview = LazTerrainReader.readPreview(
            file = file,
            options = options,
            shouldContinue = { decodeContext.isActive },
        ) ?: return@withContext null
        DemGenerator.TerrainLoadResult(
            grid = preview.grid,
            summary = preview.note,
            isBareEarth = preview.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
        )
    }

    private suspend fun decodeFile(
        file: File,
        displayName: String,
        options: LidarImportOptions,
    ): DemGenerator.TerrainLoadResult? = withContext(Dispatchers.IO) {
        val decodeContext = currentCoroutineContext()
        decodeContext.ensureActive()

        if (isLazFile(file, displayName)) {
            val laz = LazTerrainReader.read(
                file = file,
                options = options,
                shouldContinue = { decodeContext.isActive },
            ) ?: return@withContext null
            DemGenerator.TerrainLoadResult(
                grid = laz.grid,
                summary = laz.note,
                isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
            )
        } else {
            FileInputStream(file).buffered(1024 * 1024).use { input ->
                DemGenerator.parseFromStreamDetailed(displayName, input, options)
            }
        }
    }

    private fun isLazFile(file: File, displayName: String): Boolean =
        displayName.substringAfterLast('.', "").equals("laz", ignoreCase = true) ||
            file.extension.equals("laz", ignoreCase = true)

    private fun decodeKey(file: File, options: LidarImportOptions): String = buildString {
        append(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
        append('|').append(file.length())
        append('|').append(file.lastModified())
        append('|').append(options.groundMode)
        append('|').append(options.rasterResolution)
        append('|').append(options.smoothingRadius)
        append('|').append(options.focusBounds)
    }
}

/** App-wide terrain performance session consumed by the Compose/OpenGL and ViewModel layers. */
object TerrainPerformanceSession {
    private val _gpuScene = MutableStateFlow<TerrainGpuScene?>(null)
    val gpuScene: StateFlow<TerrainGpuScene?> = _gpuScene

    private val _exactTerrainUpdates = MutableSharedFlow<TerrainExactUpdate>(
        replay = 0,
        extraBufferCapacity = 2,
    )
    val exactTerrainUpdates: SharedFlow<TerrainExactUpdate> = _exactTerrainUpdates

    fun publish(scene: TerrainGpuScene) {
        _gpuScene.value = scene
    }

    internal fun publishExact(update: TerrainExactUpdate) {
        _exactTerrainUpdates.tryEmit(update)
    }

    fun clear() {
        _gpuScene.value = null
    }
}
