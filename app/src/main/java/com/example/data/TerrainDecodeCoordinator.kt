package com.example.data

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
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
 * parallel. File/cache I/O runs on Dispatchers.IO; bounded GPU preview construction runs on
 * Dispatchers.Default. Coroutine cancellation is checked between LAZ point batches.
 *
 * Full-footprint opens decode at the requested overview resolution on the first pass (default
 * 1,024 px). There is no progressive 256 px stub — first paint is the detailed product.
 *
 * When [onPreview] is provided, the decoded elevation product is handed off before the GPU mesh
 * is finished so 2D hillshade can paint while 3D mesh work continues.
 */
class TerrainDecodeCoordinator(
    private val cache: LazTerrainCache,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun decode(
        file: File,
        displayName: String = file.name,
        options: LidarImportOptions,
        onPreview: (suspend (TerrainDecodeOutcome) -> Unit)? = null,
        onStage: suspend (String) -> Unit = {},
    ): TerrainDecodeOutcome {
        val sanitized = options.sanitized()
        val fullKey = decodeKey(file, sanitized)
        val lock = locks.getOrPut(fullKey) { Mutex() }
        try {
            return lock.withLock {
                currentCoroutineContext().ensureActive()
                val fullLookup = withContext(Dispatchers.IO) { cache.get(file, sanitized) }
                if (fullLookup.result != null) {
                    onStage(
                        when (fullLookup.hit) {
                            LazTerrainCache.Hit.MEMORY -> "Opening decoded terrain from memory cache…"
                            LazTerrainCache.Hit.DISK -> "Opening decoded terrain from disk cache…"
                            LazTerrainCache.Hit.MISS -> "Reading point cloud…"
                        },
                    )
                    val cached = fullLookup.result
                    val scene = buildGpuScene(cached.grid)
                    LazSpatialIndex.ensureBuiltAsync(file)
                    val outcome = TerrainDecodeOutcome(cached, fullLookup.hit, scene)
                    // Cache hits are already fast; still notify so callers share one paint path.
                    onPreview?.invoke(outcome)
                    return@withLock outcome
                }

                onStage("Decoding LAZ/LAS at ${sanitized.rasterResolution} px…")
                val decoded = decodeFile(file, displayName, sanitized)
                    ?: error("Could not decode ${file.name}")
                currentCoroutineContext().ensureActive()
                // Memory caching is immediate. Persistent cache writing is queued on Dispatchers.IO
                // by LazTerrainCache so it no longer extends the user-visible first-open delay.
                cache.put(file, sanitized, decoded)
                LazSpatialIndex.ensureBuiltAsync(file)

                currentCoroutineContext().ensureActive()
                // 2D path can start as soon as the elevation grid exists; GPU mesh is secondary.
                if (onPreview != null) {
                    onStage("Terrain ready — finishing GPU mesh…")
                    onPreview(
                        TerrainDecodeOutcome(
                            terrain = decoded,
                            cacheHit = LazTerrainCache.Hit.MISS,
                            // Same quality floor (1,024) but coarser tiling so mesh work is cheaper
                            // for the intermediate publish; final return still uses standard tiles.
                            gpuScene = withContext(Dispatchers.Default) {
                                TerrainGpuSceneBuilder.build(
                                    source = decoded.grid,
                                    maxFinestDimension = GPU_PREVIEW_MAX_DIMENSION,
                                    tileSize = GPU_FAST_TILE_SIZE,
                                )
                            },
                        ),
                    )
                } else {
                    onStage("Preparing detailed GPU terrain…")
                }
                val scene = buildGpuScene(decoded.grid)
                TerrainDecodeOutcome(decoded, LazTerrainCache.Hit.MISS, scene)
            }
        } finally {
            if (!lock.isLocked) locks.remove(fullKey, lock)
        }
    }

    suspend fun decodeRemoteCopc(
        url: String,
        cacheDirectory: File,
        options: LidarImportOptions,
        onStage: suspend (String) -> Unit = {},
    ): TerrainDecodeOutcome {
        val stableAssetUrl = url.substringBefore('?')
        val safeName = MessageDigest.getInstance("SHA-256")
            .digest(stableAssetUrl.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) } + ".copc.range-cache"
        val rangeCache = File(cacheDirectory.apply { mkdirs() }, safeName)
        onStage("Streaming selected COPC byte ranges…")
        val terrain = withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val laz = runCatching {
                LazTerrainReader.readRemote(
                    url = url,
                    rangeCacheFile = rangeCache,
                    options = options,
                    shouldContinue = { context.isActive },
                )
            }.getOrElse { ex ->
                // Soft-fail path: surface a clear message instead of crashing the open pipeline.
                throw java.io.IOException(
                    "COPC stream failed (network, range, or format). Try again or download LAZ. " +
                        (ex.message ?: ex::class.java.simpleName),
                    ex,
                )
            } ?: throw java.io.IOException(
                "COPC stream returned no terrain. Check selection bounds and connectivity.",
            )
            DemGenerator.TerrainLoadResult(
                grid = laz.grid,
                summary = "COPC range stream · ${laz.note}",
                isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
            )
        }
        onStage("Preparing detailed GPU terrain…")
        val scene = buildGpuScene(terrain.grid)
        return TerrainDecodeOutcome(terrain, LazTerrainCache.Hit.MISS, scene)
    }

    private suspend fun buildGpuScene(grid: ElevationGrid): TerrainGpuScene =
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            // The 2D analysis grid keeps the requested resolution, and the GPU 3D scene now
            // keeps a matching detailed finest level (1,024 cells) so zoomed-in terrain never
            // turns blocky. Coarser LOD levels still handle zoomed-out rendering cheaply.
            TerrainGpuSceneBuilder.build(
                source = grid,
                maxFinestDimension = GPU_PREVIEW_MAX_DIMENSION,
                tileSize = GPU_PREVIEW_TILE_SIZE,
            )
        }

    private suspend fun decodeFile(
        file: File,
        displayName: String,
        options: LidarImportOptions,
    ): DemGenerator.TerrainLoadResult? = withContext(Dispatchers.IO) {
        val decodeContext = currentCoroutineContext()
        decodeContext.ensureActive()
        if (displayName.substringAfterLast('.', "").equals("laz", ignoreCase = true) ||
            file.extension.equals("laz", ignoreCase = true)
        ) {
            // Use LASReader(File), not the generic InputStream path. This avoids another buffering
            // layer and lets laszip4j apply insideRectangle() for cropped refinement requests.
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

    companion object {
        internal const val GPU_PREVIEW_MAX_DIMENSION = 1_024
        internal const val GPU_PREVIEW_TILE_SIZE = 128
        /**
         * Larger tiles = fewer GPU batches for intermediate first-paint publish.
         * Spatial tiles use inclusive ends → (tileSize+1)² vertices; 256 was one over the
         * ushort limit and crashed LAS/LAZ open with bare "Failed requirement".
         */
        internal const val GPU_FAST_TILE_SIZE = 192
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
