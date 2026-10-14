package com.example.update

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Pages `/repos/{owner}/{repo}/releases` (never `/latest`) and keeps only `findit2-v*` tags.
 * The other Find It app publishes `findit-v*` and must not be offered as an update.
 * Public repos work with no token. [token] is optional and used only when set (private later).
 */
class GitHubReleaseClient(
    private val repo: String = "Strobingn/Find-It-App",
    private val token: String = "",
    private val http: OkHttpClient = defaultHttpClient(),
    private val downloadHttp: OkHttpClient = downloadHttpClient(http),
) {
    fun latestFinditRelease(applicationId: String? = null): UpdateCheckResult {
        val tokenPresent = token.isNotBlank()
        val releases = ArrayList<FinditRelease>()
        var page = 1
        while (page <= MAX_PAGES) {
            val url = "https://api.github.com/repos/$repo/releases?per_page=30&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (tokenPresent) header("Authorization", "Bearer $token") }
                .build()
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                AppUpdateParse.tokenErrorMessage(
                    status = response.code,
                    tokenPresent = tokenPresent,
                    body = body,
                    remainingHeader = response.header("X-RateLimit-Remaining"),
                )?.let { return it }
                if (!response.isSuccessful) {
                    return UpdateCheckResult.Failed("GitHub releases HTTP ${response.code}")
                }
                val array = runCatching { JSONArray(body) }.getOrElse {
                    return UpdateCheckResult.Failed("GitHub releases response was not a release list")
                }
                if (array.length() == 0) break
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    runCatching { parseRelease(item.toString()) }.getOrNull()?.let { releases.add(it) }
                }
                if (array.length() < 30) break
            }
            page += 1
        }
        val newest = releases
            .filter { release ->
                val id = release.applicationId
                applicationId.isNullOrBlank() || id.isNullOrBlank() || id == applicationId
            }
            .maxByOrNull { it.versionCode }
            ?: return UpdateCheckResult.Failed("No findit2-v* releases yet")
        return UpdateCheckResult.Available(newest)
    }

    /**
     * Streams the APK to [destination] (via a `.part` file) with progress. Blocking: call off the
     * main thread. [isActive] is polled between chunks so the caller can cancel; a cancelled or
     * failed download never leaves a partial APK behind.
     */
    fun downloadRelease(
        release: FinditRelease,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ) {
        val url = if (token.isNotBlank()) {
            release.apkApiUrl
        } else {
            release.browserDownloadUrl.ifBlank { release.apkApiUrl }
        }
        downloadAsset(url, destination, onProgress, isActive)
    }

    fun downloadAsset(
        apiUrl: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ) {
        destination.parentFile?.mkdirs()
        val part = File(destination.path + ".part")
        part.delete()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val call = downloadHttp.newCall(request)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    if (AppUpdateParse.isRateLimit(
                            response.code,
                            err,
                            response.header("X-RateLimit-Remaining"),
                        )
                    ) {
                        throw IOException(AppUpdateParse.RATE_LIMIT_MESSAGE)
                    }
                    if (token.isNotBlank() && (response.code == 401 || response.code == 403)) {
                        throw IOException("Update token expired or invalid")
                    }
                    throw IOException("APK download HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty APK download")
                val total = body.contentLength()
                var downloaded = 0L
                onProgress(0L, total)
                part.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (!isActive()) {
                                call.cancel()
                                throw DownloadCancelledException()
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (downloaded <= 0L) throw IOException("Empty APK download")
                if (total > 0 && downloaded != total) {
                    throw IOException("APK download incomplete ($downloaded of $total bytes)")
                }
                destination.delete()
                if (!part.renameTo(destination)) {
                    part.copyTo(destination, overwrite = true)
                }
                if (!destination.isFile || destination.length() <= 0L) {
                    throw IOException("Empty APK download")
                }
            }
        } finally {
            part.delete()
        }
    }

    class DownloadCancelledException : IOException("Download cancelled")

    companion object {
        /** Release list pages are small; keep the whole check well under the 15 s UI budget. */
        private const val MAX_PAGES = 5

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

        /**
         * APK downloads (~25 MB) must not share the 12 s whole-call budget of the release check,
         * which killed downloads on normal mobile links. A stalled socket still fails via the
         * read timeout; total time is bounded by the user's Cancel.
         */
        fun downloadHttpClient(base: OkHttpClient = defaultHttpClient()): OkHttpClient =
            base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(DOWNLOAD_STALL_TIMEOUT_S, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()

        const val DOWNLOAD_STALL_TIMEOUT_S = 20L

        fun parseRelease(json: String): FinditRelease? {
            val obj = org.json.JSONObject(json)
            val tag = obj.optString("tag_name")
            val code = AppUpdateParse.versionCodeFromTag(tag) ?: return null
            val body = obj.optString("body")
            val assets = obj.optJSONArray("assets") ?: return null
            var assetId = -1L
            var assetName = ""
            var assetUrl = ""
            var browserUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    assetId = asset.optLong("id")
                    assetName = name
                    assetUrl = asset.optString("url")
                    browserUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (assetId <= 0L || (assetUrl.isBlank() && browserUrl.isBlank())) return null
            return FinditRelease(
                tag = tag,
                versionCode = code,
                name = obj.optString("name").ifBlank { tag },
                body = body,
                apkAssetId = assetId,
                apkName = assetName,
                apkApiUrl = assetUrl,
                browserDownloadUrl = browserUrl,
                sha256 = AppUpdateParse.sha256FromNotes(body),
                applicationId = AppUpdateParse.appIdFromNotes(body),
            )
        }
    }
}
