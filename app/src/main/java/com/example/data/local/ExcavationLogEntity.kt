package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.field.ExcavationLogEntry

@Entity(tableName = "excavation_logs")
data class ExcavationLogEntity(
    @PrimaryKey val id: String,
    val targetId: Long,
    val terrainKey: String?,
    val startedAtMillis: Long,
    val completedAtMillis: Long?,
    val depthCentimeters: Int?,
    val soilNotes: String,
    val findsDescription: String,
    val findsCount: Int,
    val photoUris: String,
    val voiceNoteUris: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

fun ExcavationLogEntry.toEntity() = ExcavationLogEntity(
    id = id,
    targetId = targetId,
    terrainKey = terrainKey,
    startedAtMillis = startedAtMillis,
    completedAtMillis = completedAtMillis,
    depthCentimeters = depthCentimeters,
    soilNotes = soilNotes,
    findsDescription = findsDescription,
    findsCount = findsCount,
    photoUris = photoUris.joinToString("\n") { it.replace("\n", "") },
    voiceNoteUris = voiceNoteUris.joinToString("\n") { it.replace("\n", "") },
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun ExcavationLogEntity.toDomain() = ExcavationLogEntry(
    id = id,
    targetId = targetId,
    terrainKey = terrainKey,
    startedAtMillis = startedAtMillis,
    completedAtMillis = completedAtMillis,
    depthCentimeters = depthCentimeters,
    soilNotes = soilNotes,
    findsDescription = findsDescription,
    findsCount = findsCount,
    photoUris = photoUris.lineSequence().filter { it.isNotBlank() }.toList(),
    voiceNoteUris = voiceNoteUris.lineSequence().filter { it.isNotBlank() }.toList(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)
