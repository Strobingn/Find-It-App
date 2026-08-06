package com.example.data

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.system.measureNanoTime

/**
 * Lightweight performance harness: times named ops, keeps an in-memory ring, and can append
 * JSONL to disk for regression comparison. Used by Tools "Run perf harness" and unit tests.
 */
object PerfHarness {

    data class Sample(
        val name: String,
        val millis: Double,
        val detail: String,
        val atMillis: Long = System.currentTimeMillis(),
    )

    data class Summary(
        val name: String,
        val count: Int,
        val p50Ms: Double,
        val p95Ms: Double,
        val maxMs: Double,
        val lastDetail: String,
    )

    private val samples = ConcurrentLinkedQueue<Sample>()
    private const val MAX_SAMPLES = 200

    fun clear() {
        samples.clear()
    }

    fun record(name: String, millis: Double, detail: String = "") {
        samples.add(Sample(name, millis, detail))
        while (samples.size > MAX_SAMPLES) samples.poll()
    }

    fun <T> time(name: String, detail: String = "", block: () -> T): T {
        var result: T
        val nanos = measureNanoTime {
            result = block()
        }
        record(name, nanos / 1_000_000.0, detail)
        return result
    }

    fun allSamples(): List<Sample> = samples.toList()

    fun summarize(name: String? = null): List<Summary> {
        val grouped = allSamples()
            .filter { name == null || it.name == name }
            .groupBy { it.name }
        return grouped.map { (n, list) ->
            val sorted = list.map { it.millis }.sorted()
            Summary(
                name = n,
                count = sorted.size,
                p50Ms = percentile(sorted, 0.50),
                p95Ms = percentile(sorted, 0.95),
                maxMs = sorted.lastOrNull() ?: 0.0,
                lastDetail = list.lastOrNull()?.detail.orEmpty(),
            )
        }.sortedBy { it.name }
    }

    fun formatReport(): String {
        val rows = summarize()
        if (rows.isEmpty()) return "Perf harness: no samples yet."
        return buildString {
            appendLine("Perf harness report")
            appendLine("samples=${allSamples().size}")
            rows.forEach { s ->
                appendLine(
                    String.format(
                        Locale.US,
                        "%s n=%d p50=%.1fms p95=%.1fms max=%.1fms %s",
                        s.name,
                        s.count,
                        s.p50Ms,
                        s.p95Ms,
                        s.maxMs,
                        s.lastDetail,
                    ),
                )
            }
            appendLine("Honesty: timings are device-local synthetic/real ops — not metal/depth claims.")
        }.trimEnd()
    }

    fun appendJsonl(file: File) {
        file.parentFile?.mkdirs()
        val chunk = allSamples().joinToString("\n") { s ->
            """{"name":"${s.name}","ms":${"%.3f".format(Locale.US, s.millis)},"detail":${jsonString(s.detail)},"at":${s.atMillis}}"""
        }
        if (chunk.isNotEmpty()) {
            file.appendText(chunk + "\n")
        }
    }

    /**
     * Synthetic DEM micro-benchmark: builds a small bowl grid and runs hillshade + preview path.
     * Safe on any device; no LAZ I/O; synchronous (no coroutine engine).
     */
    fun runSyntheticTerrainBenchmark(gridSide: Int = 96, repeats: Int = 2): String {
        clear()
        val side = gridSide.coerceIn(32, 256)
        val size = side * side
        val elev = FloatArray(size)
        val canopy = FloatArray(size)
        val cx = side / 2f
        val cy = side / 2f
        for (y in 0 until side) {
            for (x in 0 until side) {
                val dx = x - cx
                val dy = y - cy
                val r = kotlin.math.sqrt(dx * dx + dy * dy)
                elev[y * side + x] = 100f - max(0f, 8f - r * 0.35f)
            }
        }
        val grid = time("synthetic_grid_build", "${side}x$side") {
            ElevationGrid(
                width = side,
                height = side,
                bareEarth = elev,
                canopySpikes = canopy,
                cellSizeMeters = 1f,
            )
        }
        repeat(repeats.coerceAtLeast(1)) { i ->
            try {
                time("hillshade_render", "${side}x$side r=$i") {
                    grid.renderHillshade(
                        sunAzimuth = 315f,
                        sunAltitude = 35f,
                        vegetationFilter = 1f,
                        palette = 0,
                        contrast = 1f,
                        zScale = 1f,
                    )
                }
            } catch (_: Throwable) {
                // Unit tests without Robolectric/Android Bitmap stub still exercise build + helpers.
                record("hillshade_render_skipped", 0.0, "no Android Bitmap runtime")
            }
            time("preview_max_side", "zoom=2") {
                previewMaxSideForZoom(2f, side)
            }
            time("debounce_ms", "mode=3") {
                hillshadeDebounceMs(visualizationMode = 3, immediate = false)
            }
            // Pure CPU load so harness is never empty of timed work
            time("grid_scan", "${side}x$side r=$i") {
                var acc = 0f
                for (v in elev) acc += v
                acc
            }
        }
        return formatReport()
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size - 1) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
