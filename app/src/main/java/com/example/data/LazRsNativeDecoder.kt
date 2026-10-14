package com.example.data

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ABI 2: i32 source XYZ, u8 class, u8 key/withheld flags and two padding bytes. */
object LazRsNativeDecoder {
    const val RECORD_BYTES = 16
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("lazrs_jni")
        nativeAbi() == 2
    }.getOrDefault(false)

    fun open(file: File, workers: Int): Session? {
        if (!isAvailable) return null
        val handle = nativeOpen(file.absolutePath, workers.coerceIn(1, 8))
        if (handle == 0L) return null
        val batch = nativeMaxBatch(handle)
        if (batch <= 0) { nativeClose(handle); return null }
        return Session(handle, batch)
    }

    class Session internal constructor(private var handle: Long, val maxBatch: Int) : Closeable {
        @Synchronized
        fun read(start: Long, count: Int, destination: ByteBuffer): Int {
            check(handle != 0L) { "Decoder is closed" }
            require(start >= 0 && count in 1..maxBatch)
            require(destination.isDirect && destination.capacity().toLong() >= count.toLong() * RECORD_BYTES)
            destination.clear()
            destination.order(ByteOrder.LITTLE_ENDIAN)
            return nativeRead(handle, start, count, destination)
        }
        @Synchronized
        override fun close() {
            if (handle != 0L) { nativeClose(handle); handle = 0L }
        }
    }
    private external fun nativeAbi(): Int
    private external fun nativeOpen(path: String, workers: Int): Long
    private external fun nativeMaxBatch(handle: Long): Int
    private external fun nativeRead(handle: Long, start: Long, count: Int, destination: ByteBuffer): Int
    private external fun nativeClose(handle: Long)
}
