package com.example.data

import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Engine vs. the standard las2dem/PDAL recipe (class-2 ground → Delaunay TIN, kill 100).
 * The synthetic cases always run; the real-tile case runs where the sample LAZ and a reference
 * raster produced by that recipe are present (FINDIT_REF_DIR, default /workspace/refdem).
 */
class ReferenceDemComparisonTest {
    @Test
    fun groundCellIsMeanNotMinimumAndUnbiased() {
        val r = LidarRasterizer(0.0, 40.0, 0.0, 40.0, LidarImportOptions(rasterResolution = 128), 40_000)
        var seed = 7L
        fun noise(): Float { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % 1000 / 1000f - 0.5f) * 0.2f }
        for (i in 0 until 200) for (j in 0 until 200) {
            val x = i * 0.2; val y = j * 0.2
            r.addPoint(x, y, (10.0 + 0.05 * x).toFloat() + noise(), classification = 2)
        }
        val g = requireNotNull(r.finish(0, "plane")).grid
        var sum = 0.0; var n = 0
        for (yy in 5 until g.height - 5) for (xx in 5 until g.width - 5) {
            val i = yy * g.width + xx
            val x = xx * 40.0 / (g.width - 1);
            sum += (g.bareEarth[i] - (10.0 + 0.05 * x)); n++
        }
        assertTrue("bias ${sum / n} must be ~0 (min picking biased it low)", abs(sum / n) < 0.02)
    }

    @Test
    fun buildingSizedVoidIsFilledSmoothlyWithoutSteps() {
        val r = LidarRasterizer(0.0, 120.0, 0.0, 120.0, LidarImportOptions(rasterResolution = 240), 60_000)
        for (i in 0..240) for (j in 0..240) {
            val x = i * 0.5; val y = j * 0.5
            if (x in 40.0..80.0 && y in 40.0..80.0) continue // 40 m building footprint
            r.addPoint(x, y, (20.0 + 0.1 * x + 0.05 * y).toFloat(), classification = 2)
        }
        val g = requireNotNull(r.finish(0, "void")).grid
        val c = g.height / 2 * g.width + g.width / 2
        assertTrue("void centre filled", g.validData[c] && g.bareEarth[c].isFinite())
        var maxSecond = 0f
        for (yy in 70 until 170) for (xx in 71 until 169) {
            val i = yy * g.width + xx
            maxSecond = maxOf(maxSecond, abs(g.bareEarth[i - 1] - 2 * g.bareEarth[i] + g.bareEarth[i + 1]))
        }
        assertTrue("fill must be smooth (max 2nd diff $maxSecond)", maxSecond < 0.05f)
    }

    @Test
    fun realTilesMatchTinReference() {
        val dir = File(System.getenv("FINDIT_REF_DIR") ?: "/workspace/refdem")
        val cases = listOf(
            "/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz" to "NY",
            "/workspace/lazdata/autzen-classified.laz" to "autzen",
        )
        assumeTrue(cases.any { File(it.first).exists() && File(dir, "ref_${it.second}_zoom.bin").exists() })
        for ((path, name) in cases) {
            val ref = File(dir, "ref_${name}_zoom.bin")
            if (!File(path).exists() || !ref.exists()) continue
            val opts = LidarImportOptions(rasterResolution = 2048, focusBounds = NormalizedRasterBounds(0.45, 0.45, 0.55, 0.55))
            val g = requireNotNull(LazTerrainReader.read(File(path), opts.sanitized())).grid
            val (rw, rh, rz) = DataInputStream(ref.inputStream().buffered()).use { s ->
                val w = s.readInt(); val h = s.readInt(); s.readFloat()
                Triple(w, h, FloatArray(w * h) { s.readFloat() })
            }
            var se = 0.0; var n = 0; var holes = 0; var refValid = 0
            for (y in 0 until g.height) for (x in 0 until g.width) {
                val rx = (x * (rw - 1).toDouble() / (g.width - 1)).toInt(); val ry = (y * (rh - 1).toDouble() / (g.height - 1)).toInt()
                val rv = rz[ry * rw + rx]; if (!rv.isFinite()) continue
                refValid++
                val i = y * g.width + x
                if (!g.validData[i] || !g.bareEarth[i].isFinite()) { holes++; continue }
                val d = g.bareEarth[i] - rv; se += d * d; n++
            }
            val rmse = sqrt(se / n)
            val holePct = 100.0 * holes / refValid
            println("REF $name zoom rmse=$rmse holes%=$holePct")
            assertTrue("$name holes $holePct%", holePct < 0.5)
            assertTrue("$name rmse $rmse", rmse < if (name == "NY") 0.12 else 0.35)
        }
    }
}
