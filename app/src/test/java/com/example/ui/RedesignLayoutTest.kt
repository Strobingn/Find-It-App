package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.ui.components.AnalyzeSegment
import com.example.ui.components.AnalyzeSheet
import com.example.ui.components.TargetLoggerPanel
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the redesigned Map sheet and Finds list to confirm the new chrome lays out and its
 * segment/filter/expand interactions are wired to the right state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi", sdk = [35])
class RedesignLayoutTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun signal(
        id: Long,
        status: String,
        metalType: MetalType = MetalType.MANUAL_MARKER,
    ) = TargetSignal(
        id = id,
        gridX = 42f,
        gridY = 61f,
        metalType = metalType,
        signalStrength = 84f,
        depthCm = 18,
        source = DetectionSource.MANUAL,
        status = status,
    )

    @Test
    fun analyzeSheet_starts_collapsed_on_the_relief_peek_row() {
        setAnalyzeSheet()

        composeTestRule.onNodeWithText("Analyze").assertIsDisplayed()
        composeTestRule.onNodeWithTag("analyze_peek_row").assertIsDisplayed()
        AnalyzeSegment.entries.forEach { segment ->
            composeTestRule.onNodeWithTag("analyze_segment_${segment.label.lowercase()}").assertIsDisplayed()
        }
        // Relief is the default segment, so its first two styles head the peek row.
        composeTestRule.onNodeWithText("Hillshade").assertIsDisplayed()
        composeTestRule.onNodeWithText("Multi-light").assertIsDisplayed()
    }

    @Test
    fun analyzeSheet_segment_tab_swaps_the_peek_row() {
        setAnalyzeSheet()

        composeTestRule.onNodeWithTag("analyze_segment_lighting").performClick()

        // The Lighting peek offers the eight compass directions instead of relief styles.
        composeTestRule.onNodeWithText("NE").assertIsDisplayed()
        composeTestRule.onNodeWithText("45°").assertIsDisplayed()
    }

    @Test
    fun analyzeSheet_handle_expands_to_the_full_control_cards() {
        setAnalyzeSheet()

        composeTestRule.onNodeWithTag("analyze_sheet_handle").performClick()

        // Expanded swaps the peek row for the real LidarControlPanel cards, scrolled to Relief.
        composeTestRule.onNodeWithText("Relief style").assertIsDisplayed()
        composeTestRule.onNodeWithTag("visualization_0").assertIsDisplayed()
    }

    @Test
    fun analyzeSheet_relief_peek_selection_reaches_the_callback() {
        var chosen = -1
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                var segment by remember { mutableStateOf(AnalyzeSegment.RELIEF) }
                AnalyzeSheet(
                    expanded = false,
                    onExpandedChange = {},
                    segment = segment,
                    onSegmentChange = { segment = it },
                    extentLabel = "1.4 km × 1.4 km",
                    maxExpandedHeight = 600.dp,
                    selectedSiteIndex = 0,
                    onSiteSelected = {},
                    sunAzimuth = 315f,
                    onSunAzimuthChanged = {},
                    sunAltitude = 35f,
                    onSunAltitudeChanged = {},
                    vegetationFilter = 0.8f,
                    onVegetationFilterChanged = {},
                    paletteType = 1,
                    onPaletteTypeChanged = {},
                    contrast = 1.5f,
                    onContrastChanged = {},
                    visualizationMode = 0,
                    onVisualizationModeChanged = { chosen = it },
                    overlayType = 0,
                    onOverlayTypeChanged = {},
                    overlayOpacity = 0.4f,
                    onOverlayOpacityChanged = {},
                    gridSpacing = 0f,
                    onGridSpacingChanged = {},
                    zScale = 1f,
                    onZScaleChanged = {},
                    featureScaleMeters = 6f,
                    onFeatureScaleChanged = {},
                    analysisSensitivity = 1.2f,
                    onAnalysisSensitivityChanged = {},
                    contourIntervalMeters = 0f,
                    onContourIntervalChanged = {},
                    heatmapEnabled = false,
                    onHeatmapEnabledChanged = {},
                    basemapEnabled = false,
                    onBasemapEnabledChanged = {},
                    basemapOpacity = 0.6f,
                    onBasemapOpacityChanged = {},
                    basemapStatus = null,
                )
            }
        }

        composeTestRule.onNodeWithText("Multi-light").performClick()

        assertEquals(1, chosen)
    }

    @Test
    fun findsScreen_leads_with_the_list_and_a_floating_log_button() {
        setFindsPanel(
            listOf(
                signal(1L, "Logged", MetalType.IRON),
                signal(2L, "Excavated"),
                signal(3L, "Excavated"),
            ),
        )

        composeTestRule.onNodeWithText("Finds").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 logged · 2 excavated").assertIsDisplayed()
        composeTestRule.onNodeWithTag("logged_signals_list").assertIsDisplayed()
        composeTestRule.onNodeWithTag("log_signal_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Iron Nail/Spike").assertIsDisplayed()
    }

    @Test
    fun findsScreen_status_chip_filters_the_list() {
        setFindsPanel(
            listOf(
                signal(1L, "Logged", MetalType.IRON),
                signal(2L, "Excavated", MetalType.GOLD),
            ),
        )

        composeTestRule.onNodeWithText("Iron Nail/Spike").assertIsDisplayed()

        composeTestRule.onNodeWithText("Excavated 1").performClick()

        composeTestRule.onNodeWithText("Gold Coin/Ring").assertIsDisplayed()
        composeTestRule.onNodeWithText("Iron Nail/Spike").assertDoesNotExist()
    }

    @Test
    fun findsScreen_empty_state_still_offers_logging() {
        setFindsPanel(emptyList())

        composeTestRule.onNodeWithText("Nothing logged yet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("log_signal_button").assertIsDisplayed()
    }

    @Test
    fun homeScreen_open_workspace_button_reaches_the_callback() {
        val application = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val viewModel = HillshadeViewModel(application)
        var opened = false

        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                HomeScreen(viewModel = viewModel, onOpenWorkspace = { opened = true })
            }
        }

        composeTestRule.onNodeWithTag("home_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Open workspace").performClick()

        assertEquals(true, opened)
    }

    private fun setFindsPanel(signals: List<TargetSignal>) {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                TargetLoggerPanel(
                    loggedSignals = signals,
                    currentSweepX = 50f,
                    currentSweepY = 50f,
                    onLogSignal = {},
                    onDeleteSignal = {},
                    onUpdateSignal = {},
                    onClearAll = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    private fun setAnalyzeSheet() {
        composeTestRule.setContent {
            MyApplicationTheme(darkTheme = true) {
                var expanded by remember { mutableStateOf(false) }
                var segment by remember { mutableStateOf(AnalyzeSegment.RELIEF) }
                var visualization by remember { mutableStateOf(0) }
                var azimuth by remember { mutableStateOf(315f) }
                AnalyzeSheet(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    segment = segment,
                    onSegmentChange = { segment = it },
                    extentLabel = "1.4 km × 1.4 km",
                    maxExpandedHeight = 600.dp,
                    selectedSiteIndex = 0,
                    onSiteSelected = {},
                    sunAzimuth = azimuth,
                    onSunAzimuthChanged = { azimuth = it },
                    sunAltitude = 35f,
                    onSunAltitudeChanged = {},
                    vegetationFilter = 0.8f,
                    onVegetationFilterChanged = {},
                    paletteType = 1,
                    onPaletteTypeChanged = {},
                    contrast = 1.5f,
                    onContrastChanged = {},
                    visualizationMode = visualization,
                    onVisualizationModeChanged = { visualization = it },
                    overlayType = 0,
                    onOverlayTypeChanged = {},
                    overlayOpacity = 0.4f,
                    onOverlayOpacityChanged = {},
                    gridSpacing = 0f,
                    onGridSpacingChanged = {},
                    zScale = 1f,
                    onZScaleChanged = {},
                    featureScaleMeters = 6f,
                    onFeatureScaleChanged = {},
                    analysisSensitivity = 1.2f,
                    onAnalysisSensitivityChanged = {},
                    contourIntervalMeters = 0f,
                    onContourIntervalChanged = {},
                    heatmapEnabled = false,
                    onHeatmapEnabledChanged = {},
                    basemapEnabled = false,
                    onBasemapEnabledChanged = {},
                    basemapOpacity = 0.6f,
                    onBasemapOpacityChanged = {},
                    basemapStatus = null,
                )
            }
        }
    }
}
