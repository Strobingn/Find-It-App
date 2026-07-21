package com.example.data.local

import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetSignalEntityTest {
    @Test
    fun photoUrisSurviveDatabaseMapping() {
        val signal = TargetSignal(
            id = 42,
            gridX = 12f,
            gridY = 34f,
            metalType = MetalType.MANUAL_MARKER,
            signalStrength = 0f,
            source = DetectionSource.MANUAL,
            photoUris = listOf(
                "content://media/picker/first",
                "content://media/picker/second",
            ),
        )

        val restored = signal.toEntity().toDomain()

        assertEquals(signal.photoUris, restored.photoUris)
    }

    @Test
    fun databaseMappingDropsBlankPhotoEntries() {
        val entity = TargetSignal(
            gridX = 0f,
            gridY = 0f,
            metalType = MetalType.MANUAL_MARKER,
            signalStrength = 0f,
        ).toEntity().copy(photoUris = "content://one\n\ncontent://two\n")

        assertEquals(listOf("content://one", "content://two"), entity.toDomain().photoUris)
    }
}
