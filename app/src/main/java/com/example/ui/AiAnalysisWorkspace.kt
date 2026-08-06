package com.example.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.FieldAiSessionPack
import com.example.analysis.MetalDetectingTarget
import androidx.compose.ui.text.font.FontFamily
import com.example.analysis.LayerVerdict
import com.example.analysis.MetalDetectingTargetRefiner
import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.TerrainIntelligenceEngine
import com.example.analysis.VerifiedFeedback
import com.example.data.DetectionSource
import com.example.data.MetalType
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.analysis.DigDepthEstimate
import com.example.analysis.DigDepthEstimator
import com.example.analysis.HuntZone
import com.example.analysis.HuntZoneClusterer
import com.example.data.field.NavigationTarget
import com.example.data.local.SavedTarget
import com.example.data.local.buildAnalyzedDatasetEntity
import com.example.data.local.parseTargets
import com.example.geospatial.GeoSpatialLibrary
import com.example.ui.components.LidarCanvasMode
import com.example.ui.components.LidarMapCanvas
import com.example.ui.components.LidarOverlayTarget
import java.nio.ByteBuffer
import java.security.MessageDigest
internal const val AI_HISTORIC_TARGETS_DEFAULT_VISIBLE = true
private val CompactButtonHeight = 32.dp
private val CompactButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)

/**
 * One-map AI workspace tailored to historic-site reconnaissance for metal detecting.
 * LiDAR ranks occupation and travel features; it cannot directly identify a silver coin.
 */
