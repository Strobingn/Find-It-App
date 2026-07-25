package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
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
import com.example.analysis.TerrainDerivedLayer
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
 * One-map AI workspace. The selected source/derived layer is the only rendered map.
 * Pinch, zoom, manual markers, AI markers, refinement, and cloud analysis all share it.
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
    val sourceBitmap by viewModel.hillshadeBitmap.collectAsStateWithLifecycle()
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
    val analysisBitmap = aiState.localLayerBitmap ?: sourceBitmap

    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine, markingMode.value) {
        if (markingMode.value || !canRefine || zoomLevel.value < AI_REFINE_ZOOM_THRESHOLD) return@LaunchedEffect
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
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            aiState.localResult?.let { aiState.selectedLayer.label } ?: "Terrain source",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                markingMode.value -> "Move the crosshair and save a target"
                                isRefining -> "Reloading original LAZ detail…"
                                canRefine && zoomLevel.value >= AI_REFINE_ZOOM_THRESHOLD -> "${"%.1f".format(zoomLevel.value)}× · auto-refine enabled"
                                canRefine -> "${"%.1f".format(zoomLevel.value)}× · Refine now works at any zoom"
                                else -> "Pinch and drag this analysis render"
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
                    ) { Text(if (isRefining) "Refining…" else "Refine") }
                    Button(
                        onClick = { assistantViewModel.runLocalAnalysis(grid, summary) },
                        enabled = !aiState.isLocalAnalyzing,
                        modifier = Modifier.testTag("ai_run_local_analysis_button"),
                    ) {
                        if (aiState.isLocalAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (aiState.localResult == null) "Analyze" else "Re-run")
                        }
                    }
                }

                if (aiState.localResult != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TerrainDerivedLayer.entries.forEach { layer ->
                            FilterChip(
                                selected = aiState.selectedLayer == layer,
                                onClick = { assistantViewModel.selectLocalLayer(layer) },
                                label = { Text(layer.label) },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { markingMode.value = !markingMode.value },
                        modifier = Modifier.testTag("ai_marker_mode_button"),
                    ) { Text(if (markingMode.value) "Pan/zoom" else "Place marker") }
                    Button(
                        onClick = {
                            saveMarker(
                                sweepX,
                                sweepY,
                                MetalType.MANUAL_MARKER,
                                DetectionSource.MANUAL,
                                0f,
                                "Manual marker placed on the AI analysis render.",
                            )
                        },
                        enabled = markingMode.value,
                        modifier = Modifier.testTag("ai_save_manual_marker_button"),
                    ) { Text("Save marker") }
                    Button(
                        onClick = {
                            aiState.localResult?.candidates
                                ?.sortedByDescending { it.score }
                                ?.take(MAX_AI_MARKERS)
                                ?.forEachIndexed { index, candidate ->
                                    saveMarker(
                                        candidate.xPercent,
                                        candidate.yPercent,
                                        MetalType.MAGNETIC_ANOMALY,
                                        DetectionSource.AI_ANALYSIS,
                                        candidate.score * 100f,
                                        buildString {
                                            append("AI target ${index + 1}: ${candidate.type.label}")
                                            append(" · confidence ${"%.0f".format(candidate.score * 100f)}%")
                                            if (candidate.evidence.isNotEmpty()) append(" · ${candidate.evidence.take(3).joinToString("; ")}")
                                        },
                                    )
                                }
                        },
                        enabled = aiState.localResult?.candidates?.isNotEmpty() == true,
                        modifier = Modifier.testTag("ai_add_target_markers_button"),
                    ) { Text("Mark AI targets") }
                    Text("${signals.size} saved", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        LidarMapCanvas(
            bitmap = analysisBitmap,
            isRendering = isRendering || aiState.isLocalAnalyzing,
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
                .height(390.dp)
                .testTag("ai_single_analysis_map"),
        )

        if (isRefining || aiState.isLocalAnalyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        AiCloudPanel(
            terrainSummary = summary,
            grid = grid,
            metadata = metadata,
            assistantViewModel = assistantViewModel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
