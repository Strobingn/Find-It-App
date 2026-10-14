package com.example.data

import kotlin.math.max
import kotlin.math.min

/**
 * Device-adaptive memory limits for large LiDAR workloads.
 *
 * Android owns the process heap ceiling. With android:largeHeap enabled,
 * Runtime.maxMemory() reports the larger per-process limit granted by the device.
 * CHATGPTV2.0 uses nearly all non-reserved heap for the two persistent LRU caches while
 * preserving explicit headroom for Compose, bitmaps, LAZ decoding, OpenGL, and analysis arrays.
 */
object AppMemoryBudget {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    /** Actual Java heap ceiling for this process. */
    val maxHeapBytes: Long
        get() = Runtime.getRuntime().maxMemory().coerceAtLeast(128L * MIB)

    /** Heap ceiling below which tablets/phones get smaller caches and fewer decode workers. */
    const val LOW_MEMORY_HEAP_BYTES = 768L * MIB

    /** Test hook: force (true/false) or auto-detect (null) the low-memory profile. */
    @Volatile var lowMemoryOverride: Boolean? = null

    /** Set after an OutOfMemoryError so later decodes stay on the low-memory profile. */
    @Volatile private var memoryPressure = false

    internal val hasMemoryPressure: Boolean get() = memoryPressure

    val isLowMemory: Boolean
        get() = lowMemoryOverride ?: (memoryPressure || maxHeapBytes < LOW_MEMORY_HEAP_BYTES)

    /** Parallel LAZ decode worker count. Fewer workers only shrink the in-flight chunk window; output is identical. */
    @Volatile var calibratedDecodeWorkers: Int? = null
    @Volatile var thermalStatus: Int = 0

    val decodeHeadroomBytes: Long
        get() {
            val runtime=Runtime.getRuntime()
            val spare=(runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory()).coerceAtLeast(0)
            return minOf(reservedHeadroomBytes,spare/2).coerceAtLeast(1)
        }

    fun decodeWorkers(requested: Int? = null): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        if(thermalStatus>=4) return 1
        if(thermalStatus>=3) return cores.coerceIn(1,2)
        return if (memoryPressure || lowMemoryOverride == true) cores.coerceIn(1,2)
        else minOf(cores.coerceAtLeast(1),requested ?: calibratedDecodeWorkers ?: if(isLowMemory) 4 else 6).coerceIn(1,8)
    }

    private val trimmables = java.util.Collections.synchronizedList(ArrayList<java.lang.ref.WeakReference<() -> Unit>>())

    /** Register an in-memory cache that can be dropped under memory pressure. */
    fun registerTrimmable(clear: () -> Unit) {
        trimmables.add(java.lang.ref.WeakReference(clear))
    }

    /** Called after an OutOfMemoryError: drop every registered cache and switch to the low-memory profile. */
    fun onOutOfMemory() {
        memoryPressure = true
        val hooks = synchronized(trimmables) { trimmables.removeAll { it.get() == null }; trimmables.mapNotNull { it.get() } }
        hooks.forEach { runCatching(it) }
        System.gc()
    }

    /** Reserved for transient decoding, UI bitmaps, native/GPU allocations, and network buffers. */
    val reservedHeadroomBytes: Long
        get() = max(128L * MIB, (maxHeapBytes * 0.18).toLong())
            .coerceAtMost((maxHeapBytes * 0.30).toLong())

    /** Budget available to the decoded-terrain and derived-layer caches together. */
    val persistentCacheBudgetBytes: Long
        get() = (maxHeapBytes - reservedHeadroomBytes).coerceAtLeast(0)

    /** Decoded DEM cache gets the larger share because reopening LAZ is the most expensive path. */
    fun terrainMemoryCacheBytes(): Long = shareOfPersistentBudget(
        fraction = 0.62,
        maximum = 3L * GIB,
    )

    /** Derived terrain layers retain the remainder for rapid analysis-mode switching. */
    fun derivedLayerMemoryCacheBytes(): Long = shareOfPersistentBudget(
        fraction = 0.38,
        maximum = 2L * GIB,
    )

    fun describe(): String = buildString {
        append("Heap ")
        append(formatBytes(maxHeapBytes))
        append(" · decoded cache ")
        append(formatBytes(terrainMemoryCacheBytes()))
        append(" · derived cache ")
        append(formatBytes(derivedLayerMemoryCacheBytes()))
        append(" · reserved ")
        append(formatBytes(reservedHeadroomBytes))
    }

    private fun shareOfPersistentBudget(fraction: Double, maximum: Long): Long {
        // Low-memory devices give caches half the usual share, leaving room for decode + bitmaps.
        val available = if (isLowMemory) persistentCacheBudgetBytes / 2 else persistentCacheBudgetBytes
        val requested = (available * fraction).toLong()
        // The two shares together must fit the common budget, including small heaps.
        return min(maximum, requested)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= GIB -> String.format("%.2f GiB", bytes.toDouble() / GIB)
        else -> String.format("%.0f MiB", bytes.toDouble() / MIB)
    }
}
