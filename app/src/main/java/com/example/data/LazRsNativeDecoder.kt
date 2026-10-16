package com.example.data

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNI front for the streaming laz-rs decoder (`native/lazrs-jni`, prebuilt `.so` in jniLibs).
 *
 * A [Session] decodes an explicit list of compressed chunks on native worker threads (one
 * LasZipDecompressor per worker, seeking via the chunk table). Kotlin pulls chunks in order as
 * direct ByteBuffers over native memory, bins them with [PackedPointRasterizer.addAll], then
 * releases each slot so its buffer is reused. Memory is bounded by `2 × workers` chunks.
 *
 * Packed record ([RECORD_BYTES] = 24, little endian): `f64 x, f64 y, f32 z, u8 class (raw LAS
 * byte), u8 flags (bit0 key-point, bit1 withheld), u16 pad`. Doubles keep x/y bit-identical to
 * laszip4j's `get_x()/get_y()`.
 *
 * The library is optional: if it fails to load, [isAvailable] is false and every caller uses
 * laszip4j. Any native failure during a decode also falls back to laszip4j.
 */
object LazRsNativeDecoder {
    const val RECORD_BYTES = 24

    private val bundled: Boolean = runCatching {
        System.loadLibrary("lazrs_jni")
        nativeAvailable()
    }.getOrDefault(false)

    @Volatile
    private var testLoaded = false

    val isAvailable: Boolean get() = bundled || testLoaded

    /** JVM unit tests: load a host build (`cargo build --release`) by absolute path. */
    internal fun loadForTest(path: String): Boolean {
        testLoaded = runCatching {
            System.load(path)
            nativeAvailable()
        }.getOrDefault(false)
        return testLoaded
    }

    internal fun unloadForTest() {
        testLoaded = false
    }

    /** One open file. Not thread-safe: drive it from a single (binner) thread. */
    class Session internal constructor(
        private var handle: Long,
        val pointCount: Long,
        val chunkSize: Long,
        val pointFormat: Int,
    ) : Closeable {
        private val counts = LongArray(2)

        /** Starts decoding [chunks] (indices into the LAZ chunk table) with the laszip4j filter rule. */
        fun start(chunks: IntArray, rect: DoubleArray?, stride: Int, workers: Int): Boolean {
            check(handle != 0L) { "session closed" }
            return nativeStart(handle, chunks, rect, stride.coerceAtLeast(1), workers.coerceAtLeast(1))
        }

        /**
         * Waits for [slot]. Returns the packed buffer (valid until [release]/[close]) with
         * [lastKept]/[lastRaw] set, or null on native error. Polls [shouldContinue] while waiting;
         * throws [CancellationSignal] if it turns false.
         */
        fun next(slot: Int, shouldContinue: () -> Boolean): ByteBuffer? {
            while (true) {
                if (!shouldContinue()) throw CancellationSignal()
                val buffer = nativeNext(handle, slot, counts, POLL_MS)
                if (buffer != null) return buffer.order(ByteOrder.LITTLE_ENDIAN)
                if (counts[0] != -2L) return null
            }
        }

        val lastKept: Int get() = counts[0].toInt()
        val lastRaw: Int get() = counts[1].toInt()

        fun release(slot: Int) {
            if (handle != 0L) nativeRelease(handle, slot)
        }

        fun cancel() {
            if (handle != 0L) nativeCancel(handle)
        }

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeClose(h)
        }
    }

    class CancellationSignal : RuntimeException("decode cancelled")

    /** Opens [file] for chunked decode, or null (missing lib, not LAZ, variable chunks, …). */
    fun open(file: File): Session? {
        if (!isAvailable) return null
        val header = LongArray(4)
        val handle = runCatching { nativeOpen(file.absolutePath, header) }.getOrDefault(0L)
        if (handle == 0L) return null
        return Session(handle, header[0], header[1], header[2].toInt())
    }

    private const val POLL_MS = 50

    @JvmStatic private external fun nativeAvailable(): Boolean
    @JvmStatic private external fun nativeOpen(path: String, header: LongArray): Long
    @JvmStatic private external fun nativeStart(handle: Long, chunks: IntArray, rect: DoubleArray?, stride: Int, workers: Int): Boolean
    @JvmStatic private external fun nativeNext(handle: Long, slot: Int, counts: LongArray, timeoutMs: Int): ByteBuffer?
    @JvmStatic private external fun nativeRelease(handle: Long, slot: Int)
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeClose(handle: Long)
}
