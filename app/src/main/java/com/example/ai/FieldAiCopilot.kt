package com.example.ai

import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainIntelligenceResult
import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.ExcavationLogEntry
import com.example.data.field.FieldNavigation
import com.example.data.field.FindSiteClusterer
import java.util.Locale

/**
 * Twenty AI-heavy field features that turn session context into specialist prompts
 * for [TerrainAiGateway] (OpenAI primary, Gemini fallback). Pack 1 (10) + pack 3 (10).
 */
enum class FieldAiFeature(
    val title: String,
    val shortLabel: String,
    val description: String,
    /** Prefer attaching the current terrain viewport image when available. */
    val prefersViewportImage: Boolean,
) {
    DIG_BRIEF(
        title = "Dig brief",
        shortLabel = "Dig brief",
        description = "Next-dig briefing: where, why, what to look for, risk of false positives",
        prefersViewportImage = true,
    ),
    SITE_NARRATIVE(
        title = "Site narrative",
        shortLabel = "Narrative",
        description = "Occupation / scatter story from clustered finds and field outcomes",
        prefersViewportImage = false,
    ),
    LIGHTING_ADVISOR(
        title = "Lighting advisor",
        shortLabel = "Lighting",
        description = "Recommend hillshade sun angles for earthworks; emits LIGHT_AZ / LIGHT_ALT",
        prefersViewportImage = true,
    ),
    SWEEP_PLAN(
        title = "Sweep plan",
        shortLabel = "Sweep",
        description = "Priority zones and walk order from coverage gaps + candidates",
        prefersViewportImage = true,
    ),
    FIELD_REPORT(
        title = "Field report",
        shortLabel = "Report",
        description = "Multi-section session report ready to share with partners",
        prefersViewportImage = false,
    ),
    OUTCOME_COACH(
        title = "Outcome coach",
        shortLabel = "Outcomes",
        description = "Calibrate strategy from confirmed vs false-positive outcomes",
        prefersViewportImage = false,
    ),
    FIND_INTERPRETER(
        title = "Find interpreter",
        shortLabel = "Finds AI",
        description = "Interpret notes, metal types, and status of logged finds",
        prefersViewportImage = false,
    ),
    HISTORIC_CORRELATOR(
        title = "Historic correlator",
        shortLabel = "Historic",
        description = "Correlate terrain candidates with historic homesite / road patterns",
        prefersViewportImage = true,
    ),
    ANOMALY_DEEPDIVE(
        title = "Anomaly deep-dive",
        shortLabel = "Deep-dive",
        description = "Deep analysis of top candidates with optional map markers",
        prefersViewportImage = true,
    ),
    DAY_DEBRIEF(
        title = "Day debrief",
        shortLabel = "Debrief",
        description = "End-of-day structured debrief from freeform notes + session data",
        prefersViewportImage = false,
    ),
    // --- Pack 3 ---
    RETURN_TRIP_PLANNER(
        title = "Return-trip planner",
        shortLabel = "Return trip",
        description = "Ordered next-visit stops from starred finds and open digs; optional NAV_TARGET lines",
        prefersViewportImage = false,
    ),
    FALSE_POSITIVE_AUTOPSY(
        title = "False-positive autopsy",
        shortLabel = "FP autopsy",
        description = "Why rejected finds looked real; never-again cues and VIZ_MODE suggestions",
        prefersViewportImage = true,
    ),
    COMPARE_TWO_SITES(
        title = "Compare two sites",
        shortLabel = "Compare",
        description = "Side-by-side rank of primary vs secondary parcel/tile for hunting priority",
        prefersViewportImage = false,
    ),
    QUESTION_THE_CELL(
        title = "Question the cell",
        shortLabel = "Ask cell",
        description = "Plain-language micro-topography for an inspected cell + viz mode",
        prefersViewportImage = true,
    ),
    EVIDENCE_CHAIN(
        title = "Evidence chain",
        shortLabel = "Evidence",
        description = "Observation → inference → field test chain for top candidates",
        prefersViewportImage = true,
    ),
    VOICE_STRUCTURED_FIND(
        title = "Voice → structured find",
        shortLabel = "Voice find",
        description = "Freeform notes/transcript → suggested METAL_TYPE / OUTCOME / STATUS / NOTES",
        prefersViewportImage = false,
    ),
    PHOTO_CATALOG_ASSIST(
        title = "Photo catalog assist",
        shortLabel = "Photo AI",
        description = "Catalog hints from find notes (no dating claims); vision optional later",
        prefersViewportImage = false,
    ),
    COVERAGE_GAP_AI(
        title = "Coverage gap AI",
        shortLabel = "Gaps",
        description = "Unswept high-value terrain relative to trails; map targets for gaps",
        prefersViewportImage = true,
    ),
    PARTNER_HANDOFF(
        title = "Partner handoff brief",
        shortLabel = "Handoff",
        description = "One-page brief for a teammate taking over the detector",
        prefersViewportImage = false,
    ),
    RISK_ETHICS_COACH(
        title = "Risk & ethics coach",
        shortLabel = "Ethics",
        description = "Do/don't dig cautions; never invent property or heritage law",
        prefersViewportImage = false,
    ),
}

