package com.example.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoricMapFeatureDao {
    @Query("SELECT * FROM historic_map_features WHERE mapId = :mapId ORDER BY createdAtMillis ASC")
    fun observeByMapId(mapId: String): Flow<List<HistoricMapFeatureEntity>>

    @Upsert
    suspend fun upsert(feature: HistoricMapFeatureEntity)

    @Query("DELETE FROM historic_map_features WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM historic_map_features WHERE mapId = :mapId")
    suspend fun deleteByMapId(mapId: String)
}
