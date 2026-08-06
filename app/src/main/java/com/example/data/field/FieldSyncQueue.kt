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
 * When a superseding UPSERT revises a still-unsent payload, [SyncConflictResolver] records a
 * review note so both revisions stay visible until cloud sync (Phase 9) can merge them.
 *
 * Failed sends stay queued with their attempt count and error; nothing is dropped silently.
 */
class FieldSyncQueue(
    val entries: List<PendingSyncEntry> = emptyList(),
    /** Conflict reports from payload-revising coalesces; oldest first, capped for diagnostics. */
    val conflicts: List<SyncConflictReport> = emptyList(),
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
        val previous = entries.firstOrNull { it.entityType == entityType && it.entityId == entityId }
        val kept = entries.filterNot { it.entityType == entityType && it.entityId == entityId }
        val nextId = (entries.maxOfOrNull { it.id } ?: 0L) + 1L

        // Superseding UPSERT with a different payload while the prior write is still unsent:
        // treat prior queued time as a competing "remote" revision vs the new local write so the
        // resolver flags MERGE_REQUIRED instead of silently dropping the earlier state.
        var nextConflicts = conflicts
        var conflictNote: String? = null
        if (previous != null &&
            previous.operation == SyncOperation.UPSERT &&
            operation == SyncOperation.UPSERT &&
            previous.payload != payload
        ) {
            val report = SyncConflictResolver.resolve(
                entityId = entityId,
                baseUpdatedAtMillis = previous.queuedAtMillis - 1L,
                localUpdatedAtMillis = queuedAtMillis,
                remoteUpdatedAtMillis = previous.queuedAtMillis,
            )
            nextConflicts = (conflicts + report).takeLast(32)
            if (report.resolution == SyncConflictResolution.MERGE_REQUIRED) {
                conflictNote = "revision while prior unsent · ${report.note}"
            }
        }

        return FieldSyncQueue(
            entries = kept + PendingSyncEntry(
                id = nextId,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = payload,
                queuedAtMillis = queuedAtMillis,
                attemptCount = 0,
                lastError = conflictNote,
            ),
            conflicts = nextConflicts,
        )
    }

    /** Removes the entry after a successful send. */
    fun markSent(id: Long): FieldSyncQueue {
        val removed = entries.firstOrNull { it.id == id }
        return FieldSyncQueue(
            entries = entries.filterNot { it.id == id },
            conflicts = if (removed == null) {
                conflicts
            } else {
                conflicts.filterNot { it.entityId == removed.entityId }
            },
        )
    }

    /** Keeps the entry queued, recording the failure for backoff and diagnostics. */
    fun markFailed(id: Long, error: String, atMillis: Long): FieldSyncQueue =
        FieldSyncQueue(
            entries = entries.map { entry ->
                if (entry.id == id) {
                    entry.copy(attemptCount = entry.attemptCount + 1, lastError = error)
                } else {
                    entry
                }
            },
            conflicts = conflicts,
        )

    /** Oldest-first replay order so dependent state is applied in the order it happened. */
    fun nextBatch(limit: Int): List<PendingSyncEntry> =
        entries.sortedBy { it.queuedAtMillis }.take(limit.coerceAtLeast(0))

    fun pendingFor(entityType: SyncEntityType, entityId: String): PendingSyncEntry? =
        entries.firstOrNull { it.entityType == entityType && it.entityId == entityId }

    /** Latest conflict report for [entityId], if any. */
    fun conflictFor(entityId: String): SyncConflictReport? =
        conflicts.lastOrNull { it.entityId == entityId }
}
