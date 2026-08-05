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
}
