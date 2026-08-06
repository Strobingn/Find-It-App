package com.example.data

import java.io.Serializable

/**
 * Represents a detected and logged metallic target signal on our map grid.
 */
data class TargetSignal(
    val id: Long = System.currentTimeMillis() + (0..1000).random(),
    val gridX: Float, // X position (0 to 100) on our DEM grid
    val gridY: Float, // Y position (0 to 100) on our DEM grid
    val metalType: MetalType,
    val signalStrength: Float, // percentage (0f to 100f)
    val depthCm: Int? = null, // Only known for the built-in simulation or manual field notes
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Actual device GPS position captured when this marker was saved, if a fresh fix existed. */
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAccuracyMeters: Float? = null,
    val source: DetectionSource = DetectionSource.MANUAL,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = "",
    val photoUris: List<String> = emptyList(),
    /** App-private audio recordings captured while reviewing this specific target. */
    val voiceNoteUris: List<String> = emptyList(),
    val status: String = "Logged", // "Logged", "Excavated", "Anomalous", "Trash"
    /**
     * Ground truth from field-checking this find, used to feed the verified-outcome feedback
     * loop back into future terrain analysis of the same dataset. Distinct from [status], which
     * describes physical handling state rather than whether the detection itself was correct.
     */
    val outcome: VerificationOutcome = VerificationOutcome.UNVERIFIED,
    /**
     * Signature of the analyzed terrain dataset this find was logged against, when known (set for
     * AI-suggested markers). Lets feedback be matched back to the exact dataset it came from
     * instead of any dataset that happens to have a candidate at a similar grid position.
     */
    val datasetKey: String? = null,
    /**
     * Stable identity of the imported LiDAR source that owns this marker. Unlike [datasetKey],
     * this remains unchanged when the same file is re-rendered or refined at another resolution.
     */
    val terrainKey: String? = null,
    /**
     * Name of the [com.example.analysis.MetalDetectingTargetType] this marker was logged from,
     * when it came from an AI candidate. Lets a later field-verified [outcome] be attributed back
     * to the specific feature type that was predicted, so per-type detection confidence can be
     * calibrated from real outcomes across every dataset the user has ever checked - not just the
     * one exact spot [datasetKey]-based feedback already re-scores.
     */
    val detectedFeatureType: String? = null,
    /** User-flagged priority find for return trips, sorting, and AI offline drafts. */
    val starred: Boolean = false,
) : Serializable

fun targetsForTerrain(signals: List<TargetSignal>, terrainKey: String): List<TargetSignal> =
    signals.filter { it.terrainKey == terrainKey }

/**
 * Field-verified outcome of a logged find, used as ground truth to adjust future candidate
 * scoring for the same terrain dataset. Not a prediction - only ever set by the user after
 * checking a location in the field.
 */
enum class VerificationOutcome(val label: String) {
    UNVERIFIED("Not yet checked"),
    CONFIRMED_FEATURE("Confirmed real feature"),
    REJECTED_FALSE_POSITIVE("Checked - false positive"),
    INCONCLUSIVE("Checked - inconclusive"),
}

enum class MetalType(val label: String, val colorHex: Long) {
    GOLD("Gold Coin/Ring", 0xFFFFD700),
    SILVER("Silver Relic", 0xFFC0C0C0),
    BRONZE("Bronze artifact", 0xFFCD7F32),
    IRON("Iron Nail/Spike", 0xFF8B0000),
    MAGNETIC_ANOMALY("AI target", 0xFF29B6F6),
    MANUAL_MARKER("Manual marker", 0xFFFFB300),
}

enum class DetectionSource {
    SIMULATED,
    MAGNETOMETER,
    MANUAL,
    AI_ANALYSIS,
    CLOUD_AI,
}
