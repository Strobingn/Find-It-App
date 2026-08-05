package com.example.analysis

import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The reviewed-example schema is the contract every future ranking model trains against, so its
 * serialization must be lossless and its append-only store must survive interruption without
 * losing earlier reviews.
 */
class ReviewedCandidateExampleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun example(
        id: String = "example-1",
        verdict: ReviewedVerdict = ReviewedVerdict.PRODUCTIVE,
        note: String = "stone foundation corner",
    ) = ReviewedCandidateExample(
        id = id,
        datasetKey = "dataset-abc",
        terrainKey = "terrain-xyz",
        candidateId = "candidate-7",
        featureType = TerrainFeatureType.CELLAR_HOLE.name,
        xPercent = 42.5f,
        yPercent = 17.25f,
        latitude = 41.4301,
        longitude = -74.0402,
        scoreAtReview = 0.82f,
        supportingEvidence = listOf("cellar depression 1.8 m deep", "stone wall within 20 m"),
        negativeEvidence = listOf("drainage channel 40 m east"),
        verdict = verdict,
        note = note,
        modelVersion = "ranker-0.3.1",
        processingVersion = "pipeline-2026.08",
        reviewedAtMillis = 1_754_000_000_000L,
    )

    @Test
    fun aManifestLineRoundTripsLosslessly() {
        assertEquals(example(), reviewedExampleFromManifestLine(example().toManifestLine()))
    }

    /** Field notes come from the field: tabs, newlines, percent signs, and unicode must survive. */
    @Test
    fun hostileNoteAndEvidenceTextSurvives() {
        val hostile = example().copy(
            note = "wall segment\tbroken\n50% buried — véry overgrown",
            supportingEvidence = listOf("rim\tedge\nmulti\tline"),
        )

        assertEquals(hostile, reviewedExampleFromManifestLine(hostile.toManifestLine()))
    }

    @Test
    fun optionalFieldsStayAbsent() {
        val minimal = example().copy(
            terrainKey = null,
            candidateId = null,
            featureType = null,
            latitude = null,
            longitude = null,
            scoreAtReview = null,
            supportingEvidence = emptyList(),
            negativeEvidence = emptyList(),
        )

        val restored = reviewedExampleFromManifestLine(minimal.toManifestLine())
        assertEquals(minimal, restored)
        assertNull(restored?.scoreAtReview)
        assertTrue(restored?.supportingEvidence?.isEmpty() == true)
    }

    @Test
    fun malformedLinesAreSkippedRatherThanFailingTheRead() {
        assertNull(reviewedExampleFromManifestLine("not an example"))
        assertNull(reviewedExampleFromManifestLine(example().toManifestLine().substringBeforeLast('\t')))
        assertNull(reviewedExampleFromManifestLine(example().toManifestLine() + "\textra"))
    }

    @Test
    fun theStoreAppendsWithoutRewritingAndReadsInOrder() {
        val file = File(tmp.newFolder(), "reviewed-examples.manifest")
        val store = ReviewedExampleStore(file)
        store.append(example(id = "first"))
        val sizeAfterFirst = file.length()
        store.append(example(id = "second", verdict = ReviewedVerdict.REJECTED))

        assertTrue("appending must not rewrite earlier bytes", file.length() > sizeAfterFirst)
        assertEquals(listOf("first", "second"), store.readAll().map { it.id })
    }

    @Test
    fun aCorruptFinalLineDoesNotDestroyEarlierReviews() {
        val file = File(tmp.newFolder(), "reviewed-examples.manifest")
        val store = ReviewedExampleStore(file)
        store.append(example(id = "kept"))
        file.appendText("partial-write-without-enough-fields\t12\n")

        assertEquals(listOf("kept"), store.readAll().map { it.id })
    }

    @Test
    fun readForDatasetFiltersByDatasetKey() {
        val file = File(tmp.newFolder(), "reviewed-examples.manifest")
        val store = ReviewedExampleStore(file)
        store.append(example(id = "ours"))
        store.append(example(id = "theirs").copy(datasetKey = "other-dataset"))

        assertEquals(listOf("ours"), store.readForDataset("dataset-abc").map { it.id })
    }

    @Test
    fun aMissingStoreReadsAsEmpty() {
        assertTrue(ReviewedExampleStore(File(tmp.newFolder(), "absent.manifest")).readAll().isEmpty())
    }

    @Test
    fun signalOutcomesMapToVerdicts() {
        fun signal(outcome: VerificationOutcome) = TargetSignal(
            id = 99L,
            gridX = 10f,
            gridY = 20f,
            metalType = MetalType.MAGNETIC_ANOMALY,
            signalStrength = 77f,
            source = DetectionSource.AI_ANALYSIS,
            outcome = outcome,
            datasetKey = "dataset-abc",
            detectedFeatureType = MetalDetectingTargetType.CELLAR_HOLE.name,
            timestamp = 123L,
        )

        assertEquals(ReviewedVerdict.PRODUCTIVE, ReviewedCandidateExample.fromSignal(signal(VerificationOutcome.CONFIRMED_FEATURE))?.verdict)
        assertEquals(ReviewedVerdict.REJECTED, ReviewedCandidateExample.fromSignal(signal(VerificationOutcome.REJECTED_FALSE_POSITIVE))?.verdict)
        assertEquals(ReviewedVerdict.AMBIGUOUS, ReviewedCandidateExample.fromSignal(signal(VerificationOutcome.INCONCLUSIVE))?.verdict)
        assertNull(
            "an unchecked marker is not a review and must not enter training data",
            ReviewedCandidateExample.fromSignal(signal(VerificationOutcome.UNVERIFIED)),
        )
    }

    @Test
    fun fromSignalCarriesIdentityAndLocation() {
        val signal = TargetSignal(
            id = 42L,
            gridX = 33.5f,
            gridY = 66.5f,
            metalType = MetalType.MAGNETIC_ANOMALY,
            signalStrength = 60f,
            latitude = 41.5,
            longitude = -74.1,
            outcome = VerificationOutcome.CONFIRMED_FEATURE,
            datasetKey = "dataset-abc",
            terrainKey = "terrain-xyz",
            detectedFeatureType = MetalDetectingTargetType.STONE_WALL.name,
            notes = "wall confirmed",
            timestamp = 999L,
        )

        val example = ReviewedCandidateExample.fromSignal(
            signal,
            scoreAtReview = 0.71f,
            supportingEvidence = listOf("linear rise 120 m"),
            modelVersion = "ranker-0.3.1",
            processingVersion = "pipeline-2026.08",
        )

        assertEquals("signal-42", example?.id)
        assertEquals("dataset-abc", example?.datasetKey)
        assertEquals("terrain-xyz", example?.terrainKey)
        assertEquals(MetalDetectingTargetType.STONE_WALL.name, example?.featureType)
        assertEquals(33.5f, example?.xPercent ?: 0f, 1e-4f)
        assertEquals(41.5, example?.latitude ?: 0.0, 1e-9)
        assertEquals(0.71f, example?.scoreAtReview ?: 0f, 1e-4f)
        assertEquals("ranker-0.3.1", example?.modelVersion)
        assertEquals(999L, example?.reviewedAtMillis)
    }
}
