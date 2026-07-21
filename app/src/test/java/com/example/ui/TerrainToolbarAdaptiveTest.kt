package com.example.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi", sdk = [35])
class TerrainToolbarAdaptiveTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun compactToolbarKeepsEveryControlInsidePhoneWidth() {
        composeTestRule.setContent {
            MyApplicationTheme {
                TerrainToolbar(
                    compact = true,
                    azimuth = 315f,
                    focusMode = false,
                    controlsVisible = false,
                    onRotateLeft = {},
                    onRotateRight = {},
                    onFit = {},
                    onToggleControls = {},
                    onToggleFocusMode = {},
                )
            }
        }

        val toolbarBounds = composeTestRule.onNodeWithTag("terrain_toolbar")
            .fetchSemanticsNode().boundsInRoot
        val controls = listOf(
            composeTestRule.onNodeWithContentDescription("Rotate light 45 degrees left"),
            composeTestRule.onNodeWithContentDescription("Rotate light 45 degrees right"),
            composeTestRule.onNodeWithText("Fit"),
            composeTestRule.onNodeWithText("Controls"),
            composeTestRule.onNodeWithText("Full screen"),
        )

        controls.forEach { interaction ->
            val bounds = interaction.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= toolbarBounds.left)
            assertTrue(bounds.right <= toolbarBounds.right)
            assertTrue(bounds.top >= toolbarBounds.top)
            assertTrue(bounds.bottom <= toolbarBounds.bottom)
        }
    }
}