package com.example.analysis.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlDataToolsTest {
    @Test
    fun foldAssignmentIsDeterministicAndInRange() {
        val first = SpatialFoldSplitter.foldIndex(41.44, -74.02, 0f, 0f, 4)
        val second = SpatialFoldSplitter.foldIndex(41.44, -74.02, 0f, 0f, 4)

        assertEquals(first, second)
        assertTrue(first in 0..3)
    }

    @Test
    fun nearbyPointsShareTheirSpatialBlock() {
        val base = SpatialFoldSplitter.foldIndex(41.440, -74.020, 0f, 0f, 5)
        val fiftyMetersAway = SpatialFoldSplitter.foldIndex(41.4404, -74.0205, 0f, 0f, 5)

        assertEquals(base, fiftyMetersAway)
    }

    @Test
    fun gridBlocksUsedWhenCoordinatesAreMissing() {
        val fold = SpatialFoldSplitter.foldIndex(null, null, xPercent = 45f, yPercent = 55f, 4)

        assertTrue(fold in 0..3)
        assertEquals(
            fold,
            SpatialFoldSplitter.foldIndex(null, null, xPercent = 46f, yPercent = 56f, 4),
        )
    }

    @Test
    fun splitPreservesEveryExample() {
        val examples = (0 until 40).map { index ->
            SpatialFoldSplitter.FoldLocation(
                latitude = 41.0 + index * 0.02,
                longitude = -74.0 - index * 0.02,
                xPercent = 0f,
                yPercent = 0f,
            )
        }

        val folds = SpatialFoldSplitter.split(examples, 4) { it }

        assertEquals(4, folds.size)
        assertEquals(examples.size, folds.sumOf { it.size })
        assertEquals(examples.toSet(), folds.flatten().toSet())
    }

    @Test
    fun hardNegativeMinerReturnsHighestScoringRejected() {
        val rejected = listOf("low" to 0.2f, "highest" to 0.9f, "mid" to 0.5f, "high" to 0.7f)

        val mined = HardNegativeMiner.select(rejected, { it.second }, limit = 2)

        assertEquals(listOf("highest", "high"), mined.map { it.first })
        assertTrue(HardNegativeMiner.select(rejected, { it.second }, limit = 0).isEmpty())
    }

    @Test
    fun registryActivatesAndRollsBackExplicitly() {
        fun ranker(version: String) = ExplainableRanker(
            modelVersion = version,
            featureNames = listOf("a"),
            weights = floatArrayOf(1f),
            bias = 0f,
            featureMeans = floatArrayOf(0f),
            featureStds = floatArrayOf(1f),
        )

        val empty = ModelRegistry()
        assertNull(empty.activeVersion)
        assertNull(empty.rollback("v1"))

        val withV1 = empty.activate(ranker("v1"))
        assertEquals("v1", withV1.activeVersion)
        assertNotSame(empty, withV1)
        assertNull(empty.activeVersion) // the original registry never changes

        val withV2 = withV1.activate(ranker("v2"))
        assertEquals("v2", withV2.activeVersion)
        assertEquals(setOf("v1", "v2"), withV2.knownVersions)

        val rolledBack = withV2.rollback("v1")
        assertEquals("v1", rolledBack?.activeVersion)
        assertNull(withV2.rollback("v99"))
    }
}
