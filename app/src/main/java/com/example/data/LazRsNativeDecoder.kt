package com.example.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI front for laz-rs (`native/lazrs-jni`). Packed record is 16 bytes, little-endian:
 * `f32 x, f32 y, f32 z, u8 class, u8 flags, u16 pad`.
 *
 * The library is optional. [isAvailable] is false until `liblazrs_jni.so` is built with
 * cargo-ndk and placed in `jniLibs`. laszip4j remains the default fallback.
 */
object LazRsNativeDecoder {
    const val RECORD_BYTES = 16

    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lazrs_jni")
        nativeAvailable()
    }.getOrDefault(false)

    /**
     * Decodes every return into [destination] (direct buffer). Returns the point count, or -1
     * if the native library is missing or the file could not be read.
     */
    fun decodePacked(file: File, destination: ByteBuffer): Int {
        if (!isAvailable) return -1
        require(destination.isDirect) { "laz-rs JNI writes a direct buffer" }
        destination.order(ByteOrder.LITTLE_ENDIAN)
        return nativeDecode(file.absolutePath, destination)
    }

    @Suppress("unused")
    private external fun nativeAvailable(): Boolean

    @Suppress("unused")
    private external fun nativeDecode(path: String, destination: ByteBuffer): Int
}
