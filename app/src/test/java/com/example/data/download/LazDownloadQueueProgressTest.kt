package com.example.data.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LazDownloadQueueProgressTest {
    private val url = "https://example.org/fast.laz"

    @After
    fun tearDown() {
        LazDownloadQueue.requestCancel(url)
        LazDownloadQueue.markFailed(url, "teardown")
        LazDownloadQueue.dismiss(url)
        LazDownloadQueue.clearFinished()
    }

    @Test
    fun progressKeepsSpeedAndEtaForTheUi() {
        LazDownloadQueue.enqueue(url, "fast.laz")
        LazDownloadQueue.markRunning(url)
        LazDownloadQueue.markProgress(
            url = url,
            downloaded = 50L * 1024L * 1024L,
            total = 100L * 1024L * 1024L,
            bytesPerSecond = 5L * 1024L * 1024L,
            etaSeconds = 10L,
        )
        val task = LazDownloadQueue.taskFor(url)!!
        assertEquals(5L * 1024L * 1024L, task.bytesPerSecond)
        assertEquals(10L, task.etaSeconds)
        assertTrue(task.statusLine(), task.statusLine().contains("5.0 MiB/s"))
        assertTrue(task.statusLine(), task.statusLine().contains("10s left"))
    }

    @Test
    fun aDetectedCopcFileCanStreamWhileDownloading() {
        LazDownloadQueue.enqueue(url, "area.copc.laz")
        LazDownloadQueue.markStreamable(url)
        assertTrue(LazDownloadQueue.taskFor(url)!!.canStreamWhileDownloading)
    }
}
