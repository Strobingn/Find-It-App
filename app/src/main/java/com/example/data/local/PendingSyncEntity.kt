package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.field.PendingSyncEntry
import com.example.data.field.SyncEntityType
import com.example.data.field.SyncOperation

@Entity(tableName = "pending_sync")
data class PendingSyncEntity(
    @PrimaryKey val id: Long,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val payload: String,
    val queuedAtMillis: Long,
    val attemptCount: Int,
    val lastError: String?,
)

fun PendingSyncEntry.toEntity() = PendingSyncEntity(
    id = id,
    entityType = entityType.name,
    entityId = entityId,
    operation = operation.name,
    payload = payload,
    queuedAtMillis = queuedAtMillis,
    attemptCount = attemptCount,
    lastError = lastError,
)

private inline fun <reified T : Enum<T>> syncEnumOrDefault(value: String, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: fallback

fun PendingSyncEntity.toDomain() = PendingSyncEntry(
    id = id,
    entityType = syncEnumOrDefault(entityType, SyncEntityType.TARGET_SIGNAL),
    entityId = entityId,
    operation = syncEnumOrDefault(operation, SyncOperation.UPSERT),
    payload = payload,
    queuedAtMillis = queuedAtMillis,
    attemptCount = attemptCount,
    lastError = lastError,
)
