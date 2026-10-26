package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.relief.LidarTerrainServer
import com.example.data.relief.ReliefMode
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Keeps the Mapbox tab's floating controls compact. Set FINDIT_SCREENSHOT_DIR to also write
 * a PNG of each state for eyeballing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [35])
class MapboxControlCardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val status = MapboxControlStatus(
        zoom = { 15.0 },
        lidarStats = { LidarTerrainServer.Stats(requested = 12, served = 9, inFlight = 3) },
        lidarStarting = false,
        hasMyLidar = true,
        myLidarUnplaced = false,
        reliefLoading = 0,
        reliefResolution = 0.93,
        reliefMinZoom = 12,
        topoDecades = listOf(1890, 1940, 1950, 1970),
        topoSheetLines = listOf("1954 · Ithaca West, NY · 1:24000"),
        topoStatus = null,
    )

    private fun show(controls: MapboxControls) {
        rule.setContent {
            MyApplicationTheme(darkTheme = true) {
                // Stand-in for the map so the translucent card reads as it would on satellite imagery.
                Box(Modifier.fillMaxWidth().height(560.dp).background(Color(0xFF4A5A3C))) {
                    MapboxControlCard(
                        controls = controls,
                        status = status,
                        onTilt = {},
                        modifier = Modifier.padding(6.dp).widthIn(max = 420.dp),
                    )
                }
            }
        }
    }

    private fun cardHeightDp(): Float = rule.onNodeWithTag("mapbox_controls").getBoundsInRoot().let {
        (it.bottom - it.top).value
    }

    private fun snap(name: String) {
        val dir = System.getenv("FINDIT_SCREENSHOT_DIR") ?: return
        rule.waitForIdle()
        // Software draw: captureToImage waits on a hardware frame Robolectric never produces.
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(dir).mkdirs()
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun startsFoldedToOneSlimRow() {
        show(MapboxControls())
        snap("controls_collapsed")
        assertTrue("collapsed card is ${cardHeightDp()} dp", cardHeightDp() <= 44f)
    }

    @Test
    fun tappingATabOpensItAndTappingAgainFoldsIt() {
        val controls = MapboxControls()
        show(controls)
        rule.onNodeWithTag("mapbox_panel_terrain").performClick()
        assertTrue(controls.expanded)
        assertEquals(MapboxPanel.TERRAIN, controls.panel)
        rule.onNodeWithTag("mapbox_panel_terrain").performClick()
        assertFalse(controls.expanded)
    }

    @Test
    fun mapPanelIsShort() {
        show(MapboxControls().apply { expanded = true; panel = MapboxPanel.MAP })
        snap("controls_map")
        assertTrue("map panel is ${cardHeightDp()} dp", cardHeightDp() <= 170f)
    }

    @Test
    fun terrainPanelIsShort() {
        show(MapboxControls().apply { expanded = true; panel = MapboxPanel.TERRAIN })
        snap("controls_3d")
        rule.onNodeWithText("Loading 3…").assertExists()
        assertTrue("3D panel is ${cardHeightDp()} dp", cardHeightDp() <= 170f)
    }

    @Test
    fun reliefPanelHidesFineTuningUntilAsked() {
        val controls = MapboxControls().apply {
            expanded = true
            panel = MapboxPanel.LIDAR
            reliefOn = true
            reliefMode = ReliefMode.HILLSHADE
        }
        show(controls)
        snap("controls_lidar")
        assertTrue("relief panel is ${cardHeightDp()} dp", cardHeightDp() <= 200f)
        rule.onNodeWithText("Adjust look").performClick()
        rule.waitForIdle()
        snap("controls_lidar_more")
        // Scrolls inside a capped panel instead of covering the map.
        assertTrue("expanded relief panel is ${cardHeightDp()} dp", cardHeightDp() <= 300f)
    }

    @Test
    fun topoPanelIsShort() {
        show(MapboxControls().apply { expanded = true; panel = MapboxPanel.TOPO; topoOn = true; topoDecade = 1950 })
        snap("controls_topo")
        assertTrue("topo panel is ${cardHeightDp()} dp", cardHeightDp() <= 200f)
    }
}
