package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.historicmap.GeoReferenceConfidence
import com.example.data.historicmap.GeoReferenceTransform
import com.example.data.historicmap.GeoReferencedMap
import com.example.data.historicmap.controlPointsFromStorage
import com.example.data.historicmap.controlPointsToStorage

@Entity(tableName = "historic_maps")
data class HistoricMapEntity(
    @PrimaryKey val id: String,
    val terrainKey: String,
    val displayName: String,
    val imageUri: String,
    val sourceAttribution: String,
    val controlPointsText: String,
    val transformText: String?,
    val rmseMeters: Double?,
    val maxResidualMeters: Double?,
    val confidence: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

fun GeoReferencedMap.toEntity() = HistoricMapEntity(
    id = id,
    terrainKey = terrainKey,
    displayName = displayName,
    imageUri = imageUri,
    sourceAttribution = sourceAttribution,
    controlPointsText = controlPointsToStorage(controlPoints),
    transformText = transform?.toStorage(),
    rmseMeters = rmseMeters,
    maxResidualMeters = maxResidualMeters,
    confidence = confidence.name,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun HistoricMapEntity.toDomain() = GeoReferencedMap(
    id = id,
    terrainKey = terrainKey,
    displayName = displayName,
    imageUri = imageUri,
    sourceAttribution = sourceAttribution,
    controlPoints = controlPointsFromStorage(controlPointsText),
    transform = transformText?.let { GeoReferenceTransform.fromStorage(it) },
    rmseMeters = rmseMeters,
    maxResidualMeters = maxResidualMeters,
    confidence = GeoReferenceConfidence.entries.firstOrNull { it.name == confidence }
        ?: GeoReferenceConfidence.INSUFFICIENT_POINTS,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)
