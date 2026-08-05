package com.example.data.field

enum class SyncEntityType {
    TARGET_SIGNAL,
    EXCAVATION_LOG,
    BREADCRUMB_TRACK,
    SURVEY_BOUNDARY,
}

enum class SyncOperation {
    UPSERT,
    DELETE,
}

data class PendingSyncEntry(
    val id: Long,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperation,
    val payload: String,
    val queuedAtMillis: Long,
    val attemptCount: Int,
    val lastError: String?,
)

/**
 * Offline-first synchronization queue. Entries are recorded locally while offline and replayed
 * in order when connectivity returns. Coalescing rules keep the queue free of duplicates without
 * ever losing a state change:
 *
 * - a second UPSERT for the same entity replaces the first (latest state wins)
 * - a DELETE discards any pending UPSERT for that entity and replaces a prior DELETE
 * - an UPSERT after a DELETE replaces it (the entity exists again, with its latest state)
 *
 * Failed sends stay queued with their attempt count and error; nothing is dropped silently.
 */
class FieldSyncQueue(
    val entries: List<PendingSyncEntry> = emptyList(),
) {
    val pendingCount: Int
        get() = entries.size

    fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
        queuedAtMillis: Long,
    ): FieldSyncQueue {
        val kept = entries.filterNot { it.entityType == entityType && it.entityId == entityId }
        val nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1L
        return FieldSyncQueue(
            kept + PendingSyncEntry(
                id = nextId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = payload,
                queuedAtMillis = queuedAtMillis,
                attemptCount = 0,
                lastError = null,
            ),
        )
    }

    /** Removes the entry after a successful send. */
    fun markSent(id: Long): FieldSyncQueue =
        FieldSyncQueue(entries.filterNot { it.id == id })

    /** Keeps the entry queued, recording the failure for backoff and diagnostics. */
    fun markFailed(id: Long, error: String, atMillis: Long): FieldSyncQueue =
        FieldSyncQueue(
            entries.map { entry ->
                if (entry.id == id) {
                    entry.copy(attemptCount = entry.attemptCount + 1, lastError = error)
                } else {
                    entry
                }
            },
        )

    /** Oldest-first replay order so dependent state is applied in the order it happened. */
    fun nextBatch(limit: Int): List<PendingSyncEntry> =
        entries.sortedBy { it.queuedAtMillis }.take(limit.coerceAtLeast(0))

    fun pendingFor(entityType: SyncEntityType, entityId: String): PendingSyncEntry? =
        entries.firstOrNull { it.entityType == entityType && it.entityId == entityId }
}
