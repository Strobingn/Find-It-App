package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetSignalDao {
    @Query("SELECT * FROM target_signals ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<TargetSignalEntity>>

    @Upsert
    suspend fun upsert(signal: TargetSignalEntity)

    @Query("DELETE FROM target_signals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM target_signals")
    suspend fun deleteAll()
}
