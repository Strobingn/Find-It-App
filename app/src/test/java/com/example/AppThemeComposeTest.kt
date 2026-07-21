package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.material3.Text
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [35])
class AppThemeComposeTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun app_title_is_displayed() {
    composeTestRule.setContent {
      MyApplicationTheme {
        Text("Find It")
      }
    }

    composeTestRule.onNodeWithText("Find It").assertIsDisplayed()
  }
}
