package com.example.data

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureNanoTime

/**
 * Separate decode / gridding / mesh / hillshade / cache timings.
 *
 * Logs to [TAG] so field traces can be grepped without mixing into hillshade diagnostics.
 */
object TerrainStageProfiler {
    const val TAG = "FindItTerrainPerf"

    data class StageSample(val name: String, val millis: Double, val detail: String)

    @Volatile
    var lastReport: String = ""
        private set

    private val totalsMs = ConcurrentHashMap<String, Double>()
    private val counts = ConcurrentHashMap<String, Int>()
    private val lastDetail = ConcurrentHashMap<String, String>()

    fun reset() {
        totalsMs.clear()
        counts.clear()
        lastDetail.clear()
        lastReport = ""
    }

    fun <T> stage(name: String, detail: String = "", block: () -> T): T {
        var result: T
        val nanos = measureNanoTime { result = block() }
        record(name, nanos / 1_000_000.0, detail)
        return result
    }

    suspend fun <T> stageSuspend(name: String, detail: String = "", block: suspend () -> T): T {
        val start = System.nanoTime()
        val result = block()
        record(name, (System.nanoTime() - start) / 1_000_000.0, detail)
        return result
    }

    fun record(name: String, millis: Double, detail: String = "") {
        totalsMs.merge(name, millis) { a, b -> a + b }
        counts.merge(name, 1) { a, b -> a + b }
        if (detail.isNotEmpty()) lastDetail[name] = detail
        PerfHarness.record(name, millis, detail)
        emit("$name ${"%.2f".format(Locale.US, millis)}ms${if (detail.isEmpty()) "" else " · $detail"}")
    }

    fun snapshot(): List<StageSample> = totalsMs.keys.sorted().map { name ->
        val n = counts[name] ?: 1
        val total = totalsMs[name] ?: 0.0
        StageSample(name, total / n, lastDetail[name].orEmpty())
    }

    fun count(name: String): Int = counts[name] ?: 0

    fun formatReport(): String {
        val rows = snapshot()
        lastReport = buildString {
            appendLine("Terrain stage timings")
            if (rows.isEmpty()) {
                appendLine("no samples")
            } else {
                rows.forEach { row ->
                    appendLine(
                        String.format(
                            Locale.US,
                            "  %-22s %8.2f ms  %s",
                            row.name,
                            row.millis,
                            row.detail,
                        ),
                    )
                }
            }
            append(TerrainMemoryMonitor.snapshot().format())
        }.trimEnd()
        emit(lastReport)
        return lastReport
    }

    internal fun emit(message: String) {
        runCatching { android.util.Log.i(TAG, message) }
    }
}
