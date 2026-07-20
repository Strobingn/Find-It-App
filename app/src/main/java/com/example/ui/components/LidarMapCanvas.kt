package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.TargetSignal

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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1D21))
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .testTag("lidar_map_canvas_container")
    ) {
        if (bitmap != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                val xPct = (offset.x / size.width) * 100f
                                val yPct = (offset.y / size.height) * 100f
                                onSweepPositionChanged(xPct, yPct)
                                tryAwaitRelease()
                                onStopSweeping()
                             }
                        )
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val xPct = (offset.x / size.width) * 100f
                                val yPct = (offset.y / size.height) * 100f
                                onSweepPositionChanged(xPct, yPct)
                            },
                            onDragEnd = { onStopSweeping() },
                            onDragCancel = { onStopSweeping() },
                            onDrag = { change, _ ->
                                change.consume()
                                val offset = change.position
                                val xPct = (offset.x / size.width) * 100f
                                val yPct = (offset.y / size.height) * 100f
                                onSweepPositionChanged(xPct, yPct)
                            }
                        )
                    }
                    .testTag("lidar_canvas")
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                // 1. Draw the raw hillshaded elevation bitmap (scaled full bleed)
                val imgBmp = bitmap.asImageBitmap()
                drawImage(
                    image = imgBmp,
                    dstOffset = IntOffset(0, 0),
                    dstSize = IntSize(canvasWidth.toInt(), canvasHeight.toInt())
                )

                // Draw Search Grid Planner (Priority 4)
                if (gridSpacing > 0f) {
                    val cols = (100f / gridSpacing).toInt()
                    val rows = (100f / gridSpacing).toInt()

                    // Vertical lines
                    for (i in 1 until cols) {
                        val px = (i * gridSpacing / 100f) * canvasWidth
                        drawLine(
                            color = Color(0xFF29B6F6),
                            start = Offset(px, 0f),
                            end = Offset(px, canvasHeight),
                            strokeWidth = 1f,
                            alpha = 0.35f
                        )
                    }

                    // Horizontal lines
                    for (i in 1 until rows) {
                        val py = (i * gridSpacing / 100f) * canvasHeight
                        drawLine(
                            color = Color(0xFF29B6F6),
                            start = Offset(0f, py),
                            end = Offset(canvasWidth, py),
                            strokeWidth = 1f,
                            alpha = 0.35f
                        )
                    }
                }

                // 2. Draw logged signals (archeological target pins)
                for (sig in loggedSignals) {
                    val px = (sig.gridX / 100f) * canvasWidth
                    val py = (sig.gridY / 100f) * canvasHeight

                    // Pin base glow
                    drawCircle(
                        color = Color(sig.metalType.colorHex),
                        radius = 12f,
                        center = Offset(px, py),
                        alpha = 0.5f
                    )

                    // Pin center core
                    drawCircle(
                        color = Color.White,
                        radius = 4f,
                        center = Offset(px, py)
                    )

                    // Pin outer ring indicator
                    drawCircle(
                        color = Color(sig.metalType.colorHex),
                        radius = 18f,
                        center = Offset(px, py),
                        style = Stroke(width = 2f)
                    )
                }

                // 3. Draw current search coil position & Sweep overlay
                val sx = (sweepX / 100f) * canvasWidth
                val sy = (sweepY / 100f) * canvasHeight

                // Draw concentric detecting coil rings
                // Outermost light ring
                drawCircle(
                    color = Color(0xFFFFD700),
                    radius = 36f,
                    center = Offset(sx, sy),
                    style = Stroke(width = 1.5f),
                    alpha = 0.35f
                )

                // Main search coil ring (copper yellow)
                drawCircle(
                    color = Color(0xFFFFD700),
                    radius = 24f,
                    center = Offset(sx, sy),
                    style = Stroke(width = 3.5f),
                    alpha = 0.85f
                )

                // Coil crosshair
                drawLine(
                    color = Color(0xFFFFD700),
                    start = Offset(sx - 10f, sy),
                    end = Offset(sx + 10f, sy),
                    strokeWidth = 2f,
                    alpha = 0.8f
                )
                drawLine(
                    color = Color(0xFFFFD700),
                    start = Offset(sx, sy - 10f),
                    end = Offset(sx, sy + 10f),
                    strokeWidth = 2f,
                    alpha = 0.8f
                )

                // Tiny core center indicator
                drawCircle(
                    color = Color.White,
                    radius = 3f,
                    center = Offset(sx, sy)
                )
            }
        } else {
            // Loading elevation map state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No LiDAR data loaded.\nSelect a template below to render.",
                    color = Color.LightGray,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        // Tiny overlay indicating rendering progress
        if (isRendering) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
