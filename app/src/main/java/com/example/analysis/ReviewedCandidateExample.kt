package com.example.analysis

import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The field verdict a user recorded after physically checking a candidate location.
 *
 * Ambiguous outcomes are kept on purpose: Phase 7 hard-negative mining and calibration need the
 * "we checked and still could not tell" examples just as much as clean confirmations, and the
 * roadmap requires productive, rejected, and ambiguous examples to all be retained.
 */
enum class ReviewedVerdict { PRODUCTIVE, REJECTED, AMBIGUOUS }

/**
 * One field-reviewed candidate example - the unit every future ranking model is trained and
 * evaluated against.
 *
 * This is a snapshot, not a live reference: the score and evidence lists are frozen at review
 * time so a later model version can be compared honestly against the outcomes an earlier version
 * produced. [modelVersion] and [processingVersion] identify exactly which detector and ranking
 * code produced the candidate, so examples never silently change meaning when the algorithms do.
 *
 * Location is recorded twice on purpose: grid percentages tie the example to the exact dataset
 * raster it was reviewed in, while geographic coordinates keep it usable in GIS exports and
 * spatially separated training/evaluation splits when the dataset is re-rendered.
 */
data class ReviewedCandidateExample(
    val id: String,
    /** Signature of the terrain dataset the review belongs to, matching the analysis cache key. */
    val datasetKey: String,
    /** Stable identity of the imported LiDAR source, unchanged by re-render or refinement. */
    val terrainKey: String? = null,
    /** Stable candidate id when the review came from a ranked candidate; null for manual finds. */
    val candidateId: String? = null,
    /** Name of the predicted feature type (TerrainFeatureType or MetalDetectingTargetType), if any. */
    val featureType: String? = null,
    val xPercent: Float,
    val yPercent: Float,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Candidate score at the moment of review; null when the user logged the spot manually. */
    val scoreAtReview: Float? = null,
    val supportingEvidence: List<String> = emptyList(),
    val negativeEvidence: List<String> = emptyList(),
    val verdict: ReviewedVerdict,
    val note: String = "",
    val modelVersion: String = MODEL_VERSION_UNTRACKED,
    val processingVersion: String = PROCESSING_VERSION_UNTRACKED,
    val reviewedAtMillis: Long,
) {
    companion object {
        const val MODEL_VERSION_UNTRACKED = "untracked"
        const val PROCESSING_VERSION_UNTRACKED = "untracked"

        /**
         * Builds an example from a field-verified [TargetSignal]. Returns null when the signal
         * has not been checked yet - an UNVERIFIED marker is not a review and must not leak into
         * training data.
         */
        fun fromSignal(
            signal: TargetSignal,
            scoreAtReview: Float? = null,
            supportingEvidence: List<String> = emptyList(),
            negativeEvidence: List<String> = emptyList(),
            candidateId: String? = null,
            modelVersion: String = MODEL_VERSION_UNTRACKED,
            processingVersion: String = PROCESSING_VERSION_UNTRACKED,
        ): ReviewedCandidateExample? {
            val verdict = when (signal.outcome) {
                VerificationOutcome.CONFIRMED_FEATURE -> ReviewedVerdict.PRODUCTIVE
                VerificationOutcome.REJECTED_FALSE_POSITIVE -> ReviewedVerdict.REJECTED
                VerificationOutcome.INCONCLUSIVE -> ReviewedVerdict.AMBIGUOUS
                VerificationOutcome.UNVERIFIED -> return null
            }
            return ReviewedCandidateExample(
                id = "signal-${signal.id}",
                datasetKey = signal.datasetKey ?: "",
                terrainKey = signal.terrainKey,
                candidateId = candidateId,
                featureType = signal.detectedFeatureType,
                xPercent = signal.gridX,
                yPercent = signal.gridY,
                latitude = signal.latitude,
                longitude = signal.longitude,
                scoreAtReview = scoreAtReview,
                supportingEvidence = supportingEvidence,
                negativeEvidence = negativeEvidence,
                verdict = verdict,
                note = signal.notes,
                modelVersion = modelVersion,
                processingVersion = processingVersion,
                reviewedAtMillis = signal.timestamp,
            )
        }
    }
}

private const val REVIEWED_EXAMPLE_FORMAT_VERSION = "v1"
private const val REVIEWED_EXAMPLE_FIELD_COUNT = 17

/**
 * Serializes one example to a single manifest line. The line-oriented, URL-encoded format keeps
 * the store append-safe (a crash mid-write can only damage the final, skippable line) and avoids
 * treating untrusted field notes as executable data.
 */
