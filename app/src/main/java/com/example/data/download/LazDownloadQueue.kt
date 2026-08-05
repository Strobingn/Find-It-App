package com.example.data.download

import android.content.Context
import com.example.data.LazDatasetStore
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where a queued tile download has got to. */
enum class LazDownloadState { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

data class LazDownloadTask(
    val url: String,
    val displayName: String,
    val state: LazDownloadState = LazDownloadState.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    /** Absolute path of the finished file, set only once [state] is COMPLETED. */
    val filePath: String? = null,
    val error: String? = null,
) {
    val isFinished: Boolean
        get() = state == LazDownloadState.COMPLETED ||
            state == LazDownloadState.FAILED ||
            state == LazDownloadState.CANCELLED

    /** Null when the server never declared a length, which some LiDAR hosts do not. */
    val fraction: Float?
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Process-wide record of tile downloads, owned by [LazDownloadService] and observed by the UI.
 *
 * Downloads used to run in the picker's own composition scope, so leaving the Import tab - or
 * simply backgrounding the app - cancelled a transfer that could be several gigabytes in. Keeping
 * the state here means the screen can come and go while the service keeps working, and a returning
 * user sees the real progress rather than a download that silently died.
 */
object LazDownloadQueue {
    private val _tasks = MutableStateFlow<List<LazDownloadTask>>(emptyList())
    val tasks: StateFlow<List<LazDownloadTask>> = _tasks.asStateFlow()

    /** Cancellation requests, read by the running transfer between buffer writes. */
    private val cancelled = mutableSetOf<String>()

    /**
     * The single durable storage location shared by the service and the UI
     * ([Context.getFilesDir]/lidar, with one-time migration from the older external path).
     */
    fun store(context: Context): LazDatasetStore = LazDatasetStore.open(context)

    fun taskFor(url: String): LazDownloadTask? = _tasks.value.firstOrNull { it.url == url }

    fun isPending(url: String): Boolean =
        taskFor(url)?.state.let { it == LazDownloadState.QUEUED || it == LazDownloadState.RUNNING }

    fun hasPendingWork(): Boolean = _tasks.value.any {
        it.state == LazDownloadState.QUEUED || it.state == LazDownloadState.RUNNING
    }

    /** Adds a task unless the same URL is already queued or running. Returns true if it was added. */
    fun enqueue(url: String, displayName: String): Boolean {
        if (isPending(url)) return false
        synchronized(cancelled) { cancelled.remove(url) }
        _tasks.update { current ->
            current.filterNot { it.url == url } + LazDownloadTask(url = url, displayName = displayName)
        }
        return true
    }

    fun nextQueued(): LazDownloadTask? = _tasks.value.firstOrNull { it.state == LazDownloadState.QUEUED }

    fun markRunning(url: String) = update(url) { it.copy(state = LazDownloadState.RUNNING) }

    fun markProgress(url: String, downloaded: Long, total: Long) = update(url) {
        it.copy(downloadedBytes = downloaded, totalBytes = total)
    }

    fun markCompleted(url: String, file: File) = update(url) {
        it.copy(
            state = LazDownloadState.COMPLETED,
            filePath = file.absolutePath,
            downloadedBytes = file.length(),
            totalBytes = file.length(),
            error = null,
        )
    }

    fun markFailed(url: String, message: String) = update(url) {
        val wasCancelled = synchronized(cancelled) { cancelled.contains(url) }
        it.copy(
            state = if (wasCancelled) LazDownloadState.CANCELLED else LazDownloadState.FAILED,
            error = if (wasCancelled) null else message,
        )
    }

    fun requestCancel(url: String) {
        synchronized(cancelled) { cancelled.add(url) }
        // A task still waiting its turn can be retired immediately; a running one stops when the
        // transfer next checks in.
        update(url) { if (it.state == LazDownloadState.QUEUED) it.copy(state = LazDownloadState.CANCELLED) else it }
    }

    fun isCancelled(url: String): Boolean = synchronized(cancelled) { cancelled.contains(url) }

    /** Drops a finished entry once the UI has acted on it. */
    fun dismiss(url: String) {
        synchronized(cancelled) { cancelled.remove(url) }
        _tasks.update { current -> current.filterNot { it.url == url && it.isFinished } }
    }

    fun clearFinished() {
        _tasks.update { current -> current.filterNot(LazDownloadTask::isFinished) }
    }

    private fun update(url: String, transform: (LazDownloadTask) -> LazDownloadTask) {
        _tasks.update { current ->
            current.map { if (it.url == url) transform(it) else it }
        }
    }
}
