package com.example.analysis.ml

import android.content.Context

/**
 * Persists the [ModelRegistry] (active model plus rollback history) in app-private shared
 * preferences, using the ranker's own versioned storage codec. Nothing here activates a model —
 * activation still happens only through an explicit [ModelRegistry.activate] call followed by
 * [save], so production ranking can never change silently across restarts either.
 */
class RankerModelStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ModelRegistry {
        val activeVersion = prefs.getString(KEY_ACTIVE_VERSION, null)
        val versions = prefs.getStringSet(KEY_VERSIONS, emptySet()).orEmpty()
        val retained = HashMap<String, ExplainableRanker>()
        for (version in versions) {
            if (version == activeVersion) continue
            val stored = prefs.getString(modelKey(version), null) ?: continue
            val model = ExplainableRanker.fromStorage(stored) ?: continue
            retained[version] = model
        }
        val active = activeVersion
            ?.let { prefs.getString(modelKey(it), null) }
            ?.let(ExplainableRanker::fromStorage)
        return ModelRegistry(activeModel = active, retained = retained)
    }

    fun save(registry: ModelRegistry) {
        val versions = registry.knownVersions
        val editor = prefs.edit()
        prefs.getStringSet(KEY_VERSIONS, emptySet()).orEmpty()
            .filterNot { it in versions }
            .forEach { editor.remove(modelKey(it)) }
        editor.putStringSet(KEY_VERSIONS, versions)
        for (version in versions) {
            registry.modelFor(version)?.let { editor.putString(modelKey(version), it.toStorage()) }
        }
        if (registry.activeVersion != null) {
            editor.putString(KEY_ACTIVE_VERSION, registry.activeVersion)
        } else {
            editor.remove(KEY_ACTIVE_VERSION)
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun modelKey(version: String): String = "$KEY_MODEL_PREFIX$version"

    companion object {
        private const val PREFS_NAME = "findit-ml-ranker"
        private const val KEY_ACTIVE_VERSION = "activeVersion"
        private const val KEY_VERSIONS = "versions"
        private const val KEY_MODEL_PREFIX = "model."
    }
}
