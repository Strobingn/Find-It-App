package com.example.data

import android.content.Context

/**
 * LAZ decode backend. When the APK includes liblazrs_jni.so and the user has not
 * picked a backend, native laz-rs (parallel chunk decompression) is used. laszip4j
 * stays the fallback when the .so is missing or the user selects it in Tools.
 */
enum class LazDecoderBackend {
    LASZIP4J,
    NATIVE_LAZ_RS,
}

object LazDecoderSettings {
    private const val PREFS = "terrain_decoder"
    private const val KEY = "backend"

    @Volatile
    var preferred: LazDecoderBackend = LazDecoderBackend.LASZIP4J

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        preferred = if (raw == null) {
            // No explicit choice yet: use the native parallel decoder when the APK shipped it.
            if (LazRsNativeDecoder.isAvailable) LazDecoderBackend.NATIVE_LAZ_RS else LazDecoderBackend.LASZIP4J
        } else {
            runCatching { LazDecoderBackend.valueOf(raw) }.getOrDefault(LazDecoderBackend.LASZIP4J)
        }
    }

    fun persist(context: Context, backend: LazDecoderBackend) {
        preferred = backend
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, backend.name)
            .apply()
    }

    /** Resolved backend that will actually run. Never silently drops to a missing native lib. */
    fun resolve(): LazDecoderBackend {
        return if (preferred == LazDecoderBackend.NATIVE_LAZ_RS && LazRsNativeDecoder.isAvailable) {
            LazDecoderBackend.NATIVE_LAZ_RS
        } else {
            LazDecoderBackend.LASZIP4J
        }
    }
}
