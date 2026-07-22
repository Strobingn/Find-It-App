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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.example.geospatial.GeoSpatialLibrary

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
    modifier: Modifier = Modifier,
) {
    val imageBitmap = remember(bitmap) {
        try {
            bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewportResetKey, mode) {
        zoom = 1f
        pan = Offset.Zero
    }
    LaunchedEffect(zoom, pan, viewportSize, imageBitmap) {
        val image = imageBitmap ?: return@LaunchedEffect
        val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
        val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
        val fit = viewportWidth / image.width
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
        onViewportChanged(bounds, zoom)
    }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (mode == LidarCanvasMode.EXPLORE) {
            val nextZoom = (zoom * zoomChange).coerceIn(1f, 32f)
            val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
            val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
            val sourceWidth = imageBitmap?.width?.toFloat()?.coerceAtLeast(1f) ?: viewportWidth
            val sourceHeight = imageBitmap?.height?.toFloat()?.coerceAtLeast(1f) ?: viewportHeight
            val fit = viewportWidth / sourceWidth
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
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1D21))
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .testTag("lidar_map_canvas_container"),
    ) {
        if (imageBitmap != null && bitmap != null) {
            val interactionModifier = if (mode == LidarCanvasMode.EXPLORE) {
                Modifier.transformable(transformState)
            } else {
                Modifier.pointerInput(onSweepPositionChanged, onStopSweeping, bitmap) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val canvasWidth = size.width.toFloat().coerceAtLeast(1f)
                        val canvasHeight = size.height.toFloat().coerceAtLeast(1f)
                        val fit = canvasWidth / bitmap.width
                        val imageWidth = bitmap.width * fit
                        val imageHeight = bitmap.height * fit
                        val imageLeft = (canvasWidth - imageWidth) * 0.5f
                        val imageTop = (canvasHeight - imageHeight) * 0.5f
                        fun report(offset: Offset) {
                            val xPct = ((offset.x - imageLeft) / imageWidth * 100f).coerceIn(0f, 100f)
                            val yPct = ((offset.y - imageTop) / imageHeight * 100f).coerceIn(0f, 100f)
                            onSweepPositionChanged(xPct, yPct)
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
                val fit = canvasWidth / imageBitmap.width
                val fittedWidth = imageBitmap.width * fit
                val fittedHeight = imageBitmap.height * fit
                val displayWidth = fittedWidth * zoom
                val displayHeight = fittedHeight * zoom
                val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
                val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y
                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(imageLeft.toInt(), imageTop.toInt()),
                    dstSize = IntSize(displayWidth.toInt(), displayHeight.toInt()),
                )
                if (gridSpacing >= 1f) {
                    val cols = (100f / gridSpacing).toInt().coerceIn(1, 50)
                    val rows = (100f / gridSpacing).toInt().coerceIn(1, 50)
                    for (i in 1 until cols) {
                        val px = imageLeft + (i * gridSpacing / 100f) * displayWidth
                        drawLine(
                            color = Color(0xFF29B6F6),
                            start = Offset(px, imageTop),
                            end = Offset(px, imageTop + displayHeight),
                            strokeWidth = 1f,
                            alpha = 0.35f,
                        )
                    }
                    for (i in 1 until rows) {
                        val py = imageTop + (i * gridSpacing / 100f) * displayHeight
                        drawLine(
                            color = Color(0xFF29B6F6),
                            start = Offset(imageLeft, py),
                            end = Offset(imageLeft + displayWidth, py),
                            strokeWidth = 1f,
                            alpha = 0.35f,
                        )
                    }
                }
                for (sig in loggedSignals) {
                    val px = imageLeft + (sig.gridX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val py = imageTop + (sig.gridY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val pinColor = try {
                        Color(sig.metalType.colorHex)
                    } catch (_: Exception) {
                        Color(0xFFFFD700)
                    }
                    drawCircle(color = pinColor, radius = 12f, center = Offset(px, py), alpha = 0.5f)
                    drawCircle(color = Color.White, radius = 4f, center = Offset(px, py))
                    drawCircle(
                        color = pinColor,
                        radius = 18f,
                        center = Offset(px, py),
                        style = Stroke(width = 2f),
                    )
                }
                if (showSurveyCursor) {
                    val sx = imageLeft + (sweepX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val sy = imageTop + (sweepY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val coil = Offset(sx, sy)
                    drawCircle(
                        color = Color(0xFFFFD700),
                        radius = 36f,
                        center = coil,
                        style = Stroke(width = 1.5f),
                        alpha = 0.35f,
                    )
                    drawCircle(
                        color = Color(0xFFFFD700),
                        radius = 24f,
                        center = coil,
                        style = Stroke(width = 3.5f),
                        alpha = 0.85f,
                    )
                    drawLine(
                        color = Color(0xFFFFD700),
                        start = Offset(sx - 10f, sy),
                        end = Offset(sx + 10f, sy),
                        strokeWidth = 2f,
                        alpha = 0.8f,
                    )
                    drawLine(
                        color = Color(0xFFFFD700),
                        start = Offset(sx, sy - 10f),
                        end = Offset(sx, sy + 10f),
                        strokeWidth = 2f,
                        alpha = 0.8f,
                    )
                    drawCircle(color = Color.White, radius = 3f, center = coil)
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
                        text = geoMetadata.crs + " • " + geoMetadata.datum,
                        color = Color.LightGray,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (showCoordinateHud) Column(
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
                    val utm = runCatching {
                        GeoSpatialLibrary.geographicToUtm(currentLat, currentLon)
                    }.getOrNull()
                    Text(
                        text = GeoSpatialLibrary.formatDms(currentLat, true) + "  ·  " + GeoSpatialLibrary.formatDms(currentLon, false),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                    if (utm != null) {
                        Text(
                            text = "UTM " + utm.zone + utm.hemisphere + "  E " + "%.1f".format(utm.easting) + " m  N " + "%.1f".format(utm.northing) + " m",
                            color = Color(0xFF64B5F6),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else {
                    Text(
                        text = "Local grid " + sweepX.toInt() + ", " + sweepY.toInt() + " · Geographic CRS unavailable",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No LiDAR data loaded.
Select a template below to render.",
                    color = Color.LightGray,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (isRendering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}