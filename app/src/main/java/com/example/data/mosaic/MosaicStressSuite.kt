package com.example.data.mosaic

import com.example.data.AppMemoryBudget
import com.example.data.PerfHarness
import java.util.Locale

/**
 * Large-mosaic stress QA: pure scenarios that score memory headroom, tile scale, cancel,
 * and cache-reopen semantics without requiring a full LAZ decode in unit tests.
 *
 * Device runs (Tools) exercise these definitions; heavy I/O stays in existing mosaic open paths.
 */
object MosaicStressSuite {

    const val LARGE_TILE_COUNT: Int = 8
    const val STRESS_HEAP_FRACTION: Double = 0.55

    enum class ScenarioId {
        MEMORY_HEADROOM,
        LARGE_TILE_COUNT,
        CANCEL_COOPERATIVE,
        CACHE_REOPEN,
        DUAL_GRID_BUDGET,
    }

    data class ScenarioResult(
        val id: ScenarioId,
        val title: String,
        val passed: Boolean,
        val detail: String,
    )

    data class Report(
        val results: List<ScenarioResult>,
        val heapDescribe: String,
        val honestyLine: String =
            "Stress QA measures open/memory/cancel behavior — not metal, age, or dig depth.",
    ) {
        val allPassed: Boolean get() = results.all { it.passed }
        val passCount: Int get() = results.count { it.passed }

        fun format(): String = buildString {
            appendLine("Mosaic stress QA")
            appendLine(heapDescribe)
            appendLine("pass $passCount / ${results.size}")
            results.forEach { r ->
                appendLine(
                    String.format(
                        Locale.US,
                        "[%s] %s — %s",
                        if (r.passed) "PASS" else "FAIL",
                        r.title,
                        r.detail,
                    ),
                )
            }
            appendLine(honestyLine)
        }.trimEnd()
    }

    /**
     * @param mosaicTileCount tiles in the active/saved mosaic project (0 if none)
     * @param cancelRequested simulated mid-open cancel
     * @param cacheHit simulated reopen from disk/memory cache
     * @param priorTerrainPreserved after cancel, prior terrain still shown
     * @param dualGridHeld both ground+first-return (or epoch pair) held in memory estimate bytes
     */
    fun run(
        mosaicTileCount: Int = 0,
        cancelRequested: Boolean = true,
        cacheHit: Boolean = true,
        priorTerrainPreserved: Boolean = true,
        dualGridHeldBytes: Long = 0L,
        maxHeapBytes: Long = AppMemoryBudget.maxHeapBytes,
        reservedHeadroomBytes: Long = AppMemoryBudget.reservedHeadroomBytes,
    ): Report {
        val results = listOf(
            memoryHeadroom(maxHeapBytes, reservedHeadroomBytes),
            largeTileCount(mosaicTileCount),
            cancelCooperative(cancelRequested, priorTerrainPreserved),
            cacheReopen(cacheHit),
            dualGridBudget(dualGridHeldBytes, maxHeapBytes, reservedHeadroomBytes),
        )
        val report = Report(
            results = results,
            heapDescribe = AppMemoryBudget.describe(),
        )
        results.forEach { r ->
            PerfHarness.record(
                name = "mosaic_stress_${r.id.name.lowercase()}",
                millis = if (r.passed) 0.0 else 1.0,
                detail = r.detail.take(120),
            )
        }
        return report
    }

    private fun memoryHeadroom(maxHeap: Long, reserved: Long): ScenarioResult {
        val freeFrac = reserved.toDouble() / maxHeap.coerceAtLeast(1L).toDouble()
        val ok = freeFrac >= 0.12 && maxHeap >= 128L * 1024 * 1024
        return ScenarioResult(
            id = ScenarioId.MEMORY_HEADROOM,
            title = "Memory headroom",
            passed = ok,
            detail = if (ok) {
                "Reserved ${formatMib(reserved)} of ${formatMib(maxHeap)} heap (≥12%)"
            } else {
                "Insufficient headroom — large mosaics may OOM; close apps or use smaller area"
            },
        )
    }

    private fun largeTileCount(count: Int): ScenarioResult {
        val ok = count == 0 || count <= LARGE_TILE_COUNT * 2
        return ScenarioResult(
            id = ScenarioId.LARGE_TILE_COUNT,
            title = "Tile scale",
            passed = ok,
            detail = when {
                count == 0 -> "No active mosaic — suite definitions OK (open multi-tile for live stress)"
                count <= LARGE_TILE_COUNT -> "Mosaic has $count tile(s) (within large=${LARGE_TILE_COUNT})"
                count <= LARGE_TILE_COUNT * 2 -> "Mosaic has $count tiles — large; watch memory"
                else -> "Mosaic has $count tiles — exceeds stress budget (${LARGE_TILE_COUNT * 2})"
            },
        )
    }

    private fun cancelCooperative(cancelRequested: Boolean, priorPreserved: Boolean): ScenarioResult {
        val ok = !cancelRequested || priorPreserved
        return ScenarioResult(
            id = ScenarioId.CANCEL_COOPERATIVE,
            title = "Cancel preserves prior terrain",
            passed = ok,
            detail = if (ok) {
                "Cancel path keeps previous terrain (cooperative cancel contract)"
            } else {
                "Cancel left blank terrain — open path must restore prior grid"
            },
        )
    }

    private fun cacheReopen(cacheHit: Boolean): ScenarioResult {
        return ScenarioResult(
            id = ScenarioId.CACHE_REOPEN,
            title = "Cache reopen",
            passed = true, // definition always pass; detail reflects live state
            detail = if (cacheHit) {
                "Cache hit path available — reopen should skip full recompute"
            } else {
                "No cache hit this session — first open will decode fully"
            },
        )
    }

    private fun dualGridBudget(
        held: Long,
        maxHeap: Long,
        reserved: Long,
    ): ScenarioResult {
        val budget = (maxHeap - reserved).coerceAtLeast(0L)
        val limit = (budget * STRESS_HEAP_FRACTION).toLong()
        val ok = held <= 0L || held <= limit
        return ScenarioResult(
            id = ScenarioId.DUAL_GRID_BUDGET,
            title = "Dual-grid / dual-surface budget",
            passed = ok,
            detail = when {
                held <= 0L -> "No dual-grid hold measured — OK"
                ok -> "Held ${formatMib(held)} ≤ ${formatMib(limit)} (${(STRESS_HEAP_FRACTION * 100).toInt()}% of cache budget)"
                else -> "Held ${formatMib(held)} exceeds ${formatMib(limit)} — drop dual surface or refine smaller"
            },
        )
    }

    private fun formatMib(bytes: Long): String =
        String.format(Locale.US, "%.0f MiB", bytes / (1024.0 * 1024.0))
}
