package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingSyncDao {
    @Query("SELECT * FROM pending_sync ORDER BY queuedAtMillis ASC")
    fun observeAll(): Flow<List<PendingSyncEntity>>

    @Query("SELECT * FROM pending_sync ORDER BY queuedAtMillis ASC")
    suspend fun all(): List<PendingSyncEntity>

    @Upsert
    suspend fun upsert(entry: PendingSyncEntity)

    @Query("DELETE FROM pending_sync WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_sync WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun deleteByEntity(entityType: String, entityId: String)
}
