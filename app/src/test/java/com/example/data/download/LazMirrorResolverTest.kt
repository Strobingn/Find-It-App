package com.example.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LazMirrorResolverTest {
    private val rocky = "https://rockyweb.usgs.gov/vdelivery/Datasets/Staged/Elevation/LPC/Projects/NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"
    private val s3 = "https://prd-tnm.s3.amazonaws.com/StagedProducts/Elevation/LPC/Projects/NY_SouthEast4County_A22/NY_SE4County_1_A22/LAZ/USGS_LPC_NY_SouthEast4County_A22_u_5175065000_2022.laz"

    @Test fun rockywebMapsToPrdTnmS3() {
        assertEquals(listOf(s3), LazMirrorResolver.candidates(rocky))
    }

    @Test fun otherHostsHaveNoMirror() {
        assertTrue(LazMirrorResolver.candidates("https://example.org/a.laz").isEmpty())
        assertTrue(LazMirrorResolver.candidates("https://noaa-nos-coastal-lidar-pds.s3.amazonaws.com/laz/x.laz").isEmpty())
    }

    @Test fun mirrorUsedOnlyWhenSizesMatch() {
        val r = LazMirrorResolver { url -> if (url == s3) 227_436_117L else 227_436_117L }
        assertEquals(s3, r.resolve(rocky))
    }

    @Test fun sizeMismatchOrMissingMirrorFallsBackToOriginal() {
        assertNull(LazMirrorResolver { url -> if (url == s3) 100L else 227_436_117L }.resolve(rocky))
        assertNull(LazMirrorResolver { url -> if (url == s3) null else 227_436_117L }.resolve(rocky))
        assertNull(LazMirrorResolver { url -> if (url == s3) 5L else throw java.io.IOException("down") }.resolve(rocky))
    }

    @Test fun resultIsCachedPerUrl() {
        var probes = 0
        val r = LazMirrorResolver { probes++; null }
        r.resolve(rocky); r.resolve(rocky)
        assertEquals(1, probes)
    }
}
