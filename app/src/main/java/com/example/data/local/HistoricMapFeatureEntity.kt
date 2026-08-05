package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.field.boundaryVerticesFromStorage
import com.example.data.field.boundaryVerticesToStorage
import com.example.data.historicmap.HistoricMapFeature
import com.example.data.historicmap.MapFeatureType

@Entity(tableName = "historic_map_features")
data class HistoricMapFeatureEntity(
    @PrimaryKey val id: String,
    val mapId: String,
    val type: String,
    val pointsText: String,
    val confidence: Float,
    val note: String,
    val createdAtMillis: Long,
)

fun HistoricMapFeature.toEntity() = HistoricMapFeatureEntity(
    id = id,
    mapId = mapId,
    type = type.name,
    pointsText = boundaryVerticesToStorage(points),
    confidence = confidence,
    note = note,
    createdAtMillis = createdAtMillis,
)

fun HistoricMapFeatureEntity.toDomain() = HistoricMapFeature(
    id = id,
    mapId = mapId,
    type = MapFeatureType.entries.firstOrNull { it.name == type } ?: MapFeatureType.ROAD,
    points = boundaryVerticesFromStorage(pointsText),
    confidence = confidence,
    note = note,
    createdAtMillis = createdAtMillis,
)
