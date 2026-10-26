package com.example.thermals

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLoadIssueTest {

    @Test
    fun rateLimitedTileIsAQuietRetryNotAFailure() {
        val issue = MapLoadIssue.from("TILE", "Failed to load tile: HTTP status code 429. Too Many Requests", "thermal-dem")
        assertFalse(issue.fatal)
        assertEquals("Some 3D terrain tiles are being rate-limited by Mapbox — retrying.", issue.text)
        assertFalse(issue.text.contains("failed to load", ignoreCase = true))
    }

    @Test
    fun otherTileErrorsAreAlsoRetried() {
        val issue = MapLoadIssue.from("TILE", "Failed to load tile: timeout", "composite")
        assertFalse(issue.fatal)
        assertEquals("Some map tiles didn't load — retrying.", issue.text)
    }

    @Test
    fun styleFailuresAndBadTokensStayFatal() {
        assertTrue(MapLoadIssue.from("STYLE", "Unable to resolve host api.mapbox.com", null).fatal)
        val token = MapLoadIssue.from("TILE", "HTTP status code 401", "composite")
        assertTrue(token.fatal)
        assertTrue(token.text.contains("MAPBOX_ACCESS_TOKEN"))
        // "4010" in a message is not a 401.
        assertFalse(MapLoadIssue.from("TILE", "tile 4010 timed out", null).fatal)
    }
}
