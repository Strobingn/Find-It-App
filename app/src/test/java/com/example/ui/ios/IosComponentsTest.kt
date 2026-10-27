package com.example.ui.ios

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.example.ui.AppDestination
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", sdk = [35])
class IosComponentsTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun tabBarIsSlimAndFitsEveryPrimaryTabOnASmallPhone() {
        val destinations = AppDestination.primaryDestinations
        val tabs = destinations.map { IosTabItem(it.name.lowercase(), it.label, it.icon) }
        // A real second tab rather than a hardcoded name, so moving a destination out of the
        // bar can't leave this test clicking a tag that no longer exists.
        val other = tabs[1].key
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                var selected by remember { mutableStateOf("home") }
                IosTabBar(items = tabs, selectedKey = selected, onSelect = { selected = it.key })
            }
        }
        // 49dp bar + hairline; the old Material bar was 80dp.
        val height = rule.onNodeWithTag("nav_home").getUnclippedBoundsInRoot().height
        assertTrue("tab height $height should be ≤ 50dp", height <= 50.dp)

        // The half this test only claimed to cover: each tab needs room for a 10sp word. The bar
        // had grown to eight tabs, which leaves 45dp per slot on this 360dp phone — the longest
        // labels ("Thermals", "Terrain") ran the full width of their slot with no gap to the
        // next one, and overflow was Clip, so a longer label would have been cut mid-glyph.
        val tabBounds = rule.onNodeWithTag("nav_home").getUnclippedBoundsInRoot()
        val tabWidth = tabBounds.right - tabBounds.left
        assertTrue(
            "${destinations.size} tabs leave $tabWidth each on a 360dp phone; " +
                "under $MIN_TAB_WIDTH the labels clip",
            tabWidth >= MIN_TAB_WIDTH,
        )

        rule.onNodeWithTag("nav_home").assertIsSelected()
        rule.onNodeWithTag("nav_$other").performClick()
        rule.onNodeWithTag("nav_$other").assertIsSelected()
        rule.onNodeWithTag("nav_home").assertIsNotSelected()
        tabs.forEach { rule.onNodeWithText(it.label).assertExists() }
    }

    @Test
    fun segmentedControlSelectsTappedSegment() {
        var picked = -1
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                var index by remember { mutableIntStateOf(0) }
                IosSegmentedControl(
                    options = listOf("Live forecast", "My temps"),
                    selectedIndex = index,
                    onSelect = {
                        index = it
                        picked = it
                    },
                )
            }
        }
        rule.onNodeWithText("My temps").performClick()
        assertEquals(1, picked)
    }

    @Test
    fun navBarBackButtonCallsBack() {
        var backs = 0
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                IosNavBar(title = "Site map", backLabel = "Home", onBack = { backs++ }, backTestTag = "nav_back_home")
            }
        }
        rule.onNodeWithText("Site map").assertExists()
        rule.onNodeWithTag("nav_back_home").performClick()
        assertEquals(1, backs)
    }

    @Test
    fun rowWithoutClickStillRendersValue() {
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                IosSection(header = "Setup") {
                    IosRow(title = "Location", value = "Not set", icon = Icons.Default.Home, showChevron = true)
                }
            }
        }
        rule.onNodeWithText("SETUP").assertExists()
        rule.onNodeWithText("Not set").assertExists()
    }

    private companion object {
        /** Narrowest slot that still fits the longest tab label at 10sp without clipping. */
        val MIN_TAB_WIDTH = 64.dp
    }
}
