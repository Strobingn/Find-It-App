package com.example.data

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result from the complete Phase 2 decode pipeline. */
data class TerrainDecodeOutcome(
    val terrain: DemGenerator.TerrainLoadResult,
    val cacheHit: LazTerrainCache.Hit,
    val gpuScene: TerrainGpuScene,
)

/**
 * Serializes duplicate work per source/options key while allowing unrelated datasets to decode in
 * parallel. File parsing runs on Dispatchers.IO; LOD/spatial/GPU batch construction runs on
 * Dispatchers.Default. Coroutine cancellation is checked between LAZ point batches.
 */
class TerrainDecodeCoordinator(
    private val cache: LazTerrainCache,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun decode(
        file: File,
        displayName: String = file.name,
        options: LidarImportOptions,
        onStage: suspend (String) -> Unit = {},
    ): TerrainDecodeOutcome {
        val key = decodeKey(file, options)
        val lock = locks.getOrPut(key) { Mutex() }
        try {
            return lock.withLock {
                currentCoroutineContext().ensureActive()
                val firstLookup = cache.get(file, options)
                val terrain = if (firstLookup.result != null) {
                    onStage(
                        when (firstLookup.hit) {
                            LazTerrainCache.Hit.MEMORY -> "Opening decoded terrain from memory cache…"
                            LazTerrainCache.Hit.DISK -> "Opening decoded terrain from disk cache…"
                            LazTerrainCache.Hit.MISS -> "Reading point cloud…"
                        },
                    )
                    firstLookup.result
                } else {
                    onStage("Decoding LAZ/LAS point batches…")
                    val decoded = decodeFile(file, displayName, options)
                        ?: error("Could not decode ${file.name}")
                    currentCoroutineContext().ensureActive()
                    onStage("Saving decoded terrain cache…")
                    withContext(Dispatchers.IO) { cache.put(file, options, decoded) }
                    decoded
                }

                currentCoroutineContext().ensureActive()
                onStage("Building spatial index, LOD levels, and GPU batches…")
                val scene = withContext(Dispatchers.Default) {
                    currentCoroutineContext().ensureActive()
                    TerrainGpuSceneBuilder.build(terrain.grid)
                }
                TerrainDecodeOutcome(terrain, firstLookup.hit, scene)
            }
        } finally {
            if (!lock.isLocked) locks.remove(key, lock)
        }
    }

    private suspend fun decodeFile(
        file: File,
        displayName: String,
        options: LidarImportOptions,
    ): DemGenerator.TerrainLoadResult? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        FileInputStream(file).buffered(256 * 1024).use { input ->
            if (displayName.substringAfterLast('.', "").equals("laz", ignoreCase = true)) {
                val laz = LazTerrainReader.read(input, options) { currentCoroutineContext().isActive }
                    ?: return@use null
                DemGenerator.TerrainLoadResult(
                    grid = laz.grid,
                    summary = laz.note,
                    isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                )
            } else {
                DemGenerator.parseFromStreamDetailed(displayName, input, options)
            }
        }
    }

    private fun decodeKey(file: File, options: LidarImportOptions): String {
        val sanitized = options.sanitized()
        return buildString {
            append(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
            append('|').append(file.length())
            append('|').append(file.lastModified())
            append('|').append(sanitized.groundMode)
            append('|').append(sanitized.rasterResolution)
            append('|').append(sanitized.smoothingRadius)
            append('|').append(sanitized.focusBounds)
        }
    }
}

/** App-wide current GPU terrain session consumed by the Compose/OpenGL renderer. */
object TerrainPerformanceSession {
    private val _gpuScene = MutableStateFlow<TerrainGpuScene?>(null)
    val gpuScene: StateFlow<TerrainGpuScene?> = _gpuScene.asStateFlow()

    fun publish(scene: TerrainGpuScene) {
        _gpuScene.value = scene
    }

    fun clear() {
        _gpuScene.value = null
    }
}
