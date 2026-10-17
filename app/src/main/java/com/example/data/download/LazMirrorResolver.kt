package com.example.data.download

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks a faster byte-identical mirror for a LiDAR download URL, or null to keep the original.
 *
 * USGS rockyweb (`/vdelivery/Datasets/Staged/…`) is slow (measured 0.1–0.5 MB/s per connection).
 * The same TNM staged products are published on the prd-tnm S3 bucket (`/StagedProducts/…`,
 * measured ~94 MB/s single / >600 MB/s with 12 ranges from the box). A mirror is only used when
 * it answers and its size equals the original's, so a missing or different file can never be
 * substituted; the download manager falls back to the original URL on any mirror failure.
 * (As of 2026-10 the LPC LAZ tiles themselves are not on prd-tnm, so most rockyweb tiles fall
 * back; the rule starts working automatically wherever USGS publishes them.)
 */
class LazMirrorResolver(
    /** Remote size in bytes (HEAD or ranged GET), or null when unavailable. */
    private val sizeProbe: (String) -> Long? = ::probeSize,
) {
    private val cache = ConcurrentHashMap<String, String>()

    fun resolve(originalUrl: String): String? {
        cache[originalUrl]?.let { return it.ifEmpty { null } }
        val mirror = candidates(originalUrl).firstOrNull { candidate ->
            val mirrorSize = runCatching { sizeProbe(candidate) }.getOrNull() ?: return@firstOrNull false
            val originalSize = runCatching { sizeProbe(originalUrl) }.getOrNull()
            mirrorSize > 0L && originalSize == mirrorSize
        }
        cache[originalUrl] = mirror.orEmpty()
        return mirror
    }

    companion object {
        private const val ROCKYWEB_STAGED = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/"
        private const val PRD_TNM_S3 = "https://prd-tnm.s3.amazonaws.com/StagedProducts/"
        private const val PROBE_TIMEOUT_MS = 6_000

        /** Mirror URLs to try, fastest first. Pure: unit-tested. */
        fun candidates(url: String): List<String> {
            val u = url.trim()
            if (u.startsWith(ROCKYWEB_STAGED, ignoreCase = true)) {
                return listOf(PRD_TNM_S3 + u.substring(ROCKYWEB_STAGED.length))
            }
            return emptyList()
        }

        fun probeSize(url: String): Long? {
            fun open(method: String, range: Boolean): HttpURLConnection =
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = PROBE_TIMEOUT_MS
                    readTimeout = PROBE_TIMEOUT_MS
                    instanceFollowRedirects = true
                    if (range) setRequestProperty("Range", "bytes=0-0")
                }
            val head = open("HEAD", false)
            try {
                if (head.responseCode == 200) head.getHeaderField("Content-Length")?.toLongOrNull()?.let { return it }
                if (head.responseCode == 404 || head.responseCode == 403) return null
            } finally {
                head.disconnect()
            }
            val ranged = open("GET", true)
            try {
                if (ranged.responseCode == 206) {
                    return ranged.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                }
            } finally {
                ranged.disconnect()
            }
            return null
        }
    }
}
