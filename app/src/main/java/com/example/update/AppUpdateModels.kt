package com.example.update

data class FinditRelease(
    val tag: String,
    val versionCode: Int,
    val name: String,
    val body: String,
    val apkAssetId: Long,
    val apkName: String,
    val apkApiUrl: String,
    val browserDownloadUrl: String = "",
    val sha256: String?,
    val applicationId: String?,
) {
    val notesForUi: String
        get() = body.lines()
            .filter { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() &&
                    !trimmed.startsWith("app-id:", ignoreCase = true) &&
                    !trimmed.startsWith("sha256:", ignoreCase = true) &&
                    !trimmed.startsWith("version-code:", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()
}

sealed class UpdateCheckResult {
    data class Available(val release: FinditRelease) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class TokenMissing(val message: String) : UpdateCheckResult()
    data class TokenInvalid(val message: String) : UpdateCheckResult()
    data class RateLimited(val message: String) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

object AppUpdateParse {
    private val tagCode = Regex("""^findit-v(\d+)$""", RegexOption.IGNORE_CASE)
    private val shaLine = Regex("""(?im)^sha256:\s*([0-9a-f]{64})\s*$""")
    private val appIdLine = Regex("""(?im)^app-id:\s*(\S+)\s*$""")

    fun versionCodeFromTag(tag: String): Int? =
        tagCode.matchEntire(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun sha256FromNotes(body: String): String? =
        shaLine.find(body)?.groupValues?.get(1)?.lowercase()

    fun appIdFromNotes(body: String): String? =
        appIdLine.find(body)?.groupValues?.get(1)

    fun isFinditTag(tag: String): Boolean = versionCodeFromTag(tag) != null

    fun hexSha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun sha256Matches(expected: String?, actualHex: String): Boolean {
        val want = expected?.trim()?.lowercase().orEmpty()
        return want.length == 64 && want == actualHex.lowercase()
    }

    const val RATE_LIMIT_MESSAGE =
        "GitHub rate limit reached (60 unauthenticated requests/hour). Wait, or set FINDIT_UPDATE_TOKEN to raise it."

    fun isRateLimit(status: Int, body: String = "", remainingHeader: String? = null): Boolean {
        if (remainingHeader == "0" && status == 403) return true
        if (status != 403) return false
        return body.contains("rate limit", ignoreCase = true)
    }

    fun tokenErrorMessage(
        status: Int,
        tokenPresent: Boolean,
        body: String = "",
        remainingHeader: String? = null,
    ): UpdateCheckResult? = when {
        isRateLimit(status, body, remainingHeader) ->
            UpdateCheckResult.RateLimited(RATE_LIMIT_MESSAGE)
        tokenPresent && (status == 401 || status == 403) ->
            UpdateCheckResult.TokenInvalid("Update token expired or invalid")
        else -> null
    }
}
