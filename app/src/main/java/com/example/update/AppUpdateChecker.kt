package com.example.update

import android.content.Context
import com.example.BuildConfig

object AppUpdateChecker {
    /** The UI always resolves to available / up to date / error within this budget. */
    const val CHECK_TIMEOUT_MS = 15_000L

    /** e.g. "Build 1000685 (findit-v1000685)"; the name is dropped when blank or redundant. */
    fun buildLabel(versionName: String?, versionCode: Int): String {
        val name = versionName?.trim().orEmpty()
        return if (name.isEmpty() || name == versionCode.toString()) "Build $versionCode" else "Build $versionCode ($name)"
    }

    fun installedBuildLabel(): String = buildLabel(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    fun client(
        token: String = BuildConfig.FINDIT_UPDATE_TOKEN.trim(),
        repo: String = BuildConfig.FINDIT_UPDATE_REPO.trim().ifBlank { "Strobingn/Find-It-App" },
    ): GitHubReleaseClient = GitHubReleaseClient(repo = repo, token = token)

    fun check(
        installedVersionCode: Int = BuildConfig.VERSION_CODE,
        client: GitHubReleaseClient = client(),
    ): UpdateCheckResult {
        return runCatching {
            when (val latest = client.latestFinditRelease(BuildConfig.APPLICATION_ID)) {
                is UpdateCheckResult.Available -> {
                    if (latest.release.versionCode > installedVersionCode) latest
                    else UpdateCheckResult.UpToDate
                }
                else -> latest
            }
        }.getOrElse { error ->
            UpdateCheckResult.Failed(
                "Update check failed: " + (error.message ?: error.javaClass.simpleName),
            )
        }
    }

    fun statusLabel(result: UpdateCheckResult): String = when (result) {
        is UpdateCheckResult.Available ->
            "Update ${result.release.name} (versionCode ${result.release.versionCode}) is ready"
        UpdateCheckResult.UpToDate -> "You are on the latest find it 2 build"
        is UpdateCheckResult.TokenMissing -> result.message
        is UpdateCheckResult.TokenInvalid -> result.message
        is UpdateCheckResult.RateLimited -> result.message
        is UpdateCheckResult.Failed -> result.message
    }

    fun cacheApk(context: Context): java.io.File =
        java.io.File(context.cacheDir, "updates/findit-update.apk")
}
