package com.example.data.download

import android.content.Context

/** Where LAZ tile bytes come from. Chosen on the LAZ download screen and persisted. */
enum class LazDownloadSource(val label: String, val description: String) {
    AUTO("Auto", "Amazon EPT first, then the fast mirror, then USGS rockyweb"),
    AMAZON("Amazon", "Fast USGS 3DEP EPT rebuild from usgs-lidar-public (S3)"),
    USGS("USGS", "Original LAZ file from USGS rockyweb"),
    OPENTOPOGRAPHY("OpenTopography", "3DEP survey found via the OpenTopography catalog, streamed from Amazon EPT"),
    ;

    /** One attempt in the download chain. */
    enum class Step { EPT, OPENTOPOGRAPHY, MIRROR, ORIGINAL }

    /** Steps tried in order; the last one's failure is the download's failure. */
    val steps: List<Step>
        get() = when (this) {
            // OpenTopography yields the same 3DEP points (equal density, exact point-count check),
            // so Auto ranks it right after the URL-derived EPT lookup, ahead of the mirror.
            AUTO -> listOf(Step.EPT, Step.OPENTOPOGRAPHY, Step.MIRROR, Step.ORIGINAL)
            // Amazon is EPT only; if EPT cannot serve the tile we fall back (with a notice) rather
            // than failing the download outright.
            AMAZON -> listOf(Step.EPT, Step.MIRROR, Step.ORIGINAL)
            USGS -> listOf(Step.ORIGINAL)
            OPENTOPOGRAPHY -> listOf(Step.OPENTOPOGRAPHY, Step.MIRROR, Step.ORIGINAL)
        }

    /** Message shown when the chosen source could not serve the tile and a fallback was used. */
    fun fallbackNotice(fileName: String, reason: String?): String? = when (this) {
        AMAZON -> "Amazon (EPT) is unavailable for $fileName" +
            (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            "; downloading the original USGS LAZ instead."
        OPENTOPOGRAPHY -> "OpenTopography has no matching 3DEP point cloud for $fileName" +
            (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") +
            "; downloading the original USGS LAZ instead."
        else -> null
    }

    companion object {
        val DEFAULT = AUTO
        fun fromName(name: String?): LazDownloadSource =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Persists the picker choice (SharedPreferences). */
object LazDownloadSourcePrefs {
    private const val PREFS = "laz_download_source"
    private const val KEY = "source"

    fun load(context: Context): LazDownloadSource = LazDownloadSource.fromName(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null),
    )

    fun save(context: Context, source: LazDownloadSource) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, source.name).apply()
    }
}
