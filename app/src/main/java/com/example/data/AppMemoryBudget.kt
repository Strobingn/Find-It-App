package com.example.data

import kotlin.math.max
import kotlin.math.min

/**
 * Device-adaptive memory limits for large LiDAR workloads.
 *
 * Android owns the process heap ceiling. With android:largeHeap enabled,
 * Runtime.maxMemory() reports the larger per-process limit granted by the device.
 * The app deliberately leaves headroom for Compose, bitmaps, LAZ decoding,
 * OpenGL buffers, networking, and temporary analysis arrays.
 */
object AppMemoryBudget {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    /** Actual Java heap ceiling for this process. */
    val maxHeapBytes: Long
        get() = Runtime.getRuntime().maxMemory().coerceAtLeast(128L * MIB)

    /** Keep this portion uncommitted for transient allocations and native/GPU work. */
    val reservedHeadroomBytes: Long
        get() = max(192L * MIB, (maxHeapBytes * 0.28).toLong())
            .coerceAtMost((maxHeapBytes * 0.45).toLong())

    /** Decoded DEM memory cache: approximately 38% of the granted heap. */
    fun terrainMemoryCacheBytes(): Long = boundedFraction(
        fraction = 0.38,
        minimum = 96L * MIB,
        maximum = 2L * GIB,
    )

    /** Derived-layer cache: approximately 27% of the granted heap. */
    fun derivedLayerMemoryCacheBytes(): Long = boundedFraction(
        fraction = 0.27,
        minimum = 64L * MIB,
        maximum = 1536L * MIB,
    )

    /** Budget available to both persistent in-memory terrain caches together. */
    val persistentCacheBudgetBytes: Long
        get() = (maxHeapBytes - reservedHeadroomBytes).coerceAtLeast(96L * MIB)

    fun describe(): String = buildString {
        append("Heap ceiling ")
        append(formatBytes(maxHeapBytes))
        append(" · decoded cache ")
        append(formatBytes(terrainMemoryCacheBytes()))
        append(" · analysis cache ")
        append(formatBytes(derivedLayerMemoryCacheBytes()))
        append(" · reserved ")
        append(formatBytes(reservedHeadroomBytes))
    }

    private fun boundedFraction(fraction: Double, minimum: Long, maximum: Long): Long {
        val available = persistentCacheBudgetBytes
        val requested = (maxHeapBytes * fraction).toLong()
        return min(maximum, max(minimum.coerceAtMost(available), requested.coerceAtMost(available)))
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= GIB -> String.format("%.2f GiB", bytes.toDouble() / GIB)
        else -> String.format("%.0f MiB", bytes.toDouble() / MIB)
    }
}
