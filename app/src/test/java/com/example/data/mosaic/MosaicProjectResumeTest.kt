package com.example.data.mosaic

import com.example.geospatial.GeoSpatialLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Partial-project resumption is what makes a multi-gigabyte area survivable: an interrupted
 * transfer must come back as "keep downloading the missing members", never as "start over"
 * or "pretend it finished". These pin the exact state transitions and recovery wording the
 * picker saves to the project record.
 */
class MosaicProjectResumeTest {
    private fun tile(name: String) = MosaicProjectTile(
        displayName = name,
        localFileName = name,
        sourceUrl = "https://example.test/$name",
        bounds = GeoSpatialLibrary.GeographicBounds(42.0, 42.1, -74.1, -74.0),
    )

    private fun project(state: MosaicProjectState, tileCount: Int = 3) = MosaicProject(
        id = "project-1",
        displayName = "North woods",
        tiles = (1..tileCount).map { tile("tile-$it.laz") },
        createdAtMillis = 1L,
        updatedAtMillis = 2L,
        state = state,
    )

    @Test
    fun aFullyDownloadedReadyProjectReopensWithoutDownloading() {
        assertEquals(
            MosaicProjectState.READY,
            MosaicProjectResume.stateWhenStarted(project(MosaicProjectState.READY), missingSourceCount = 0),
        )
    }

    /** Files can vanish (cleared storage, moved SD card) even when the record says READY. */
    @Test
    fun aReadyProjectWithMissingMembersResumesAsDownloading() {
        assertEquals(
            MosaicProjectState.DOWNLOADING,
            MosaicProjectResume.stateWhenStarted(project(MosaicProjectState.READY), missingSourceCount = 1),
        )
    }

    @Test
    fun anInterruptedProjectResumesAsDownloadingEvenWhenNothingIsMissing() {
        assertEquals(
            MosaicProjectState.DOWNLOADING,
            MosaicProjectResume.stateWhenStarted(project(MosaicProjectState.NEEDS_ATTENTION), missingSourceCount = 0),
        )
    }

    @Test
    fun aReadyProjectWithEveryFilePresentOnlyReopens() {
        assertFalse(MosaicProjectResume.canResume(project(MosaicProjectState.READY), availableSourceCount = 3))
    }

    @Test
    fun aReadyProjectWithLostFilesCanResume() {
        assertTrue(MosaicProjectResume.canResume(project(MosaicProjectState.READY), availableSourceCount = 1))
    }

    @Test
    fun anUnfinishedProjectCanAlwaysResume() {
        assertTrue(MosaicProjectResume.canResume(project(MosaicProjectState.DOWNLOADING), availableSourceCount = 3))
        assertTrue(MosaicProjectResume.canResume(project(MosaicProjectState.NEEDS_ATTENTION), availableSourceCount = 0))
    }

    /** The recovery note is how the user learns what survived an interrupted transfer. */
    @Test
    fun thePauseMessageReportsHowMuchSurvived() {
        assertEquals(
            "Download paused. 2 of 5 source files are ready.",
            MosaicProjectResume.pausedMessage(2, 5),
        )
    }
}
