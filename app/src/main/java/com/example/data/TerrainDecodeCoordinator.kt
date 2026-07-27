package com.example.data

import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result from the terrain decode path. Expensive cache persistence and 3D mesh work are deferred. */
data class TerrainDecodeOutcome(
    val terrain: DemGenerator.TerrainLoadResult,
    val cacheHit: LazTerrainCache.Hit,
    val needsDiskCacheWrite: Boolean,
)

/**
 * Serializes duplicate work per source/options key while allowing unrelated datasets to decode in
 * parallel. The critical path ends as soon as an exact terrain raster is available. Disk-cache
 * serialization and multi-LOD GPU mesh construction are intentionally handled by callers after the
 * 2D terrain is visible.
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
                val firstLookup = withContext(Dispatchers.IO) { cache.get(file, options) }
                if (firstLookup.result != null) {
                    onStage(
                        when (firstLookup.hit) {
                            LazTerrainCache.Hit.MEMORY -> "Opening decoded terrain from memory cache…"
                            LazTerrainCache.Hit.DISK -> "Opening decoded terrain from disk cache…"
                            LazTerrainCache.Hit.MISS -> "Reading point cloud…"
                        },
                    )
                    return@withLock TerrainDecodeOutcome(
                        terrain = firstLookup.result,
                        cacheHit = firstLookup.hit,
                        needsDiskCacheWrite = false,
                    )
                }

                onStage("Decoding exact LAZ/LAS terrain…")
                val decoded = decodeFile(file, displayName, options)
                    ?: error("Could not decode ${file.name}")
                currentCoroutineContext().ensureActive()

                // Memory insertion is cheap and prevents duplicate work immediately. Persistent
                // serialization is deliberately deferred so it cannot delay the first terrain frame.
                cache.putMemory(file, options, decoded)
                TerrainDecodeOutcome(
                    terrain = decoded,
                    cacheHit = LazTerrainCache.Hit.MISS,
                    needsDiskCacheWrite = true,
                )
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
