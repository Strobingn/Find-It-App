package com.example.data

import android.content.Context

/**
 * LAZ decode backend. Native laz-rs (streaming per-chunk decompression via JNI, bundled for
 * arm64-v8a and x86_64) is the default: it produces bit-identical grids to laszip4j
 * (LazRsNativeParityTest) and is faster with lower peak memory. Tools → "Toggle preferred
 * decoder" switches back to laszip4j. If the .so is missing (other ABIs) or any native decode
 * fails, laszip4j runs automatically.
 */
enum class LazDecoderBackend {
    LASZIP4J,
    NATIVE_LAZ_RS,
}

object LazDecoderSettings {
    private const val PREFS = "terrain_decoder"
    /** v2: earlier builds could persist LASZIP4J while native was never bundled; start fresh. */
    private const val KEY = "backend_v2"
    val DEFAULT = LazDecoderBackend.NATIVE_LAZ_RS

    @Volatile
    var preferred: LazDecoderBackend = DEFAULT

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        preferred = runCatching { LazDecoderBackend.valueOf(raw ?: "") }
            .getOrDefault(DEFAULT)
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
