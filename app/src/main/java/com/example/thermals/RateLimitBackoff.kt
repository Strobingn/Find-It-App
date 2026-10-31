package com.example.thermals

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** HTTP 429 or 503. [retryAfter] is the raw Retry-After header, when the server sent one. */
internal class RateLimitedException(
    service: String,
    val code: Int,
    val retryAfter: String?,
) : IOException("$service HTTP $code")

/**
 * Waits out a rate limit: honor Retry-After when it is a delay or an HTTP date, otherwise
 * exponential backoff with a little jitter. A handful of attempts, then the original error.
 */
internal object RateLimitBackoff {
    const val MAX_ATTEMPTS = 4
    const val BASE_DELAY_MS = 500L
    const val MAX_DELAY_MS = 20_000L

    /**
     * How long to sleep before attempt number [attempt] (1 = first retry).
     * [random] is uniform in 0..1; 0.5 means no jitter.
     */
    fun delayMs(attempt: Int, retryAfter: String?, random: () -> Double, nowMs: Long = System.currentTimeMillis()): Long {
        retryAfterMillis(retryAfter, nowMs)?.let { return it.coerceIn(0L, MAX_DELAY_MS) }
        val shift = (attempt - 1).coerceIn(0, 5)
        val exp = BASE_DELAY_MS shl shift
        val unit = random().let { if (it.isNaN()) 0.5 else it }.coerceIn(0.0, 1.0)
        val factor = 1.0 + (unit - 0.5) * 0.4
        return (exp * factor).toLong().coerceIn(0L, MAX_DELAY_MS)
    }

    /** Retry-After as a millisecond delay, or null when the header is missing or not understood. */
    fun retryAfterMillis(header: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = header?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0) * 1_000L }
        val parsed = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.parse(raw)?.time
        }.getOrNull() ?: return null
        return (parsed - nowMs).coerceAtLeast(0)
    }

    suspend fun <T> withRetries(
        sleeper: suspend (Long) -> Unit,
        random: () -> Double = { Math.random() },
        maxAttempts: Int = MAX_ATTEMPTS,
        block: () -> T,
    ): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: RateLimitedException) {
                attempt++
                if (attempt >= maxAttempts) throw e
                sleeper(delayMs(attempt, e.retryAfter, random))
            }
        }
    }
}
