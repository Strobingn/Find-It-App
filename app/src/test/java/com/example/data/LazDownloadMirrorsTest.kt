package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazDownloadMirrorsTest {
    @Test
    fun rockywebGetsATnmS3Mirror() {
        val rocky =
            "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/NY_SouthEast4County_A22/tile.laz"
        val candidates = LazDownloadMirrors.candidates(rocky)
        assertEquals(rocky, candidates.first())
        assertTrue(
            candidates.contains(
                "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/LPC/Projects/NY_SouthEast4County_A22/tile.laz",
            ),
        )
    }

    @Test
    fun tnmS3GetsARockywebMirror() {
        val s3 =
            "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/LPC/Projects/NY_SouthEast4County_A22/tile.laz"
        val candidates = LazDownloadMirrors.candidates(s3)
        assertTrue(
            candidates.contains(
                "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/NY_SouthEast4County_A22/tile.laz",
            ),
        )
    }

    @Test
    fun unrelatedHostsStaySingleCandidate() {
        val url = "https://example.org/clouds/tile.laz"
        assertEquals(listOf(url), LazDownloadMirrors.candidates(url))
    }

    @Test
    fun copcUrlsAreDetectedEvenWithAQueryString() {
        assertTrue(LazDownloadMirrors.looksLikeCopcUrl("https://host/a.copc.laz?st=token"))
        assertFalse(LazDownloadMirrors.looksLikeCopcUrl("https://host/a.laz"))
    }
}
