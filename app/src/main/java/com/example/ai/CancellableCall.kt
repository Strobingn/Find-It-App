package com.example.ai

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response

/**
 * Runs a blocking OkHttp call so that cancelling the coroutine (a timeout, the user tapping Stop,
 * leaving the screen) actually aborts the request: `execute()` and body reads ignore coroutine
 * cancellation, but [Call.cancel] makes them throw right away. Call from Dispatchers.IO.
 */
internal suspend fun <T> Call.executeCancellable(read: (Response) -> T): T = coroutineScope {
    val call = this@executeCancellable
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            call.cancel()
        }
    }
    try {
        call.execute().use(read)
    } catch (e: java.io.IOException) {
        // Cancelled under us: report it as cancellation, not as a provider failure to fall back from.
        ensureActive()
        throw e
    } finally {
        watcher.cancel()
    }
}
