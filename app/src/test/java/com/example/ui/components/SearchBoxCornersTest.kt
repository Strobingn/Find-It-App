package com.example.ui.components

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A search box can be dragged in any direction, so neither corner is reliably the north-west one.
 * Getting this wrong produces an inverted box, which intersects nothing and silently returns no
 * tiles rather than reporting a problem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchBoxCornersTest {
    private val northWest = LatLng(41.44, -74.05)
    private val southEast = LatLng(41.42, -74.03)

    @Test
    fun aBoxDraggedFromNorthWestToSouthEastNormalizes() {
        val bounds = searchBoundsFromCorners(northWest, southEast)

        assertNotNull(bounds)
        assertEquals(41.42, bounds!!.minLat, 1e-9)
        assertEquals(41.44, bounds.maxLat, 1e-9)
        assertEquals(-74.05, bounds.minLon, 1e-9)
        assertEquals(-74.03, bounds.maxLon, 1e-9)
    }

    /** Dragging the other way must produce exactly the same box. */
    @Test
    fun dragDirectionDoesNotChangeTheResult() {
        assertEquals(
            searchBoundsFromCorners(northWest, southEast),
            searchBoundsFromCorners(southEast, northWest),
        )
    }

    @Test
    fun theOtherDiagonalProducesTheSameBox() {
        val northEast = LatLng(41.44, -74.03)
        val southWest = LatLng(41.42, -74.05)

        assertEquals(
            searchBoundsFromCorners(northWest, southEast),
            searchBoundsFromCorners(northEast, southWest),
        )
    }

    @Test
    fun aBoxWithNoWidthIsRejected() {
        assertNull(searchBoundsFromCorners(LatLng(41.42, -74.04), LatLng(41.44, -74.04)))
    }

    @Test
    fun aBoxWithNoHeightIsRejected() {
        assertNull(searchBoundsFromCorners(LatLng(41.43, -74.05), LatLng(41.43, -74.03)))
    }

    @Test
    fun aSinglePointIsRejected() {
        assertNull(searchBoundsFromCorners(northWest, northWest))
    }

    @Test
    fun aBoxSpanningTheSouthernHemisphereStillNormalizes() {
        val bounds = searchBoundsFromCorners(LatLng(-33.9, 151.1), LatLng(-33.8, 151.3))

        assertNotNull(bounds)
        assertEquals(-33.9, bounds!!.minLat, 1e-9)
        assertEquals(-33.8, bounds.maxLat, 1e-9)
    }
}
