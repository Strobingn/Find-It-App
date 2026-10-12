package com.example.data

import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Alternate hosts for the same USGS 3DEP LPC object.
 *
 * National Map listings flip between rockyweb and the public TNM S3 bucket. The file bytes are
 * the same; the closer or less-loaded host usually answers first. Racing a one-byte Range probe
 * picks that host without downloading the tile twice.
 */
object LazDownloadMirrors {
    fun looksLikeCopcUrl(url: String): Boolean =
        url.substringBefore('?').contains(".copc.laz", ignoreCase = true)

    fun candidates(url: String): List<String> {
        val trimmed = url.trim()
        val seen = LinkedHashSet<String>()
        seen += trimmed
        val parsed = trimmed.toHttpUrlOrNull() ?: return seen.toList()
        val host = parsed.host.lowercase(Locale.US)
        val path = parsed.encodedPath
        val elevationTail = path.substringAfter("/Elevation/", missingDelimiterValue = "")
        if (elevationTail.isBlank()) return seen.toList()
        when {
            host.contains("rockyweb.usgs.gov") ->
                seen += "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/$elevationTail"
            host.contains("prd-tnm.s3.amazonaws.com") ||
                (host.contains("amazonaws.com") && path.contains("/StagedProducts/Elevation/", ignoreCase = true)) ->
                seen += "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/$elevationTail"
        }
        return seen.toList()
    }
}
