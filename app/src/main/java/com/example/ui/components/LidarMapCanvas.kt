package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.data.computeDigPriorityHeatmap
import com.example.geospatial.GeoSpatialLibrary
import kotlinx.coroutines.delay
import kotlin.math.min


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
            bitmap
                ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                ?.asImageBitmap()
        }.getOrNull()
    }
    val basemapImageBitmap = remember(basemapBitmap) {
        runCatching {
            basemapBitmap
                ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
                ?.asImageBitmap()
        }.getOrNull()
    }
    val heatmapCells = remember(loggedSignals, showHeatmap) {
        if (showHeatmap) computeDigPriorityHeatmap(loggedSignals, HEATMAP_BINS) else null
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(viewportResetKey, mode, imageBitmap) {
        zoom = 1f
        pan = Offset.Zero
    }

    LaunchedEffect(zoom, pan, viewportSize, imageBitmap) {
        val image = imageBitmap ?: return@LaunchedEffect
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return@LaunchedEffect
        delay(VIEWPORT_SETTLE_DELAY_MS)

        val viewportWidth = viewportSize.width.toFloat()
        val viewportHeight = viewportSize.height.toFloat()
        val fit = containScale(viewportWidth, viewportHeight, image.width.toFloat(), image.height.toFloat())
        val displayWidth = image.width * fit * zoom
        val displayHeight = image.height * fit * zoom
        val imageLeft = (viewportWidth - displayWidth) * 0.5f + pan.x
        val imageTop = (viewportHeight - displayHeight) * 0.5f + pan.y

        onViewportChanged(
            NormalizedRasterBounds(
                left = ((-imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
                top = ((-imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
                right = ((viewportWidth - imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
                bottom = ((viewportHeight - imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
            ).sanitized(),
            zoom,
        )
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (mode != LidarCanvasMode.EXPLORE) return@rememberTransformableState

        val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
        val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
        val sourceWidth = imageBitmap?.width?.toFloat()?.coerceAtLeast(1f) ?: viewportWidth
        val sourceHeight = imageBitmap?.height?.toFloat()?.coerceAtLeast(1f) ?: viewportHeight
        val fit = containScale(viewportWidth, viewportHeight, sourceWidth, sourceHeight)
        val nextZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val maxPanX = ((sourceWidth * fit * nextZoom - viewportWidth) * 0.5f).coerceAtLeast(0f)
        val maxPanY = ((sourceHeight * fit * nextZoom - viewportHeight) * 0.5f).coerceAtLeast(0f)

        zoom = nextZoom
        pan = Offset(
            x = (pan.x + panChange.x).coerceIn(-maxPanX, maxPanX),
            y = (pan.y + panChange.y).coerceIn(-maxPanY, maxPanY),
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1D21))
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .testTag("lidar_map_canvas_container"),
    ) {
        if (imageBitmap != null && bitmap != null) {
            val interactionModifier = if (mode == LidarCanvasMode.EXPLORE) {
                Modifier.transformable(transformState)
            } else {
                Modifier.pointerInput(onSweepPositionChanged, onStopSweeping, bitmap, viewportResetKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val canvasWidth = size.width.toFloat().coerceAtLeast(1f)
                        val canvasHeight = size.height.toFloat().coerceAtLeast(1f)
                        val fit = containScale(canvasWidth, canvasHeight, bitmap.width.toFloat(), bitmap.height.toFloat())
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
                val fit = containScale(canvasWidth, canvasHeight, imageBitmap.width.toFloat(), imageBitmap.height.toFloat())
                val displayWidth = imageBitmap.width * fit * zoom
                val displayHeight = imageBitmap.height * fit * zoom
                val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
                val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y
                val destinationOffset = IntOffset(imageLeft.toInt(), imageTop.toInt())
                val destinationSize = IntSize(displayWidth.toInt().coerceAtLeast(1), displayHeight.toInt().coerceAtLeast(1))

                drawImage(imageBitmap, dstOffset = destinationOffset, dstSize = destinationSize)
                if (showBasemap && basemapImageBitmap != null) {
                    drawImage(
                        image = basemapImageBitmap,
                        dstOffset = destinationOffset,
                        dstSize = destinationSize,
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
                                size = Size(cellWidth, cellHeight),
                                alpha = 0.18f + intensity * 0.5f,
                            )
                        }
                    }
                }

                if (gridSpacing >= 1f) {
                    val cols = (100f / gridSpacing).toInt().coerceIn(1, 50)
                    val rows = (100f / gridSpacing).toInt().coerceIn(1, 50)
                    val gridColor = Color(0xFFFF1E1E)
                    val gridStroke = 2.5f
                    val gridAlpha = 0.92f

                    for (i in 1 until cols) {
                        val px = imageLeft + (i * gridSpacing / 100f) * displayWidth
                        drawLine(
                            color = gridColor,
                            start = Offset(px, imageTop),
                            end = Offset(px, imageTop + displayHeight),
                            strokeWidth = gridStroke,
                            alpha = gridAlpha,
                        )
                    }
                    for (i in 1 until rows) {
                        val py = imageTop + (i * gridSpacing / 100f) * displayHeight
                        drawLine(
                            color = gridColor,
                            start = Offset(imageLeft, py),
                            end = Offset(imageLeft + displayWidth, py),
                            strokeWidth = gridStroke,
                            alpha = gridAlpha,
                        )
                    }
                    drawRect(
                        color = gridColor,
                        topLeft = Offset(imageLeft, imageTop),
                        size = Size(displayWidth, displayHeight),
                        style = Stroke(width = 3f),
                        alpha = 1f,
                    )
                }

                loggedSignals.forEach { signal ->
                    val px = imageLeft + (signal.gridX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val py = imageTop + (signal.gridY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val pinColor = runCatching { Color(signal.metalType.colorHex) }.getOrDefault(Color(0xFFFFD700))
                    val marker = Offset(px, py)
                    drawCircle(color = pinColor, radius = 12f, center = marker, alpha = 0.5f)
                    drawCircle(color = Color.White, radius = 4f, center = marker)
                    drawCircle(color = pinColor, radius = 18f, center = marker, style = Stroke(width = 2f))
                }

                if (showSurveyCursor) {
                    val sx = imageLeft + (sweepX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val sy = imageTop + (sweepY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val coil = Offset(sx, sy)
                    drawCircle(Color(0xFFFFD700), 36f, coil, alpha = 0.35f, style = Stroke(1.5f))
                    drawCircle(Color(0xFFFFD700), 24f, coil, alpha = 0.85f, style = Stroke(3.5f))
                    drawLine(Color(0xFFFFD700), Offset(sx - 10f, sy), Offset(sx + 10f, sy), 2f, alpha = 0.8f)
                    drawLine(Color(0xFFFFD700), Offset(sx, sy - 10f), Offset(sx, sy + 10f), 2f, alpha = 0.8f)
                    drawCircle(Color.White, 3f, coil)
                }

                deviceGridPosition?.let { devicePosition ->
                    if (devicePosition.first in 0f..100f && devicePosition.second in 0f..100f) {
                        val here = Offset(
                            imageLeft + (devicePosition.first / 100f) * displayWidth,
                            imageTop + (devicePosition.second / 100f) * displayHeight,
                        )
                        drawCircle(Color(0xFF2196F3), 26f, here, alpha = 0.25f)
                        drawCircle(Color(0xFF2196F3), 10f, here)
                        drawCircle(Color.White, 10f, here, style = Stroke(2.5f))
                    }
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xE60D0E12))
                    .border(0.5.dp, Color(0xFF2C2E35), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column {
                    Text(
                        text = geoMetadata.siteName.uppercase(),
                        color = Color(0xFFFFD700),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = "${geoMetadata.crs} • ${geoMetadata.datum}",
                        color = Color.LightGray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (mode == LidarCanvasMode.EXPLORE && zoom > 1.01f) {
                        Text(
                            text = "${"%.1f".format(zoom)}× zoom",
                            color = Color(0xFF64B5F6),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    if (showBasemap && basemapStatus != null) {
                        Text(
                            text = basemapStatus,
                            color = Color(0xFF64B5F6),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            if (showCoordinateHud) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE60D0E12))
                        .border(0.5.dp, Color(0xFF2C2E35), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                ) {
                    if (currentLat != null && currentLon != null) {
                        val utm = runCatching { GeoSpatialLibrary.geographicToUtm(currentLat, currentLon) }.getOrNull()
                        Text(
                            text = "${GeoSpatialLibrary.formatDms(currentLat, true)}  ·  ${GeoSpatialLibrary.formatDms(currentLon, false)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        if (utm != null) {
                            Text(
                                text = "UTM ${utm.zone}${utm.hemisphere}  E ${"%.1f".format(utm.easting)} m  N ${"%.1f".format(utm.northing)} m",
                                color = Color(0xFF64B5F6),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        Text(
                            text = "Local grid ${sweepX.toInt()}, ${sweepY.toInt()} · Geographic CRS unavailable",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            if (showBasemap && basemapImageBitmap != null) {
                Text(
                    text = "© OpenStreetMap contributors",
                    color = Color.White,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB0000000))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No LiDAR data loaded.\nSelect a template below to render.",
                    color = Color.LightGray,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (isRendering) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun containScale(
    viewportWidth: Float,
    viewportHeight: Float,
    sourceWidth: Float,
    sourceHeight: Float,
): Float = min(
    viewportWidth / sourceWidth.coerceAtLeast(1f),
    viewportHeight / sourceHeight.coerceAtLeast(1f),
).coerceAtLeast(0.0001f)

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 32f
private const val VIEWPORT_SETTLE_DELAY_MS = 450L
private const val HEATMAP_BINS = 24

private fun heatmapColor(intensity: Float): Color = if (intensity < 0.5f) {
    lerp(Color(0xFF1565C0), Color(0xFFFFC107), intensity / 0.5f)
} else {
    lerp(Color(0xFFFFC107), Color(0xFFE53935), (intensity - 0.5f) / 0.5f)
}
