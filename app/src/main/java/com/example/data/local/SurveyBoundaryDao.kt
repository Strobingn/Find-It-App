package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyBoundaryDao {
    @Query("SELECT * FROM survey_boundaries WHERE terrainKey = :terrainKey ORDER BY createdAtMillis DESC")
    fun observeByTerrainKey(terrainKey: String): Flow<List<SurveyBoundaryEntity>>

    @Upsert
    suspend fun upsert(boundary: SurveyBoundaryEntity)

    @Query("DELETE FROM survey_boundaries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM survey_boundaries WHERE terrainKey = :terrainKey")
    suspend fun deleteByTerrainKey(terrainKey: String)
}
