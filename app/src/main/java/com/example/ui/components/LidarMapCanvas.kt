package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.TerrainVisionSession
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.data.TerrainPerformanceSession
import com.example.data.computeDigPriorityHeatmap
import com.example.geospatial.GeoSpatialLibrary
import kotlinx.coroutines.delay
import kotlin.math.max


enum class LidarCanvasMode { SURVEY, EXPLORE }

@Composable
fun LidarMapCanvas(
    bitmap: Bitmap?,
    isRendering: Boolean,
    sweepX: Float,
    sweepY: Float,
    loggedSignals: List<TargetSignal>,
    onSweepPositionChanged: (Float, Float) -> Unit,
    onStopSweeping: () -> Unit,
    gridSpacing: Float = 0f,
    geoMetadata: GeoSpatialLibrary.GeoSpatialMetadata,
    currentLat: Double?,
    currentLon: Double?,
    mode: LidarCanvasMode = LidarCanvasMode.SURVEY,
    viewportResetKey: Int = 0,
    showSurveyCursor: Boolean = true,
    showCoordinateHud: Boolean = true,
    onViewportChanged: (NormalizedRasterBounds, Float) -> Unit = { _, _ -> },
    showHeatmap: Boolean = false,
    basemapBitmap: Bitmap? = null,
    showBasemap: Boolean = false,
    basemapOpacity: Float = 0.6f,
    basemapStatus: String? = null,
    deviceGridPosition: Pair<Float, Float>? = null,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) {
        runCatching {
            bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
        }.getOrNull()
    }
    val basemapImageBitmap = remember(basemapBitmap) {
        runCatching {
            basemapBitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
        }.getOrNull()
    }
    val heatmapCells = remember(loggedSignals, showHeatmap) {
        if (showHeatmap) computeDigPriorityHeatmap(loggedSignals, HEATMAP_BINS) else null
    }
    val gpuScene by TerrainPerformanceSession.gpuScene.collectAsStateWithLifecycle()
    var useGpuTerrain by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(gpuScene) {
        if (gpuScene == null) useGpuTerrain = false
    }
    LaunchedEffect(viewportResetKey, mode) {
        zoom = 1f
        pan = Offset.Zero
    }
    LaunchedEffect(bitmap) {
        if (bitmap == null) TerrainVisionSession.clear()
    }

    // Pinch and pan can produce dozens of updates per second. Wait until the gesture
    // settles before asking the ViewModel to persist/rerender the visible viewport.
    // LaunchedEffect automatically cancels the previous pending callback, preventing
    // render-job churn and the flashing caused by rapidly replaced bitmaps.
    LaunchedEffect(zoom, pan, viewportSize, imageBitmap, bitmap) {
        val image = imageBitmap ?: return@LaunchedEffect
        val sourceBitmap = bitmap ?: return@LaunchedEffect
        if (viewportSize == IntSize.Zero) return@LaunchedEffect
        delay(VIEWPORT_IDLE_DELAY_MS)

        val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
        val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
        val fit = coverScale(viewportWidth, viewportHeight, image.width.toFloat(), image.height.toFloat())
        val displayWidth = image.width * fit * zoom
        val displayHeight = image.height * fit * zoom
        val imageLeft = (viewportWidth - displayWidth) * 0.5f + pan.x
        val imageTop = (viewportHeight - displayHeight) * 0.5f + pan.y
        val bounds = NormalizedRasterBounds(
            left = ((-imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
            top = ((-imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
            right = ((viewportWidth - imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
            bottom = ((viewportHeight - imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
        ).sanitized()
        TerrainVisionSession.publish(sourceBitmap, bounds, zoom)
        onViewportChanged(bounds, zoom)
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (mode == LidarCanvasMode.EXPLORE) {
            val nextZoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
            val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
            val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
            val sourceWidth = imageBitmap?.width?.toFloat()?.coerceAtLeast(1f) ?: viewportWidth
            val sourceHeight = imageBitmap?.height?.toFloat()?.coerceAtLeast(1f) ?: viewportHeight
            val fit = coverScale(viewportWidth, viewportHeight, sourceWidth, sourceHeight)
            val maxPanX = ((sourceWidth * fit * nextZoom - viewportWidth) * 0.5f).coerceAtLeast(0f)
            val maxPanY = ((sourceHeight * fit * nextZoom - viewportHeight) * 0.5f).coerceAtLeast(0f)
            zoom = nextZoom
            pan = Offset(
                x = (pan.x + panChange.x).coerceIn(-maxPanX, maxPanX),
                y = (pan.y + panChange.y).coerceIn(-maxPanY, maxPanY),
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("lidar_map_canvas_container"),
    ) {
        val activeGpuScene = gpuScene
        if (useGpuTerrain && activeGpuScene != null) {
            GpuTerrainSurface(
                scene = activeGpuScene,
                modifier = Modifier.fillMaxSize().testTag("gpu_terrain_surface"),
            )
            TerrainBadge(
                title = "GPU 3D",
                subtitle = "Drag to rotate · pinch for LOD · double-tap to reset",
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
            )
        } else if (imageBitmap != null && bitmap != null) {
            val interactionModifier = if (mode == LidarCanvasMode.EXPLORE) {
                Modifier.transformable(transformState)
            } else {
                Modifier.pointerInput(onSweepPositionChanged, onStopSweeping, bitmap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val canvasWidth = size.width.toFloat().coerceAtLeast(1f)
                        val canvasHeight = size.height.toFloat().coerceAtLeast(1f)
                        val fit = coverScale(canvasWidth, canvasHeight, bitmap.width.toFloat(), bitmap.height.toFloat())
                        val imageWidth = bitmap.width * fit
                        val imageHeight = bitmap.height * fit
                        val imageLeft = (canvasWidth - imageWidth) * 0.5f
                        val imageTop = (canvasHeight - imageHeight) * 0.5f
                        fun report(offset: Offset) {
                            onSweepPositionChanged(
                                ((offset.x - imageLeft) / imageWidth * 100f).coerceIn(0f, 100f),
                                ((offset.y - imageTop) / imageHeight * 100f).coerceIn(0f, 100f),
                            )
                        }
                        report(down.position)
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break
                                if (change.positionChange() != Offset.Zero) change.consume()
                                report(change.position)
                            }
                        } finally {
                            onStopSweeping()
                        }
                    }
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .then(interactionModifier)
                    .testTag("lidar_canvas"),
            ) {
                val canvasWidth = size.width.coerceAtLeast(1f)
                val canvasHeight = size.height.coerceAtLeast(1f)
                val fit = coverScale(canvasWidth, canvasHeight, imageBitmap.width.toFloat(), imageBitmap.height.toFloat())
                val displayWidth = imageBitmap.width * fit * zoom
                val displayHeight = imageBitmap.height * fit * zoom
                val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
                val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y
                val imageOffset = IntOffset(imageLeft.toInt(), imageTop.toInt())
                val imageSize = IntSize(displayWidth.toInt().coerceAtLeast(1), displayHeight.toInt().coerceAtLeast(1))

                drawImage(image = imageBitmap, dstOffset = imageOffset, dstSize = imageSize)
                if (showBasemap && basemapImageBitmap != null) {
                    drawImage(
                        image = basemapImageBitmap,
                        dstOffset = imageOffset,
                        dstSize = imageSize,
                        alpha = basemapOpacity.coerceIn(0f, 1f),
                    )
                }
                if (heatmapCells != null) {
                    val cellWidth = displayWidth / HEATMAP_BINS
                    val cellHeight = displayHeight / HEATMAP_BINS
                    for (row in 0 until HEATMAP_BINS) {
                        for (col in 0 until HEATMAP_BINS) {
                            val intensity = heatmapCells[row * HEATMAP_BINS + col]
                            if (intensity <= 0.03f) continue
                            drawRect(
                                color = heatmapColor(intensity),
                                topLeft = Offset(imageLeft + col * cellWidth, imageTop + row * cellHeight),
                                size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                                alpha = 0.18f + intensity * 0.5f,
                            )
                        }
                    }
                }
                if (gridSpacing >= 1f) {
                    val divisions = (100f / gridSpacing).toInt().coerceIn(1, 50)
                    for (i in 1 until divisions) {
                        val fraction = i.toFloat() / divisions
                        val px = imageLeft + fraction * displayWidth
                        val py = imageTop + fraction * displayHeight
                        drawLine(Color.White, Offset(px, imageTop), Offset(px, imageTop + displayHeight), 1f, alpha = 0.18f)
                        drawLine(Color.White, Offset(imageLeft, py), Offset(imageLeft + displayWidth, py), 1f, alpha = 0.18f)
                    }
                }
                loggedSignals.forEach { signal ->
                    val px = imageLeft + (signal.gridX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val py = imageTop + (signal.gridY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val pinColor = runCatching { Color(signal.metalType.colorHex) }.getOrDefault(Color(0xFFFFC107))
                    drawCircle(pinColor, 15f, Offset(px, py), alpha = 0.42f)
                    drawCircle(Color.White, 4f, Offset(px, py))
                    drawCircle(pinColor, 20f, Offset(px, py), style = Stroke(2f))
                }
                if (showSurveyCursor) {
                    val sx = imageLeft + (sweepX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val sy = imageTop + (sweepY.coerceIn(0f, 100f) / 100f) * displayHeight
                    drawCircle(Color(0xFFFFC107), 18f, Offset(sx, sy), style = Stroke(2f))
                    drawLine(Color(0xFFFFC107), Offset(sx - 10f, sy), Offset(sx + 10f, sy), 2f)
                    drawLine(Color(0xFFFFC107), Offset(sx, sy - 10f), Offset(sx, sy + 10f), 2f)
                    drawCircle(Color.White, 3f, Offset(sx, sy))
                }
                deviceGridPosition?.let { position ->
                    if (position.first in 0f..100f && position.second in 0f..100f) {
                        val here = Offset(
                            imageLeft + position.first / 100f * displayWidth,
                            imageTop + position.second / 100f * displayHeight,
                        )
                        drawCircle(Color(0xFF42A5F5), 25f, here, alpha = 0.24f)
                        drawCircle(Color(0xFF42A5F5), 9f, here)
                        drawCircle(Color.White, 10f, here, style = Stroke(2.5f))
                    }
                }
            }

            TerrainBadge(
                title = geoMetadata.siteName,
                subtitle = "${geoMetadata.crs} · ${geoMetadata.datum} · ${"%.1f".format(zoom)}×",
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )
            if (showCoordinateHud) {
                val coordinateText = if (currentLat != null && currentLon != null) {
                    "${GeoSpatialLibrary.formatDms(currentLat, true)} · ${GeoSpatialLibrary.formatDms(currentLon, false)}"
                } else {
                    "Local grid ${sweepX.toInt()}, ${sweepY.toInt()}"
                }
                TerrainBadge(
                    title = coordinateText,
                    subtitle = basemapStatus ?: "Pinch to zoom · drag to move",
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Text("No terrain loaded", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Import a LAZ, LAS, TIFF, or GeoTIFF file to begin.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
        ) {
            if (isRendering && !useGpuTerrain) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    tonalElevation = 3.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.testTag("terrain_rendering_indicator"),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.padding(1.dp))
                        Text("Refreshing detail", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            if (activeGpuScene != null) {
                OutlinedButton(
                    onClick = { useGpuTerrain = !useGpuTerrain },
                    modifier = Modifier.testTag("toggle_gpu_terrain_button"),
                ) {
                    Text(if (useGpuTerrain) "2D" else "3D")
                }
            }
        }
    }
}

@Composable
private fun TerrainBadge(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
            )
        }
    }
}

private fun coverScale(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Float = max(
    viewportWidth / imageWidth.coerceAtLeast(1f),
    viewportHeight / imageHeight.coerceAtLeast(1f),
)

private const val HEATMAP_BINS = 24
private const val VIEWPORT_IDLE_DELAY_MS = 300L
private const val MAX_ZOOM = 32f

private fun heatmapColor(intensity: Float): Color = if (intensity < 0.5f) {
    lerp(Color(0xFF1565C0), Color(0xFFFFC107), intensity / 0.5f)
} else {
    lerp(Color(0xFFFFC107), Color(0xFFE53935), (intensity - 0.5f) / 0.5f)
}
