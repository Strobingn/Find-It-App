package com.example.data

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
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
)

/**
 * Serializes duplicate work per source/options key while allowing unrelated datasets to decode in
 * parallel. The critical path ends as soon as an exact terrain raster and coarse GPU scene are
 * available. Persistent cache serialization and higher-detail 3D meshes continue off the UI path.
 */
class TerrainDecodeCoordinator(
    private val cache: LazTerrainCache,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                val firstLookup = withContext(Dispatchers.IO) { cache.get(file, options) }
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
                    onStage("Decoding exact LAZ/LAS terrain…")
                    val decoded = decodeFile(file, displayName, options)
                        ?: error("Could not decode ${file.name}")
                    currentCoroutineContext().ensureActive()

                    // Prevent duplicate decodes immediately, but keep slow persistent serialization
                    // off the first-frame path.
                    cache.putMemory(file, options, decoded)
                    maintenanceScope.launch { cache.putDisk(file, options, decoded) }
                    decoded
                }

                currentCoroutineContext().ensureActive()
                onStage("Preparing terrain preview…")
                val scene = withContext(Dispatchers.Default) {
                    TerrainGpuSceneBuilder.buildProgressive(terrain.grid)
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
        val decodeContext = currentCoroutineContext()
        decodeContext.ensureActive()
        val isLaz = displayName.substringAfterLast('.', "").equals("laz", ignoreCase = true) ||
            file.extension.equals("laz", ignoreCase = true)

        if (isLaz) {
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
            FileInputStream(file).buffered(256 * 1024).use { input ->
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
    val gpuScene: StateFlow<TerrainGpuScene?> = _gpuScene

    fun publish(scene: TerrainGpuScene) {
        _gpuScene.value = scene
    }

    fun clear() {
        _gpuScene.value = null
    }
}
