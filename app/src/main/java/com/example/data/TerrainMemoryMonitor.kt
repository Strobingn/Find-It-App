package com.example.data

import android.os.Debug
import java.util.Locale

/** Java heap, native heap, and estimated GPU buffer bytes for the open terrain. */
data class TerrainMemorySnapshot(
    val javaUsedBytes: Long,
    val javaMaxBytes: Long,
    val nativeHeapBytes: Long,
    val estimatedGpuBytes: Long,
    val demCells: Int,
) {
    fun format(): String = String.format(
        Locale.US,
        "memory java=%s/%s native=%s gpu~%s demCells=%d",
        human(javaUsedBytes),
        human(javaMaxBytes),
        human(nativeHeapBytes),
        human(estimatedGpuBytes),
        demCells,
    )

    companion object {
        fun human(bytes: Long): String = when {
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MiB", bytes / 1024.0 / 1024.0)
            bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

object TerrainMemoryMonitor {
    @Volatile
    var lastGpuBytes: Long = 0L

    @Volatile
    var lastDemCells: Int = 0

    fun noteGpuScene(scene: TerrainGpuScene) {
        var bytes = 0L
        for (level in scene.levels) {
            for (batch in level.batches) {
                bytes += batch.vertices.size.toLong() * 4L
                bytes += batch.indices.size.toLong() * 2L
                bytes += batch.intIndices?.size?.toLong()?.times(4L) ?: 0L
            }
        }
        // Clipmap R32F heightmaps occupy texture memory in the VTF renderer path.
        for (clipmap in scene.clipmaps) {
            bytes += clipmap.heightmap.floats.size.toLong() * 4L
        }
        lastGpuBytes = bytes
        lastDemCells = scene.sourceWidth * scene.sourceHeight
    }

    fun snapshot(): TerrainMemorySnapshot {
        val runtime = Runtime.getRuntime()
        val native = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(0L)
        return TerrainMemorySnapshot(
            javaUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            javaMaxBytes = runtime.maxMemory(),
            nativeHeapBytes = native,
            estimatedGpuBytes = lastGpuBytes,
            demCells = lastDemCells,
        )
    }
}
