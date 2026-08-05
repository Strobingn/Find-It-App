package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoricMapDao {
    @Query("SELECT * FROM historic_maps WHERE terrainKey = :terrainKey ORDER BY updatedAtMillis DESC")
    fun observeByTerrainKey(terrainKey: String): Flow<List<HistoricMapEntity>>

    @Upsert
    suspend fun upsert(map: HistoricMapEntity)

    @Query("DELETE FROM historic_maps WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM historic_maps WHERE terrainKey = :terrainKey")
    suspend fun deleteByTerrainKey(terrainKey: String)
}
