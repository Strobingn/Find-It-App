package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSyncQueueTest {
    private fun FieldSyncQueue.enqueueTarget(
        entityId: String,
        operation: SyncOperation,
        payload: String = "{}",
        atMillis: Long,
    ): FieldSyncQueue = enqueue(SyncEntityType.TARGET_SIGNAL, entityId, operation, payload, atMillis)

    @Test
    fun repeatedUpsertsCoalesceToLatestState() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.UPSERT, payload = "{\"note\":\"first\"}", atMillis = 1_000L)
            .enqueueTarget("t1", SyncOperation.UPSERT, payload = "{\"note\":\"latest\"}", atMillis = 2_000L)

        assertEquals(1, queue.pendingCount)
        val pending = queue.pendingFor(SyncEntityType.TARGET_SIGNAL, "t1")
        assertEquals("{\"note\":\"latest\"}", pending?.payload)
        assertEquals(SyncOperation.UPSERT, pending?.operation)
    }

    @Test
    fun deleteDiscardsPendingUpsert() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.UPSERT, atMillis = 1_000L)
            .enqueueTarget("t1", SyncOperation.DELETE, atMillis = 2_000L)

        assertEquals(1, queue.pendingCount)
        assertEquals(
            SyncOperation.DELETE,
            queue.pendingFor(SyncEntityType.TARGET_SIGNAL, "t1")?.operation,
        )
    }

    @Test
    fun upsertAfterDeleteRestoresEntityWithLatestState() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.DELETE, atMillis = 1_000L)
            .enqueueTarget("t1", SyncOperation.UPSERT, payload = "{\"note\":\"recreated\"}", atMillis = 2_000L)

        assertEquals(1, queue.pendingCount)
        val pending = queue.pendingFor(SyncEntityType.TARGET_SIGNAL, "t1")
        assertEquals(SyncOperation.UPSERT, pending?.operation)
        assertEquals("{\"note\":\"recreated\"}", pending?.payload)
    }

    @Test
    fun differentEntitiesAndTypesStaySeparate() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.UPSERT, atMillis = 1_000L)
            .enqueue(SyncEntityType.EXCAVATION_LOG, "t1", SyncOperation.UPSERT, "{}", 2_000L)
            .enqueueTarget("t2", SyncOperation.UPSERT, atMillis = 3_000L)

        assertEquals(3, queue.pendingCount)
    }

    @Test
    fun batchesReplayInQueueOrder() {
        val queue = FieldSyncQueue()
            .enqueueTarget("late", SyncOperation.UPSERT, atMillis = 3_000L)
            .enqueueTarget("early", SyncOperation.UPSERT, atMillis = 1_000L)
            .enqueueTarget("middle", SyncOperation.UPSERT, atMillis = 2_000L)

        val batch = queue.nextBatch(2)

        assertEquals(listOf("early", "middle"), batch.map { it.entityId })
    }

    @Test
    fun failuresStayQueuedWithAttemptsAndErrors() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.UPSERT, atMillis = 1_000L)
        val id = queue.entries.single().id

        val failed = queue.markFailed(id, "timeout", atMillis = 2_000L)
        assertEquals(1, failed.pendingCount)
        assertEquals(1, failed.entries.single().attemptCount)
        assertEquals("timeout", failed.entries.single().lastError)

        val failedAgain = failed.markFailed(id, "offline", atMillis = 3_000L)
        assertEquals(2, failedAgain.entries.single().attemptCount)
        assertEquals("offline", failedAgain.entries.single().lastError)
    }

    @Test
    fun successfulSendRemovesOnlyThatEntry() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.UPSERT, atMillis = 1_000L)
            .enqueueTarget("t2", SyncOperation.UPSERT, atMillis = 2_000L)
        val firstId = queue.nextBatch(1).single().id

        val sent = queue.markSent(firstId)

        assertEquals(1, sent.pendingCount)
        assertEquals("t2", sent.entries.single().entityId)
    }

    @Test
    fun coalescingKeepsIdsMonotonic() {
        val queue = FieldSyncQueue()
            .enqueueTarget("t1", SyncOperation.UPSERT, atMillis = 1_000L)
            .enqueueTarget("t1", SyncOperation.UPSERT, atMillis = 2_000L)
            .enqueueTarget("t2", SyncOperation.UPSERT, atMillis = 3_000L)

        val ids = queue.entries.map { it.id }
        assertEquals(ids.sorted(), ids)
        assertNotEquals(queue.entries[0].id, queue.entries[1].id)
    }
}