/** Packed field session context for AI copilot prompts. */
data class FieldAiSessionPack(
    val terrainSummary: String,
    val terrainContext: String,
    val sunAzimuth: Float,
    val sunAltitude: Float,
    val gridWidth: Int,
    val gridHeight: Int,
    val cellSizeMeters: Float,
    val deviceLatitude: Double? = null,
    val deviceLongitude: Double? = null,
    val signals: List<TargetSignal> = emptyList(),
    val excavationLogs: List<ExcavationLogEntry> = emptyList(),
    val breadcrumbTracks: List<BreadcrumbTrack> = emptyList(),
    val localResult: TerrainIntelligenceResult? = null,
    val freeformNotes: String = "",
    /** Plain-language / structured summary of the user-inspected terrain cell. */
    val inspectedCellSummary: String = "",
    /** Current visualization mode 0..8 (hillshade, LRM, etc.). */
    val visualizationMode: Int = 0,
    /** Secondary parcel/tile terrain summary for compare-two-sites. */
    val secondaryTerrainSummary: String = "",
    val secondaryCandidateCount: Int = 0,
    val secondaryFindCount: Int = 0,
    val secondaryTerrainContext: String = "",
    /**
     * Focused terrain candidate the operator picked (e.g. from target details).
     * Used by dig brief / evidence chain / deep-dive when non-blank.
     */
    val selectedCandidateSummary: String = "",
    /** Optional ground-quality banner (valid %, canopy, georef) — not metal/age/depth. */
    val terrainQualitySummary: String = "",
)

object FieldAiCopilot {

    private val lightAzPattern = Regex("""LIGHT_AZ\s*=\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)
    private val lightAltPattern = Regex("""LIGHT_ALT\s*=\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE)

    data class LightingRecommendation(val azimuth: Float, val altitude: Float)

    fun parseLightingRecommendation(text: String): LightingRecommendation? {
        val az = lightAzPattern.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return null
        val alt = lightAltPattern.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return null
        return LightingRecommendation(
            azimuth = ((az % 360f) + 360f) % 360f,
            altitude = alt.coerceIn(5f, 85f),
        )
    }

    // Pack-3 structured-tag parsers (delegates keep call sites on FieldAiCopilot)
    fun parseNavTargetIds(text: String): List<Long> = FieldAiStructuredTags.parseNavTargetIds(text)
    fun parseVizMode(text: String): Int? = FieldAiStructuredTags.parseVizMode(text)
    fun parseMetalTypeSuggestion(text: String): String? = FieldAiStructuredTags.parseMetalTypeSuggestion(text)
    fun parseOutcomeSuggestion(text: String): String? = FieldAiStructuredTags.parseOutcomeSuggestion(text)
    fun parseStatusSuggestion(text: String): String? = FieldAiStructuredTags.parseStatusSuggestion(text)
    fun parseNotesSuggestion(text: String): String? = FieldAiStructuredTags.parseNotesSuggestion(text)

    fun buildUserPrompt(feature: FieldAiFeature, pack: FieldAiSessionPack): String {
        val body = when (feature) {
            FieldAiFeature.DIG_BRIEF -> digBriefPrompt(pack)
            FieldAiFeature.SITE_NARRATIVE -> siteNarrativePrompt(pack)
            FieldAiFeature.LIGHTING_ADVISOR -> lightingAdvisorPrompt(pack)
            FieldAiFeature.SWEEP_PLAN -> sweepPlanPrompt(pack)
            FieldAiFeature.FIELD_REPORT -> fieldReportPrompt(pack)
            FieldAiFeature.OUTCOME_COACH -> outcomeCoachPrompt(pack)
            FieldAiFeature.FIND_INTERPRETER -> findInterpreterPrompt(pack)
            FieldAiFeature.HISTORIC_CORRELATOR -> historicCorrelatorPrompt(pack)
            FieldAiFeature.ANOMALY_DEEPDIVE -> anomalyDeepDivePrompt(pack)
            FieldAiFeature.DAY_DEBRIEF -> dayDebriefPrompt(pack)
            FieldAiFeature.RETURN_TRIP_PLANNER -> returnTripPlannerPrompt(pack)
            FieldAiFeature.FALSE_POSITIVE_AUTOPSY -> falsePositiveAutopsyPrompt(pack)
            FieldAiFeature.COMPARE_TWO_SITES -> compareTwoSitesPrompt(pack)
            FieldAiFeature.QUESTION_THE_CELL -> questionTheCellPrompt(pack)
            FieldAiFeature.EVIDENCE_CHAIN -> evidenceChainPrompt(pack)
            FieldAiFeature.VOICE_STRUCTURED_FIND -> voiceStructuredFindPrompt(pack)
            FieldAiFeature.PHOTO_CATALOG_ASSIST -> photoCatalogAssistPrompt(pack)
            FieldAiFeature.COVERAGE_GAP_AI -> coverageGapAiPrompt(pack)
            FieldAiFeature.PARTNER_HANDOFF -> partnerHandoffPrompt(pack)
            FieldAiFeature.RISK_ETHICS_COACH -> riskEthicsCoachPrompt(pack)
        }
        return body.trim()
    }

