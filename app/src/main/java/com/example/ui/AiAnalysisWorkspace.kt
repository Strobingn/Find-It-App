package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.geospatial.GeoSpatialLibrary
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarMapCanvas
import kotlinx.coroutines.delay

private const val AI_REFINE_ZOOM_THRESHOLD = 1.5f
private const val AI_REFINE_SETTLE_MS = 650L
private const val MAX_AI_MARKERS = 8

/**
 * AI analysis page with an interactive LAZ viewport, manual markers, and AI-suggested dig targets.
 */
@Composable
fun AiAnalysisWorkspace(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    assistantViewModel: AiTerrainViewModel = viewModel(),
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
    val aiState by assistantViewModel.state.collectAsStateWithLifecycle()

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val lastRefinedBounds = remember { mutableStateOf<NormalizedRasterBounds?>(null) }
    val markingMode = rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine, markingMode.value) {
        if (markingMode.value || !canRefine || zoomLevel.value < AI_REFINE_ZOOM_THRESHOLD) {
            return@LaunchedEffect
        }
        delay(AI_REFINE_SETTLE_MS)
        val requested = visibleBounds.value.sanitized()
        if (!isRefining && requested != lastRefinedBounds.value) {
            lastRefinedBounds.value = requested
            viewModel.refineTerrain(requested)
        }
    }

    fun saveMarker(
        x: Float,
        y: Float,
        metalType: MetalType,
        source: DetectionSource,
        strength: Float,
        notes: String,
    ) {
        val coordinate = GeoSpatialLibrary.gridToGeographic(x, y, metadata)
        viewModel.updateLoggedSignal(
            TargetSignal(
                gridX = x.coerceIn(0f, 100f),
                gridY = y.coerceIn(0f, 100f),
                metalType = metalType,
                signalStrength = strength.coerceIn(0f, 100f),
                latitude = coordinate?.first,
                longitude = coordinate?.second,
                source = source,
                notes = notes,
                status = if (source == DetectionSource.AI_ANALYSIS) "AI suggested" else "Logged",
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI terrain viewport", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                markingMode.value -> "Tap or drag the crosshair, then save the marker"
                                isRefining -> "Reloading original LAZ detail for the visible area…"
                                canRefine && zoomLevel.value >= AI_REFINE_ZOOM_THRESHOLD -> "${"%.1f".format(zoomLevel.value)}× · auto-refines after pinch settles"
                                canRefine -> "${"%.1f".format(zoomLevel.value)}× · tap Refine now at any zoom"
                                else -> "Import a LAZ/LAS file to enable viewport rerendering"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            val requested = visibleBounds.value.sanitized()
                            lastRefinedBounds.value = requested
                            viewModel.refineTerrain(requested)
                        },
                        enabled = canRefine && !isRefining && !markingMode.value,
                        modifier = Modifier.testTag("ai_refine_now_button"),
                    ) {
                        Text(if (isRefining) "Refining…" else "Refine now")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { markingMode.value = !markingMode.value },
                        modifier = Modifier.testTag("ai_marker_mode_button"),
                    ) {
                        Text(if (markingMode.value) "Exit marking" else "Place marker")
                    }
                    Button(
                        onClick = {
                            saveMarker(
                                x = sweepX,
                                y = sweepY,
                                metalType = MetalType.MANUAL_MARKER,
                                source = DetectionSource.MANUAL,
                                strength = 0f,
                                notes = "Manual marker placed from the AI terrain preview.",
                            )
                        },
                        enabled = markingMode.value,
                        modifier = Modifier.testTag("ai_save_manual_marker_button"),
                    ) {
                        Text("Save marker")
                    }
                    Button(
                        onClick = {
                            aiState.localResult?.candidates
                                ?.sortedByDescending { it.score }
                                ?.take(MAX_AI_MARKERS)
                                ?.forEachIndexed { index, candidate ->
                                    saveMarker(
                                        x = candidate.xPercent,
                                        y = candidate.yPercent,
                                        metalType = MetalType.MAGNETIC_ANOMALY,
                                        source = DetectionSource.AI_ANALYSIS,
                                        strength = candidate.score * 100f,
                                        notes = buildString {
                                            append("AI target ${index + 1}: ${candidate.type.label}")
                                            append(" · score ${"%.0f".format(candidate.score * 100f)}%")
                                            if (candidate.evidence.isNotEmpty()) {
                                                append(" · ${candidate.evidence.take(3).joinToString("; ")}")
                                            }
                                        },
                                    )
                                }
                        },
                        enabled = aiState.localResult?.candidates?.isNotEmpty() == true,
                        modifier = Modifier.testTag("ai_add_target_markers_button"),
                    ) {
                        Text("Mark AI targets")
                    }
                    Text(
                        "${signals.size} saved",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            mode = if (markingMode.value) LidarCanvasMode.SURVEY else LidarCanvasMode.EXPLORE,
            viewportResetKey = viewportResetKey,
            showSurveyCursor = markingMode.value,
            showCoordinateHud = false,
            onViewportChanged = { bounds, zoom ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp)
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
            assistantViewModel = assistantViewModel,
        )
    }
}
