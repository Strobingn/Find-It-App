package com.example.data.download

import com.example.data.download.LazDownloadSource.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazDownloadSourceTest {
    @Test
    fun autoIsDefaultAndKeepsCurrentChain() {
        assertEquals(LazDownloadSource.AUTO, LazDownloadSource.DEFAULT)
        assertEquals(listOf(Step.EPT, Step.MIRROR, Step.ORIGINAL), LazDownloadSource.AUTO.steps)
    }

    @Test
    fun usgsUsesOnlyOriginalRockyweb() =
        assertEquals(listOf(Step.ORIGINAL), LazDownloadSource.USGS.steps)

    @Test
    fun amazonTriesEptFirstThenFallsBackWithMessage() {
        assertEquals(Step.EPT, LazDownloadSource.AMAZON.steps.first())
        val msg = LazDownloadSource.AMAZON.fallbackNotice("tile.laz", "no EPT coverage")!!
        assertTrue(msg.contains("Amazon"))
        assertTrue(msg.contains("tile.laz"))
        assertTrue(msg.contains("no EPT coverage"))
        assertNull(LazDownloadSource.AUTO.fallbackNotice("tile.laz", "x"))
        assertNull(LazDownloadSource.USGS.fallbackNotice("tile.laz", "x"))
    }

    @Test
    fun persistedNameRoundTripsAndUnknownFallsBackToAuto() {
        LazDownloadSource.entries.forEach { assertEquals(it, LazDownloadSource.fromName(it.name)) }
        assertEquals(LazDownloadSource.AUTO, LazDownloadSource.fromName(null))
        assertEquals(LazDownloadSource.AUTO, LazDownloadSource.fromName("bogus"))
    }
}
