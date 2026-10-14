package com.example.data

import java.io.File
import java.lang.management.ManagementFactory
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual decoder benchmark (skipped unless FINDIT_DECODER_BENCH=LASZIP4J or NATIVE_LAZ_RS).
 * Run each backend in its own JVM so peak RSS (VmHWM) is per backend:
 *   FINDIT_DECODER_BENCH=NATIVE_LAZ_RS ./gradlew :app:testDebugUnitTest --tests '*LazDecoderBenchTest*' -i
 */
class LazDecoderBenchTest {
    @Test
    fun bench() {
        val backend = System.getenv("FINDIT_DECODER_BENCH")?.let { LazDecoderBackend.valueOf(it) }
        assumeTrue(backend != null)
        val lib = File(System.getenv("FINDIT_LAZRS_HOST_LIB") ?: "../native/lazrs-jni/target/release/liblazrs_jni.so").absoluteFile
        if (backend == LazDecoderBackend.NATIVE_LAZ_RS) check(LazRsNativeDecoder.loadForTest(lib.path)) { "load $lib" }
        val files = (System.getenv("FINDIT_PARITY_LAZ") ?: "/workspace/lazdata/autzen-classified.laz:" +
            "/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz").split(':').map(::File).filter { it.isFile }
        LazDecoderSettings.preferred = backend!!
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
        for (file in files) {
            for (focus in listOf(null, NormalizedRasterBounds(0.31, 0.42, 0.47, 0.58))) {
                val options = LidarImportOptions(focusBounds = focus).sanitized()
                LazTerrainReader.read(file, options) // warm-up (JIT, page cache, chunk index)
                System.gc()
                pools.forEach { it.resetPeakUsage() }
                val times = (1..3).map {
                    val t = System.nanoTime()
                    val r = requireNotNull(LazTerrainReader.read(file, options))
                    check(backend != LazDecoderBackend.NATIVE_LAZ_RS || r.note.contains("laz-rs")) { r.note }
                    (System.nanoTime() - t) / 1e6
                }.sorted()
                val heapPeak = pools.sumOf { it.peakUsage.used } shr 20
                val hwm = File("/proc/self/status").readLines().first { it.startsWith("VmHWM") }
                println("BENCH $backend ${file.name} ${if (focus == null) "full" else "refine"} median=${"%.0f".format(times[1])}ms runs=${times.map { "%.0f".format(it) }} heapPeak=${heapPeak}MB $hwm")
            }
        }
    }
}
