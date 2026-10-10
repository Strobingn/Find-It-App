package com.example.data

import android.content.Context

/**
 * LAZ decode backend. Default stays laszip4j until the owner compares both on the same file
 * (Tools → Decoder benchmark). Native laz-rs is used only when the .so is present **and** the
 * preference is NATIVE_LAZ_RS.
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
        preferred = runCatching { LazDecoderBackend.valueOf(raw ?: "") }
            .getOrDefault(LazDecoderBackend.LASZIP4J)
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
