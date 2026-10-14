package com.example.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI front for laz-rs (`native/lazrs-jni`). Packed record is 16 bytes, little-endian:
 * `f32 dx, f32 dy, f32 dz` relative to a double-precision origin, then `u8 class, u8 flags, u16 pad`.
 *
 * The library is optional. [isAvailable] is false until `liblazrs_jni.so` is built with
 * cargo-ndk and placed in `jniLibs`. laszip4j remains the default fallback.
 */
object LazRsNativeDecoder {
    const val RECORD_BYTES = 16

    @Volatile
    var continueCheck: () -> Boolean = { true }

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lazrs_jni")
        nativeAvailable()
    }.getOrDefault(false)

    @JvmStatic
    fun isCancelled(): Boolean = !continueCheck() || Thread.currentThread().isInterrupted

    /**
     * Decodes returns into [destination] (direct buffer) as origin-relative f32.
     * Writes the reconstruction origin into [origins] (`x, y, z`).
     * Returns the point count, or -1 if the native library is missing or the file could not be read.
     */
    @Synchronized
    fun decodePacked(
        file: File,
        destination: ByteBuffer,
        origins: DoubleArray = DoubleArray(3),
        shouldContinue: () -> Boolean = { true },
    ): Int {
        if (!isAvailable) return -1
        require(destination.isDirect) { "laz-rs JNI writes a direct buffer" }
        require(origins.size >= 3) { "origins must hold x, y, z" }
        destination.order(ByteOrder.LITTLE_ENDIAN)
        val previous = continueCheck
        continueCheck = shouldContinue
        return try {
            nativeDecode(file.absolutePath, destination, origins)
        } finally {
            continueCheck = previous
        }
    }

    @Suppress("unused")
    private external fun nativeAvailable(): Boolean

    @Suppress("unused")
    private external fun nativeDecode(path: String, destination: ByteBuffer, origins: DoubleArray): Int
}
