package com.example.data

import android.content.Context
import com.example.BuildConfig

/** Device or build-time myOpenTopo key used only when a cropped LAZ transfer starts. */
object OpenTopographyApiKey {
    private const val PREFS_NAME = "opentopography_credentials"
    private const val PREF_API_KEY = "api_key"

    fun load(context: Context): String {
        val device = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_API_KEY, null)
        return sanitize(device).ifBlank { sanitize(BuildConfig.OPENTOPOGRAPHY_API_KEY) }
    }

    fun hasDeviceKey(context: Context): Boolean =
        sanitize(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_API_KEY, null),
        ).isNotBlank()

    fun isConfigured(context: Context): Boolean = load(context).isNotBlank()

    fun save(context: Context, value: String): Boolean {
        val cleaned = sanitize(value)
        if (cleaned.isBlank()) return false
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_API_KEY, cleaned)
            .apply()
        return true
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_API_KEY)
            .apply()
    }

    internal fun sanitize(value: String?): String {
        val cleaned = value?.trim().orEmpty()
        val upper = cleaned.uppercase()
        if (cleaned.isBlank()) return ""
        if (upper.startsWith("YOUR_") || upper.startsWith("MY_") || upper.contains("PLACEHOLDER")) {
            return ""
        }
        return cleaned
    }
}
