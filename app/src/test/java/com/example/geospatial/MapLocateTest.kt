package com.example.geospatial

import com.example.geospatial.MapLocate.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLocateTest {
    @Test
    fun asksForPermissionFirst() =
        assertEquals(Outcome.NEED_PERMISSION, MapLocate.decide(hasPermission = false, gpsEnabled = true, networkEnabled = true))

    @Test
    fun reportsLocationOffWhenNoProvider() {
        assertEquals(Outcome.LOCATION_OFF, MapLocate.decide(true, gpsEnabled = false, networkEnabled = false))
        assertTrue(MapLocate.message(Outcome.LOCATION_OFF).contains("turned off"))
    }

    @Test
    fun readyWithGpsOrNetwork() {
        assertEquals(Outcome.READY, MapLocate.decide(true, gpsEnabled = true, networkEnabled = false))
        assertEquals(Outcome.READY, MapLocate.decide(true, gpsEnabled = false, networkEnabled = true))
    }

    @Test
    fun noFixHasFriendlyMessage() = assertTrue(MapLocate.message(null).contains("GPS fix"))
}

class MapLocateApproximateTest {
    @Test
    fun approximateOnlyWithGpsOnlyAsksForPrecise() {
        assertEquals(Outcome.NEED_PRECISE, MapLocate.decide(true, gpsEnabled = true, networkEnabled = false, hasFine = false))
        assertTrue(MapLocate.message(Outcome.NEED_PRECISE).contains("precise"))
    }

    @Test
    fun approximateWithNetworkIsReady() =
        assertEquals(Outcome.READY, MapLocate.decide(true, gpsEnabled = true, networkEnabled = true, hasFine = false))

    @Test
    fun approximateNeverReadsGpsLastKnown() {
        assertEquals(listOf(android.location.LocationManager.NETWORK_PROVIDER), MapLocate.lastKnownProviders(false))
        assertEquals(2, MapLocate.lastKnownProviders(true).size)
    }
}