@Composable
fun AiAnalysisWorkspace(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    assistantViewModel: AiTerrainViewModel = viewModel(key = "ai_analysis_workspace"),
) {
    val summary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val metadata by viewModel.activeGeoMetadata.collectAsStateWithLifecycle()
    val sourceBitmap by viewModel.hillshadeBitmap.collectAsStateWithLifecycle()
    val isRendering by viewModel.isRendering.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val refinementProgress by viewModel.terrainRefinementProgress.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val excavationLogs by viewModel.excavationLogs.collectAsStateWithLifecycle()
    val breadcrumbTracks by viewModel.breadcrumbTracks.collectAsStateWithLifecycle()
    val sunAzimuth by viewModel.sunAzimuth.collectAsStateWithLifecycle()
    val sunAltitude by viewModel.sunAltitude.collectAsStateWithLifecycle()
    val deviceLatitude by viewModel.deviceLatitude.collectAsStateWithLifecycle()
    val deviceLongitude by viewModel.deviceLongitude.collectAsStateWithLifecycle()
    val terrainKey by viewModel.activeTerrainKey.collectAsStateWithLifecycle()
    val gridSpacing by viewModel.gridSpacing.collectAsStateWithLifecycle()
    val featureTypeCalibration by viewModel.featureTypeCalibration.collectAsStateWithLifecycle()
    val historicMapAgreementScore by viewModel.historicMapAgreementScore.collectAsStateWithLifecycle()
    val visualizationMode by viewModel.visualizationMode.collectAsStateWithLifecycle()
    val inspectedCellSummary by viewModel.inspectedCellSummary.collectAsStateWithLifecycle()
    val terrainQuality by viewModel.terrainQuality.collectAsStateWithLifecycle()
    val aiState by assistantViewModel.state.collectAsStateWithLifecycle()
    val analyzedDatasets by viewModel.analyzedDatasets.collectAsStateWithLifecycle()

    // Keep AI ranker state in sync with map-published historic agreement so ExplainableRanker
    // applies the same capped adjustment as MetalDetectingTargetRefiner.
    LaunchedEffect(historicMapAgreementScore) {
        assistantViewModel.setHistoricMapAgreementScore(historicMapAgreementScore)
    }

    val alternateDatasets = remember(analyzedDatasets, terrainKey) {
        analyzedDatasets.filter { it.datasetKey != terrainKey }
    }
    var selectedSecondaryKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(alternateDatasets, terrainKey) {
        if (selectedSecondaryKey != null &&
            alternateDatasets.none { it.datasetKey == selectedSecondaryKey }
        ) {
            selectedSecondaryKey = null
        }
        if (selectedSecondaryKey == null && alternateDatasets.isNotEmpty()) {
            selectedSecondaryKey = alternateDatasets.first().datasetKey
        }
    }
    val secondaryDataset = remember(alternateDatasets, selectedSecondaryKey) {
        alternateDatasets.firstOrNull { it.datasetKey == selectedSecondaryKey }
            ?: alternateDatasets.firstOrNull()
    }
    var selectedCandidateSummary by rememberSaveable { mutableStateOf("") }

    val fieldPack = remember(
        summary,
        metadata.crs,
        metadata.siteName,
        sunAzimuth,
        sunAltitude,
        grid.width,
        grid.height,
        grid.cellSizeMeters,
        deviceLatitude,
        deviceLongitude,
        signals,
        excavationLogs,
        breadcrumbTracks,
        aiState.localResult,
        visualizationMode,
        secondaryDataset,
        inspectedCellSummary,
        selectedCandidateSummary,
        terrainQuality,
    ) {
        val secondarySummary = secondaryDataset?.let { ds ->
            buildString {
                append(ds.displayName)
                if (ds.siteName.isNotBlank()) append(" · ").append(ds.siteName)
                append(" · ${ds.width}x${ds.height} @ ${ds.cellSizeMeters} m")
                if (ds.crs.isNotBlank()) append(" · ").append(ds.crs)
            }
        }.orEmpty()
        val secondaryContext = secondaryDataset?.let { ds ->
            "CRS=${ds.crs}; site=${ds.siteName}; key=${ds.datasetKey}"
        }.orEmpty()
        FieldAiSessionPack(
            terrainSummary = summary,
            terrainContext = "CRS=${metadata.crs}; site=${metadata.siteName}",
            sunAzimuth = sunAzimuth,
            sunAltitude = sunAltitude,
            gridWidth = grid.width,
            gridHeight = grid.height,
            cellSizeMeters = grid.cellSizeMeters,
            deviceLatitude = deviceLatitude,
            deviceLongitude = deviceLongitude,
            signals = signals,
            excavationLogs = excavationLogs,
            breadcrumbTracks = breadcrumbTracks,
            localResult = aiState.localResult,
            inspectedCellSummary = inspectedCellSummary,
            visualizationMode = visualizationMode,
            secondaryTerrainSummary = secondarySummary,
            secondaryTerrainContext = secondaryContext,
            secondaryCandidateCount = secondaryDataset?.let { ds ->
                ds.targetsJson.count { it == '{' }.coerceAtLeast(0)
            } ?: 0,
            secondaryFindCount = secondaryDataset?.let { ds ->
                signals.count { it.datasetKey == ds.datasetKey || it.terrainKey == ds.datasetKey }
            } ?: 0,
            selectedCandidateSummary = selectedCandidateSummary,
            terrainQualitySummary = terrainQuality?.bannerLine().orEmpty(),
        )
    }

    val visibleBounds = remember { mutableStateOf(NormalizedRasterBounds.Full) }
    val zoomLevel = rememberSaveable { mutableStateOf(1f) }
    val centerMarkerMode = rememberSaveable { mutableStateOf(false) }
    val showTargetDetails = rememberSaveable { mutableStateOf(false) }
    val showHuntZones = rememberSaveable { mutableStateOf(false) }
    val showHistoricTargets = rememberSaveable { mutableStateOf(AI_HISTORIC_TARGETS_DEFAULT_VISIBLE) }
    val showCloudTargets = rememberSaveable { mutableStateOf(true) }
    val showDatasetComparison = rememberSaveable { mutableStateOf(false) }
    val pendingLocalLayer = remember { mutableStateOf<TerrainDerivedLayer?>(null) }
    val localBitmapAtRequest = remember { mutableStateOf(aiState.localLayerBitmap) }
    val sourceRenderLabel = aiSourceVisualizationLabel(visualizationMode)
    val localLayerPending = !aiState.showSourceHillshade && pendingLocalLayer.value != null
    val analysisBitmap = when {
        aiState.showSourceHillshade -> sourceBitmap
        localLayerPending -> null
        // Falling back to the source hillshade keeps a map on screen when the derived layer is
        // gone. Handing null to LidarMapCanvas makes it render "Import a LAZ/LAS dataset to
        // begin", which is both blank and untrue while a dataset is loaded.
        else -> aiState.localLayerBitmap ?: sourceBitmap
    }

    LaunchedEffect(aiState.localLayerBitmap, pendingLocalLayer.value) {
        if (pendingLocalLayer.value != null &&
            aiState.localLayerBitmap != null &&
            aiState.localLayerBitmap !== localBitmapAtRequest.value
        ) {
            pendingLocalLayer.value = null
            localBitmapAtRequest.value = aiState.localLayerBitmap
        }
    }

    // Refining reloads the point cloud at a new resolution, producing a grid whose signature has
    // no cached analysis, so restoreLocalAnalysis clears localResult and the derived layers. The
    // layer chips are hidden while localResult is null, so a viewer left in derived-layer mode had
    // no control to get back to a visible map. Return to the source hillshade, which the refine
    // just regenerated at the new detail, and leave the status line telling them to tap Analyze.
    LaunchedEffect(aiState.localResult, aiState.isLocalRestoring, aiState.isLocalAnalyzing) {
        if (aiState.localResult == null &&
            !aiState.isLocalRestoring &&
            !aiState.isLocalAnalyzing &&
            !aiState.showSourceHillshade
        ) {
            pendingLocalLayer.value = null
            assistantViewModel.selectSourceHillshade()
        }
    }

    // Re-derives live from the current logged finds (not just at "Analyze" time) so marking a
    // find CONFIRMED/REJECTED in the Finds tab immediately re-scores historic targets here too,
    // without needing to re-run the full (much more expensive) derived-layer analysis.
    val historicTargets = remember(
        aiState.localResult,
        signals,
        featureTypeCalibration,
        historicMapAgreementScore,
    ) {
        val result = aiState.localResult ?: return@remember emptyList()
        val feedbackPoints = VerifiedFeedback.derive(signals, result.datasetKey)
        MetalDetectingTargetRefiner.refine(
            result = result,
            feedback = feedbackPoints,
            calibration = featureTypeCalibration,
            historicMapAgreementScore = historicMapAgreementScore,
        )
    }
    val huntZones = remember(historicTargets, aiState.localResult) {
        val resultLayers = aiState.localResult?.layers ?: return@remember emptyList()
        HuntZoneClusterer.cluster(historicTargets, resultLayers)
    }
    val targetZoneIds = remember(huntZones) {
        huntZones.flatMap { zone -> zone.targets.map { it to zone.id } }.toMap()
    }
    // Falls back to the database snapshot when the derived-layer cache is gone. The cache lives in
    // the cache directory, which Android may purge at any time; without this the ranked targets
    // vanish on reopen and only a full re-analysis brings them back, even though they were saved.
    val snapshotTargets = remember { mutableStateOf<List<SavedTarget>>(emptyList()) }
    // Keyed on the saved set too, so forgetting a snapshot clears its targets from the map instead
    // of leaving them drawn until something else happens to retrigger this.
    LaunchedEffect(grid, aiState.localResult, isRendering, analyzedDatasets) {
        if (aiState.localResult != null || isRendering || grid.width <= 2 || grid.height <= 2) {
            snapshotTargets.value = emptyList()
            return@LaunchedEffect
        }
        val datasetKey = TerrainIntelligenceEngine.terrainSignature(grid)
        snapshotTargets.value = viewModel.savedDatasetSnapshot(datasetKey)?.parseTargets().orEmpty()
    }

    val targetOverlays = remember(historicTargets, snapshotTargets.value) {
        if (historicTargets.isEmpty() && snapshotTargets.value.isNotEmpty()) {
            return@remember snapshotTargets.value
                .sortedByDescending { it.score }
                .mapIndexed { index, target ->
                    LidarOverlayTarget(
                        xPercent = target.xPercent,
                        yPercent = target.yPercent,
                        label = "${index + 1}. ${target.type.label} · ${(target.score * 100f).toInt()}% (saved)",
                    )
                }
        }
        historicTargets
            .sortedByDescending { it.score }
            .mapIndexed { index, target ->
                LidarOverlayTarget(
                    xPercent = target.xPercent,
                    yPercent = target.yPercent,
                    label = "${index + 1}. ${target.type.label} · ${(target.score * 100f).toInt()}%",
                )
            }
    }
    val savedCloudTargets = remember(signals) {
        signals.filter { it.source == DetectionSource.CLOUD_AI }.map { signal ->
            CloudMapTarget(
                xPercent = signal.gridX,
                yPercent = signal.gridY,
                label = signal.notes.substringAfter(CLOUD_AI_NOTE_PREFIX).substringBefore(" ·").ifBlank { "AI target" },
                confidence = (signal.signalStrength / 100f).coerceIn(0f, 1f),
            )
        }
    }
    val currentCloudTargets = cloudTargetsForTerrain(aiState, terrainKey)
    val visibleCloudTargets = remember(savedCloudTargets, currentCloudTargets) {
        (savedCloudTargets + currentCloudTargets)
            .distinctBy { cloudTargetIdentity(it) }
    }
    val cloudTargetOverlays = remember(visibleCloudTargets, showCloudTargets.value) {
        if (!showCloudTargets.value) return@remember emptyList()
        visibleCloudTargets.mapIndexed { index, target ->
            LidarOverlayTarget(
                xPercent = target.xPercent,
                yPercent = target.yPercent,
                label = "Cloud AI ${index + 1}. ${target.label} · ${(target.confidence * 100f).toInt()}%",
            )
        }
    }
    val classifiedTargetOverlays = remember(aiState.classifiedTargets, grid) {
        aiState.classifiedTargets.mapIndexed { index, target ->
            LidarOverlayTarget(
                xPercent = if (grid.width <= 1) 50f else target.region.centerCol * 100f / (grid.width - 1),
                yPercent = if (grid.height <= 1) 50f else target.region.centerRow * 100f / (grid.height - 1),
                label = "AI class ${index + 1}. ${target.label} · ${(target.confidence * 100f).toInt()}%",
            )
        }
    }

    LaunchedEffect(currentCloudTargets, terrainKey, metadata) {
        currentCloudTargets.forEach { target ->
            val coordinate = GeoSpatialLibrary.gridToGeographic(target.xPercent, target.yPercent, metadata)
            viewModel.updateLoggedSignal(
                TargetSignal(
                    id = stableCloudTargetId(terrainKey, target),
                    gridX = target.xPercent,
                    gridY = target.yPercent,
                    metalType = MetalType.MAGNETIC_ANOMALY,
                    signalStrength = target.confidence * 100f,
                    latitude = coordinate?.first,
                    longitude = coordinate?.second,
                    source = DetectionSource.CLOUD_AI,
                    notes = "$CLOUD_AI_NOTE_PREFIX${target.label} · Generated from the attached AI viewport; terrain evidence only.",
                    status = "AI suggested",
                    datasetKey = aiState.localResult?.datasetKey,
                    terrainKey = terrainKey,
                ),
            )
        }
    }

    // Persists a stable (feedback-free) snapshot of this dataset's targets whenever a fresh
    // analysis result arrives, so it can later be cross-compared against a different dataset -
    // without this, there is nothing for multi-dataset comparison to compare against once the
    // app moves on to a different import.
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

    LaunchedEffect(grid, summary, isRendering) {
        // The ViewModel is recreated after an update or process death, but the expensive derived
        // layers remain in the on-disk cache. Restore them as soon as the real terrain is ready.
        if (!isRendering && grid.width > 2 && grid.height > 2) {
            assistantViewModel.restoreLocalAnalysis(grid, summary)
        }
    }

    fun saveMarker(
        x: Float,
        y: Float,
        metalType: MetalType,
        source: DetectionSource,
        strength: Float,
        notes: String,
        detectedFeatureType: String? = null,
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
                // Ties this find back to the exact analyzed dataset, so a later verified outcome
                // (confirmed/rejected in the Finds tab) feeds back into re-scoring this dataset's
                // candidates instead of being unattributable.
                datasetKey = aiState.localResult?.datasetKey,
                terrainKey = terrainKey,
                // Ties this find back to the specific candidate type that was predicted, so a
                // later verified outcome also feeds FeatureTypeCalibration - the app's per-type
                // confidence, generalized across every dataset, not just this exact spot.
                detectedFeatureType = detectedFeatureType,
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
                            if (aiState.showSourceHillshade) {
                                sourceRenderLabel
                            } else {
                                aiState.localResult?.let { aiState.selectedLayer.label } ?: "AI terrain layer"
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            when {
                                localLayerPending -> "Rendering ${pendingLocalLayer.value?.label ?: "AI layer"}…"
                                centerMarkerMode.value -> "Pan/zoom until the target is centered, then save it"
                                isRefining -> "Reloading original LAZ detail without changing your zoom…"
                                canRefine -> "${"%.1f".format(zoomLevel.value)}× · tap Refine for source detail"
                                else -> "Pre-1900 silver-site profile · pinch and drag"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            val requested = visibleBounds.value.sanitized()
                            viewModel.refineTerrain(requested, viewModel.recommendedAiRefineResolution())
                        },
                        enabled = canRefine && !isRefining && !centerMarkerMode.value,
                        modifier = Modifier.height(CompactButtonHeight).testTag("ai_refine_now_button"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text(
                            if (!canRefine) "No LAZ source" else if (isRefining) "Refining…" else "Refine",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Button(
                        onClick = { assistantViewModel.runLocalAnalysis(grid, summary, signals) },
                        enabled = !aiState.isLocalAnalyzing,
                        modifier = Modifier.height(CompactButtonHeight).testTag("ai_run_local_analysis_button"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        if (aiState.isLocalAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.height(14.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (aiState.localResult == null) "Analyze" else "Re-run", style = MaterialTheme.typography.labelSmall)
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
                        FilterChip(
                            selected = aiState.showSourceHillshade,
                            onClick = {
                                pendingLocalLayer.value = null
                                assistantViewModel.selectSourceHillshade()
                            },
                            label = { Text("Source: $sourceRenderLabel", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(CompactButtonHeight),
                        )
                        TerrainDerivedLayer.entries.forEach { layer ->
                            FilterChip(
                                selected = !aiState.showSourceHillshade && aiState.selectedLayer == layer,
                                onClick = {
                                    localBitmapAtRequest.value = aiState.localLayerBitmap
                                    pendingLocalLayer.value = layer
                                    assistantViewModel.selectLocalLayer(layer)
                                },
                                enabled = pendingLocalLayer.value == null,
                                label = { Text(layer.label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(CompactButtonHeight),
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
                        modifier = Modifier.height(CompactButtonHeight).testTag("ai_marker_mode_button"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text(
                            if (centerMarkerMode.value) "Cancel marker" else "Mark map center",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
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
                        modifier = Modifier.height(CompactButtonHeight).testTag("ai_save_manual_marker_button"),
                        contentPadding = CompactButtonPadding,
                    ) { Text("Save center", style = MaterialTheme.typography.labelSmall) }
                    Button(
                        onClick = {
                            showHistoricTargets.value = !showHistoricTargets.value
                        },
                        enabled = historicTargets.isNotEmpty(),
                        modifier = Modifier.height(CompactButtonHeight).testTag("ai_add_target_markers_button"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text(
                            if (showHistoricTargets.value) "Hide historic targets" else "Mark historic targets",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (historicTargets.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showTargetDetails.value = !showTargetDetails.value },
                            modifier = Modifier.height(CompactButtonHeight).testTag("ai_show_target_details_button"),
                            contentPadding = CompactButtonPadding,
                        ) {
                            Text(
                                if (showTargetDetails.value) "Hide details" else "Show details",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (huntZones.size > 1) {
                        OutlinedButton(
                            onClick = { showHuntZones.value = !showHuntZones.value },
                            modifier = Modifier.height(CompactButtonHeight).testTag("ai_toggle_hunt_zones_button"),
                            contentPadding = CompactButtonPadding,
                        ) {
                            Text(
                                if (showHuntZones.value) "Hide zones" else "Zones (${huntZones.size})",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (visibleCloudTargets.isNotEmpty()) {
                        OutlinedButton(
                            onClick = { showCloudTargets.value = !showCloudTargets.value },
                            modifier = Modifier.height(CompactButtonHeight).testTag("ai_toggle_cloud_targets_button"),
                            contentPadding = CompactButtonPadding,
                        ) {
                            Text(
                                if (showCloudTargets.value) "Hide cloud AI (${visibleCloudTargets.size})" else "Show cloud AI (${visibleCloudTargets.size})",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { assistantViewModel.classifyTargets(grid, summary, sourceBitmap) },
                        enabled = !aiState.isClassifyingTargets && grid.width > 2 && aiState.activeProvider != null,
                        modifier = Modifier.height(CompactButtonHeight).testTag("ai_classify_targets_button"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        if (aiState.isClassifyingTargets) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Classify disturbance targets", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (analyzedDatasets.size >= 2) {
                        OutlinedButton(
                            onClick = { showDatasetComparison.value = true },
                            modifier = Modifier.height(CompactButtonHeight).testTag("ai_compare_datasets_button"),
                            contentPadding = CompactButtonPadding,
                        ) { Text("Compare datasets", style = MaterialTheme.typography.labelSmall) }
                    }
                    Text("${signals.size} saved", style = MaterialTheme.typography.labelMedium)
                }

                aiState.classificationError?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                if (aiState.classifiedTargets.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).testTag("ai_classified_targets_list"),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(aiState.classifiedTargets) { target ->
                            Text(
                                text = "${target.label} · ${(target.confidence * 100f).toInt()}% — ${target.description}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                if (showHuntZones.value && huntZones.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .testTag("ai_hunt_zone_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(huntZones, key = { it.id }) { zone ->
                            HuntZoneCard(
                                zone = zone,
                                onNavigate = GeoSpatialLibrary.gridToGeographic(
                                    zone.centerXPercent,
                                    zone.centerYPercent,
                                    metadata,
                                )?.let { (lat, lon) ->
                                    {
                                        viewModel.setNavigationTarget(
                                            NavigationTarget(
                                                label = "Zone ${zone.id} · ${zone.dominantType.label}",
                                                latitude = lat,
                                                longitude = lon,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }

                if (showTargetDetails.value && historicTargets.isNotEmpty()) {
                    if (selectedCandidateSummary.isNotBlank()) {
                        Text(
                            "AI focus set — dig brief / evidence chain will prioritize it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(bottom = 4.dp)
                                .testTag("ai_focus_candidate_hint"),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .testTag("ai_target_details_list"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(historicTargets.sortedByDescending { it.score }, key = { "${it.type}-${it.xPercent}-${it.yPercent}" }) { target ->
                            TargetDetailCard(
                                target = target,
                                zoneId = targetZoneIds[target],
                                depthEstimate = aiState.localResult?.layers?.let {
                                    DigDepthEstimator.estimate(target, it)
                                },
                                onNavigate = GeoSpatialLibrary.gridToGeographic(
                                    target.xPercent,
                                    target.yPercent,
                                    metadata,
                                )?.let { (lat, lon) ->
                                    {
                                        viewModel.setNavigationTarget(
                                            NavigationTarget(
                                                label = "${target.type.label} · ${(target.score * 100f).toInt()}%",
                                                latitude = lat,
                                                longitude = lon,
                                            ),
                                        )
                                    }
                                },
                                onFocusForAi = {
                                    selectedCandidateSummary = buildString {
                                        append(target.type.label)
                                        append(" · score=").append((target.score * 100f).toInt()).append("%")
                                        append(" · x=").append("%.1f".format(target.xPercent)).append("%")
                                        append(" y=").append("%.1f".format(target.yPercent)).append("%")
                                        if (target.evidence.isNotEmpty()) {
                                            append(" · evidence=").append(target.evidence.joinToString("; "))
                                        }
                                        if (target.cautionReasons.isNotEmpty()) {
                                            append(" · cautions=").append(target.cautionReasons.joinToString("; "))
                                        }
                                    }
                                },
                                onLog = {
                                    saveMarker(
                                        target.xPercent,
                                        target.yPercent,
                                        MetalType.MAGNETIC_ANOMALY,
                                        DetectionSource.AI_ANALYSIS,
                                        target.score * 100f,
                                        "Historic AI candidate: ${target.type.label} · ${target.evidence.joinToString(" · ")}",
                                        detectedFeatureType = target.type.name,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        LidarMapCanvas(
            bitmap = analysisBitmap,
            isRendering = isRendering || aiState.isLocalAnalyzing || localLayerPending,
            sweepX = 50f,
            sweepY = 50f,
            loggedSignals = signals.filterNot { it.source == DetectionSource.CLOUD_AI },
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
            overlayTargets = classifiedTargetOverlays + cloudTargetOverlays +
                if (showHistoricTargets.value) targetOverlays else emptyList(),
            onViewportChanged = { bounds, zoom, _, _ ->
                visibleBounds.value = bounds
                zoomLevel.value = zoom
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("ai_single_analysis_map"),
        )

        if (isRefining) {
            val progress = refinementProgress
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LinearProgressIndicator(
                    progress = { progress?.fraction?.coerceIn(0f, 1f) ?: 0f },
                    modifier = Modifier.fillMaxWidth().testTag("ai_refine_progress"),
                )
                Text(
                    progress?.message ?: "Preparing refinement…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (aiState.isLocalAnalyzing || localLayerPending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (alternateDatasets.size > 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("ai_secondary_dataset_picker"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Compare secondary site",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    alternateDatasets.forEach { ds ->
                        FilterChip(
                            selected = secondaryDataset?.datasetKey == ds.datasetKey,
                            onClick = { selectedSecondaryKey = ds.datasetKey },
                            label = {
                                Text(
                                    ds.displayName.take(28).ifBlank { ds.datasetKey.take(12) },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            },
                            modifier = Modifier.height(CompactButtonHeight),
                        )
                    }
                }
            }
        }

        AiCloudPanel(
            terrainSummary = summary,
            grid = grid,
            metadata = metadata,
            terrainKey = terrainKey,
            assistantViewModel = assistantViewModel,
            loggedSignals = signals,
            onConfirmAiSuggestions = { signalId, metal, outcome, status, notes ->
                viewModel.applyAiFindSuggestions(signalId, metal, outcome, status, notes)
            },
            fieldSessionPack = fieldPack,
            onClearFocusedCandidate = { selectedCandidateSummary = "" },
            onApplyLighting = { azimuth, altitude ->
                viewModel.updateSunAzimuth(azimuth)
                viewModel.updateSunAltitude(altitude)
            },
            onApplyVizMode = viewModel::updateVisualizationMode,
            onApplyNavTargets = { ids ->
                // Apply multi-stop playlist immediately, then clear the AI pending handoff.
                viewModel.setNavPlaylist(ids)
                assistantViewModel.consumeNavTargets()
            },
            // weight(1f), not fillMaxSize(): this Column isn't scrollable, and the header +
            // map above already claim their own height, so a fillMaxSize() panel here asked
            // for the full column height on top of that and pushed its own internal chat
            // list - including the text input at the bottom of it - past the visible screen
            // with no way to scroll to it. weight(1f) bounds it to the actual remaining space.
            modifier = Modifier.weight(1f),
        )
    }

    if (showDatasetComparison.value) {
        DatasetComparisonDialog(
            datasets = analyzedDatasets,
            onDismiss = { showDatasetComparison.value = false },
            onDeleteDataset = viewModel::deleteDatasetSnapshot,
        )
    }
}

private const val CLOUD_AI_NOTE_PREFIX = "Cloud AI target: "

internal fun aiSourceVisualizationLabel(mode: Int): String = when (mode) {
    0 -> "Standard hillshade"
    1 -> "Multi-directional hillshade"
    2 -> "Slope"
    3 -> "Local relief"
    4 -> "Curvature"
    5 -> "Disturbance screening"
    6 -> "Aspect"
    7 -> "Elevation"
    8 -> "Canopy height"
    else -> "Terrain render"
}

internal fun cloudTargetIdentity(target: CloudMapTarget): String =
    "${target.xPercent.toInt()}:${target.yPercent.toInt()}:${target.label.trim().lowercase()}"

internal fun cloudTargetsForTerrain(state: AiTerrainState, terrainKey: String): List<CloudMapTarget> =
    if (state.cloudTerrainKey == terrainKey) state.cloudMapTargets else emptyList()

internal fun stableCloudTargetId(terrainKey: String, target: CloudMapTarget): Long {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$terrainKey|${cloudTargetIdentity(target)}".toByteArray())
    return ByteBuffer.wrap(digest, 0, Long.SIZE_BYTES).long and Long.MAX_VALUE
}

@Composable
private fun HuntZoneCard(
    zone: HuntZone,
    onNavigate: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Zone ${zone.id} · " + zone.targetCount + (if (zone.targetCount == 1) " target" else " targets"),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (onNavigate != null) {
                    OutlinedButton(
                        onClick = onNavigate,
                        modifier = Modifier.height(CompactButtonHeight),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Nav", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(
                "${zone.dominantType.label} site · best ${(zone.bestScore * 100f).toInt()}% · spans ${zone.spanMeters.toInt()} m",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                zone.targets.joinToString(" · ") { "${it.type.label} ${(it.score * 100f).toInt()}%" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TargetDetailCard(
    target: MetalDetectingTarget,
    onLog: () -> Unit,
    onNavigate: (() -> Unit)? = null,
    onFocusForAi: (() -> Unit)? = null,
    depthEstimate: DigDepthEstimate? = null,
    zoneId: Int? = null,
) {
    val logged = rememberSaveable(target.type, target.xPercent, target.yPercent) { mutableStateOf(false) }
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
                if (zoneId != null) {
                    Text(
                        "Zone $zoneId",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (target.verifiedNearby) {
                    Text(
                        "Field-verified nearby",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (onNavigate != null) {
                    OutlinedButton(
                        onClick = onNavigate,
                        modifier = Modifier.height(CompactButtonHeight),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Nav", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (onFocusForAi != null) {
                    OutlinedButton(
                        onClick = onFocusForAi,
                        modifier = Modifier
                            .height(CompactButtonHeight)
                            .testTag("ai_focus_candidate"),
                        contentPadding = CompactButtonPadding,
                    ) {
                        Text("Focus AI", style = MaterialTheme.typography.labelSmall)
                    }
                }
                OutlinedButton(
                    onClick = {
                        onLog()
                        logged.value = true
                    },
                    enabled = !logged.value,
                    modifier = Modifier.height(CompactButtonHeight),
                    contentPadding = CompactButtonPadding,
                ) {
                    Text(if (logged.value) "Logged" else "Log", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                target.evidence.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Feature 15 — penalty / negative-evidence badges (honest, not proof claims)
            val penaltyLabels = remember(target.evidence, target.cautionReasons) {
                buildList {
                    target.evidence.forEach { line ->
                        val lower = line.lowercase()
                        when {
                            "natural-feature penalty" in lower || "natural feature" in lower ->
                                add("Natural penalty")
                            "modern-disturbance penalty" in lower || "modern disturbance" in lower ->
                                add("Modern penalty")
                            "penalty" in lower -> add(line.take(28))
                        }
                    }
                    target.cautionReasons.take(3).forEach { reason ->
                        add(reason.take(32))
                    }
                }.distinct()
            }
            if (penaltyLabels.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    penaltyLabels.forEach { label ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.testTag("penalty_badge"),
                        ) {
                            Text(
                                label,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
            Text(
                "Search radius: ${target.radiusMeters.toInt()} m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (depthEstimate != null) {
                Text(
                    "Est. dig depth: ${depthEstimate.label} (${depthEstimate.basis})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (target.layerEvidence.isNotEmpty()) {
                Text(
                    "Layer agreement",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                target.layerEvidence.forEach { layer ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            layer.layer,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            layer.measurement,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            layer.verdict.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (layer.verdict) {
                                LayerVerdict.SUPPORTS -> MaterialTheme.colorScheme.tertiary
                                LayerVerdict.MIXED -> MaterialTheme.colorScheme.primary
                                LayerVerdict.DISAGREES -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
            target.cautionReasons.forEach { reason ->
                Text(
                    "⚠ $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Once logged, this candidate's later Confirmed/Rejected outcome (set in the Finds
            // tab after checking it in the field) feeds FeatureTypeCalibration for this type,
            // generalized across every dataset - not just re-scoring this exact spot.
            if (logged.value) {
                Text(
                    "Logged to Finds - set its outcome there once field-checked to improve future ${target.type.label} scoring.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
