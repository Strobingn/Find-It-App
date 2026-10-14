package com.example.data

import java.io.File
import java.util.Locale
import kotlin.system.measureNanoTime

/** On-device / host comparison of laszip4j vs laz-rs on the same file, plus cache reopen. */
data class DecoderBenchmarkResult(
    val fileName: String,
    val points: Long,
    val laszip4jMs: Double,
    val nativeMs: Double?,
    val nativeAvailable: Boolean,
    val cacheWriteMs: Double,
    val cacheReadMs: Double,
    val rasterizeMs: Double,
    val meshMs: Double,
    val hillshadeMs: Double?,
    val cdlodMs: Double,
    val decodeParallelMs: Double? = null,
    val gridMs: Double? = null,
    val nativeDecodeMs: Double? = null,
    val nativeBinMs: Double? = null,
    val memory: TerrainMemorySnapshot,
    val winner: String,
) {
    fun format(): String = buildString {
        appendLine("Decoder benchmark · $fileName")
        appendLine(String.format(Locale.US, "  points              %d", points))
        appendLine(String.format(Locale.US, "  laszip4j            %.2f ms", laszip4jMs))
        if (decodeParallelMs != null) {
            appendLine(String.format(Locale.US, "  laszip4j decompress %.2f ms", decodeParallelMs))
        }
        if (gridMs != null) {
            appendLine(String.format(Locale.US, "  laszip4j grid       %.2f ms", gridMs))
        }
        if (nativeAvailable && nativeMs != null) {
            appendLine(String.format(Locale.US, "  laz-rs native       %.2f ms", nativeMs))
            if (nativeDecodeMs != null && nativeBinMs != null) {
                appendLine(String.format(Locale.US, "  laz-rs decompress   %.2f ms", nativeDecodeMs))
                appendLine(String.format(Locale.US, "  laz-rs bin/grid     %.2f ms", nativeBinMs))
            }
            appendLine("  winner              $winner")
        } else {
            appendLine("  laz-rs native       not loaded (laszip4j stays default)")
        }
        appendLine(String.format(Locale.US, "  rasterize/grid      %.2f ms", rasterizeMs))
        appendLine(String.format(Locale.US, "  GPU mesh            %.2f ms", meshMs))
        appendLine(String.format(Locale.US, "  CDLOD select        %.2f ms", cdlodMs))
        if (hillshadeMs != null) {
            appendLine(String.format(Locale.US, "  hillshade CPU       %.2f ms", hillshadeMs))
        }
        appendLine(String.format(Locale.US, "  cache write         %.2f ms", cacheWriteMs))
        appendLine(String.format(Locale.US, "  cache reopen        %.2f ms", cacheReadMs))
        appendLine("  ${memory.format()}")
        append("Default backend stays laszip4j until native wins on this device.")
    }
}

