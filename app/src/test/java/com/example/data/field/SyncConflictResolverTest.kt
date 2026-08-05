package com.example.data.field

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncConflictResolverTest {
    private fun resolve(
        base: Long?,
        local: Long,
        remote: Long,
    ) = SyncConflictResolver.resolve(
        entityId = "t1",
        baseUpdatedAtMillis = base,
        localUpdatedAtMillis = local,
        remoteUpdatedAtMillis = remote,
    ).resolution

    @Test
    fun bothSidesChangedRequiresReview() {
        assertEquals(
            SyncConflictResolution.MERGE_REQUIRED,
            resolve(base = 1_000L, local = 2_000L, remote = 3_000L),
        )
    }

    @Test
    fun onlyRemoteChangedAppliesRemote() {
        assertEquals(
            SyncConflictResolution.REMOTE_WINS,
            resolve(base = 2_000L, local = 2_000L, remote = 3_000L),
        )
    }

    @Test
    fun onlyLocalChangedKeepsLocal() {
        assertEquals(
            SyncConflictResolution.LOCAL_WINS,
            resolve(base = 2_000L, local = 3_000L, remote = 2_000L),
        )
    }

    @Test
    fun unchangedCopiesTieBreakDeterministically() {
        assertEquals(
            SyncConflictResolution.REMOTE_WINS,
            resolve(base = 3_000L, local = 2_000L, remote = 2_500L),
        )
        assertEquals(
            SyncConflictResolution.LOCAL_WINS,
            resolve(base = 3_000L, local = 2_500L, remote = 2_000L),
        )
    }

    @Test
    fun withoutCommonBaseNewerTimestampWins() {
        assertEquals(
            SyncConflictResolution.REMOTE_WINS,
            resolve(base = null, local = 1_000L, remote = 2_000L),
        )
        assertEquals(
            SyncConflictResolution.LOCAL_WINS,
            resolve(base = null, local = 2_000L, remote = 1_000L),
        )
        assertEquals(
            SyncConflictResolution.LOCAL_WINS,
            resolve(base = null, local = 1_000L, remote = 1_000L),
        )
    }
}
