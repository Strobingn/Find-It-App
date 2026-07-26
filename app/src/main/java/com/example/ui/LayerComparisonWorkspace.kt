package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusStrong
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.analysis.TerrainDerivedLayer
import com.example.analysis.TerrainIntelligenceRenderer
import com.example.data.NormalizedRasterBounds
import kotlin.math.max

/**
 * Two derived-layer panes rendered side by side from the same local analysis result, sharing one
 * pinch/pan gesture so both panes always frame the same ground location - a direct visual A/B
 * comparison (e.g. slope vs. local relief) instead of switching between layers one at a time.
 */
@Composable
fun LayerComparisonWorkspace(
    viewModel: HillshadeViewModel,
    padding: PaddingValues,
    assistantViewModel: AiTerrainViewModel = viewModel(),
) {
    val grid by viewModel.elevationGrid.collectAsStateWithLifecycle()
    val summary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val canRefine by viewModel.canRefineTerrain.collectAsStateWithLifecycle()
    val isRefining by viewModel.isRefiningTerrain.collectAsStateWithLifecycle()
    val aiState by assistantViewModel.state.collectAsStateWithLifecycle()
    val result = aiState.localResult

    var leftLayer by rememberSaveable { mutableStateOf(TerrainDerivedLayer.LOCAL_RELIEF) }
    var rightLayer by rememberSaveable { mutableStateOf(TerrainDerivedLayer.SLOPE) }

    val leftBitmap = remember(result, leftLayer) { result?.let { TerrainIntelligenceRenderer.renderLayer(it, leftLayer) } }
    val rightBitmap = remember(result, rightLayer) { result?.let { TerrainIntelligenceRenderer.renderLayer(it, rightLayer) } }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(result) {
        zoom = 1f
        pan = Offset.Zero
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 16f)
        val viewportWidth = paneSize.width.toFloat().coerceAtLeast(1f)
        val viewportHeight = paneSize.height.toFloat().coerceAtLeast(1f)
        val sourceWidth = (leftBitmap?.width ?: result?.layers?.width ?: 1).toFloat().coerceAtLeast(1f)
        val sourceHeight = (leftBitmap?.height ?: result?.layers?.height ?: 1).toFloat().coerceAtLeast(1f)
        val fit = comparisonCoverScale(viewportWidth, viewportHeight, sourceWidth, sourceHeight)
        val maxPanX = ((sourceWidth * fit * nextZoom - viewportWidth) * 0.5f).coerceAtLeast(0f)
        val maxPanY = ((sourceHeight * fit * nextZoom - viewportHeight) * 0.5f).coerceAtLeast(0f)
        zoom = nextZoom
        pan = Offset(
            x = (pan.x + panChange.x).coerceIn(-maxPanX, maxPanX),
            y = (pan.y + panChange.y).coerceIn(-maxPanY, maxPanY),
        )
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
                            enabled = canRefine && !isRefining,
                            modifier = Modifier.testTag("comparison_refine_button"),
                        ) { Text(if (!canRefine) "No LAZ source" else if (isRefining) "Refining…" else "Refine") }
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
                    .clipToBounds()
                    .transformable(transformState)
                    .onSizeChanged {
                        paneSize = IntSize(
                            width = ((it.width - 1).coerceAtLeast(2)) / 2,
                            height = it.height.coerceAtLeast(1),
                        )
                    }
                    .testTag("layer_comparison_row"),
            ) {
                ComparisonPane(
                    bitmap = leftBitmap,
                    zoom = zoom,
                    pan = pan,
                    layer = leftLayer,
                    onLayerSelected = { leftLayer = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .testTag("comparison_pane_left"),
                )
                Box(modifier = Modifier.width(1.dp).fillMaxHeight()) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.outlineVariant) {}
                }
                ComparisonPane(
                    bitmap = rightBitmap,
                    zoom = zoom,
                    pan = pan,
                    layer = rightLayer,
                    onLayerSelected = { rightLayer = it },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clipToBounds()
                        .testTag("comparison_pane_right"),
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
    onLayerSelected: (TerrainDerivedLayer) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val imageBitmap = remember(bitmap) {
        bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
    }

    Column(modifier = modifier.clipToBounds()) {
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
                TerrainDerivedLayer.entries.forEach { candidate ->
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
                .testTag("comparison_canvas_${layer.name}"),
        ) {
            if (imageBitmap == null) return@Canvas
            val canvasWidth = size.width.coerceAtLeast(1f)
            val canvasHeight = size.height.coerceAtLeast(1f)
            val fit = comparisonCoverScale(canvasWidth, canvasHeight, imageBitmap.width.toFloat(), imageBitmap.height.toFloat())
            val displayWidth = imageBitmap.width * fit * zoom
            val displayHeight = imageBitmap.height * fit * zoom
            val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
            val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y
            drawImageScaled(imageBitmap, imageLeft, imageTop, displayWidth, displayHeight)
        }
    }
}

private fun DrawScope.drawImageScaled(
    image: androidx.compose.ui.graphics.ImageBitmap,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    drawImage(
        image = image,
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
    )
}

private fun comparisonCoverScale(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Float = max(
    viewportWidth / imageWidth.coerceAtLeast(1f),
    viewportHeight / imageHeight.coerceAtLeast(1f),
)

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
    val fit = comparisonCoverScale(viewportWidth, viewportHeight, imageWidth, imageHeight)
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
