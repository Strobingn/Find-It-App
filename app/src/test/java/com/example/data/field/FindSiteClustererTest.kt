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
    fun boundingBoxRejectionMaintainsGroupingAcrossDenseGrid() {
        // Create multiple clusters across different latitudes and longitudes
        val signals = mutableListOf<TargetSignal>()
        var id = 1L
        // Cluster 1: near (41.0, -74.0), 5 finds within 20m of each other
        for (i in 0 until 5) {
            signals += find(id++, 41.0000 + i * 0.00005, -74.0000 + i * 0.00005)
        }
        // Cluster 2: near (41.005, -74.005), 3 finds (~550m away from cluster 1)
        for (i in 0 until 3) {
            signals += find(id++, 41.0050 + i * 0.00004, -74.0050 + i * 0.00004)
        }
        // Cluster 3: isolated find near (42.0, -75.0) (>100 km away)
        signals += find(id++, 42.0000, -75.0000)

        val sites = FindSiteClusterer.cluster(signals, radiusMeters = 50f)
        assertEquals(3, sites.size)

        // Ordered by size descending
        assertEquals("Site A", sites[0].label)
        assertEquals(5, sites[0].signals.size)
        assertEquals((1L..5L).toSet(), sites[0].signals.map { it.id }.toSet())
        assertEquals(41.0001, sites[0].centerLatitude, 1e-6)
        assertEquals(-73.9999, sites[0].centerLongitude, 1e-6)

        assertEquals("Site B", sites[1].label)
        assertEquals(3, sites[1].signals.size)
        assertEquals((6L..8L).toSet(), sites[1].signals.map { it.id }.toSet())
        assertEquals(41.00504, sites[1].centerLatitude, 1e-6)
        assertEquals(-74.00496, sites[1].centerLongitude, 1e-6)

        assertEquals("Site C", sites[2].label)
        assertEquals(1, sites[2].signals.size)
        assertEquals(listOf(9L), sites[2].signals.map { it.id })
        assertEquals(42.0, sites[2].centerLatitude, 1e-6)
        assertEquals(-75.0, sites[2].centerLongitude, 1e-6)
    }

    @Test
    fun tieBreakingByLatitudeOrdersIdenticallySizedSites() {
        val signals = listOf(
            find(1L, 42.0000, -74.0000), // Site with higher latitude
            find(2L, 40.0000, -74.0000), // Site with lower latitude
        )
        val sites = FindSiteClusterer.cluster(signals, radiusMeters = 50f)
        assertEquals(2, sites.size)
        // Tie-breaker in sortedWith: .thenBy { it.centerLat }
        assertEquals("Site A", sites[0].label)
        assertEquals(40.0000, sites[0].centerLatitude, 1e-6)
        assertEquals("Site B", sites[1].label)
        assertEquals(42.0000, sites[1].centerLatitude, 1e-6)
    }
}
