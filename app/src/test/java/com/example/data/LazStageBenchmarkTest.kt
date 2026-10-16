package com.example.data

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Per-stage benchmark on the real NY (227 MB) and autzen tiles, same bounds for every backend.
 * Opt-in: FINDIT_BENCH=1 (and optional FINDIT_BENCH_TAG, FINDIT_BENCH_BACKEND=java|native).
 * Writes engine rasters to /workspace/refdem/eng/<tag>_<tile>_<full|zoom>.bin for cmp.py.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LazStageBenchmarkTest {
    @Test
    fun stages() {
        assumeTrue(System.getenv("FINDIT_BENCH") == "1")
        val tag = System.getenv("FINDIT_BENCH_TAG") ?: "bench"
        val backend = System.getenv("FINDIT_BENCH_BACKEND") ?: "java"
        System.getenv("FINDIT_LAZRS_LIB")?.let { LazRsNativeDecoder.loadForTest(it) }
        LazDecoderSettings.preferred = if (backend == "native") LazDecoderBackend.NATIVE_LAZ_RS else LazDecoderBackend.LASZIP4J
        val outDir = File(System.getenv("FINDIT_REF_DIR") ?: "/workspace/refdem", "eng").apply { mkdirs() }
        val files = listOf(
            "/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz" to "NY",
            "/workspace/lazdata/autzen-classified.laz" to "autzen",
        )
        val report = StringBuilder()
        for ((path, short) in files) {
            val f = File(path)
            if (!f.exists()) continue
            for ((name, opts) in listOf(
                "full" to LidarImportOptions(rasterResolution = 1024),
                "zoom" to LidarImportOptions(rasterResolution = 2048, focusBounds = NormalizedRasterBounds(0.45, 0.45, 0.55, 0.55)),
                "zoom2" to LidarImportOptions(rasterResolution = 2048, focusBounds = NormalizedRasterBounds(0.46, 0.46, 0.56, 0.56)),
            )) {
                System.gc()
                TerrainStageProfiler.reset()
                val rt = Runtime.getRuntime()
                var peak = 0L
                val run = java.util.concurrent.atomic.AtomicBoolean(true)
                val mon = Thread { while (run.get()) { peak = maxOf(peak, rt.totalMemory() - rt.freeMemory()); Thread.sleep(10) } }.apply { start() }
                val t0 = System.nanoTime()
                val r = requireNotNull(LazTerrainReader.read(f, opts.sanitized()))
                val readMs = (System.nanoTime() - t0) / 1e6
                val g = r.grid
                val h0 = System.nanoTime()
                g.renderHillshade(315f, 45f, 1f, palette = 0, contrast = 1f)
                val hsMs = (System.nanoTime() - h0) / 1e6
                run.set(false); mon.join()
                if (name != "zoom2") {
                    DataOutputStream(BufferedOutputStream(FileOutputStream(File(outDir, "${tag}_${short}_$name.bin")))).use { o ->
                        o.writeInt(g.width); o.writeInt(g.height); o.writeFloat(g.cellSizeMeters)
                        for (i in g.bareEarth.indices) o.writeFloat(if (g.validData[i]) g.bareEarth[i] else Float.NaN)
                    }
                }
                val stages = TerrainStageProfiler.snapshot().joinToString(" ") { s ->
                    val total = s.millis * (TerrainStageProfiler.count(s.name))
                    "${s.name}=${"%.0f".format(Locale.US, total)}"
                }
                val line = "BENCH $tag $backend $short $name ${g.width}x${g.height} total=${"%.0f".format(Locale.US, readMs)}ms " +
                    "$stages hillshade_cpu=${"%.0f".format(Locale.US, hsMs)} peakHeapMB=${peak / 1048576} pts=${r.totalPointsRead} " +
                    "src=${r.note.substringBefore(" · ")}"
                println(line)
                report.append(line).append('\n')
            }
        }
        File(outDir, "bench_$tag.txt").writeText(report.toString())
        LazDecoderSettings.preferred = LazDecoderBackend.LASZIP4J
    }
}
