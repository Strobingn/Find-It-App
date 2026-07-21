package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal

@Entity(tableName = "target_signals")
data class TargetSignalEntity(
    @PrimaryKey val id: Long,
    val gridX: Float,
    val gridY: Float,
    val metalType: String,
    val signalStrength: Float,
    val depthCm: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val source: String,
    val timestamp: Long,
    val notes: String,
    val photoUris: String,
    val status: String,
)

fun TargetSignal.toEntity() = TargetSignalEntity(
    id = id,
    gridX = gridX,
    gridY = gridY,
    metalType = metalType.name,
    signalStrength = signalStrength,
    depthCm = depthCm,
    latitude = latitude,
    longitude = longitude,
    source = source.name,
    timestamp = timestamp,
    notes = notes,
    photoUris = photoUris.joinToString("\n") { it.replace("\n", "") },
    status = status,
)

fun TargetSignalEntity.toDomain() = TargetSignal(
    id = id,
    gridX = gridX,
    gridY = gridY,
    metalType = enumValueOrDefault(metalType, MetalType.MANUAL_MARKER),
    signalStrength = signalStrength,
    depthCm = depthCm,
    latitude = latitude,
    longitude = longitude,
    source = enumValueOrDefault(source, DetectionSource.MANUAL),
    timestamp = timestamp,
    notes = notes,
    photoUris = photoUris.lineSequence().filter { it.isNotBlank() }.toList(),
    status = status,
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback
