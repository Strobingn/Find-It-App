package com.example.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.TerrainIntelligenceEngine
import com.example.analysis.TerrainIntelligenceRenderer
import com.example.data.NormalizedRasterBounds
import com.example.data.export.ProjectExportRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Two derived-layer panes rendered side by side from the same local analysis result, sharing one
 * pinch/pan gesture so both panes always frame the same ground location - a direct visual A/B
 * comparison (e.g. slope vs. local relief) instead of switching between layers one at a time.
 */
@Composable
fun LayerComparisonWorkspace(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    assistantViewModel: AiTerrainViewModel = viewModel(key = "layer_comparison_workspace"),
) {
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val summary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val aiState by assistantViewModel.state.collectAsStateWithLifecycle()
    val result = aiState.localResult
    val currentDatasetKey = remember(grid) { TerrainIntelligenceEngine.terrainSignature(grid) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingComparisonPng by remember { mutableStateOf(ByteArray(0)) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    val comparisonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingComparisonPng) }
                    ?: error("Could not open the selected destination")
            }.onSuccess { exportMessage = "Comparison image saved" }
                .onFailure { exportMessage = "Save failed: ${it.localizedMessage}" }
        }
    }

    var leftLayer by rememberSaveable { mutableStateOf(TerrainDerivedLayer.LOCAL_RELIEF) }
    var rightLayer by rememberSaveable { mutableStateOf(TerrainDerivedLayer.SLOPE) }

    val availableLayers = remember(result) { result?.layers?.values?.keys.orEmpty() }
    val leftBitmap = remember(result, leftLayer) { result?.let { TerrainIntelligenceRenderer.renderLayer(it, leftLayer) } }
    val rightBitmap = remember(result, rightLayer) { result?.let { TerrainIntelligenceRenderer.renderLayer(it, rightLayer) } }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(result) {
        zoom = 1f
        pan = Offset.Zero
    }

    LaunchedEffect(isRefining, currentDatasetKey, result?.datasetKey) {
        // Refinement replaces the active elevation grid. The comparison panes are derived from
        // the AI result, so regenerate that result once the refined grid is ready; otherwise both
        // panes continue pointing at the pre-refinement layers and never redraw the new viewport.
        if (!isRefining && result != null && result.datasetKey != currentDatasetKey) {
            assistantViewModel.runLocalAnalysis(grid, summary, signals)
        }
    }

    fun applyTransform(centroid: Offset, panChange: Offset, zoomChange: Float) {
        val next = applyComparisonGesture(
            current = ComparisonViewport(zoom, pan),
            centroid = centroid,
            panChange = panChange,
            zoomChange = zoomChange,
            viewportWidth = paneSize.width.toFloat(),
            viewportHeight = paneSize.height.toFloat(),
            sourceWidth = (leftBitmap?.width ?: result?.layers?.width ?: 1).toFloat(),
            sourceHeight = (leftBitmap?.height ?: result?.layers?.height ?: 1).toFloat(),
        )
        zoom = next.zoom
        pan = next.pan
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Layer comparison", fontWeight = FontWeight.Bold)
                Text(
                    if (result == null) {
                        "Run local analysis to compare derived layers side by side"
                    } else {
                        "${"%.1f".format(zoom)}× · pinch and drag - both panes move together"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exportMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (result != null) {
                        OutlinedButton(
                            onClick = { zoom = 1f; pan = Offset.Zero },
                            modifier = Modifier.testTag("comparison_reset_view_button"),
                        ) {
                            Icon(Icons.Default.CenterFocusStrong, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Fit")
                        }
                        OutlinedButton(
                            onClick = {
                                val imageWidth = (leftBitmap?.width ?: 1).toFloat()
                                val imageHeight = (leftBitmap?.height ?: 1).toFloat()
                                viewModel.refineTerrain(currentViewportBounds(paneSize, zoom, pan, imageWidth, imageHeight))
                            },
                            enabled = canRefine && !isRefining && !aiState.isLocalAnalyzing,
                            modifier = Modifier.testTag("comparison_refine_button"),
                        ) { Text(if (!canRefine) "No LAZ source" else if (isRefining) "Refining…" else "Refine") }
                        OutlinedButton(
                            onClick = {
                                val left = leftBitmap ?: return@OutlinedButton
                                val right = rightBitmap ?: return@OutlinedButton
                                isExporting = true
                                exportMessage = "Building full comparison image…"
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.Default) {
                                            ProjectExportRenderer.renderComparisonPng(
                                                left = left,
                                                leftLabel = leftLayer.label,
                                                right = right,
                                                rightLabel = rightLayer.label,
                                                projectName = summary,
                                            )
                                        }
                                    }.onSuccess {
                                        pendingComparisonPng = it
                                        comparisonExportLauncher.launch("find-it-layer-comparison.png")
                                    }.onFailure {
                                        exportMessage = "Export failed: ${it.localizedMessage}"
                                    }
                                    isExporting = false
                                }
                            },
                            enabled = !isExporting && leftBitmap != null && rightBitmap != null,
                            modifier = Modifier.testTag("comparison_export_button"),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.width(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (isExporting) "Building…" else "Export PNG")
                        }
                    }
                    Button(
                        onClick = { assistantViewModel.runLocalAnalysis(grid, summary, signals) },
                        enabled = !aiState.isLocalAnalyzing,
                        modifier = Modifier.testTag("comparison_run_analysis_button"),
                    ) {
                        if (aiState.isLocalAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (result == null) "Analyze" else "Re-run")
                        }
                    }
                }
            }
        }

        if (result == null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (aiState.isLocalAnalyzing) aiState.localStage else "No local analysis yet for this terrain.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Keyed on the rendered bitmap: the gesture lambda is captured once per key, and
                    // the fit calculation reads the source dimensions, which change when analysis reruns.
                    .pointerInput(leftBitmap, result) {
                        detectTransformGestures { centroid, panChange, zoomChange, _ ->
                            // Ignore gestures until the canvas has been measured, otherwise the
                            // header offset below is computed against a zero-height pane.
                            if (paneSize.width == 0 || paneSize.height == 0) return@detectTransformGestures
                            // Centroid arrives in Row coordinates; the right pane starts one pane
                            // width across, so fold it back into pane-local space.
                            val paneWidth = (size.width / 2).toFloat().coerceAtLeast(1f)
                            val localX = centroid.x % paneWidth
                            val headerOffset = (size.height - paneSize.height).toFloat().coerceAtLeast(0f)
                            applyTransform(
                                centroid = Offset(localX, centroid.y - headerOffset),
                                panChange = panChange,
                                zoomChange = zoomChange,
                            )
                        }
                    }
                    .testTag("layer_comparison_row"),
            ) {
                ComparisonPane(
                    bitmap = leftBitmap,
                    zoom = zoom,
                    pan = pan,
                    layer = leftLayer,
                    availableLayers = availableLayers,
                    onLayerSelected = { leftLayer = it },
                    // Both panes are laid out identically, so the left pane's canvas is the
                    // authoritative viewport size - measured below the layer header, unlike the
                    // enclosing Row.
                    onCanvasSizeChanged = { paneSize = it },
                    modifier = Modifier.weight(1f).fillMaxSize().testTag("comparison_pane_left"),
                )
                Box(modifier = Modifier.width(1.dp).fillMaxSize().padding(vertical = 4.dp)) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.outlineVariant) {}
                }
                ComparisonPane(
                    bitmap = rightBitmap,
                    zoom = zoom,
                    pan = pan,
                    layer = rightLayer,
                    availableLayers = availableLayers,
                    onLayerSelected = { rightLayer = it },
                    onCanvasSizeChanged = {},
                    modifier = Modifier.weight(1f).fillMaxSize().testTag("comparison_pane_right"),
                )
            }
        }
    }
}