    fun buildSystemAddendum(feature: FieldAiFeature): String = when (feature) {
        FieldAiFeature.DIG_BRIEF -> """
            You are a senior metal-detecting field lead and archaeological remote-sensing specialist.
            Produce a practical dig brief. Never claim buried metal is proven from LiDAR alone.
            Use short sections with clear headings. Rank uncertainty honestly.
        """.trimIndent()
        FieldAiFeature.SITE_NARRATIVE -> """
            You are a historical landscape interpreter. Weave finds, outcomes, and terrain into a
            cautious site narrative. Separate evidence from speculation.
        """.trimIndent()
        FieldAiFeature.LIGHTING_ADVISOR -> """
            You are a LiDAR visualization specialist. Recommend sun azimuth and altitude for
            revealing earthworks on hillshade. End with exactly two machine lines:
            LIGHT_AZ=<0-360>
            LIGHT_ALT=<5-85>
        """.trimIndent()
        FieldAiFeature.SWEEP_PLAN -> """
            You are a field survey planner. Produce an efficient sweep plan that respects GPS
            coverage already done, high-value terrain candidates, and find clusters.
        """.trimIndent()
        FieldAiFeature.FIELD_REPORT -> """
            You are writing a professional field report for partners. Clear sections, no hype,
            actionable next steps, and explicit confidence language.
        """.trimIndent()
        FieldAiFeature.OUTCOME_COACH -> """
            You are a detection-strategy coach. Use verification outcomes to reduce false positives
            and reinforce productive patterns. Be specific and operational.
        """.trimIndent()
        FieldAiFeature.FIND_INTERPRETER -> """
            You are a finds catalog analyst. Interpret notes, metal types, and status without
            inventing provenience. Flag data quality gaps.
        """.trimIndent()
        FieldAiFeature.HISTORIC_CORRELATOR -> """
            You are a historic-landscape correlator. Link terrain candidates to plausible
            historic occupation/access patterns (homesites, roads, outbuildings). Mark uncertainty.
        """.trimIndent()
        FieldAiFeature.ANOMALY_DEEPDIVE -> """
            You are an archaeological remote-sensing analyst doing a deep-dive on top candidates.
            If a terrain image is attached, after the written analysis emit up to 6 map markers:
            [MAP_TARGET x=42.0 y=61.0 confidence=0.82 label=short label]
            Coordinates are 0..100 left-to-right and top-to-bottom on the attached image.
        """.trimIndent()
        FieldAiFeature.DAY_DEBRIEF -> """
            You are a field team lead writing an end-of-day debrief. Structure wins, misses,
            unfinished work, and tomorrow's priorities. Be blunt and useful.
        """.trimIndent()
        FieldAiFeature.RETURN_TRIP_PLANNER -> """
            You are a field logistics planner. Order return-trip stops from starred finds and open digs.
            Hard rule: LiDAR does not prove buried metal, age, or depth.
            After the plan, optionally emit ordered machine lines: NAV_TARGET id=<long>
            using only ids provided in the user context. Never invent ids.
        """.trimIndent()
        FieldAiFeature.FALSE_POSITIVE_AUTOPSY -> """
            You are a detection QA analyst. Autopsy false positives only — what looked real and why it failed.
            Hard rule: LiDAR does not prove buried metal, age, or depth.
            After analysis, optionally suggest viz modes as machine lines: VIZ_MODE=<0-8>
            (0 hillshade, 1 multi-hillshade, 2 slope, 3 LRM, 4 curvature, 5 disturbance, 6 aspect, 7 elev, 8 canopy).
        """.trimIndent()
        FieldAiFeature.COMPARE_TWO_SITES -> """
            You are a survey prioritization analyst. Compare Site A (primary) vs Site B (secondary)
            with explicit scores and a recommended pick. Separate evidence from speculation.
            Hard rule: LiDAR does not prove buried metal, age, or depth.
        """.trimIndent()
        FieldAiFeature.QUESTION_THE_CELL -> """
            You are a micro-topography explainer. Answer what the inspected cell likely shows in plain language.
            Hard rule: LiDAR does not prove buried metal, age, or depth. Give 5 tight bullets plus uncertainty.
        """.trimIndent()
        FieldAiFeature.EVIDENCE_CHAIN -> """
            You are an explainability specialist for terrain rankers. Build numbered evidence chains:
            observation → inference → field test. Hard rule: LiDAR does not prove buried metal, age, or depth.
            If an image is attached you may add up to 4 [MAP_TARGET x= y= confidence= label=] lines.
        """.trimIndent()
        FieldAiFeature.VOICE_STRUCTURED_FIND -> """
            You are a finds data-entry assistant. Convert freeform field notes into suggested catalog fields.
            Do not invent provenience. User will confirm before save.
            End with machine lines when confident: METAL_TYPE=…  OUTCOME=…  STATUS=…  NOTES=…
            (one value per line; omit a line if unknown).
        """.trimIndent()
        FieldAiFeature.PHOTO_CATALOG_ASSIST -> """
            You are a finds catalog assistant. Suggest labels and what to photograph next from notes/context.
            Never claim dating, authenticity, or monetary value as fact. No age/depth/metal proven from LiDAR.
            Catalog hints only; mark uncertainty.
        """.trimIndent()
        FieldAiFeature.COVERAGE_GAP_AI -> """
            You are a survey coverage analyst. Identify gaps between walked GPS trails and high-value terrain.
            Hard rule: LiDAR does not prove buried metal, age, or depth.
            After the written plan, if useful emit up to 6 [MAP_TARGET x= y= confidence= label=] for gap centroids
            (0..100 image coords when an image is attached; otherwise use grid percent language in prose).
        """.trimIndent()
        FieldAiFeature.PARTNER_HANDOFF -> """
            You write a concise one-page handoff for a teammate taking over the detector.
            Be operational: nearest priorities, open digs, hazards, radio-style brevity. No hype.
            Hard rule: LiDAR does not prove buried metal, age, or depth.
        """.trimIndent()
        FieldAiFeature.RISK_ETHICS_COACH -> """
            You are a field ethics and risk coach. List practical do/don't dig cautions from context.
            Never invent property ownership, statutes, permit status, or cemetery boundaries as facts.
            Urge users to verify land permission and local heritage rules themselves. No legal advice.
            Hard rule: LiDAR does not prove buried metal, age, or depth.
        """.trimIndent()
    }

