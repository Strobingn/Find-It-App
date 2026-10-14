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
    val memory: TerrainMemorySnapshot,
    val winner: String,
) {
    fun format(): String = buildString {
        appendLine("Decoder benchmark · $fileName")
        appendLine(String.format(Locale.US, "  points              %d", points))
        appendLine(String.format(Locale.US, "  laszip4j            %.2f ms", laszip4jMs))
        if (nativeAvailable && nativeMs != null) {
            appendLine(String.format(Locale.US, "  laz-rs native       %.2f ms", nativeMs))
            appendLine("  winner              $winner")
        } else {
            appendLine("  laz-rs native       not loaded (laszip4j stays default)")
        }
        appendLine(String.format(Locale.US, "  Java decode+grid    %.2f ms", rasterizeMs))
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
    internal fun equivalent(a: DemGenerator.LasLoadResult, b: DemGenerator.LasLoadResult): Boolean =
        a.grid.width == b.grid.width && a.grid.height == b.grid.height &&
            a.sampledPoints == b.sampledPoints && a.groundPointsUsed == b.groundPointsUsed &&
            a.appliedGroundMode == b.appliedGroundMode &&
            a.grid.cellSizeXMeters == b.grid.cellSizeXMeters && a.grid.cellSizeYMeters == b.grid.cellSizeYMeters &&
            a.grid.bareEarth.contentEquals(b.grid.bareEarth) && a.grid.canopySpikes.contentEquals(b.grid.canopySpikes) &&
            a.grid.validData.contentEquals(b.grid.validData) && a.grid.interpolatedData.contentEquals(b.grid.interpolatedData)

    fun run(
        file: File,
        options: LidarImportOptions = LidarImportOptions(),
        cacheDir: File,
        shouldContinue: () -> Boolean = { true },
    ): DecoderBenchmarkResult {
        TerrainStageProfiler.reset()
        val sanitized = options.sanitized()

        var rasterizeMs = 0.0
        val javaResult = run {
            TerrainStageProfiler.stage("decode_laszip4j", file.name) {
                val start = System.nanoTime()
                val decoded = if (file.extension.equals("laz", ignoreCase = true)) {
                    LazTerrainReader.readForBenchmark(file, sanitized, LazDecoderBackend.LASZIP4J, shouldContinue)
                } else {
                    file.inputStream().buffered(256 * 1024).use { input ->
                        DemGenerator.parseLasStreamDetailed(input, sanitized, shouldContinue)
                    }
                }
                rasterizeMs = (System.nanoTime() - start) / 1_000_000.0
                decoded
            }
        }
        requireNotNull(javaResult) { "laszip4j produced no terrain for ${file.name}" }

        var nativeMs: Double? = null
        val nativeAvailable = LazRsNativeDecoder.isAvailable
        if (nativeAvailable) {
            try {
                val start = System.nanoTime()
                val native = requireNotNull(LazTerrainReader.readForBenchmark(file, sanitized, LazDecoderBackend.NATIVE_LAZ_RS, shouldContinue))
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                check(native.note.contains("native integer")) { "Native decoder fell back to Java" }
                check(equivalent(javaResult, native)) { "Native terrain differs from Java reference" }
                nativeMs = elapsed
            } catch (error: Exception) {
                TerrainStageProfiler.emit("Native benchmark rejected: ${error.message}")
            }
        }

        val mesh = TerrainStageProfiler.stage("heightfield_prepare", "${javaResult.grid.width}x${javaResult.grid.height}") {
            TerrainGpuSceneBuilder.buildHeightfield(javaResult.grid)
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
            meshMs = TerrainStageProfiler.snapshot().firstOrNull { it.name == "heightfield_prepare" }?.millis ?: 0.0,
            hillshadeMs = hillshadeMs,
            cdlodMs = cdlodTimed,
            memory = TerrainMemoryMonitor.snapshot(),
            winner = winner,
        ).also { TerrainStageProfiler.emit(it.format()) }
    }
}
