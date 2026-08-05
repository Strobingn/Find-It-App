package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LidarAreaPickerMapScreenTest {
    @Test
    fun reverseDragProducesNormalizedSearchBounds() {
        val bounds = lidarSelectionBounds(
            firstLatitude = 41.46,
            firstLongitude = -74.02,
            secondLatitude = 41.42,
            secondLongitude = -74.08,
        )

        requireNotNull(bounds)
        assertEquals(41.42, bounds.minLat, 0.0)
        assertEquals(41.46, bounds.maxLat, 0.0)
        assertEquals(-74.08, bounds.minLon, 0.0)
        assertEquals(-74.02, bounds.maxLon, 0.0)
    }

    @Test
    fun zeroAreaAndDateLineSpanningBoxesAreRejected() {
        assertNull(lidarSelectionBounds(41.42, -74.05, 41.42, -74.02))
        assertNull(lidarSelectionBounds(10.0, 179.8, 10.2, -179.8))
    }
}
