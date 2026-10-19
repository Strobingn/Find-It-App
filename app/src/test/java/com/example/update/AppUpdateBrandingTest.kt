package com.example.update

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateBrandingTest {
    @Test
    fun upToDateNamesFindItGrok() =
        assertEquals("You are on the latest Find It Grok build", AppUpdateChecker.statusLabel(UpdateCheckResult.UpToDate))
}
