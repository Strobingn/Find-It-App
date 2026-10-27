package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the shell's navigation invariants. These used to be unenforced: the bottom bar had grown
 * to eight tabs — 45dp per slot on a 360dp phone, against the five that Material and iOS both
 * cap at — and the nav bar was hidden by a hand-maintained list of destinations, which would have
 * stranded a full-bleed screen with no way back the moment it moved out of the tab bar.
 */
class MainScreenLayoutTest {

    @Test
    fun tabBarStaysReadableOnAPhone() {
        val tabs = AppDestination.primaryDestinations
        assertTrue("tab bar needs at least Home plus one workflow", tabs.size >= 2)
        assertTrue(
            "tab bar holds ${tabs.size} items (${tabs.map { it.label }}); " +
                "past ${AppDestination.TAB_BAR_LIMIT} a phone can't give each label room",
            tabs.size <= AppDestination.TAB_BAR_LIMIT,
        )
    }

    @Test
    fun homeIsTheFirstTab() {
        assertEquals(AppDestination.HOME, AppDestination.primaryDestinations.first())
    }

    @Test
    fun everyDestinationIsReachable() {
        // Home lists every workspace, so a destination outside the tab bar is still one tap away.
        val reachable = AppDestination.primaryDestinations + AppDestination.workspaceDestinations
        AppDestination.entries.forEach { dest ->
            assertTrue("$dest has no tab and no Home row", dest in reachable)
        }
    }

    @Test
    fun screensOpenedFromHomeKeepANavBar() {
        // The nav bar carries the "‹ Home" back button, so only a tab can afford to drop it.
        AppDestination.secondaryDestinations.forEach { dest ->
            assertFalse("$dest opens from Home with no way back", dest.hidesNavBar)
        }
    }

    @Test
    fun onlyFullBleedScreensHideTheNavBar() {
        AppDestination.entries.filter { it.hidesNavBar }.forEach { dest ->
            assertTrue("$dest hides the nav bar without drawing its own chrome", dest.fullBleed)
        }
    }
}
