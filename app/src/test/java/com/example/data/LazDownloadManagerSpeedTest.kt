package com.example.data

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Measures the old single-stream path against parallel Range workers on a bandwidth-limited
 * host. The mock throttles each connection independently, which is the same bottleneck USGS
 * and TNM put on one HTTP/1.1 GET.
 */
class LazDownloadManagerSpeedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun planSegmentsCoverTheWholeFileWithoutGaps() {
        val manager = LazDownloadManager(allowPrivateHosts = true, maxParallelSegments = 4)
        val ranges = manager.planSegments(1_000L, maxSegments = 4)
        assertEquals(0L, ranges.first().start)
        assertEquals(999L, ranges.last().endInclusive)
        ranges.zipWithNext { a, b -> assertEquals(a.endInclusive + 1L, b.start) }
        assertEquals(1_000L, ranges.sumOf { it.size })
    }

    @Test
    fun parallelRangesReassembleTheExactPayload() {
        val payload = lasPayload(128 * 1024)
        server.dispatcher = ThrottledRangeDispatcher(payload, bytesPerPeriod = payload.size, periodMs = 1L)
        server.start()

        val file = testManager(maxSegments = 4).download(
            sourceUrl = server.url("/tile.laz").toString(),
            destinationDirectory = folder.newFolder(),
        )
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun parallelDownloadIsFasterThanASingleStream() {
        val payload = lasPayload(128 * 1024)
        // 8 KiB every 25 ms ≈ 320 KiB/s per connection. Four workers should finish near 4× sooner.
        server.dispatcher = ThrottledRangeDispatcher(payload, bytesPerPeriod = 8 * 1024, periodMs = 25L)
        server.start()
        val url = server.url("/tile.laz").toString()

        val sequentialMs = timed {
            testManager(maxSegments = 1).download(url, folder.newFolder("seq"))
        }
        val parallelMs = timed {
            testManager(maxSegments = 4).download(url, folder.newFolder("par"))
        }

        System.out.println("LAZ download speed: sequential=${sequentialMs}ms parallel=${parallelMs}ms")
        assertTrue(
            "parallel ($parallelMs ms) should beat a single stream ($sequentialMs ms)",
            parallelMs * 3L < sequentialMs * 2L,
        )
    }

    @Test
    fun progressReportsSpeedAndEta() {
        val payload = lasPayload(64 * 1024)
        server.dispatcher = ThrottledRangeDispatcher(payload, bytesPerPeriod = 8 * 1024, periodMs = 20L)
        server.start()
        val seen = mutableListOf<LazTransferProgress>()
        testManager(maxSegments = 2).download(
            sourceUrl = server.url("/tile.laz").toString(),
            destinationDirectory = folder.newFolder(),
            progress = { seen += it },
        )
        assertTrue(seen.any { it.totalBytes == payload.size.toLong() })
        assertTrue(seen.any { it.downloadedBytes > 0L })
        assertTrue(
            "a throttled transfer must publish a non-zero rate so the UI can show MiB/s and ETA",
            seen.any { it.bytesPerSecond > 0L },
        )
    }

    @Test
    fun aCopcUrlSignalsThatDecodeCanStartImmediately() {
        val payload = lasPayload(8 * 1024)
        server.dispatcher = ThrottledRangeDispatcher(payload, bytesPerPeriod = payload.size, periodMs = 1L)
        server.start()
        var detected = false
        testManager(maxSegments = 1).download(
            sourceUrl = server.url("/area.copc.laz").toString(),
            destinationDirectory = folder.newFolder(),
            onCopcDetected = { detected = true },
        )
        assertTrue(detected)
    }

    @Test
    fun privateHostsAreRejectedByDefault() {
        server.dispatcher = ThrottledRangeDispatcher(lasPayload(32), bytesPerPeriod = 32, periodMs = 1L)
        server.start()
        val manager = LazDownloadManager()
        assertThrows(IllegalArgumentException::class.java) {
            manager.download(server.url("/tile.laz").toString(), folder.newFolder())
        }
    }

    @Test
    fun leftoverPartialFileResumesInsteadOfRestarting() {
        val payload = lasPayload(48 * 1024)
        server.dispatcher = ThrottledRangeDispatcher(payload, bytesPerPeriod = payload.size, periodMs = 1L)
        server.start()
        val destDir = folder.newFolder()
        val partial = java.io.File(destDir, "tile.laz.part")
        partial.writeBytes(payload.copyOfRange(0, 10 * 1024))

        val file = testManager(maxSegments = 4).download(
            sourceUrl = server.url("/tile.laz").toString(),
            destinationDirectory = destDir,
        )
        assertArrayEquals(payload, file.readBytes())
    }

    private fun testManager(maxSegments: Int) = LazDownloadManager(
        allowPrivateHosts = true,
        maxParallelSegments = maxSegments,
        minParallelBytes = 16 * 1024L,
        targetSegmentBytes = 32 * 1024L,
    )

    private fun lasPayload(size: Int): ByteArray {
        val bytes = ByteArray(size)
        byteArrayOf(0x4C, 0x41, 0x53, 0x46).copyInto(bytes)
        for (index in 4 until size) bytes[index] = (index % 251).toByte()
        return bytes
    }

    private fun timed(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    private class ThrottledRangeDispatcher(
        private val payload: ByteArray,
        private val bytesPerPeriod: Int,
        private val periodMs: Long,
    ) : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.method == "HEAD") {
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Length", payload.size.toString())
            }
            val range = request.getHeader("Range")
            val (start, end) = parseRange(range, payload.size)
            val slice = payload.copyOfRange(start, end + 1)
            val response = MockResponse()
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Length", slice.size.toString())
                .setBody(Buffer().write(slice))
                .throttleBody(bytesPerPeriod.toLong(), periodMs, TimeUnit.MILLISECONDS)
            return if (range != null) {
                response
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
            } else {
                response.setResponseCode(200)
            }
        }

        private fun parseRange(header: String?, size: Int): Pair<Int, Int> {
            if (header.isNullOrBlank()) return 0 to size - 1
            val spec = header.removePrefix("bytes=")
            val start = spec.substringBefore('-').toInt()
            val endToken = spec.substringAfter('-')
            val end = if (endToken.isBlank()) size - 1 else endToken.toInt()
            return start to end.coerceAtMost(size - 1)
        }
    }
}
