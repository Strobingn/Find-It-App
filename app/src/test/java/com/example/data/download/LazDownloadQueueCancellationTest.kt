package com.example.data.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cancellation is a user decision, and the queue's job is to honor it at the right moment: a
 * waiting task stops immediately, a running task stops at its next checkpoint so its partial
 * bytes stay resumable, and a re-enqueued task must never inherit a stale cancel request.
 * Cancellation must also never corrupt the queue record - a cancelled tile is finished, not
 * failed, so the UI does not offer it as an error to retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LazDownloadQueueCancellationTest {
    private val url = "https://example.org/cancel-tile.laz"
    private val otherUrl = "https://example.org/other-tile.laz"

    @After
    fun tearDown() {
        // The queue is process-wide; leave no task or cancel flag behind for other tests.
        listOf(url, otherUrl).forEach {
            LazDownloadQueue.requestCancel(it)
            LazDownloadQueue.markFailed(it, "teardown")
            LazDownloadQueue.dismiss(it)
        }
        LazDownloadQueue.clearFinished()
    }

    @Test
    fun aQueuedTaskIsCancelledImmediately() {
        LazDownloadQueue.enqueue(url, "tile.laz")
        LazDownloadQueue.requestCancel(url)

        assertEquals(LazDownloadState.CANCELLED, LazDownloadQueue.taskFor(url)?.state)
        assertFalse(LazDownloadQueue.hasPendingWork())
    }

    /** The running transfer owns its own checkpoint; the queue only records the request. */
    @Test
    fun aRunningTaskStopsAtItsNextCheckpoint() {
        LazDownloadQueue.enqueue(url, "tile.laz")
        LazDownloadQueue.markRunning(url)
        LazDownloadQueue.requestCancel(url)

        assertEquals(LazDownloadState.RUNNING, LazDownloadQueue.taskFor(url)?.state)
        assertTrue(LazDownloadQueue.isCancelled(url))

        // The transfer notices the flag between buffer writes and aborts itself.
        LazDownloadQueue.markFailed(url, "stopped")
        val task = LazDownloadQueue.taskFor(url)
        assertEquals(LazDownloadState.CANCELLED, task?.state)
        assertNull("cancellation must not surface as an error", task?.error)
    }

    @Test
    fun aCancelledTaskCanBeReEnqueuedWithoutInheritingTheCancelFlag() {
        LazDownloadQueue.enqueue(url, "tile.laz")
        LazDownloadQueue.requestCancel(url)

        assertTrue(LazDownloadQueue.enqueue(url, "tile.laz"))

        assertEquals(LazDownloadState.QUEUED, LazDownloadQueue.taskFor(url)?.state)
        assertFalse("a stale cancel request must not kill the retry", LazDownloadQueue.isCancelled(url))
    }

    @Test
    fun dismissingACancelledTaskClearsTheCancelFlag() {
        LazDownloadQueue.enqueue(url, "tile.laz")
        LazDownloadQueue.requestCancel(url)
        LazDownloadQueue.dismiss(url)

        LazDownloadQueue.enqueue(url, "tile.laz")
        assertFalse(LazDownloadQueue.isCancelled(url))
    }

    @Test
    fun theSchedulerSkipsCancelledTasks() {
        LazDownloadQueue.enqueue(url, "tile.laz")
        LazDownloadQueue.requestCancel(url)
        LazDownloadQueue.enqueue(otherUrl, "other.laz")

        assertEquals(otherUrl, LazDownloadQueue.nextQueued()?.url)
    }
}
