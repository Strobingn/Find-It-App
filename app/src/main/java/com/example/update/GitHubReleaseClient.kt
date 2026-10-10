package com.example.update

import java.io.File
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Pages `/repos/{owner}/{repo}/releases` (never `/latest`) and keeps only `findit-v*` tags.
 */
class GitHubReleaseClient(
    private val repo: String = "Strobingn/Find-It-App",
    private val token: String = "",
    private val http: OkHttpClient = OkHttpClient(),
) {
    fun latestFinditRelease(): UpdateCheckResult {
        val tokenPresent = token.isNotBlank()
        if (!tokenPresent) {
            return UpdateCheckResult.TokenMissing(
                "Update token missing. Add a fine-grained GitHub token (Contents: read, Strobingn/Find-It-App only) as the FINDIT_UPDATE_TOKEN Actions secret and rebuild.",
            )
        }
        val releases = ArrayList<FinditRelease>()
        var page = 1
        while (page <= 20) {
            val url = "https://api.github.com/repos/$repo/releases?per_page=30&page=$page"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .apply { if (tokenPresent) header("Authorization", "Bearer $token") }
                .build()
            http.newCall(request).execute().use { response ->
                AppUpdateParse.tokenErrorMessage(response.code, tokenPresent)?.let { return it }
                if (!response.isSuccessful) {
                    return UpdateCheckResult.Failed("GitHub releases HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val array = JSONArray(body)
                if (array.length() == 0) break
                for (i in 0 until array.length()) {
                    parseRelease(array.getJSONObject(i).toString())?.let { releases.add(it) }
                }
                if (array.length() < 30) break
            }
            page += 1
        }
        val newest = releases.maxByOrNull { it.versionCode }
            ?: return UpdateCheckResult.Failed("No findit-v* releases yet")
        return UpdateCheckResult.Available(newest)
    }

    fun downloadAsset(apiUrl: String, destination: File) {
        if (token.isBlank()) {
            throw IOException("Update token missing")
        }
        destination.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/octet-stream")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw IOException("Update token expired or invalid")
            }
            if (!response.isSuccessful) {
                throw IOException("APK download HTTP ${response.code}")
            }
            val bytes = response.body?.bytes() ?: throw IOException("Empty APK download")
            destination.writeBytes(bytes)
        }
    }

    companion object {
        fun parseRelease(json: String): FinditRelease? {
            val obj = org.json.JSONObject(json)
            val tag = obj.optString("tag_name")
            val code = AppUpdateParse.versionCodeFromTag(tag) ?: return null
            val body = obj.optString("body")
            val assets = obj.optJSONArray("assets") ?: return null
            var assetId = -1L
            var assetName = ""
            var assetUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    assetId = asset.optLong("id")
                    assetName = name
                    assetUrl = asset.optString("url")
                    break
                }
            }
            if (assetId <= 0L || assetUrl.isBlank()) return null
            return FinditRelease(
                tag = tag,
                versionCode = code,
                name = obj.optString("name").ifBlank { tag },
                body = body,
                apkAssetId = assetId,
                apkName = assetName,
                apkApiUrl = assetUrl,
                sha256 = AppUpdateParse.sha256FromNotes(body),
                applicationId = AppUpdateParse.appIdFromNotes(body),
            )
        }
    }
}
