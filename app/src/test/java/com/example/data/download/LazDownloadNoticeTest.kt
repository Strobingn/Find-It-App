package com.example.data.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The source-fallback notice is per tile and cleared when that tile is retried or dismissed. */
class LazDownloadNoticeTest {
    @After fun reset() = LazDownloadQueue.postNotice(null)

    @Test
    fun retryClearsOnlyItsOwnNotice() {
        LazDownloadQueue.postNotice("https://x/a.laz", "A fell back")
        LazDownloadQueue.postNotice("https://x/b.laz", "B fell back")
        assertEquals("B fell back", LazDownloadQueue.notice.value)
        LazDownloadQueue.enqueue("https://x/b.laz", "b")
        LazDownloadQueue.markRunning("https://x/b.laz")
        assertEquals("A fell back", LazDownloadQueue.notice.value)
        LazDownloadQueue.clearNotice("https://x/a.laz")
        assertNull(LazDownloadQueue.notice.value)
        LazDownloadQueue.requestCancel("https://x/b.laz")
        LazDownloadQueue.markFailed("https://x/b.laz", "x")
        LazDownloadQueue.dismiss("https://x/b.laz")
    }

    @Test
    fun dismissAndPickerChangeClear() {
        LazDownloadQueue.postNotice("https://x/c.laz", "C fell back")
        LazDownloadQueue.dismiss("https://x/c.laz")
        assertNull(LazDownloadQueue.notice.value)
        LazDownloadQueue.postNotice("https://x/d.laz", "D fell back")
        LazDownloadQueue.postNotice(null)
        assertNull(LazDownloadQueue.notice.value)
    }
}
