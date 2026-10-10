package com.example.data

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Result from the Phase 2 decode pipeline. */
data class TerrainDecodeOutcome(
    val terrain: DemGenerator.TerrainLoadResult,
    val cacheHit: LazTerrainCache.Hit,
    val gpuScene: TerrainGpuScene,
    /**
     * When non-null, [terrain] is a full-resolution sparse preview and this deferred completes
     * with the exact all-return product (or null if cancelled/failed).
     */
    val exactOutcome: Deferred<TerrainDecodeOutcome?>? = null,
    val isPreview: Boolean = false,
)

/**
 * Serializes duplicate work per source/options key while allowing unrelated datasets to decode in
 * parallel. File/cache I/O runs on Dispatchers.IO; GPU mesh construction runs on
 * Dispatchers.Default.
 *
 * Large full-footprint LAZ opens may return a **full-resolution sparse chunk preview** first
 * (same raster size, fewer points), then upgrade to the exact product in the background. Focused
 * refinements, small files, and non-LAZ formats stay exact-first. The GPU mesh is built once per
 * product (preview and exact each get a single build).
 */
class TerrainDecodeCoordinator(
    private val cache: LazTerrainCache,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val exactJobs = ConcurrentHashMap<String, Deferred<TerrainDecodeOutcome?>>()
    private val maintenanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    val scene = TerrainStageProfiler.stageSuspend("mesh", "cache") {
                        buildGpuScene(fullLookup.result.grid)
                    }
                    LazSpatialIndex.ensureBuiltAsync(file)
                    val outcome = TerrainDecodeOutcome(fullLookup.result, fullLookup.hit, scene)
                    onPreview?.invoke(outcome)
                    return@withLock outcome
                }

                val canPreview = onPreview != null &&
                    sanitized.focusBounds == null &&
                    isLazFile(file, displayName) &&
                    file.length() >= PREVIEW_MIN_FILE_BYTES
                if (canPreview) {
                    onStage("Painting a first-look preview…")
                    val previewTerrain = decodeSparsePreview(file, sanitized)
                    if (previewTerrain != null) {
                        currentCoroutineContext().ensureActive()
                        val previewScene = buildGpuScene(previewTerrain.grid)
                        val exact = startExactJob(fullKey, file, displayName, sanitized)
                        val previewOutcome = TerrainDecodeOutcome(
                            terrain = previewTerrain,
                            cacheHit = LazTerrainCache.Hit.MISS,
                            gpuScene = previewScene,
                            exactOutcome = exact,
                            isPreview = true,
                        )
                        onPreview.invoke(previewOutcome)
                        onStage("Preview visible — decoding every return…")
                        val exactOutcome = exact.await()
                        if (exactOutcome != null) {
                            onPreview.invoke(exactOutcome)
                            return@withLock exactOutcome
                        }
                    }
                }

                onStage("Decoding LAZ/LAS at ${sanitized.rasterResolution} px — every return…")
                val decoded = decodeFile(file, displayName, sanitized)
                    ?: error("Could not decode ${file.name}")
                currentCoroutineContext().ensureActive()
                cache.putMemory(file, sanitized, decoded)
                cache.putDisk(file, sanitized, decoded)
                LazSpatialIndex.ensureBuiltAsync(file)
                onStage("Preparing full-detail GPU terrain…")
                val scene = TerrainStageProfiler.stageSuspend("mesh", "${decoded.grid.width}x${decoded.grid.height}") {
                    buildGpuScene(decoded.grid)
                }
                val outcome = TerrainDecodeOutcome(decoded, LazTerrainCache.Hit.MISS, scene)
                onPreview?.invoke(outcome)
                outcome
            }
        } finally {
            if (!lock.isLocked) locks.remove(fullKey, lock)
        }
    }

    private fun startExactJob(
        key: String,
        file: File,
        displayName: String,
        options: LidarImportOptions,
    ): Deferred<TerrainDecodeOutcome?> {
        exactJobs[key]?.let { return it }
        val job = maintenanceScope.async {
            try {
                val decoded = decodeFile(file, displayName, options) ?: return@async null
                cache.putMemory(file, options, decoded)
                cache.putDisk(file, options, decoded)
                val scene = TerrainStageProfiler.stageSuspend("mesh", "exact") {
                    buildGpuScene(decoded.grid)
                }
                TerrainDecodeOutcome(
                    terrain = decoded,
                    cacheHit = LazTerrainCache.Hit.MISS,
                    gpuScene = scene,
                    isPreview = false,
                )
            } finally {
                exactJobs.remove(key)
            }
        }
        exactJobs[key] = job
        return job
    }

    private suspend fun decodeSparsePreview(
        file: File,
        options: LidarImportOptions,
    ): DemGenerator.TerrainLoadResult? = withContext(Dispatchers.IO) {
        val decodeContext = currentCoroutineContext()
        val preview = LazTerrainReader.readSparsePreview(
            file = file,
            options = options,
            shouldContinue = { decodeContext.isActive },
        ) ?: return@withContext null
        DemGenerator.TerrainLoadResult(
            grid = preview.grid,
            summary = preview.note,
            isBareEarth = preview.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
            geoMetadata = null,
        )
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
        onStage("Preparing full-detail GPU terrain…")
        val scene = TerrainStageProfiler.stageSuspend("mesh", "copc") { buildGpuScene(terrain.grid) }
        return TerrainDecodeOutcome(terrain, LazTerrainCache.Hit.MISS, scene)
    }

    private suspend fun buildGpuScene(grid: ElevationGrid): TerrainGpuScene =
        withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val scene = TerrainGpuSceneBuilder.build(
                source = grid,
                maxFinestDimension = maxOf(grid.width, grid.height, 64),
                tileSize = GPU_PREVIEW_TILE_SIZE,
                useIntIndices = true,
                attachHeightmap = true,
            )
            TerrainMemoryMonitor.noteGpuScene(scene)
            scene
        }

    private suspend fun decodeFile(
        file: File,
        displayName: String,
        options: LidarImportOptions,
    ): DemGenerator.TerrainLoadResult? = withContext(Dispatchers.IO) {
        val decodeContext = currentCoroutineContext()
        decodeContext.ensureActive()
        if (isLazFile(file, displayName)) {
            val laz = TerrainStageProfiler.stage("decode", displayName) {
                LazTerrainReader.read(
                    file = file,
                    options = options,
                    shouldContinue = { decodeContext.isActive },
                )
            } ?: return@withContext null
            DemGenerator.TerrainLoadResult(
                grid = laz.grid,
                summary = laz.note,
                isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
            )
        } else {
            if (displayName.substringAfterLast('.', "").equals("las", ignoreCase = true) ||
                file.extension.equals("las", ignoreCase = true)
            ) {
                TerrainStageProfiler.stage("decode", displayName) {
                    val las = DemGenerator.parseLasFileMapped(
                        file = file,
                        options = options,
                        shouldContinue = { decodeContext.isActive },
                    ) ?: return@stage null
                    DemGenerator.TerrainLoadResult(
                        grid = las.grid,
                        summary = las.note,
                        isBareEarth = las.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                    )
                }
            } else {
                FileInputStream(file).buffered(256 * 1024).use { input ->
                    TerrainStageProfiler.stage("decode", displayName) {
                        DemGenerator.parseFromStreamDetailed(
                            fileName = displayName,
                            inputStream = input,
                            options = options,
                            shouldContinue = { decodeContext.isActive },
                        )
                    }
                }
            }
        }
    }

    private fun isLazFile(file: File, displayName: String): Boolean =
        displayName.substringAfterLast('.', "").equals("laz", ignoreCase = true) ||
            file.extension.equals("laz", ignoreCase = true)

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
        /** Finest GPU level matches the decoded grid; this is only a safety ceiling. */
        internal const val GPU_PREVIEW_MAX_DIMENSION = Int.MAX_VALUE
        internal const val GPU_PREVIEW_TILE_SIZE = 128
        /**
         * Larger tiles = fewer GPU batches for intermediate first-paint publish.
         * Spatial tiles use inclusive ends → (tileSize+1)² vertices; stay ≤ 254 for ushort.
         */
        internal const val GPU_FAST_TILE_SIZE = 192
        private const val PREVIEW_MIN_FILE_BYTES = 32L * 1024L * 1024L
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
