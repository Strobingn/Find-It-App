package com.example.data

import java.util.ArrayDeque
import java.util.Locale

data class LazTransferProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null

    /** Compact line for the tile picker, the Import tab, and the foreground notification. */
    fun statusLine(): String {
        val amount = if (totalBytes > 0L) {
            val percent = ((downloadedBytes * 100L) / totalBytes).coerceIn(0L, 100L)
            "$percent% of ${formatBytes(totalBytes)}"
        } else {
            "${formatBytes(downloadedBytes)} downloaded"
        }
        val extras = buildList {
            formatRate(bytesPerSecond)?.let(::add)
            formatEta(etaSeconds)?.let(::add)
        }
        return if (extras.isEmpty()) amount else "$amount · ${extras.joinToString(" · ")}"
    }

    companion object {
        fun formatBytes(bytes: Long): String {
            val mib = bytes / (1024.0 * 1024.0)
            return if (mib >= 1.0) {
                String.format(Locale.US, "%.1f MiB", mib)
            } else {
                String.format(Locale.US, "%.0f KiB", bytes / 1024.0)
            }
        }

        fun formatRate(bytesPerSecond: Long): String? {
            if (bytesPerSecond <= 0L) return null
            val mib = bytesPerSecond / (1024.0 * 1024.0)
            return if (mib >= 1.0) {
                String.format(Locale.US, "%.1f MiB/s", mib)
            } else {
                String.format(Locale.US, "%.0f KiB/s", bytesPerSecond / 1024.0)
            }
        }

        fun formatEta(seconds: Long?): String? {
            val remaining = seconds ?: return null
            if (remaining < 0L) return null
            if (remaining < 60L) return "${remaining}s left"
            val minutes = remaining / 60L
            val secs = remaining % 60L
            if (minutes < 60L) return "${minutes}m ${secs}s left"
            val hours = minutes / 60L
            return "${hours}h ${minutes % 60L}m left"
        }
    }
}

/**
 * Smoothed throughput over a short rolling window so the UI does not jump every buffer write.
 */
class LazSpeedWindow(private val windowMs: Long = 2_500L) {
    private val samples = ArrayDeque<Sample>()

    fun update(downloadedBytes: Long, totalBytes: Long, nowMs: Long = System.currentTimeMillis()): LazTransferProgress {
        samples.addLast(Sample(nowMs, downloadedBytes))
        while (samples.size > 1 && nowMs - samples.first.timeMs > windowMs) {
            samples.removeFirst()
        }
        val bytesPerSecond = if (samples.size >= 2) {
            val elapsedMs = (samples.last.timeMs - samples.first.timeMs).coerceAtLeast(1L)
            val delta = (samples.last.bytes - samples.first.bytes).coerceAtLeast(0L)
            (delta * 1_000L) / elapsedMs
        } else {
            0L
        }
        val eta = if (bytesPerSecond > 0L && totalBytes > downloadedBytes) {
            (totalBytes - downloadedBytes) / bytesPerSecond
        } else {
            null
        }
        return LazTransferProgress(downloadedBytes, totalBytes, bytesPerSecond, eta)
    }

    private data class Sample(val timeMs: Long, val bytes: Long)
}
