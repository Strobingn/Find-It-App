package com.example.data.local

import com.example.data.mosaic.MosaicProject
import com.example.data.mosaic.MosaicProjectState
import com.example.data.mosaic.MosaicProjectTile
import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class MosaicProjectEntityTest {
    @Test
    fun sourceFileAndTileMetadataSurviveProjectPersistence() {
        val project = MosaicProject(
            id = "project-1",
            displayName = "North woods",
            tiles = listOf(
                MosaicProjectTile(
                    displayName = "tile 001.laz",
                    localFileName = "tile_001.laz",
                    sourceUrl = "https://example.test/tiles/tile%20001.laz",
                    bounds = GeoSpatialLibrary.GeographicBounds(42.0, 42.1, -74.1, -74.0),
                ),
            ),
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
            state = MosaicProjectState.NEEDS_ATTENTION,
            recoveryMessage = "Download paused. Resume this project.",
            areaSelectionDescription = "Radius: 804.672 m around 41.43, -74.04",
        )

        assertEquals(project, project.toEntity().toDomain())
    }
}
