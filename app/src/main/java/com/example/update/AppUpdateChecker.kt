package com.example.update

import android.content.Context
import com.example.BuildConfig

object AppUpdateChecker {
    fun client(
        token: String = BuildConfig.FINDIT_UPDATE_TOKEN.trim(),
        repo: String = BuildConfig.FINDIT_UPDATE_REPO.trim().ifBlank { "Strobingn/Find-It-App" },
    ): GitHubReleaseClient = GitHubReleaseClient(repo = repo, token = token)

    fun check(
        installedVersionCode: Int = BuildConfig.VERSION_CODE,
        client: GitHubReleaseClient = client(),
    ): UpdateCheckResult {
        return when (val latest = client.latestFinditRelease()) {
            is UpdateCheckResult.Available -> {
                if (latest.release.versionCode > installedVersionCode) latest
                else UpdateCheckResult.UpToDate
            }
            else -> latest
        }
    }

    fun statusLabel(result: UpdateCheckResult): String = when (result) {
        is UpdateCheckResult.Available ->
            "Update ${result.release.name} (versionCode ${result.release.versionCode}) is ready"
        UpdateCheckResult.UpToDate -> "You are on the latest Find It build"
        is UpdateCheckResult.TokenMissing -> result.message
        is UpdateCheckResult.TokenInvalid -> result.message
        is UpdateCheckResult.RateLimited -> result.message
        is UpdateCheckResult.Failed -> result.message
    }

    fun cacheApk(context: Context): java.io.File =
        java.io.File(context.cacheDir, "updates/findit-update.apk")
}
