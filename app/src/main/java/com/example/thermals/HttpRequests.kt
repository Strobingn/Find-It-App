package com.example.thermals

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Read and close the response on OkHttp's thread; cancellation also aborts a body still downloading. */
internal suspend fun <T> Call.awaitResult(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    continuation.resume(response.use(read))
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                }
            }
        })
    }
