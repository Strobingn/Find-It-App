package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Material resolves a surface's default content colour by matching the background's *colour value*
 * against the scheme's slots, and it tests surfaceVariant before surfaceContainer. Two slots that
 * hold the same colour therefore collapse onto whichever is tested first — which is how every
 * surfaceContainer Card ended up inheriting onSurfaceVariant (grey) for its title while the
 * buttons beside it, which set their own content colour, stayed white.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SurfaceContentColorTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun cardSurfacesTakeOnSurface_inDarkMode() = assertCardSurfacesUseOnSurface(darkTheme = true)

  @Test
  fun cardSurfacesTakeOnSurface_inLightMode() = assertCardSurfacesUseOnSurface(darkTheme = false)

  private fun assertCardSurfacesUseOnSurface(darkTheme: Boolean) {
    lateinit var resolved: Map<String, Pair<Color, Color>>
    composeTestRule.setContent {
      MyApplicationTheme(darkTheme = darkTheme) {
        resolved = cardSurfaceContentColors()
      }
    }
    resolved.forEach { (slot, colors) ->
      val (actual, onSurface) = colors
      assertEquals("$slot should take onSurface as its content colour", onSurface, actual)
    }
  }

  /** The slots the app puts cards and grouped rows on, with the content colour Material picks. */
  @Composable
  private fun cardSurfaceContentColors(): Map<String, Pair<Color, Color>> {
    val scheme = MaterialTheme.colorScheme
    return mapOf(
      "surfaceContainer" to (contentColorFor(scheme.surfaceContainer) to scheme.onSurface),
      "surfaceContainerLow" to (contentColorFor(scheme.surfaceContainerLow) to scheme.onSurface),
      "surfaceContainerHigh" to (contentColorFor(scheme.surfaceContainerHigh) to scheme.onSurface),
      "surfaceContainerHighest" to (contentColorFor(scheme.surfaceContainerHighest) to scheme.onSurface),
    )
  }
}
