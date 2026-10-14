package com.example.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The streaming laz-rs decoder must produce the *identical* terrain to laszip4j: same cells,
 * bit-for-bit elevations (bare earth + canopy), same valid/interpolated masks, same point and
 * ground counts, for full decodes and chunk-index viewport refines.
 *
 * Needs the host build of the JNI crate (`cd native/lazrs-jni && cargo build --release`) and real
 * LAZ samples; set FINDIT_LAZRS_HOST_LIB / FINDIT_PARITY_LAZ (colon separated) to override.
 * Skipped when either is missing (e.g. CI without Rust).
 */
class LazRsNativeParityTest {
    private val hostLib = File(
        System.getenv("FINDIT_LAZRS_HOST_LIB") ?: "../native/lazrs-jni/target/release/liblazrs_jni.so",
    ).absoluteFile
    private val samples = (
        System.getenv("FINDIT_PARITY_LAZ")
            ?: "/workspace/lazdata/autzen-classified.laz:" +
            "/workspace/lazdata/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"
        ).split(':').map(::File).filter { it.isFile }

    private var previous = LazDecoderBackend.LASZIP4J

    @Before
    fun setUp() {
        assumeTrue("host laz-rs lib missing: $hostLib", hostLib.isFile)
        assumeTrue("no LAZ samples", samples.isNotEmpty())
        assumeTrue("could not load $hostLib", LazRsNativeDecoder.loadForTest(hostLib.path))
        previous = LazDecoderSettings.preferred
    }

    @After
    fun tearDown() {
        LazDecoderSettings.preferred = previous
        LazRsNativeDecoder.unloadForTest()
    }

    private fun decode(file: File, backend: LazDecoderBackend, options: LidarImportOptions): DemGenerator.LasLoadResult {
        LazDecoderSettings.preferred = backend
        val rt = Runtime.getRuntime()
        System.gc()
        val start = System.nanoTime()
        val result = requireNotNull(LazTerrainReader.read(file, options)) { "$backend decoded nothing" }
        val ms = (System.nanoTime() - start) / 1e6
        println("parity ${file.name} $backend ${"%.0f".format(ms)} ms heapUsed=${(rt.totalMemory() - rt.freeMemory()) shr 20} MB · ${result.note.substringBefore(" · ")}")
        return result
    }

    private fun assertIdentical(label: String, java: DemGenerator.LasLoadResult, native: DemGenerator.LasLoadResult) {
        assertTrue("$label: native path did not run: ${native.note}", native.note.contains("laz-rs"))
        assertTrue("$label: java path used native: ${java.note}", !java.note.contains("laz-rs"))
        val a = java.grid
        val b = native.grid
        assertEquals("$label width", a.width, b.width)
        assertEquals("$label height", a.height, b.height)
        assertEquals("$label points read", java.totalPointsRead, native.totalPointsRead)
        assertEquals("$label ground points", java.groundPointsUsed, native.groundPointsUsed)
        assertEquals("$label sampled", java.sampledPoints, native.sampledPoints)
        assertEquals("$label truncated", java.wasTruncated, native.wasTruncated)
        assertEquals("$label ground mode", java.appliedGroundMode, native.appliedGroundMode)
        assertEquals("$label ground report", java.groundReport, native.groundReport)
        for (i in a.bareEarth.indices) {
            if (a.bareEarth[i].toRawBits() != b.bareEarth[i].toRawBits() ||
                a.canopySpikes[i].toRawBits() != b.canopySpikes[i].toRawBits() ||
                a.validData[i] != b.validData[i] || a.interpolatedData[i] != b.interpolatedData[i]
            ) {
                throw AssertionError("$label: cell $i differs: java=${a.bareEarth[i]}/${a.canopySpikes[i]} native=${b.bareEarth[i]}/${b.canopySpikes[i]}")
            }
        }
    }

    @Test
    fun fullDecodeIsIdentical() {
        for (file in samples) {
            val options = LidarImportOptions().sanitized()
            val java = decode(file, LazDecoderBackend.LASZIP4J, options)
            val native = decode(file, LazDecoderBackend.NATIVE_LAZ_RS, options)
            assertIdentical("${file.name} full", java, native)
        }
    }

    @Test
    fun viewportRefineThroughChunkIndexIsIdentical() {
        for (file in samples) {
            // A full pass persists the chunk index the refine path selects chunks from.
            decode(file, LazDecoderBackend.LASZIP4J, LidarImportOptions().sanitized())
            val options = LidarImportOptions(
                focusBounds = NormalizedRasterBounds(left = 0.31, top = 0.42, right = 0.47, bottom = 0.58),
            ).sanitized()
            assertTrue("${file.name}: chunk index missing", LazTerrainReader.indexedFocusPlan(file, options) != null)
            val java = decode(file, LazDecoderBackend.LASZIP4J, options)
            val native = decode(file, LazDecoderBackend.NATIVE_LAZ_RS, options)
            assertTrue("refine used chunk index: ${native.note}", native.note.contains("chunk-index refine"))
            assertIdentical("${file.name} refine", java, native)
        }
    }

    @Test
    fun nativeFullPassWritesTheSameChunkIndex() {
        val source = samples.first()
        val dir = kotlin.io.path.createTempDirectory("lazrs-index").toFile()
        try {
            val a = source.copyTo(File(dir, "java-${source.name}"))
            val b = source.copyTo(File(dir, "native-${source.name}"))
            val java = decode(a, LazDecoderBackend.LASZIP4J, LidarImportOptions().sanitized())
            val native = decode(b, LazDecoderBackend.NATIVE_LAZ_RS, LidarImportOptions().sanitized())
            assertIdentical("index build", java, native)
            val ia = dir.listFiles()!!.single { it.name.startsWith("java-") && it.name != a.name }
            val ib = dir.listFiles()!!.single { it.name.startsWith("native-") && it.name != b.name }
            // Bytes 8..24 hold the source length + mtime (copies differ in mtime); the rest is the index.
            val ba = ia.readBytes()
            val bb = ib.readBytes()
            assertEquals(ba.size, bb.size)
            assertTrue("chunk index sidecars differ", ba.copyOfRange(24, ba.size).contentEquals(bb.copyOfRange(24, bb.size)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun cancellationStopsNativeDecodeAndTruncates() {
        val file = samples.first()
        LazDecoderSettings.preferred = LazDecoderBackend.NATIVE_LAZ_RS
        var calls = 0
        val result = LazTerrainReader.read(file, LidarImportOptions().sanitized(), shouldContinue = { ++calls < 5 })
        if (result != null) assertTrue("cancelled decode must be marked truncated", result.wasTruncated)
    }

    @Test
    fun brokenFileFallsBackToLaszip4j() {
        val bad = File.createTempFile("not-laz", ".laz").apply { writeText("not a laz file") }
        try {
            LazDecoderSettings.preferred = LazDecoderBackend.NATIVE_LAZ_RS
            assertEquals(null, LazRsNativeDecoder.open(bad))
            // Reader must not throw; laszip4j also rejects it.
            LazTerrainReader.read(bad, LidarImportOptions().sanitized())
        } finally {
            bad.delete()
        }
    }
}
