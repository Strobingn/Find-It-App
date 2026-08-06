package com.example.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldAiCopilotTest {

    private val pack = FieldAiSessionPack(
        terrainSummary = "test terrain",
        terrainContext = "grid 10x10",
        sunAzimuth = 315f,
        sunAltitude = 35f,
        gridWidth = 10,
        gridHeight = 10,
        cellSizeMeters = 1f,
    )

    @Test
    fun parseLightingRecommendation_extractsAzimuthAndAltitude() {
        val text = """
            Recommend low NW light for bank shadows.
            LIGHT_AZ=315
            LIGHT_ALT=35
        """.trimIndent()

        val rec = FieldAiCopilot.parseLightingRecommendation(text)

        assertNotNull(rec)
        assertEquals(315f, rec!!.azimuth, 1e-3f)
        assertEquals(35f, rec.altitude, 1e-3f)
    }

    @Test
    fun parseLightingRecommendation_returnsNullWhenMissingLines() {
        assertNull(FieldAiCopilot.parseLightingRecommendation("no machine lines here"))
        assertNull(FieldAiCopilot.parseLightingRecommendation("LIGHT_AZ=180"))
        assertNull(FieldAiCopilot.parseLightingRecommendation("LIGHT_ALT=40"))
    }

    @Test
    fun parseLightingRecommendation_coercesAltitudeInto5to85() {
        val tooLow = FieldAiCopilot.parseLightingRecommendation(
            "LIGHT_AZ=90\nLIGHT_ALT=0",
        )
        assertNotNull(tooLow)
        assertEquals(5f, tooLow!!.altitude, 1e-3f)

        val tooHigh = FieldAiCopilot.parseLightingRecommendation(
            "LIGHT_AZ=90\nLIGHT_ALT=99",
        )
        assertNotNull(tooHigh)
        assertEquals(85f, tooHigh!!.altitude, 1e-3f)
    }

    @Test
    fun buildUserPrompt_eachFeatureIsNonBlankWithDistinctiveKeywords() {
        val expectedKeywords = mapOf(
            FieldAiFeature.DIG_BRIEF to "DIG",
            FieldAiFeature.SITE_NARRATIVE to "NARRATIVE",
            FieldAiFeature.LIGHTING_ADVISOR to "LIGHT_AZ",
            FieldAiFeature.SWEEP_PLAN to "SWEEP",
            FieldAiFeature.FIELD_REPORT to "REPORT",
            FieldAiFeature.OUTCOME_COACH to "OUTCOME",
            FieldAiFeature.FIND_INTERPRETER to "INTERPRET",
            FieldAiFeature.HISTORIC_CORRELATOR to "HISTORIC",
            FieldAiFeature.ANOMALY_DEEPDIVE to "DEEP",
            FieldAiFeature.DAY_DEBRIEF to "DEBRIEF",
            FieldAiFeature.RETURN_TRIP_PLANNER to "RETURN",
            FieldAiFeature.FALSE_POSITIVE_AUTOPSY to "FALSE-POSITIVE",
            FieldAiFeature.COMPARE_TWO_SITES to "COMPARE",
            FieldAiFeature.QUESTION_THE_CELL to "CELL",
            FieldAiFeature.EVIDENCE_CHAIN to "EVIDENCE",
            FieldAiFeature.VOICE_STRUCTURED_FIND to "METAL_TYPE",
            FieldAiFeature.PHOTO_CATALOG_ASSIST to "CATALOG",
            FieldAiFeature.COVERAGE_GAP_AI to "GAP",
            FieldAiFeature.PARTNER_HANDOFF to "HANDOFF",
            FieldAiFeature.RISK_ETHICS_COACH to "ETHICS",
        )

        for (feature in FieldAiFeature.entries) {
            val prompt = FieldAiCopilot.buildUserPrompt(feature, pack)
            assertTrue("prompt blank for $feature", prompt.isNotBlank())
            val keyword = expectedKeywords.getValue(feature)
            assertTrue(
                "prompt for $feature missing keyword '$keyword': ${prompt.take(120)}",
                prompt.contains(keyword, ignoreCase = true),
            )
        }
    }

    @Test
    fun buildSystemAddendum_lightingAdvisorMentionsLightAz() {
        val addendum = FieldAiCopilot.buildSystemAddendum(FieldAiFeature.LIGHTING_ADVISOR)
        assertFalse(addendum.isBlank())
        assertTrue(addendum.contains("LIGHT_AZ"))
    }

    @Test
    fun buildSystemAddendum_eachPack3FeatureIsNonBlank() {
        val pack3 = listOf(
            FieldAiFeature.RETURN_TRIP_PLANNER,
            FieldAiFeature.FALSE_POSITIVE_AUTOPSY,
            FieldAiFeature.COMPARE_TWO_SITES,
            FieldAiFeature.QUESTION_THE_CELL,
            FieldAiFeature.EVIDENCE_CHAIN,
            FieldAiFeature.VOICE_STRUCTURED_FIND,
            FieldAiFeature.PHOTO_CATALOG_ASSIST,
            FieldAiFeature.COVERAGE_GAP_AI,
            FieldAiFeature.PARTNER_HANDOFF,
            FieldAiFeature.RISK_ETHICS_COACH,
        )
        for (feature in pack3) {
            val addendum = FieldAiCopilot.buildSystemAddendum(feature)
            assertTrue("blank addendum for $feature", addendum.isNotBlank())
            assertTrue(
                "addendum for $feature missing LiDAR hard rule: ${addendum.take(200)}",
                addendum.contains("LiDAR", ignoreCase = true) ||
                    addendum.contains("metal", ignoreCase = true),
            )
        }
    }

    @Test
    fun fieldAiFeature_hasExactlyTwentyEntries() {
        assertEquals(20, FieldAiFeature.entries.size)
    }

    @Test
    fun parseNavTargetIds_extractsOrderedUniqueIds() {
        val text = """
            Stop order:
            NAV_TARGET id=1001
            NAV_TARGET id=2002
            NAV_TARGET id=1001
            nav_target id=3003
        """.trimIndent()
        assertEquals(listOf(1001L, 2002L, 3003L), FieldAiCopilot.parseNavTargetIds(text))
        assertTrue(FieldAiCopilot.parseNavTargetIds("no targets").isEmpty())
    }

    @Test
    fun parseVizMode_accepts0to8Only() {
        assertEquals(3, FieldAiCopilot.parseVizMode("try VIZ_MODE=3 next"))
        assertEquals(0, FieldAiCopilot.parseVizMode("VIZ_MODE=0"))
        assertEquals(8, FieldAiCopilot.parseVizMode("viz_mode=8"))
        assertNull(FieldAiCopilot.parseVizMode("VIZ_MODE=9"))
        assertNull(FieldAiCopilot.parseVizMode("no mode"))
    }

    @Test
    fun parseStructuredFindSuggestions() {
        val text = """
            Suggested catalog:
            METAL_TYPE=Iron Nail/Spike
            OUTCOME=Checked - false positive
            STATUS=Excavated
            NOTES=flat iron near fence line
        """.trimIndent()
        assertEquals("Iron Nail/Spike", FieldAiCopilot.parseMetalTypeSuggestion(text))
        assertEquals("Checked - false positive", FieldAiCopilot.parseOutcomeSuggestion(text))
        assertEquals("Excavated", FieldAiCopilot.parseStatusSuggestion(text))
        assertEquals("flat iron near fence line", FieldAiCopilot.parseNotesSuggestion(text))
        assertNull(FieldAiCopilot.parseMetalTypeSuggestion("nothing here"))
    }

    @Test
    fun pack3ShortLabelsMatchSpec() {
        assertEquals("Return trip", FieldAiFeature.RETURN_TRIP_PLANNER.shortLabel)
        assertEquals("FP autopsy", FieldAiFeature.FALSE_POSITIVE_AUTOPSY.shortLabel)
        assertEquals("Compare", FieldAiFeature.COMPARE_TWO_SITES.shortLabel)
        assertEquals("Ask cell", FieldAiFeature.QUESTION_THE_CELL.shortLabel)
        assertEquals("Evidence", FieldAiFeature.EVIDENCE_CHAIN.shortLabel)
        assertEquals("Voice find", FieldAiFeature.VOICE_STRUCTURED_FIND.shortLabel)
        assertEquals("Photo AI", FieldAiFeature.PHOTO_CATALOG_ASSIST.shortLabel)
        assertEquals("Gaps", FieldAiFeature.COVERAGE_GAP_AI.shortLabel)
        assertEquals("Handoff", FieldAiFeature.PARTNER_HANDOFF.shortLabel)
        assertEquals("Ethics", FieldAiFeature.RISK_ETHICS_COACH.shortLabel)
        assertFalse(FieldAiFeature.RETURN_TRIP_PLANNER.prefersViewportImage)
        assertTrue(FieldAiFeature.FALSE_POSITIVE_AUTOPSY.prefersViewportImage)
        assertFalse(FieldAiFeature.PHOTO_CATALOG_ASSIST.prefersViewportImage)
        assertTrue(FieldAiFeature.COVERAGE_GAP_AI.prefersViewportImage)
    }
}