object TerrainDecoderBenchmark {
    fun run(
        file: File,
        options: LidarImportOptions = LidarImportOptions(),
        cacheDir: File,
        shouldContinue: () -> Boolean = { true },
    ): DecoderBenchmarkResult {
        TerrainStageProfiler.reset()
        val sanitized = options.sanitized()

        var rasterizeMs = 0.0
        val previousPreferred = LazDecoderSettings.preferred
        val javaResult = try {
            // Force the Java backend so a native preference cannot contaminate the laszip4j timing.
            LazDecoderSettings.preferred = LazDecoderBackend.LASZIP4J
            TerrainStageProfiler.stage("decode_laszip4j", file.name) {
                val start = System.nanoTime()
                val decoded = if (file.extension.equals("laz", ignoreCase = true)) {
                    LazTerrainReader.read(file, sanitized, shouldContinue)
                } else {
                    file.inputStream().buffered(256 * 1024).use { input ->
                        DemGenerator.parseLasStreamDetailed(input, sanitized, shouldContinue)
                    }
                }
                rasterizeMs = (System.nanoTime() - start) / 1_000_000.0
                decoded
            }
        } finally {
            LazDecoderSettings.preferred = previousPreferred
        }
        requireNotNull(javaResult) { "laszip4j produced no terrain for ${file.name}" }
        val afterJava = TerrainStageProfiler.snapshot()
        val decodeParallelMs = afterJava.firstOrNull { it.name == "decode_parallel" }?.millis
        val gridMs = afterJava.firstOrNull { it.name == "grid" }?.millis

        var nativeMs: Double? = null
        var nativeDecodeMs: Double? = null
        var nativeBinMs: Double? = null
        val nativeAvailable = LazRsNativeDecoder.isAvailable
        if (nativeAvailable) {
            val bounds = LazTerrainReader.sourceBounds(file)
            nativeMs = if (bounds == null || bounds.maxX <= bounds.minX || bounds.maxY <= bounds.minY) {
                TerrainStageProfiler.emit("laz-rs benchmark skipped: no header bounds")
                null
            } else {
                runCatching {
                    TerrainStageProfiler.stage("decode_lazrs", file.name) {
                        val packed = PackedPointRasterizer.allocate(bounds.pointCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                        val origins = DoubleArray(3)
                        var decoded = 0
                        val decodeNanos = measureNanoTime {
                            decoded = LazRsNativeDecoder.decodePacked(file, packed, origins)
                        }
                        require(decoded > 0) { "laz-rs decode failed (count=$decoded)" }
                        nativeDecodeMs = decodeNanos / 1_000_000.0
                        val rasterizer = LidarRasterizer(
                            minX = bounds.minX,
                            maxX = bounds.maxX,
                            minY = bounds.minY,
                            maxY = bounds.maxY,
                            options = sanitized,
                            declaredPointCount = bounds.pointCount.coerceAtLeast(decoded.toLong()),
                            lasVersionMajor = bounds.versionMajor,
                            lasVersionMinor = bounds.versionMinor,
                            pointFormat = bounds.pointFormat,
                        )
                        val binNanos = measureNanoTime {
                            PackedPointRasterizer.addAll(
                                rasterizer,
                                packed,
                                decoded,
                                originX = origins[0],
                                originY = origins[1],
                                originZ = origins[2],
                            )
                            requireNotNull(rasterizer.finish(bounds.pointFormat, "laz-rs benchmark"))
                        }
                        nativeBinMs = binNanos / 1_000_000.0
                        (decodeNanos + binNanos) / 1_000_000.0
                    }
                }.getOrElse { error ->
                    TerrainStageProfiler.emit("laz-rs benchmark failed: ${error.message}")
                    null
                }
            }
        }

        val mesh = TerrainStageProfiler.stage("mesh_build", "${javaResult.grid.width}x${javaResult.grid.height}") {
            TerrainGpuSceneBuilder.build(
                source = javaResult.grid,
                maxFinestDimension = maxOf(javaResult.grid.width, javaResult.grid.height, 64),
                tileSize = TerrainDecodeCoordinator.GPU_PREVIEW_TILE_SIZE,
                attachHeightmap = true,
            )
        }
        TerrainMemoryMonitor.noteGpuScene(mesh)

        TerrainStageProfiler.stage("cdlod_select", "zoom=2") {
            val packed = HeightmapCdlod.pack(javaResult.grid)
            HeightmapCdlod.select(
                packed.width,
                packed.height,
                HeightmapCdlod.rangeInCellsForZoom(2f, maxOf(packed.width, packed.height)),
            )
        }
        val cdlodTimed = TerrainStageProfiler.snapshot().firstOrNull { it.name == "cdlod_select" }?.millis ?: 0.0

        val hillshadeMs = runCatching {
            TerrainStageProfiler.stage("hillshade_cpu", "${javaResult.grid.width}x${javaResult.grid.height}") {
                javaResult.grid.renderHillshade(315f, 35f, 1f, palette = 0, contrast = 1f)
            }
            TerrainStageProfiler.snapshot().firstOrNull { it.name == "hillshade_cpu" }?.millis
        }.getOrNull()

        val disk = LazTerrainDiskCache(cacheDir, maxBytes = 64L * 1024L * 1024L)
        val terrain = DemGenerator.TerrainLoadResult(
            grid = javaResult.grid,
            summary = javaResult.note,
            isBareEarth = javaResult.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
        )
        val writeMs = TerrainStageProfiler.stage("cache_write", file.name) {
            val nanos = measureNanoTime { disk.put(file, sanitized, terrain) }
            nanos / 1_000_000.0
        }
        val readMs = TerrainStageProfiler.stage("cache_reopen", file.name) {
            val nanos = measureNanoTime { requireNotNull(disk.get(file, sanitized)) }
            nanos / 1_000_000.0
        }

        val laszipMs = TerrainStageProfiler.snapshot().firstOrNull { it.name == "decode_laszip4j" }?.millis
            ?: rasterizeMs
        val winner = when {
            nativeMs == null && nativeAvailable -> "laszip4j (laz-rs failed)"
            nativeMs == null -> "laszip4j (native unavailable)"
            nativeMs < laszipMs -> "laz-rs"
            else -> "laszip4j"
        }
        return DecoderBenchmarkResult(
            fileName = file.name,
            points = javaResult.totalPointsRead.toLong(),
            laszip4jMs = laszipMs,
            nativeMs = nativeMs,
            nativeAvailable = nativeAvailable,
            cacheWriteMs = writeMs,
            cacheReadMs = readMs,
            rasterizeMs = rasterizeMs,
            meshMs = TerrainStageProfiler.snapshot().firstOrNull { it.name == "mesh_build" }?.millis ?: 0.0,
            hillshadeMs = hillshadeMs,
            cdlodMs = cdlodTimed,
            decodeParallelMs = decodeParallelMs,
            gridMs = gridMs,
            nativeDecodeMs = nativeDecodeMs,
            nativeBinMs = nativeBinMs,
            memory = TerrainMemoryMonitor.snapshot(),
            winner = winner,
        ).also { TerrainStageProfiler.emit(it.format()) }
    }
}
