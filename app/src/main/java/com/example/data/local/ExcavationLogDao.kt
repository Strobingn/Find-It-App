package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ExcavationLogDao {
    @Query("SELECT * FROM excavation_logs WHERE targetId = :targetId ORDER BY startedAtMillis DESC")
    fun observeByTarget(targetId: Long): Flow<List<ExcavationLogEntity>>

    @Query("SELECT * FROM excavation_logs WHERE terrainKey = :terrainKey ORDER BY startedAtMillis DESC")
    fun observeByTerrainKey(terrainKey: String): Flow<List<ExcavationLogEntity>>

    @Upsert
    suspend fun upsert(entry: ExcavationLogEntity)

    @Query("DELETE FROM excavation_logs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM excavation_logs WHERE terrainKey = :terrainKey")
    suspend fun deleteByTerrainKey(terrainKey: String)
}
