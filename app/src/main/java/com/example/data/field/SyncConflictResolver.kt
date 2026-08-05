package com.example.data.field

enum class SyncConflictResolution(val label: String) {
    LOCAL_WINS("Local change kept"),
    REMOTE_WINS("Remote change applied"),
    MERGE_REQUIRED("Both sides changed - needs review"),
}

data class SyncConflictReport(
    val entityId: String,
    val resolution: SyncConflictResolution,
    val note: String,
)

/**
 * Deterministic conflict detection for multi-device synchronization. The rule set never loses a
 * change silently: when both sides moved since their common base, the conflict is reported for
 * review instead of guessing; otherwise the side that changed wins, and ties break on the newer
 * timestamp so every device reaches the same answer independently.
 */
object SyncConflictResolver {
    fun resolve(
        entityId: String,
        baseUpdatedAtMillis: Long?,
        localUpdatedAtMillis: Long,
        remoteUpdatedAtMillis: Long,
    ): SyncConflictReport {
        if (baseUpdatedAtMillis == null) {
            // No common base: the newer copy wins; identical timestamps need no action.
            val resolution = if (localUpdatedAtMillis >= remoteUpdatedAtMillis) {
                SyncConflictResolution.LOCAL_WINS
            } else {
                SyncConflictResolution.REMOTE_WINS
            }
            return SyncConflictReport(
                entityId = entityId,
                resolution = resolution,
                note = "no common base; newer timestamp wins",
            )
        }
        val localChanged = localUpdatedAtMillis > baseUpdatedAtMillis
        val remoteChanged = remoteUpdatedAtMillis > baseUpdatedAtMillis
        return when {
            localChanged && remoteChanged -> SyncConflictReport(
                entityId = entityId,
                resolution = SyncConflictResolution.MERGE_REQUIRED,
                note = "both copies changed since the common base; kept queued for review",
            )
            remoteChanged -> SyncConflictReport(
                entityId = entityId,
                resolution = SyncConflictResolution.REMOTE_WINS,
                note = "only the remote copy changed",
            )
            localChanged -> SyncConflictReport(
                entityId = entityId,
                resolution = SyncConflictResolution.LOCAL_WINS,
                note = "only the local copy changed",
            )
            else -> SyncConflictReport(
                entityId = entityId,
                resolution = if (localUpdatedAtMillis >= remoteUpdatedAtMillis) {
                    SyncConflictResolution.LOCAL_WINS
                } else {
                    SyncConflictResolution.REMOTE_WINS
                },
                note = "neither copy changed; deterministic tie-break on timestamp",
            )
        }
    }
}