@Composable
private fun ComparisonPane(
    bitmap: Bitmap?,
    zoom: Float,
    pan: Offset,
    layer: TerrainDerivedLayer,
    /** Only layers this result carries; offering an absent one throws when it is selected. */
    availableLayers: Set<TerrainDerivedLayer>,
    onLayerSelected: (TerrainDerivedLayer) -> Unit,
    onCanvasSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val imageBitmap = remember(bitmap) {
        bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }

    Column(modifier = modifier) {
        Box {
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().testTag("comparison_layer_selector_${layer.name}"),
            ) {
                Row(
                    modifier = Modifier
                        .clickable { menuExpanded = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(layer.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose layer")
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                TerrainDerivedLayer.entries.filter { it in availableLayers }.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.label) },
                        onClick = {
                            onLayerSelected(candidate)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged(onCanvasSizeChanged)
                .testTag("comparison_canvas_${layer.name}"),
        ) {
            if (imageBitmap == null) return@Canvas
            val canvasWidth = size.width.coerceAtLeast(1f)
            val canvasHeight = size.height.coerceAtLeast(1f)
            val fit = comparisonFitScale(canvasWidth, canvasHeight, imageBitmap.width.toFloat(), imageBitmap.height.toFloat())
            val displayWidth = imageBitmap.width * fit * zoom
            val displayHeight = imageBitmap.height * fit * zoom
            val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
            val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y
            drawImageScaled(imageBitmap, imageLeft, imageTop, displayWidth, displayHeight)
        }
    }
}

/**
 * Draws at float precision via a draw transform. Going through integer offsets/sizes truncates the
 * placement to whole pixels every frame, which makes the image visibly jitter and shear against the
 * other pane while pinching.
 */
private fun DrawScope.drawImageScaled(
    image: androidx.compose.ui.graphics.ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    val sourceWidth = image.width.toFloat().coerceAtLeast(1f)
    val sourceHeight = image.height.toFloat().coerceAtLeast(1f)
    withTransform({
        translate(left, top)
        scale(
            scaleX = width / sourceWidth,
            scaleY = height / sourceHeight,
            pivot = Offset.Zero,
        )
    }) {
        drawImage(image = image, filterQuality = FilterQuality.Medium)
    }
}

/** Shared zoom/pan applied to both comparison panes. */
internal data class ComparisonViewport(val zoom: Float, val pan: Offset)

/**
 * Contain-fit: at 1x the whole layer is visible inside the pane. A cover-fit (max) would crop the
 * raster to fill the pane, which in a half-width comparison pane means the user starts already
 * zoomed into a fragment with no way to see the full terrain.
 */
internal fun comparisonFitScale(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Float = min(
    viewportWidth.coerceAtLeast(1f) / imageWidth.coerceAtLeast(1f),
    viewportHeight.coerceAtLeast(1f) / imageHeight.coerceAtLeast(1f),
).coerceAtLeast(0.0001f)

/**
 * Applies one transform gesture. [centroid] is in pane-local coordinates so zoom stays anchored on
 * the point under the user's fingers rather than snapping toward the pane centre; without the
 * anchoring the content slides away from whatever they were actually looking at as they pinch.
 * Pan is clamped so the raster can never be dragged off its own pane.
 */
internal fun applyComparisonGesture(
    current: ComparisonViewport,
    centroid: Offset,
    panChange: Offset,
    zoomChange: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    sourceWidth: Float,
    sourceHeight: Float,
): ComparisonViewport {
    val paneWidth = viewportWidth.coerceAtLeast(1f)
    val paneHeight = viewportHeight.coerceAtLeast(1f)
    val imageWidth = sourceWidth.coerceAtLeast(1f)
    val imageHeight = sourceHeight.coerceAtLeast(1f)
    val fit = comparisonFitScale(paneWidth, paneHeight, imageWidth, imageHeight)

    val nextZoom = (current.zoom * zoomChange).coerceIn(1f, 16f)

    val previousWidth = imageWidth * fit * current.zoom
    val previousHeight = imageHeight * fit * current.zoom
    val nextWidth = imageWidth * fit * nextZoom
    val nextHeight = imageHeight * fit * nextZoom

    // Fraction of the image sitting under the centroid before the zoom change.
    val previousLeft = (paneWidth - previousWidth) * 0.5f + current.pan.x
    val previousTop = (paneHeight - previousHeight) * 0.5f + current.pan.y
    val anchorU = (centroid.x - previousLeft) / previousWidth
    val anchorV = (centroid.y - previousTop) / previousHeight

    // Re-derive the pan that keeps that same fraction under the centroid at the new zoom.
    val zoomedPanX = (centroid.x - anchorU * nextWidth) - (paneWidth - nextWidth) * 0.5f
    val zoomedPanY = (centroid.y - anchorV * nextHeight) - (paneHeight - nextHeight) * 0.5f

    val maxPanX = ((nextWidth - paneWidth) * 0.5f).coerceAtLeast(0f)
    val maxPanY = ((nextHeight - paneHeight) * 0.5f).coerceAtLeast(0f)

    return ComparisonViewport(
        zoom = nextZoom,
        pan = Offset(
            x = (zoomedPanX + panChange.x).coerceIn(-maxPanX, maxPanX),
            y = (zoomedPanY + panChange.y).coerceIn(-maxPanY, maxPanY),
        ),
    )
}

/** Converts this workspace's shared zoom/pan into the same normalized-bounds shape [LidarMapCanvas] reports, so "Refine" can reload detail for exactly what's currently visible. */
private fun currentViewportBounds(
    paneSize: IntSize,
    zoom: Float,
    pan: Offset,
    imageWidth: Float,
    imageHeight: Float,
): NormalizedRasterBounds {
    val viewportWidth = paneSize.width.toFloat().coerceAtLeast(1f)
    val viewportHeight = paneSize.height.toFloat().coerceAtLeast(1f)
    val fit = comparisonFitScale(viewportWidth, viewportHeight, imageWidth, imageHeight)
    val displayWidth = imageWidth * fit * zoom
    val displayHeight = imageHeight * fit * zoom
    val imageLeft = (viewportWidth - displayWidth) * 0.5f + pan.x
    val imageTop = (viewportHeight - displayHeight) * 0.5f + pan.y
    return NormalizedRasterBounds(
        left = ((-imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
        top = ((-imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
        right = ((viewportWidth - imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
        bottom = ((viewportHeight - imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
    ).sanitized()
}