fun ReviewedCandidateExample.toManifestLine(): String {
    fun enc(value: String?): String =
        URLEncoder.encode(value.orEmpty(), StandardCharsets.UTF_8.name())
    // Each item is encoded separately so a newline inside one evidence string stays inside it.
    // The separator is '|': URLEncoder escapes it (%7C), so a raw pipe can never come from an
    // item, and unlike a newline it does not break the line-oriented manifest format.
    fun encList(values: List<String>): String =
        values.joinToString("|") { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }
    return buildString {
        append(enc(id)).append('\t')
        append(enc(datasetKey)).append('\t')
        append(enc(terrainKey)).append('\t')
        append(enc(candidateId)).append('\t')
        append(enc(featureType)).append('\t')
        append(xPercent).append('\t')
        append(yPercent).append('\t')
        append(latitude?.toString().orEmpty()).append('\t')
        append(longitude?.toString().orEmpty()).append('\t')
        append(scoreAtReview?.toString().orEmpty()).append('\t')
        append(encList(supportingEvidence)).append('\t')
        append(encList(negativeEvidence)).append('\t')
        append(verdict.name).append('\t')
        append(enc(note)).append('\t')
        append(enc(modelVersion)).append('\t')
        append(enc(processingVersion)).append('\t')
        append(reviewedAtMillis)
    }
}

/** Parses one manifest line, returning null for anything malformed so a bad line never aborts a read. */
fun reviewedExampleFromManifestLine(line: String): ReviewedCandidateExample? {
    val fields = line.split('\t')
    if (fields.size != REVIEWED_EXAMPLE_FIELD_COUNT) return null
    fun dec(raw: String): String? =
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8.name()) }.getOrNull()?.ifEmpty { null }
    fun decList(raw: String): List<String> =
        if (raw.isEmpty()) {
            emptyList()
        } else {
            raw.split('|').mapNotNull {
                runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
            }
        }
    val x = fields[5].toFloatOrNull() ?: return null
    val y = fields[6].toFloatOrNull() ?: return null
    val verdict = ReviewedVerdict.entries.firstOrNull { it.name == fields[12] } ?: return null
    val reviewedAt = fields[16].toLongOrNull() ?: return null
    val id = dec(fields[0]) ?: return null
    return ReviewedCandidateExample(
        id = id,
        datasetKey = dec(fields[1]).orEmpty(),
        terrainKey = dec(fields[2]),
        candidateId = dec(fields[3]),
        featureType = dec(fields[4]),
        xPercent = x,
        yPercent = y,
        latitude = fields[7].toDoubleOrNull(),
        longitude = fields[8].toDoubleOrNull(),
        scoreAtReview = fields[9].toFloatOrNull(),
        supportingEvidence = decList(fields[10]),
        negativeEvidence = decList(fields[11]),
        verdict = verdict,
        note = dec(fields[13]).orEmpty(),
        modelVersion = dec(fields[14]) ?: ReviewedCandidateExample.MODEL_VERSION_UNTRACKED,
        processingVersion = dec(fields[15]) ?: ReviewedCandidateExample.PROCESSING_VERSION_UNTRACKED,
        reviewedAtMillis = reviewedAt,
    )
}

/**
 * Append-only on-disk collection of reviewed examples.
 *
 * Appends are atomic per line and the file is never rewritten, which keeps the audit trail intact:
 * a review once recorded is never edited in place, matching the roadmap's append-safe field-edit
 * guardrail. Reads skip malformed lines rather than failing the whole file.
 */
class ReviewedExampleStore(private val file: File) {

    @Synchronized
    fun append(example: ReviewedCandidateExample) {
        val needsHeader = !file.exists() || file.length() == 0L
        file.parentFile?.mkdirs()
        file.appendText(
            buildString {
                if (needsHeader) append(REVIEWED_EXAMPLE_FORMAT_VERSION).append('\n')
                append(example.toManifestLine()).append('\n')
            },
        )
    }

    fun readAll(): List<ReviewedCandidateExample> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        if (lines.firstOrNull() != REVIEWED_EXAMPLE_FORMAT_VERSION) return emptyList()
        return lines.drop(1).mapNotNull(::reviewedExampleFromManifestLine)
    }

    /** All examples for one dataset, in the order they were recorded. */
    fun readForDataset(datasetKey: String): List<ReviewedCandidateExample> =
        readAll().filter { it.datasetKey == datasetKey }
}
