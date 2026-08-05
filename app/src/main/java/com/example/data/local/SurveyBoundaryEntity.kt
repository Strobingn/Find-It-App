package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.field.SurveyBoundary
import com.example.data.field.boundaryVerticesFromStorage
import com.example.data.field.verticesToStorage

@Entity(tableName = "survey_boundaries")
data class SurveyBoundaryEntity(
    @PrimaryKey val id: String,
    val terrainKey: String,
    val displayName: String,
    val verticesText: String,
    val createdAtMillis: Long,
)

fun SurveyBoundary.toEntity() = SurveyBoundaryEntity(
    id = id,
    terrainKey = terrainKey,
    displayName = displayName,
    verticesText = verticesToStorage(),
    createdAtMillis = createdAtMillis,
)

fun SurveyBoundaryEntity.toDomain() = SurveyBoundary(
    id = id,
    terrainKey = terrainKey,
    displayName = displayName,
    vertices = boundaryVerticesFromStorage(verticesText),
    createdAtMillis = createdAtMillis,
)
