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

    /** Latest download-source fallback message (e.g. Amazon EPT unavailable), shown on the picker. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    /** Per-tile notices, so a new attempt clears only its own stale message. */
    private val noticeByUrl = LinkedHashMap<String, String>()

    /** [message] null clears everything (e.g. the user picked another source). */
    fun postNotice(message: String?) = synchronized(noticeByUrl) {
        if (message == null) noticeByUrl.clear()
        _notice.value = message ?: noticeByUrl.values.lastOrNull()
    }

    fun postNotice(url: String, message: String) = synchronized(noticeByUrl) {
        noticeByUrl.remove(url)
        noticeByUrl[url] = message
        _notice.value = message
    }

    fun clearNotice(url: String) = synchronized(noticeByUrl) {
        if (noticeByUrl.remove(url) != null || noticeByUrl.isEmpty()) _notice.value = noticeByUrl.values.lastOrNull()
    }

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

    /** Atomically takes the next queued task and marks it running, so concurrent workers never share one. */
    @Synchronized
    fun claimNextQueued(): LazDownloadTask? {
        val next = nextQueued() ?: return null
        markRunning(next.url)
        return next
    }

    fun markRunning(url: String) {
        // A fresh attempt: the previous attempt's fallback message no longer applies.
        clearNotice(url)
        update(url) { it.copy(state = LazDownloadState.RUNNING) }
    }

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
        clearNotice(url)
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
