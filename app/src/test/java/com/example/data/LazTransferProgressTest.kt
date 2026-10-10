package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazTransferProgressTest {
    @Test
    fun statusLineIncludesPercentRateAndEta() {
        val line = LazTransferProgress(
            downloadedBytes = 50L * 1024L * 1024L,
            totalBytes = 100L * 1024L * 1024L,
            bytesPerSecond = (12.4 * 1024.0 * 1024.0).toLong(),
            etaSeconds = 18L,
        ).statusLine()
        assertTrue(line, line.contains("50% of 100.0 MiB"))
        assertTrue(line, line.contains("12.4 MiB/s"))
        assertTrue(line, line.contains("18s left"))
    }

    @Test
    fun unknownLengthOmitsPercent() {
        val line = LazTransferProgress(downloadedBytes = 2L * 1024L * 1024L, totalBytes = -1L).statusLine()
        assertEquals("2.0 MiB downloaded", line)
    }

    @Test
    fun speedWindowReportsThroughputAndEta() {
        val window = LazSpeedWindow(windowMs = 2_000L)
        window.update(0L, 4_000_000L, nowMs = 0L)
        val progress = window.update(2_000_000L, 4_000_000L, nowMs = 1_000L)
        assertEquals(2_000_000L, progress.bytesPerSecond)
        assertEquals(1L, progress.etaSeconds)
    }

    @Test
    fun speedWindowNeedsTwoSamples() {
        val progress = LazSpeedWindow().update(1_000L, 2_000L, nowMs = 10L)
        assertEquals(0L, progress.bytesPerSecond)
        assertNull(progress.etaSeconds)
    }
}
