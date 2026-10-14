package com.example.data

import android.content.Context

/**
 * LAZ decode backend. Default stays laszip4j until the owner compares both on the same file
 * (Tools → Decoder benchmark). Native laz-rs (parallel chunk decompression via JNI direct
 * buffers) is used only when the .so is present **and** the preference is NATIVE_LAZ_RS.
 */
enum class LazDecoderBackend {
    LASZIP4J,
    NATIVE_LAZ_RS,
}

object LazDecoderSettings {
    private const val PREFS = "terrain_decoder"
    private const val KEY = "backend"

    @Volatile
    var terrainTileCells: Int = 512

    @Volatile
    var preferred: LazDecoderBackend = LazDecoderBackend.LASZIP4J

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        terrainTileCells=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getInt("terrain_tile_cells",if(AppMemoryBudget.isLowMemory) 256 else 512).takeIf { it==256 || it==512 } ?: 512
        val workers=context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("workers",0)
        AppMemoryBudget.calibratedDecodeWorkers=workers.takeIf { it in 1..8 }
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

    fun applyMeasured(context: Context, backend: LazDecoderBackend, workers: Int, tileCells: Int = 512) {
        require(tileCells==256 || tileCells==512)
        terrainTileCells=tileCells
        persist(context,backend)
        AppMemoryBudget.calibratedDecodeWorkers=workers.coerceIn(1,8)
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putInt("workers",workers.coerceIn(1,8)).putInt("terrain_tile_cells",tileCells).apply()
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
