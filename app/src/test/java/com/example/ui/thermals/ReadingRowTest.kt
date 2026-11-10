package com.example.ui.thermals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.thermals.ManualReading
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
class ReadingRowTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun readingRowStepperAccessibilityAndBounds() {
        var currentReading = ManualReading(hour = 0, temperatureF = 45.0)

        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                var reading by remember { mutableStateOf(currentReading) }
                ReadingRow(
                    reading = reading,
                    canRemove = false,
                    onChange = {
                        reading = it
                        currentReading = it
                    },
                    onRemove = {},
                )
            }
        }

        // At hour 0, decrement is disabled
        rule.onNodeWithContentDescription("Earlier hour").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Later hour").assertIsEnabled()

        // Clicking increment increases hour
        rule.onNodeWithContentDescription("Later hour").performClick()
        rule.waitForIdle()
        assertEquals(1, currentReading.hour)
        rule.onNodeWithContentDescription("Earlier hour").assertIsEnabled()
    }

    @Test
    fun readingRowStepperDisabledAtMaxHour() {
        var currentReading = ManualReading(hour = 23, temperatureF = 65.0)

        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                var reading by remember { mutableStateOf(currentReading) }
                ReadingRow(
                    reading = reading,
                    canRemove = true,
                    onChange = {
                        reading = it
                        currentReading = it
                    },
                    onRemove = {},
                )
            }
        }

        rule.onNodeWithContentDescription("Later hour").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Earlier hour").assertIsEnabled()
        rule.onNodeWithContentDescription("Remove reading").assertHasClickAction()
    }
}