    // ------------------------------------------------------------------
    // Prompt builders — pack 1
    // ------------------------------------------------------------------

    private fun digBriefPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Generate a NEXT DIG BRIEF for this session.")
        appendLine("Sections required:")
        appendLine("1) Priority dig points (grid % or lat/lon when known)")
        appendLine("2) Why each point matters (terrain evidence + finds context)")
        appendLine("3) What to look for with a metal detector / shovel test")
        appendLine("4) False-positive risks")
        appendLine("5) Suggested order and time budget (minutes)")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        append(sessionFacts(pack))
        append(focusedCandidateBlock(pack))
        if (pack.inspectedCellSummary.isNotBlank()) {
            appendLine("--- Inspected cell (operator focus) ---")
            appendLine(pack.inspectedCellSummary.take(2_000))
            appendLine()
        }
        append(candidatesBrief(pack.localResult, limit = 10, detailed = true))
        append(findsDetail(pack.signals.filter { it.starred || it.outcome != VerificationOutcome.UNVERIFIED }, limit = 15))
    }

    private fun siteNarrativePrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Write a SITE NARRATIVE from the finds and outcomes.")
        appendLine("Cover: site character, likely activity zones, chronology clues (if any),")
        appendLine("scatter vs nucleus, and open questions. Keep under 500 words.")
        appendLine()
        append(sessionFacts(pack))
        appendLine()
        append(findsDetail(pack.signals, limit = 40))
        appendLine()
        append(sitesSummary(pack.signals))
    }

    private fun lightingAdvisorPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Recommend hillshade lighting for earthwork inspection.")
        appendLine("Current sun: azimuth ${pack.sunAzimuth}°, altitude ${pack.sunAltitude}°.")
        appendLine("Explain why, then end with LIGHT_AZ= and LIGHT_ALT= lines only as the final two lines.")
        appendLine()
        append(sessionFacts(pack))
        append(candidatesBrief(pack.localResult))
    }

    private fun sweepPlanPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Create a SWEEP PLAN for metal-detecting this parcel.")
        appendLine("Include: zones A/B/C, order, coil-width assumptions (default 2 m),")
        appendLine("where GPS trail coverage already exists, and skip zones.")
        appendLine()
        append(sessionFacts(pack))
        append(trailSummary(pack.breadcrumbTracks))
        append(candidatesBrief(pack.localResult))
        append(sitesSummary(pack.signals))
    }

    private fun fieldReportPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Write a FIELD REPORT with sections:")
        appendLine("Summary · Terrain · Local AI candidates · Finds · Dig logs · Trails · Recommendations · Data gaps")
        appendLine()
        append(sessionFacts(pack))
        append(findsDetail(pack.signals, limit = 50))
        append(digsSummary(pack.excavationLogs))
        append(trailSummary(pack.breadcrumbTracks))
        append(candidatesBrief(pack.localResult))
    }

    private fun outcomeCoachPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Act as OUTCOME COACH. Analyze verification outcomes and coach next detections.")
        appendLine("Focus on what predicted well, what was false-positive, and how to adjust.")
        appendLine()
        append(sessionFacts(pack))
        append(outcomeBreakdown(pack.signals))
        append(findsDetail(pack.signals.filter { it.outcome != VerificationOutcome.UNVERIFIED }, limit = 40))
        append(candidatesBrief(pack.localResult))
    }

    private fun findInterpreterPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("INTERPRET the logged finds catalog. Group by type/status, highlight notes that")
        appendLine("suggest habitation, industry, or trash, and list missing fields to fill.")
        appendLine()
        append(sessionFacts(pack))
        append(findsDetail(pack.signals, limit = 60))
        append(sitesSummary(pack.signals))
    }

    private fun historicCorrelatorPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("HISTORIC CORRELATION: relate local terrain candidates and finds to plausible")
        appendLine("historic occupation (homesite, cellar, wagon road, outbuilding scatter, etc.).")
        appendLine("Do not invent archival proof. Rank hypotheses.")
        appendLine()
        append(sessionFacts(pack))
        append(candidatesBrief(pack.localResult, limit = 20))
        append(sitesSummary(pack.signals))
        append(findsDetail(pack.signals, limit = 30))
    }

    private fun anomalyDeepDivePrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("ANOMALY DEEP-DIVE on the strongest local candidates and the visible terrain.")
        appendLine("For each: morphology, natural vs cultural likelihood, field-check method, confidence.")
        appendLine("If an image is attached, add MAP_TARGET lines for the best 3–6 check points.")
        appendLine()
        append(sessionFacts(pack))
        append(focusedCandidateBlock(pack))
        append(candidatesBrief(pack.localResult, limit = 15, detailed = true))
    }

    private fun dayDebriefPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Write an END-OF-DAY DEBRIEF.")
        appendLine("Sections: What we did · What worked · What failed · Unfinished · Tomorrow plan · Gear/data notes")
        if (pack.freeformNotes.isNotBlank()) {
            appendLine()
            appendLine("Operator freeform notes:")
            appendLine(pack.freeformNotes.take(3_000))
        }
        appendLine()
        append(sessionFacts(pack))
        append(findsDetail(pack.signals, limit = 40))
        append(digsSummary(pack.excavationLogs))
        append(trailSummary(pack.breadcrumbTracks))
        append(outcomeBreakdown(pack.signals))
    }

    // ------------------------------------------------------------------
    // Prompt builders — pack 3
    // ------------------------------------------------------------------

    private fun returnTripPlannerPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Build a RETURN TRIP PLAN for the next visit.")
        appendLine("Prioritize starred finds and open (incomplete) digs. Include order, reason, and time budget.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine("After the plan, optionally emit ordered machine lines only for provided ids:")
        appendLine("NAV_TARGET id=<long>")
        appendLine()
        append(sessionFacts(pack))
        val starred = pack.signals.filter { it.starred }
        appendLine("--- Starred finds (${starred.size}) ---")
        if (starred.isEmpty()) {
            appendLine("None starred.")
        } else {
            starred.forEachIndexed { i, s ->
                appendLine(
                    "${i + 1}. id=${s.id} ${s.metalType.label} · status=${s.status} · " +
                        locLabel(s) + noteSuffix(s.notes),
                )
            }
        }
        val openDigs = pack.excavationLogs.filter { !it.isComplete }
        appendLine()
        appendLine("--- Open digs (${openDigs.size}) ---")
        if (openDigs.isEmpty()) {
            appendLine("None open.")
        } else {
            openDigs.takeLast(25).forEach { log ->
                val notes = listOf(log.soilNotes, log.findsDescription)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .take(100)
                appendLine("targetId=${log.targetId} complete=false depth=${log.depthCentimeters ?: "?"} notes=$notes")
            }
        }
        appendLine()
        append(trailSummary(pack.breadcrumbTracks))
        append(candidatesBrief(pack.localResult, limit = 10))
        append(findsDetail(pack.signals, limit = 20))
    }

    private fun falsePositiveAutopsyPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("FALSE-POSITIVE AUTOPSY: explain why rejected finds looked real and how to avoid repeats.")
        appendLine("Use only rejected outcomes plus local terrain candidates as evidence.")
        appendLine("Deliver: pattern checklist of 'never again' cues, and optional VIZ_MODE=0..8 lines.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        append(sessionFacts(pack))
        val rejected = pack.signals.filter {
            it.outcome == VerificationOutcome.REJECTED_FALSE_POSITIVE
        }
        appendLine("--- Rejected false positives (${rejected.size}) ---")
        if (rejected.isEmpty()) {
            appendLine("None logged as false positive yet.")
        } else {
            append(findsDetail(rejected, limit = 40))
        }
        append(outcomeBreakdown(pack.signals))
        append(candidatesBrief(pack.localResult, limit = 15, detailed = true))
        appendLine("Current viz mode: ${pack.visualizationMode} (${vizModeLabel(pack.visualizationMode)})")
    }

    private fun compareTwoSitesPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("COMPARE TWO SITES for hunting priority. Score A vs B and pick one with reasons.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        appendLine("--- Site A (primary) ---")
        append(sessionFacts(pack))
        append(candidatesBrief(pack.localResult, limit = 12))
        append(sitesSummary(pack.signals))
        append(findsDetail(pack.signals, limit = 20))
        appendLine()
        appendLine("--- Site B (secondary) ---")
        if (pack.secondaryTerrainSummary.isBlank() && pack.secondaryTerrainContext.isBlank()) {
            appendLine("Secondary site data not provided. Note that comparison is incomplete.")
        } else {
            if (pack.secondaryTerrainContext.isNotBlank()) {
                appendLine(pack.secondaryTerrainContext.trim())
            }
            appendLine("Terrain summary: ${pack.secondaryTerrainSummary.ifBlank { "(none)" }}")
            appendLine("Secondary candidate count: ${pack.secondaryCandidateCount}")
            appendLine("Secondary find count: ${pack.secondaryFindCount}")
        }
        appendLine()
        appendLine("Produce side-by-side scores (terrain promise, find density, access/risk, data quality)")
        appendLine("and a clear recommended pick with caveats.")
    }

    private fun questionTheCellPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("QUESTION THE CELL: explain the inspected micro-topography in plain language.")
        appendLine("Answer format: 5 bullets — what it looks like, natural vs cultural likelihood,")
        appendLine("what to check in the field, what would disprove it, confidence.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        append(sessionFacts(pack))
        appendLine("Visualization mode: ${pack.visualizationMode} (${vizModeLabel(pack.visualizationMode)})")
        appendLine("Sun: az=${pack.sunAzimuth}° alt=${pack.sunAltitude}°")
        appendLine()
        appendLine("--- Inspected cell ---")
        if (pack.inspectedCellSummary.isBlank()) {
            appendLine("No cell summary provided. Describe general viewport morphology if an image is attached.")
        } else {
            appendLine(pack.inspectedCellSummary.take(4_000))
        }
        appendLine()
        append(candidatesBrief(pack.localResult, limit = 8, detailed = true))
    }

    private fun evidenceChainPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("EVIDENCE CHAIN for the top local candidates.")
        appendLine("For each candidate produce a numbered chain: observation → inference → field test.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        append(sessionFacts(pack))
        append(focusedCandidateBlock(pack))
        if (pack.selectedCandidateSummary.isNotBlank()) {
            appendLine("Prioritize a full evidence chain for the FOCUSED candidate above, then cover the next top candidates.")
            appendLine()
        }
        append(candidatesBrief(pack.localResult, limit = 10, detailed = true))
        append(findsDetail(pack.signals, limit = 15))
        append(outcomeBreakdown(pack.signals))
    }

    private fun voiceStructuredFindPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("VOICE / FREEFORM → STRUCTURED FIND fields.")
        appendLine("From the transcript/notes, suggest catalog values the operator can confirm.")
        appendLine("End with machine lines when confident (omit unknown):")
        appendLine("METAL_TYPE=<type>")
        appendLine("OUTCOME=<outcome>")
        appendLine("STATUS=<status>")
        appendLine("NOTES=<cleaned notes>")
        appendLine("Do not invent location or provenience. Hard rule: LiDAR ≠ metal/age/depth.")
        appendLine()
        if (pack.freeformNotes.isNotBlank()) {
            appendLine("--- Freeform notes / voice transcript ---")
            appendLine(pack.freeformNotes.take(4_000))
        } else {
            appendLine("No freeform notes provided — ask what fields can still be inferred from session finds.")
        }
        appendLine()
        append(sessionFacts(pack))
        append(findsDetail(pack.signals, limit = 15))
    }

    private fun photoCatalogAssistPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("PHOTO / CATALOG ASSIST for finds (text/notes based; no dating claims).")
        appendLine("Suggest catalog labels, missing fields, and what to photograph next.")
        appendLine("Never claim age, authenticity, or value as fact. LiDAR does not prove metal/age/depth.")
        appendLine()
        if (pack.freeformNotes.isNotBlank()) {
            appendLine("--- Operator notes ---")
            appendLine(pack.freeformNotes.take(3_000))
            appendLine()
        }
        append(sessionFacts(pack))
        append(photoInventory(pack))
        append(findsDetail(pack.signals, limit = 40))
        append(sitesSummary(pack.signals))
        append(digsSummary(pack.excavationLogs))
    }

    private fun coverageGapAiPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("COVERAGE GAP AI: find where trails have not covered high-value terrain.")
        appendLine("Describe gap zones, priority order, and suggested re-walk lanes.")
        appendLine("Mention MAP_TARGET protocol for gap centroids when an image is attached:")
        appendLine("[MAP_TARGET x=.. y=.. confidence=.. label=..]")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        append(sessionFacts(pack))
        append(trailSummary(pack.breadcrumbTracks))
        append(candidatesBrief(pack.localResult, limit = 15, detailed = true))
        append(sitesSummary(pack.signals))
        append(findsDetail(pack.signals, limit = 20))
    }

    private fun partnerHandoffPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("Write a PARTNER HANDOFF brief (one page, radio-style, share-sheet ready).")
        appendLine("Sections: Where we are · What is hot · Open digs · Nearest priorities ·")
        appendLine("Do not dig / watch-outs · Suggested next 60 minutes · Data state.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        append(sessionFacts(pack))
        val starred = pack.signals.filter { it.starred }
        if (starred.isNotEmpty()) {
            appendLine("--- Starred for handoff ---")
            starred.take(12).forEach { s ->
                appendLine("id=${s.id} ${s.metalType.label} · ${locLabel(s)}${noteSuffix(s.notes)}")
            }
            appendLine()
        }
        append(findsDetail(pack.signals, limit = 25))
        append(digsSummary(pack.excavationLogs))
        append(trailSummary(pack.breadcrumbTracks))
        append(candidatesBrief(pack.localResult, limit = 8))
        append(outcomeBreakdown(pack.signals))
    }

    private fun riskEthicsCoachPrompt(pack: FieldAiSessionPack): String = buildString {
        appendLine("RISK & ETHICS COACH: practical do/don't dig cautions for this session.")
        appendLine("Never invent property ownership, permits, cemetery status, or statutes.")
        appendLine("Urge the operator to verify permission and local heritage rules themselves.")
        appendLine("Hard rule: LiDAR does not prove buried metal, age, or depth.")
        appendLine()
        if (pack.freeformNotes.isNotBlank()) {
            appendLine("--- Operator notes / concerns ---")
            appendLine(pack.freeformNotes.take(3_000))
            appendLine()
        }
        append(sessionFacts(pack))
        append(findsDetail(pack.signals, limit = 20))
        append(digsSummary(pack.excavationLogs))
        append(sitesSummary(pack.signals))
        append(candidatesBrief(pack.localResult, limit = 8))
    }

    // ------------------------------------------------------------------
    // Context formatters
    // ------------------------------------------------------------------

    private fun sessionFacts(pack: FieldAiSessionPack): String = buildString {
        appendLine("--- Session facts ---")
        appendLine(pack.terrainContext.trim())
        appendLine("Terrain summary: ${pack.terrainSummary}")
        appendLine("Raster: ${pack.gridWidth}x${pack.gridHeight} @ ${pack.cellSizeMeters} m/cell")
        appendLine("Sun: az=${pack.sunAzimuth}° alt=${pack.sunAltitude}°")
        appendLine("Visualization mode: ${pack.visualizationMode} (${vizModeLabel(pack.visualizationMode)})")
        if (pack.terrainQualitySummary.isNotBlank()) {
            appendLine("Ground quality: ${pack.terrainQualitySummary}")
        }
        val lat = pack.deviceLatitude
        val lon = pack.deviceLongitude
        if (lat != null && lon != null) {
            appendLine(String.format(Locale.US, "Device GPS: %.6f, %.6f", lat, lon))
        } else {
            appendLine("Device GPS: unavailable")
        }
        appendLine("Logged finds: ${pack.signals.size}")
        appendLine("Starred finds: ${pack.signals.count { it.starred }}")
        val photoFinds = pack.signals.count { it.photoUris.isNotEmpty() }
        val voiceFinds = pack.signals.count { it.voiceNoteUris.isNotEmpty() }
        val totalPhotos = pack.signals.sumOf { it.photoUris.size } +
            pack.excavationLogs.sumOf { it.photoUris.size }
        val totalVoice = pack.signals.sumOf { it.voiceNoteUris.size } +
            pack.excavationLogs.sumOf { it.voiceNoteUris.size }
        appendLine("Media: $totalPhotos photo(s) on $photoFinds find(s); $totalVoice voice note(s) on $voiceFinds find(s)")
        appendLine("Dig logs: ${pack.excavationLogs.size}")
        appendLine("GPS trails: ${pack.breadcrumbTracks.size}")
        pack.localResult?.let {
            appendLine("Local analysis recommendation: ${it.recommendation}")
            appendLine("Local candidates: ${it.candidates.size}")
        } ?: appendLine("Local analysis: not run")
        if (pack.inspectedCellSummary.isNotBlank()) {
            appendLine("Inspected cell: present (${pack.inspectedCellSummary.take(80)}…)")
        }
        if (pack.selectedCandidateSummary.isNotBlank()) {
            appendLine("Focused candidate: present")
        }
        if (pack.secondaryTerrainSummary.isNotBlank() || pack.secondaryTerrainContext.isNotBlank()) {
            appendLine("Secondary site for compare: present")
        }
        appendLine()
    }

    private fun focusedCandidateBlock(pack: FieldAiSessionPack): String {
        if (pack.selectedCandidateSummary.isBlank()) return ""
        return buildString {
            appendLine("--- Focused candidate (operator selected) ---")
            appendLine(pack.selectedCandidateSummary.take(4_000))
            appendLine()
        }
    }

    private fun photoInventory(pack: FieldAiSessionPack): String {
        val withPhotos = pack.signals.filter { it.photoUris.isNotEmpty() }
        val digWithPhotos = pack.excavationLogs.filter { it.photoUris.isNotEmpty() }
        if (withPhotos.isEmpty() && digWithPhotos.isEmpty()) {
            return "Photo inventory: no photos attached to finds or dig logs yet.\n"
        }
        return buildString {
            appendLine("--- Photo inventory ---")
            withPhotos.take(30).forEach { s ->
                append("Find id=${s.id} photos=${s.photoUris.size}")
                if (s.voiceNoteUris.isNotEmpty()) append(" voice=${s.voiceNoteUris.size}")
                append(" · ${s.metalType.label} · ${s.status}")
                if (s.notes.isNotBlank()) append(" · notes=${s.notes.take(80)}")
                appendLine()
            }
            digWithPhotos.take(15).forEach { log ->
                appendLine(
                    "Dig targetId=${log.targetId} photos=${log.photoUris.size}" +
                        if (log.voiceNoteUris.isNotEmpty()) " voice=${log.voiceNoteUris.size}" else "",
                )
            }
            appendLine()
        }
    }

    private fun findsDetail(signals: List<TargetSignal>, limit: Int): String {
        if (signals.isEmpty()) return "Finds: none logged.\n"
        return buildString {
            appendLine("--- Finds (up to $limit) ---")
            signals.sortedByDescending { it.timestamp }.take(limit).forEachIndexed { i, s ->
                val geo = if (s.latitude != null && s.longitude != null) {
                    String.format(Locale.US, "%.5f,%.5f", s.latitude, s.longitude)
                } else {
                    "grid ${s.gridX.toInt()},${s.gridY.toInt()}"
                }
                append("${i + 1}. id=${s.id} ${s.metalType.label}")
                if (s.starred) append(" ★")
                append(" · $geo · ${s.status} · ${s.outcome.label}")
                if (s.photoUris.isNotEmpty()) append(" · photos=${s.photoUris.size}")
                if (s.voiceNoteUris.isNotEmpty()) append(" · voice=${s.voiceNoteUris.size}")
                s.detectedFeatureType?.takeIf { it.isNotBlank() }?.let {
                    append(" · feature=$it")
                }
                if (s.notes.isNotBlank()) append(" · notes=${s.notes.take(120)}")
                appendLine()
            }
        }
    }

    private fun sitesSummary(signals: List<TargetSignal>): String {
        val sites = FindSiteClusterer.cluster(signals)
        if (sites.isEmpty()) return "Sites: no proximity clusters.\n"
        return buildString {
            appendLine("--- Proximity sites ---")
            sites.take(12).forEachIndexed { i, site ->
                appendLine(
                    "${i + 1}. ${site.label} n=${site.signals.size} · types=${site.topTypes.take(4).joinToString(",")} · " +
                        "confirmed=${site.confirmedCount} rejected=${site.rejectedCount}",
                )
            }
        }
    }

    private fun digsSummary(logs: List<ExcavationLogEntry>): String {
        if (logs.isEmpty()) return "Dig logs: none.\n"
        return buildString {
            appendLine("--- Dig logs ---")
            logs.takeLast(20).forEach { log ->
                val notes = listOf(log.soilNotes, log.findsDescription)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .take(100)
                val media = buildString {
                    if (log.photoUris.isNotEmpty()) append(" photos=${log.photoUris.size}")
                    if (log.voiceNoteUris.isNotEmpty()) append(" voice=${log.voiceNoteUris.size}")
                }
                appendLine(
                    "target=${log.targetId} complete=${log.isComplete} " +
                        "depth=${log.depthCentimeters ?: "?"}$media notes=$notes",
                )
            }
        }
    }

    private fun trailSummary(tracks: List<BreadcrumbTrack>): String {
        if (tracks.isEmpty()) return "GPS trails: none.\n"
        val points = tracks.sumOf { it.points.size }
        var meters = 0.0
        tracks.forEach { track ->
            track.points.zipWithNext { a, b ->
                meters += FieldNavigation.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            }
        }
        return "GPS trails: ${tracks.size} track(s), $points points, ~${"%.0f".format(meters)} m walked.\n"
    }

    private fun outcomeBreakdown(signals: List<TargetSignal>): String {
        if (signals.isEmpty()) return "Outcomes: no finds.\n"
        val counts = VerificationOutcome.entries.associateWith { outcome ->
            signals.count { it.outcome == outcome }
        }
        return buildString {
            appendLine("--- Outcome counts ---")
            counts.forEach { (outcome, n) -> appendLine("${outcome.label}: $n") }
        }
    }

    private fun candidatesBrief(
        result: TerrainIntelligenceResult?,
        limit: Int = 12,
        detailed: Boolean = false,
    ): String {
        if (result == null) return "Local candidates: none (run local analysis first for better results).\n"
        val list = result.candidates.sortedByDescending { it.score }.take(limit)
        if (list.isEmpty()) return "Local candidates: empty list.\n"
        return buildString {
            appendLine("--- Local terrain candidates ---")
            list.forEachIndexed { i, c -> appendLine(formatCandidate(i + 1, c, detailed)) }
        }
    }

    private fun formatCandidate(index: Int, c: TerrainFeatureCandidate, detailed: Boolean): String {
        val base = String.format(
            Locale.US,
            "%d. %s score=%.3f at x=%.1f%% y=%.1f%%",
            index,
            c.type.label,
            c.score,
            c.xPercent,
            c.yPercent,
        )
        return if (detailed && c.evidence.isNotEmpty()) {
            "$base evidence=${c.evidence.joinToString(";")}"
        } else {
            base
        }
    }

    private fun vizModeLabel(mode: Int): String = when (mode.coerceIn(0, 8)) {
        0 -> "Hillshade"
        1 -> "Multi-directional hillshade"
        2 -> "Slope"
        3 -> "Local relief"
        4 -> "Curvature"
        5 -> "Disturbance candidates"
        6 -> "Aspect"
        7 -> "Elevation"
        8 -> "Canopy height"
        else -> "Unknown"
    }

    private fun locLabel(s: TargetSignal): String =
        if (s.latitude != null && s.longitude != null) {
            String.format(Locale.US, "%.5f,%.5f", s.latitude, s.longitude)
        } else {
            "grid ${s.gridX.toInt()},${s.gridY.toInt()}"
        }

    private fun noteSuffix(notes: String): String =
        if (notes.isNotBlank()) " · notes=${notes.take(80)}" else ""
}
