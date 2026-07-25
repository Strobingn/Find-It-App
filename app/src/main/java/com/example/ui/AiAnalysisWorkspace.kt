package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.NormalizedRasterBounds
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarMapCanvas
import kotlinx.coroutines.delay

private const val AI_REFINE_ZOOM_THRESHOLD = 1.5f
private const val AI_REFINE_SETTLE_MS = 650L

/**
 * AI analysis page with its own interactive LAZ viewport.
 * Pinch/drag remains smooth; the original LAZ is re-rasterized only after the gesture settles.
 */
@Composable
fun AiAnalysisWorkspace(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
) {
    val summary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    val bitmap by viewModel.hillshadeBitmap.collectAsStateWithLifecycle()
    val isRendering by viewModel.isRendering.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val sweepX by viewModel.sweepX.collectAsStateWithLifecycle()
    val sweepY by viewModel.sweepY.collectAsStateWithLifecycle()
    val gridSpacing by viewModel.gridSpacing.collectAsStateWithLifecycle()
    val viewportResetKey by viewModel.viewportResetKey.collectAsStateWithLifecycle()

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val lastRefinedBounds = remember { mutableStateOf<NormalizedRasterBounds?>(null) }

    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine) {
        if (!canRefine || zoomLevel.value < AI_REFINE_ZOOM_THRESHOLD) return@LaunchedEffect
        delay(AI_REFINE_SETTLE_MS)
        val requested = visibleBounds.value.sanitized()
        if (!isRefining && requested != lastRefinedBounds.value) {
            lastRefinedBounds.value = requested
            viewModel.refineTerrain(requested)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("AI terrain viewport", fontWeight = FontWeight.Bold)
                Text(
                    when {
                        isRefining -> "Reloading original LAZ detail for the visible area…"
                        canRefine && zoomLevel.value >= AI_REFINE_ZOOM_THRESHOLD -> "${"%.1f".format(zoomLevel.value)}× · LAZ detail refreshed after pinch settles"
                        canRefine -> "Pinch to ${AI_REFINE_ZOOM_THRESHOLD}× or farther to rerender the visible LAZ area"
                        else -> "Import a LAZ/LAS file to enable viewport rerendering"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LidarMapCanvas(
            bitmap = bitmap,
            isRendering = isRendering,
            sweepX = sweepX,
            sweepY = sweepY,
            loggedSignals = signals,
            onSweepPositionChanged = viewModel::setSweepPosition,
            onStopSweeping = {},
            gridSpacing = gridSpacing,
            geoMetadata = metadata,
            currentLat = null,
            currentLon = null,
            mode = LidarCanvasMode.EXPLORE,
            viewportResetKey = viewportResetKey,
            showSurveyCursor = false,
            showCoordinateHud = false,
            onViewportChanged = { bounds, zoom ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .testTag("ai_analysis_laz_viewport"),
        )

        if (isRefining) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        GeminiAssistantScreen(
            terrainSummary = summary,
            grid = grid,
            metadata = metadata,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
