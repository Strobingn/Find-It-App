package com.example.ai

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableCallTest {

    @Test
    fun timeoutAbortsAHungRequestInsteadOfWaitingOutTheReadTimeout() {
        // A server that accepts the connection and never answers — like a provider stuck thinking.
        ServerSocket(0).use { server ->
            val accepter = Thread { runCatching { server.accept() } }.apply { isDaemon = true; start() }
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS).build()
            val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()
            val started = System.nanoTime()
            val result = runBlocking {
                withTimeoutOrNull(300) {
                    withContext(Dispatchers.IO) { client.newCall(request).executeCancellable { it.code } }
                }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertNull(result)
            assertTrue("took ${elapsedMs}ms — the blocking call wasn't cancelled", elapsedMs < 5_000)
            accepter.interrupt()
        }
    }
}
