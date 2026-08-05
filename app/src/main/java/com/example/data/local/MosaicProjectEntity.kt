package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.mosaic.MosaicProject
import com.example.data.mosaic.MosaicProjectState
import com.example.data.mosaic.mosaicTilesFromManifest
import com.example.data.mosaic.tilesToManifest

@Entity(tableName = "mosaic_projects")
data class MosaicProjectEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val tileManifest: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val status: String = MosaicProjectState.READY.name,
    val recoveryMessage: String? = null,
    val areaSelectionDescription: String? = null,
)

fun MosaicProject.toEntity() = MosaicProjectEntity(
    id = id,
    displayName = displayName,
    tileManifest = tilesToManifest(),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    status = state.name,
    recoveryMessage = recoveryMessage,
    areaSelectionDescription = areaSelectionDescription,
)

fun MosaicProjectEntity.toDomain() = MosaicProject(
    id = id,
    displayName = displayName,
    tiles = mosaicTilesFromManifest(tileManifest),
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
    state = MosaicProjectState.entries.firstOrNull { it.name == status }
        ?: MosaicProjectState.NEEDS_ATTENTION,
    recoveryMessage = recoveryMessage,
    areaSelectionDescription = areaSelectionDescription,
)
