package com.example.ai

import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.data.field.BreadcrumbPoint
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.ExcavationLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldOfflineAssistTest {

    private fun signal(
        id: Long,
        lat: Double?,
        lon: Double?,
        starred: Boolean = false,
        gridX: Float = 50f,
        gridY: Float = 50f,
    ) = TargetSignal(
        id = id,
        gridX = gridX,
        gridY = gridY,
        metalType = MetalType.IRON,
        signalStrength = 50f,
        latitude = lat,
        longitude = lon,
        source = DetectionSource.MANUAL,
        starred = starred,
    )

    @Test
    fun returnTripDraft_emptySignals_message() {
        val draft = FieldOfflineAssist.returnTripDraft(
            signals = emptyList(),
            excavationLogs = emptyList(),
            deviceLat = 42.0,
            deviceLon = -74.0,
        )
        assertTrue(draft.contains("No logged finds", ignoreCase = true))
        assertFalse(draft.contains("NAV_TARGET"))
    }

    @Test
    fun returnTripDraft_ordersTwoPointsNearestNeighborFromDevice() {
        // Device at origin-ish; stop A is farther east than stop B (closer).
        val far = signal(id = 100L, lat = 42.010, lon = -74.000, starred = true)
        val near = signal(id = 200L, lat = 42.001, lon = -74.000, starred = true)

        val draft = FieldOfflineAssist.returnTripDraft(
            signals = listOf(far, near),
            excavationLogs = emptyList(),
            deviceLat = 42.000,
            deviceLon = -74.000,
        )

        assertTrue(draft.contains("NAV_TARGET id=200"))
        assertTrue(draft.contains("NAV_TARGET id=100"))
        // Near stop (200) should appear before far stop (100) in visit order section
        val idxNear = draft.indexOf("id=200")
        val idxFar = draft.indexOf("id=100")
        assertTrue("near stop should be ordered first: $draft", idxNear in 0 until idxFar)
        // NAV lines should preserve same order
        val navNear = draft.indexOf("NAV_TARGET id=200")
        val navFar = draft.indexOf("NAV_TARGET id=100")
        assertTrue(navNear in 0 until navFar)
    }

    @Test
    fun returnTripDraft_prefersStarredOverAll() {
        val unstarredNear = signal(id = 1L, lat = 42.001, lon = -74.0, starred = false)
        val starredFar = signal(id = 2L, lat = 42.020, lon = -74.0, starred = true)

        val draft = FieldOfflineAssist.returnTripDraft(
            signals = listOf(unstarredNear, starredFar),
            excavationLogs = emptyList(),
            deviceLat = 42.0,
            deviceLon = -74.0,
        )

        assertTrue(draft.contains("NAV_TARGET id=2"))
        assertFalse(draft.contains("NAV_TARGET id=1"))
        assertTrue(draft.contains("starred", ignoreCase = true))
    }

    @Test
    fun returnTripDraft_includesOpenDigTarget() {
        val starred = signal(id = 10L, lat = 42.0, lon = -74.0, starred = true)
        val openDigTarget = signal(id = 99L, lat = 42.002, lon = -74.0, starred = false)
        val openLog = ExcavationLogEntry(
            id = "dig-1",
            targetId = 99L,
            terrainKey = null,
            startedAtMillis = 1L,
            completedAtMillis = null,
            depthCentimeters = null,
            soilNotes = "still open",
            findsDescription = "",
            findsCount = 0,
            photoUris = emptyList(),
            voiceNoteUris = emptyList(),
            createdAtMillis = 1L,
            updatedAtMillis = 1L,
        )

        val draft = FieldOfflineAssist.returnTripDraft(
            signals = listOf(starred, openDigTarget),
            excavationLogs = listOf(openLog),
            deviceLat = 42.0,
            deviceLon = -74.0,
        )

        assertTrue(draft.contains("NAV_TARGET id=10"))
        assertTrue(draft.contains("NAV_TARGET id=99"))
        assertTrue(draft.contains("open dig", ignoreCase = true))
    }

    @Test
    fun coverageGapTargets_emptyCandidates_messageAndEmptyList() {
        val (text, targets) = FieldOfflineAssist.coverageGapTargets(
            candidates = emptyList(),
            breadcrumbTracks = emptyList(),
            signals = emptyList(),
        )
        assertTrue(text.contains("No terrain candidates", ignoreCase = true))
        assertTrue(targets.isEmpty())
    }

    @Test
    fun coverageGapTargets_selectsFarFromFinds() {
        val nearFind = TerrainFeatureCandidate(
            id = "c-near",
            type = TerrainFeatureType.DEPRESSION,
            xPercent = 50f,
            yPercent = 50f,
            score = 0.9f,
            radiusMeters = 5f,
            evidence = listOf("near"),
        )
        val far = TerrainFeatureCandidate(
            id = "c-far",
            type = TerrainFeatureType.CELLAR_HOLE,
            xPercent = 90f,
            yPercent = 90f,
            score = 0.7f,
            radiusMeters = 6f,
            evidence = listOf("far"),
        )
        val find = signal(id = 1L, lat = null, lon = null, gridX = 50f, gridY = 50f)

        val (text, targets) = FieldOfflineAssist.coverageGapTargets(
            candidates = listOf(nearFind, far),
            breadcrumbTracks = emptyList(),
            signals = listOf(find),
        )

        assertEquals(1, targets.size)
        assertEquals("Gap · Cellar hole", targets[0].label)
        assertEquals(90f, targets[0].xPercent, 0.01f)
        assertEquals(90f, targets[0].yPercent, 0.01f)
        assertTrue(text.contains("gap", ignoreCase = true))
    }

    @Test
    fun coverageGapTargets_mentionsTrailPointCount() {
        val candidate = TerrainFeatureCandidate(
            id = "c1",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 20f,
            yPercent = 30f,
            score = 0.8f,
            radiusMeters = 7f,
            evidence = emptyList(),
        )
        val track = BreadcrumbTrack(
            id = "t1",
            terrainKey = "k",
            displayName = "walk",
            points = listOf(
                BreadcrumbPoint(42.0, -74.0, 5f, 1L),
                BreadcrumbPoint(42.001, -74.001, 5f, 2L),
            ),
            isRecording = false,
            createdAtMillis = 1L,
            updatedAtMillis = 2L,
        )

        val (text, targets) = FieldOfflineAssist.coverageGapTargets(
            candidates = listOf(candidate),
            breadcrumbTracks = listOf(track),
            signals = emptyList(),
        )

        assertTrue(text.contains("2 point", ignoreCase = true) || text.contains("2 points", ignoreCase = true))
        assertEquals(1, targets.size)
        assertEquals(0.8f, targets[0].confidence, 0.01f)
    }

    @Test
    fun digBriefDraft_emptyCandidates_message() {
        val draft = FieldOfflineAssist.digBriefDraft(
            candidates = emptyList(),
            signals = emptyList(),
            excavationLogs = emptyList(),
        )
        assertTrue(draft.contains("Offline dig brief", ignoreCase = true))
        assertTrue(draft.contains("No terrain candidates", ignoreCase = true))
        assertTrue(draft.contains("LiDAR", ignoreCase = true))
    }

    @Test
    fun digBriefDraft_includesTopCandidatesAndFocus() {
        val candidate = TerrainFeatureCandidate(
            id = "c1",
            type = TerrainFeatureType.FOUNDATION,
            xPercent = 40f,
            yPercent = 55f,
            score = 0.82f,
            radiusMeters = 8f,
            evidence = listOf("Flat interior: 80%"),
        )
        val draft = FieldOfflineAssist.digBriefDraft(
            candidates = listOf(candidate),
            signals = listOf(signal(id = 9L, lat = 42.0, lon = -74.0, starred = true)),
            excavationLogs = emptyList(),
            selectedCandidateSummary = "Focused foundation at 40/55",
            inspectedCellSummary = "Cell elev 120 m",
        )
        assertTrue(draft.contains("Focused foundation"))
        assertTrue(draft.contains("Cell elev"))
        assertTrue(draft.contains("40.0%") || draft.contains("40%"))
        assertTrue(draft.contains("Starred finds: 1"))
        assertTrue(draft.contains("id=9"))
    }
}
