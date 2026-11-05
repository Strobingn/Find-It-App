package com.example.data.field

import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindSiteClustererTest {

    private fun find(
        id: Long,
        latitude: Double?,
        longitude: Double?,
        outcome: VerificationOutcome = VerificationOutcome.UNVERIFIED,
        metalType: MetalType = MetalType.IRON,
    ) = TargetSignal(
        id = id,
        gridX = 0f,
        gridY = 0f,
        metalType = metalType,
        signalStrength = 50f,
        latitude = latitude,
        longitude = longitude,
        source = DetectionSource.MANUAL,
        outcome = outcome,
    )

    // Roughly 11 m of latitude per 0.0001°; 0.0001° of longitude ≈ 8.4 m at 41°N.
    @Test
    fun nearbyFindsFormOneSiteDistantFindsAnother() {
        val signals = listOf(
            find(1L, 41.0000, -74.0000),
            find(2L, 41.0002, -74.0001),
            find(3L, 41.0100, -74.0100), // ~1.3 km away
        )
        val sites = FindSiteClusterer.cluster(signals)
        assertEquals(2, sites.size)
        assertEquals("Site A", sites[0].label) // larger group first
        assertEquals(2, sites[0].signals.size)
        assertEquals(1, sites[1].signals.size)
        assertEquals(setOf(1L, 2L), sites[0].signals.map { it.id }.toSet())
    }

    @Test
    fun chainingLinksFindsAcrossTheRadius() {
        // A-B and B-C are each within 50 m, but A-C is ~80 m apart: one site via the chain.
        val signals = listOf(
            find(1L, 41.0000, -74.0000),
            find(2L, 41.0000, -73.9995), // ~42 m east
            find(3L, 41.0000, -73.9990), // ~42 m further east
        )
        val sites = FindSiteClusterer.cluster(signals)
        assertEquals(1, sites.size)
        assertEquals(3, sites[0].signals.size)
    }

    @Test
    fun findsWithoutCoordinatesAreSkipped() {
        val sites = FindSiteClusterer.cluster(
            listOf(find(1L, null, null), find(2L, 41.0, -74.0)),
        )
        assertEquals(1, sites.size)
        assertEquals(listOf(2L), sites[0].signals.map { it.id })
    }

    @Test
    fun clusteringIsDeterministicRegardlessOfInputOrder() {
        val forward = listOf(
            find(1L, 41.0000, -74.0000),
            find(2L, 41.0002, -74.0001),
            find(3L, 41.0100, -74.0100),
            find(4L, 41.0101, -74.0099),
        )
        val reversed = forward.reversed()
        assertEquals(
            FindSiteClusterer.cluster(forward).map { site -> site.signals.map { it.id }.toSet() },
            FindSiteClusterer.cluster(reversed).map { site -> site.signals.map { it.id }.toSet() },
        )
    }

    @Test
    fun statsCountOutcomesAndRankTypes() {
        val site = FindSiteClusterer.cluster(
            listOf(
                find(1L, 41.0, -74.0, VerificationOutcome.CONFIRMED_FEATURE, MetalType.SILVER),
                find(2L, 41.0001, -74.0001, VerificationOutcome.REJECTED_FALSE_POSITIVE),
                find(3L, 41.0002, -74.0002, VerificationOutcome.UNVERIFIED),
            ),
        ).single()
        assertEquals(1, site.confirmedCount)
        assertEquals(1, site.rejectedCount)
        assertEquals(MetalType.IRON.label, site.topTypes.first())
        assertTrue(site.centerLatitude in 41.0..41.0002)
    }

    @Test
    fun highLatitudePointsWithinRadiusFormClusterCorrectly() {
        // At 70°N, 1 degree of longitude is only ~38 km, so points within 50m have larger degree delta in longitude
        // than at equator. Verify that bounding box threshold correctly clusters them without false pruning.
        val signals = listOf(
            find(1L, 70.0000, 20.0000),
            find(2L, 70.0000, 20.0008), // ~30 meters apart at 70°N
            find(3L, 0.0000, 0.0000),   // Far away point at equator in the same dataset
        )
        val sites = FindSiteClusterer.cluster(signals)
        assertEquals(2, sites.size)
        assertEquals("Site A", sites[0].label)
        assertEquals(2, sites[0].signals.size)
        assertEquals(setOf(1L, 2L), sites[0].signals.map { it.id }.toSet())
    }

    @Test
    fun boundingBoxRejectionHandlesDistantLongitudesAndLatitudes() {
        // Signals far apart across latitude or longitude should be pruned cleanly without grouping.
        val distant = listOf(
            find(1L, 41.0000, -74.0000),
            find(2L, 41.0000, -73.5000), // ~42 km east
            find(3L, 41.5000, -74.0000), // ~55 km north
        )
        val sites = FindSiteClusterer.cluster(distant)
        assertEquals(3, sites.size)
        assertEquals(1, sites[0].signals.size)
        assertEquals(1, sites[1].signals.size)
        assertEquals(1, sites[2].signals.size)
    }

    @Test
    fun largeDenseSetCalculatesAccurateCentroidsAndOrdering() {
        // 50 points in cluster 1 around (42.0, -73.0), 30 points in cluster 2 around (43.0, -73.0)
        val list = mutableListOf<TargetSignal>()
        for (i in 0 until 50) {
            list.add(find(i.toLong(), 42.0000 + i * 0.00001, -73.0000 + i * 0.00001))
        }
        for (i in 50 until 80) {
            list.add(find(i.toLong(), 43.0000 + (i - 50) * 0.00001, -73.0000 + (i - 50) * 0.00001))
        }
        val sites = FindSiteClusterer.cluster(list)
        assertEquals(2, sites.size)
        assertEquals("Site A", sites[0].label)
        assertEquals(50, sites[0].signals.size)
        assertEquals("Site B", sites[1].label)
        assertEquals(30, sites[1].signals.size)
        assertTrue(sites[0].centerLatitude in 42.0..42.001)
        assertTrue(sites[1].centerLatitude in 43.0..43.001)
    }

    @Test
    fun singleElementAndEmptyListsAreHandledGracefully() {
        assertTrue(FindSiteClusterer.cluster(emptyList()).isEmpty())
        val single = listOf(find(42L, 45.0, -70.0))
        val sites = FindSiteClusterer.cluster(single)
        assertEquals(1, sites.size)
        assertEquals("Site A", sites[0].label)
        assertEquals(45.0, sites[0].centerLatitude, 1e-6)
        assertEquals(-70.0, sites[0].centerLongitude, 1e-6)
    }
}
