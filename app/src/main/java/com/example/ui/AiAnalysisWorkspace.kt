package com.example.ui

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.analysis.MetalDetectingTarget
import com.example.analysis.MetalDetectingTargetRefiner
import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.VerifiedFeedback
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.data.local.buildAnalyzedDatasetEntity
import com.example.geospatial.GeoSpatialLibrary
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarMapCanvas
import kotlinx.coroutines.delay

private const val AI_REFINE_ZOOM_THRESHOLD = 1.5f
private const val AI_REFINE_SETTLE_MS = 650L
private const val MAX_AI_MARKERS = 8

/**
 * One-map AI workspace tailored to historic-site reconnaissance for metal detecting.
 * LiDAR ranks occupation and travel features; it cannot directly identify a silver coin.
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
    val gridSpacing by viewModel.gridSpacing.collectAsStateWithLifecycle()
    val aiState by assistantViewModel.state.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val lastRefinedBounds = remember { mutableStateOf<NormalizedRasterBounds?>(null) }
    val centerMarkerMode = rememberSaveable { mutableStateOf(false) }
    val showTargetDetails = rememberSaveable { mutableStateOf(false) }
    val showDatasetComparison = rememberSaveable { mutableStateOf(false) }
    val analyzedDatasets by viewModel.analyzedDatasets.collectAsStateWithLifecycle()
    val analysisBitmap = aiState.localLayerBitmap ?: sourceBitmap
    val historicTargets = remember(aiState.localResult, signals) {
        val result = aiState.localResult ?: return@remember emptyList()
        val feedbackPoints = VerifiedFeedback.derive(signals, result.datasetKey)
        MetalDetectingTargetRefiner.refine(result, feedbackPoints)
    }

    LaunchedEffect(aiState.localResult) {
        val result = aiState.localResult ?: return@LaunchedEffect
        val rawTargets = MetalDetectingTargetRefiner.refine(result)
        viewModel.saveDatasetSnapshot(
            buildAnalyzedDatasetEntity(
                datasetKey = result.datasetKey,
                displayName = summary.take(60).ifBlank { result.datasetKey },
                metadata = metadata,
                targets = rawTargets,
            ),
        )
    }

    LaunchedEffect(visibleBounds.value, zoomLevel.value, canRefine, centerMarkerMode.value) {
        if (centerMarkerMode.value || !canRefine || zoomLevel.value < AI_REFINE_ZOOM_THRESHOLD) return@LaunchedEffect
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
                datasetKey = aiState.localResult?.datasetKey,
            ),
        )
    }

    val workspaceControls: @Composable () -> Unit = {
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
                            aiState.localResult?.let { aiState.selectedLayer.label } ?: "Historic terrain source",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                centerMarkerMode.value -> "Pan/zoom until the target is centered, then save it"
                                isRefining -> "Reloading original LAZ detail without changing your zoom…"
                                canRefine && zoomLevel.value >= AI_REFINE_ZOOM_THRESHOLD -> "${"%.1f".format(zoomLevel.value)}× · auto-refine enabled"
                                canRefine -> "${"%.1f".format(zoomLevel.value)}× · Refine works at any zoom"
                                else -> "Pre-1900 silver-site profile · pinch and drag"
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
                        enabled = canRefine && !isRefining && !centerMarkerMode.value,
                        modifier = Modifier.testTag("ai_refine_now_button"),
                    ) { Text(if (!canRefine) "No LAZ source" else if (isRefining) "Refining…" else "Refine") }
                    Button(
                        onClick = { assistantViewModel.runLocalAnalysis(grid, summary, signals) },
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
                    Text(
                        "Historic silver profile: homesites, cellar holes, refuse/privy pits, wagon roads, camps and stone walls",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        onClick = { centerMarkerMode.value = !centerMarkerMode.value },
                        modifier = Modifier.testTag("ai_marker_mode_button"),
                    ) { Text(if (centerMarkerMode.value) "Cancel marker" else "Mark map center") }
                    Button(
                        onClick = {
                            val bounds = visibleBounds.value.sanitized()
                            val centerX = ((bounds.left + bounds.right) * 50.0).toFloat()
                            val centerY = ((bounds.top + bounds.bottom) * 50.0).toFloat()
                            saveMarker(
                                centerX,
                                centerY,
                                MetalType.MANUAL_MARKER,
                                DetectionSource.MANUAL,
                                0f,
                                "Manual historic-site marker placed at the center of the zoomed AI viewport.",
                            )
                            centerMarkerMode.value = false
                        },
                        enabled = centerMarkerMode.value,
                        modifier = Modifier.testTag("ai_save_manual_marker_button"),
                    ) { Text("Save center") }
                    Button(
                        onClick = {
                            historicTargets
                                .sortedByDescending { it.score }
                                .take(MAX_AI_MARKERS)
                                .forEachIndexed { index, target ->
                                    saveMarker(
                                        target.xPercent,
                                        target.yPercent,
                                        MetalType.MAGNETIC_ANOMALY,
                                        DetectionSource.AI_ANALYSIS,
                                        target.score * 100f,
                                        buildString {
                                            append("Historic target ${index + 1}: ${target.type.label}")
                                            append(" · screening score ${"%.0f".format(target.score * 100f)}%")
                                            append(" · ${target.evidence.joinToString("; ")}")
                                            if (target.cautionReasons.isNotEmpty()) {
                                                append(" · Caution: ${target.cautionReasons.joinToString(" ")}")
                                            }
                                            append(" · Intended for pre-1900 occupation/travel-site field verification; not proof of metal.")
                                        },
                                    )
                                }
                        },
                        enabled = historicTargets.isNotEmpty(),
                        modifier = Modifier.testTag("ai_add_target_markers_button"),
                    ) { Text("Mark historic targets") }
                    if (historicTargets.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showTargetDetails.value = !showTargetDetails.value },
                            modifier = Modifier.testTag("ai_show_target_details_button"),
                        ) { Text(if (showTargetDetails.value) "Hide details" else "Show details") }
                    }
                    if (analyzedDatasets.size >= 2) {
                        OutlinedButton(
                            onClick = { showDatasetComparison.value = true },
                            modifier = Modifier.testTag("ai_compare_datasets_button"),
                        ) { Text("Compare datasets") }
                    }
                    Text("${signals.size} saved", style = MaterialTheme.typography.labelMedium)
                }

                if (showTargetDetails.value && historicTargets.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = if (isLandscape) 110.dp else 220.dp)
                            .testTag("ai_target_details_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(historicTargets.sortedByDescending { it.score }, key = { "${it.type}-${it.xPercent}-${it.yPercent}" }) { target ->
                            TargetDetailCard(target)
                        }
                    }
                }
            }
        }
    }

    val analysisMap: @Composable (Modifier) -> Unit = { mapModifier ->
        LidarMapCanvas(
            bitmap = analysisBitmap,
            isRendering = isRendering || aiState.isLocalAnalyzing,
            sweepX = 50f,
            sweepY = 50f,
            loggedSignals = signals,
            onSweepPositionChanged = { _, _ -> },
            onStopSweeping = {},
            gridSpacing = gridSpacing,
            geoMetadata = metadata,
            currentLat = null,
            currentLon = null,
            mode = LidarCanvasMode.EXPLORE,
            viewportResetKey = 0,
            showSurveyCursor = false,
            showCoordinateHud = false,
            onViewportChanged = { bounds, zoom, _, _ ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
            },
            modifier = mapModifier.testTag("ai_single_analysis_map"),
        )
    }

    if (isLandscape) {
        Row(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1.15f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                workspaceControls()
                analysisMap(Modifier.fillMaxWidth().weight(1f))
                if (isRefining || aiState.isLocalAnalyzing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            AiCloudPanel(
                terrainSummary = summary,
                grid = grid,
                metadata = metadata,
                assistantViewModel = assistantViewModel,
                modifier = Modifier.weight(0.85f).fillMaxHeight(),
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            workspaceControls()
            analysisMap(Modifier.fillMaxWidth().weight(1f))
            if (isRefining || aiState.isLocalAnalyzing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            AiCloudPanel(
                terrainSummary = summary,
                grid = grid,
                metadata = metadata,
                assistantViewModel = assistantViewModel,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    if (showDatasetComparison.value) {
        DatasetComparisonDialog(
            datasets = analyzedDatasets,
            onDismiss = { showDatasetComparison.value = false },
        )
    }
}

@Composable
private fun TargetDetailCard(target: MetalDetectingTarget) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${target.type.label} · ${(target.score * 100f).toInt()}%",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (target.verifiedNearby) {
                    Text(
                        "Field-verified nearby",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                target.evidence.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            target.cautionReasons.forEach { reason ->
                Text(
                    "⚠ $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
