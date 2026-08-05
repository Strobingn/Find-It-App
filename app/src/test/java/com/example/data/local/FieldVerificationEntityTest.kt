package com.example.data.local

import com.example.data.field.ExcavationLogEntry
import com.example.data.field.PendingSyncEntry
import com.example.data.field.SyncEntityType
import com.example.data.field.SyncOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldVerificationEntityTest {
    @Test
    fun excavationLogRoundTripsThroughEntity() {
        val entry = ExcavationLogEntry(
            id = "log-1",
            targetId = 42L,
            terrainKey = "terrain-1",
            startedAtMillis = 1_000L,
            completedAtMillis = 2_000L,
            depthCentimeters = 35,
            soilNotes = "dark loam over clay",
            findsDescription = "hand-wrought nail, ceramic shard",
            findsCount = 2,
            photoUris = listOf("content://photos/1", "content://photos/2"),
            voiceNoteUris = listOf("content://audio/1"),
            createdAtMillis = 900L,
            updatedAtMillis = 2_000L,
        )

        val restored = entry.toEntity().toDomain()

        assertEquals(entry, restored)
    }

    @Test
    fun excavationLogHandlesEmptyUrisAndOptionalFields() {
        val entry = ExcavationLogEntry(
            id = "log-2",
            targetId = 7L,
            terrainKey = null,
            startedAtMillis = 1_000L,
            completedAtMillis = null,
            depthCentimeters = null,
            soilNotes = "",
            findsDescription = "",
            findsCount = 0,
            photoUris = emptyList(),
            voiceNoteUris = emptyList(),
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )

        val restored = entry.toEntity().toDomain()

        assertEquals(entry, restored)
        assertTrue(restored.photoUris.isEmpty())
        assertNull(restored.completedAtMillis)
        assertTrue(!restored.isComplete)
    }

    @Test
    fun surveyBoundaryRoundTripsThroughEntity() {
        val boundary = com.example.data.field.SurveyBoundary(
            id = "b1",
            terrainKey = "terrain-1",
            displayName = "Cellar ridge",
            vertices = listOf(
                com.example.data.field.BoundaryVertex(41.4, -74.0),
                com.example.data.field.BoundaryVertex(41.4, -73.9),
                com.example.data.field.BoundaryVertex(41.5, -73.95),
            ),
            createdAtMillis = 5_000L,
        )

        val restored = boundary.toEntity().toDomain()

        assertEquals(boundary, restored)
        assertTrue(restored.contains(41.42, -73.96))
    }

    @Test
    fun pendingSyncRoundTripsThroughEntity() {
        val entry = PendingSyncEntry(
            id = 9L,
            entityType = SyncEntityType.EXCAVATION_LOG,
            entityId = "log-1",
            operation = SyncOperation.DELETE,
            payload = "",
            queuedAtMillis = 3_000L,
            attemptCount = 2,
            lastError = "offline",
        )

        assertEquals(entry, entry.toEntity().toDomain())
    }

    @Test
    fun pendingSyncUnknownEnumNamesFallBackSafely() {
        val entity = PendingSyncEntity(
            id = 1L,
            entityType = "FUTURE_TYPE",
            entityId = "x",
            operation = "FUTURE_OP",
            payload = "{}",
            queuedAtMillis = 1L,
            attemptCount = 0,
            lastError = null,
        )

        val domain = entity.toDomain()

        assertEquals(SyncEntityType.TARGET_SIGNAL, domain.entityType)
        assertEquals(SyncOperation.UPSERT, domain.operation)
    }
}
