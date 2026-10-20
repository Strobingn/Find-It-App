package com.example.data.download

import com.example.data.LazDownloadManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A user cancel during the EPT rebuild is a cancel, never "EPT unavailable" + fallback downloads. */
class EptCancelTest {
    private val nyUrl = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/" +
        "NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"
    private val eptJson = """{"srs":{"horizontal":"3857"},"bounds":[-8388608,5033164.8,0,-8126464,5295308.8,262144]}"""

    private fun header(): ByteArray {
        val w = "PROJCS[\"NAD83(2011) / UTM zone 18N\",UNIT[\"metre\",1]]".toByteArray()
        val size = 375 + 54 + w.size
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("LASF".toByteArray()); bb.put(24, 1); bb.put(25, 4)
        bb.putShort(94, 375); bb.putInt(96, size); bb.putInt(100, 1)
        bb.putDouble(179, 519000.0); bb.putDouble(187, 517500.0); bb.putDouble(195, 4651500.0); bb.putDouble(203, 4650000.0)
        bb.putLong(247, 1000)
        bb.position(375 + 2); bb.put("LASF_Projection".toByteArray())
        bb.putShort(375 + 18, 2112); bb.putShort(375 + 20, w.size.toShort())
        bb.position(375 + 54); bb.put(w)
        return bb.array()
    }

    /** Cancels (flips [running]) as soon as the first EPT data node is fetched. */
    private fun fetch(running: AtomicBoolean, calls: AtomicInteger): (String, LongRange?) -> ByteArray? {
        val hdr = header()
        return { url, range ->
            calls.incrementAndGet()
            when {
                url.endsWith("NY_SE4County_1_A22/ept.json") -> eptJson.toByteArray()
                url.endsWith("ept.json") -> null
                // Counts stay inside [header point count, 3x] so the hierarchy gate still downloads
                // both nodes; cancel is observed on the second node fetch.
                url.contains("ept-hierarchy/0-0-0-0") -> """{"0-0-0-0":1000,"1-0-0-0":3}""".toByteArray()
                url.contains("ept-data/") -> { running.set(false); Thread.sleep(50); ByteArray(10) }
                range != null -> hdr.copyOfRange(range.first.toInt(), minOf(hdr.size, range.last.toInt() + 1))
                else -> null
            }
        }
    }

    private fun builder(running: AtomicBoolean, calls: AtomicInteger) =
        EptTileBuilder(1, fetch(running, calls), { _, _, out, _ -> out.writeBytes(ByteArray(4)); longArrayOf(1000, 1000) }, { true })

    @Test
    fun cancelDuringNodeDownloadThrowsCancellation() {
        val running = AtomicBoolean(true)
        val err = runCatching {
            builder(running, AtomicInteger()).build(nyUrl, Files.createTempDirectory("c").toFile(), null, { running.get() })
        }.exceptionOrNull()
        assertTrue("got $err", err is CancellationException)
    }

    @Test
    fun alreadyCancelledBuildDoesNoNetwork() {
        val calls = AtomicInteger()
        val err = runCatching {
            builder(AtomicBoolean(true), calls).build(nyUrl, Files.createTempDirectory("c").toFile(), null, { false })
        }.exceptionOrNull()
        assertTrue(err is CancellationException)
        assertEquals(0, calls.get())
    }

    @Test
    fun managerPropagatesCancelWithoutFallbackNotice() {
        val running = AtomicBoolean(true)
        val calls = AtomicInteger()
        val notices = mutableListOf<String>()
        val err = runCatching {
            LazDownloadManager(parallelConnections = 1, mirrorResolver = null, eptBuilder = builder(running, calls), openTopography = null)
                .download(
                    nyUrl, Files.createTempDirectory("m").toFile(),
                    shouldContinue = { running.get() },
                    source = LazDownloadSource.AMAZON,
                    onNotice = { notices += it },
                )
        }.exceptionOrNull()
        assertTrue("got $err", err is CancellationException)
        assertTrue("notices $notices", notices.isEmpty())
    }
}
