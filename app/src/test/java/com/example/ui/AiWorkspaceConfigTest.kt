package com.example.ui

import com.example.data.NormalizedRasterBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWorkspaceConfigTest {
    @Test
    fun aiRefineResolutionAdaptsToDeviceCapability() {
        assertEquals(1_024, chooseAiRefineResolution(256, false, 8, 2_048))
        assertEquals(768, chooseAiRefineResolution(1_024, true, 8, 8_192))
        assertEquals(1_536, chooseAiRefineResolution(320, false, 4, 4_096))
        assertEquals(1_536, chooseAiRefineResolution(384, false, 6, 4_096))
        assertEquals(1_536, chooseAiRefineResolution(256, false, 8, 3_498))
    }

    @Test
    fun wholeTerrainRefineUsesAlreadyDecodedSource() {
        assertTrue(isEffectivelyWholeTerrain(NormalizedRasterBounds.Full))
        assertTrue(isEffectivelyWholeTerrain(NormalizedRasterBounds(0.02, 0.02, 0.98, 0.98)))
        assertTrue(!isEffectivelyWholeTerrain(NormalizedRasterBounds(0.2, 0.2, 0.8, 0.8)))
    }

    @Test
    fun activeAiPanelKeepsExpandedFieldQuestions() {
        // Field Closure Pack expands the original five prompts into sixteen focused
        // field-analysis prompts. Keep this explicit so the pack cannot silently
        // disappear when the AI panel is refactored.
        assertEquals(16, AI_BUILT_IN_QUESTIONS.size)
        assertEquals(AI_BUILT_IN_QUESTIONS.size, AI_BUILT_IN_QUESTIONS.distinct().size)
        assertTrue(AI_BUILT_IN_QUESTIONS.all(String::isNotBlank))
    }

    @Test
    fun aiAnalysisDefaultsToSourceRender() {
        assertTrue(AiTerrainState().showSourceHillshade)
        assertTrue(AI_HISTORIC_TARGETS_DEFAULT_VISIBLE)
    }

    @Test
    fun aiSourceRenderUsesTheActualTerrainVisualizationLabel() {
        assertEquals("Standard hillshade", aiSourceVisualizationLabel(0))
        assertEquals("Multi-directional hillshade", aiSourceVisualizationLabel(1))
        assertEquals("Slope", aiSourceVisualizationLabel(2))
        assertEquals("Local relief", aiSourceVisualizationLabel(3))
        assertEquals("Terrain render", aiSourceVisualizationLabel(99))
    }

    @Test
    fun cloudAiTargetsMapFromViewportCoordinates() {
        val targets = parseCloudMapTargets(
            "[MAP_TARGET x=50 y=25 confidence=0.8 label=possible cellar rim]",
            NormalizedRasterBounds(0.2, 0.4, 0.6, 0.8),
        )
        assertEquals(1, targets.size)
        assertEquals(40f, targets.single().xPercent, 0.001f)
        assertEquals(50f, targets.single().yPercent, 0.001f)
        assertEquals("possible cellar rim", targets.single().label)
    }

    @Test
    fun cloudTargetIdsAreStableAndTerrainSpecific() {
        val target = CloudMapTarget(40f, 50f, "possible cellar rim", 0.8f)
        assertEquals(
            stableCloudTargetId("lidar:file:///first.laz", target),
            stableCloudTargetId("lidar:file:///first.laz", target),
        )
        assertTrue(
            stableCloudTargetId("lidar:file:///first.laz", target) !=
                stableCloudTargetId("lidar:file:///second.laz", target),
        )
    }

    @Test
    fun delayedCloudTargetsCannotCrossIntoAnotherTerrain() {
        val target = CloudMapTarget(40f, 50f, "possible cellar rim", 0.8f)
        val state = AiTerrainState(
            cloudMapTargets = listOf(target),
            cloudTerrainKey = "lidar:file:///first.laz",
        )
        assertEquals(listOf(target), cloudTargetsForTerrain(state, "lidar:file:///first.laz"))
        assertTrue(cloudTargetsForTerrain(state, "lidar:file:///second.laz").isEmpty())
    }
}
