package com.example.ui.thermals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.thermals.ThermalPhase
import com.example.thermals.ThermalWindEngine

/**
 * Thin time-of-day scrubber: the track is tinted by thermal phase hour by hour (rising / falling /
 * switching), white ticks mark the forecast switch times, and hour labels sit exactly under their
 * positions. Tap or drag anywhere on it.
 */
@Composable
internal fun HourTrack(
    hour: Float,
    onHour: (Float) -> Unit,
    first: Float,
    last: Float,
    phaseAt: (Float) -> ThermalPhase?,
    switches: List<Double>,
    modifier: Modifier = Modifier,
    labels: List<Pair<Float, String>> = DEFAULT_LABELS,
    height: Dp = 40.dp,
    testTag: String = "hour_track",
) {
    val currentOnHour by rememberUpdatedState(onHour)
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val emptyTrack = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val thumbRing = MaterialTheme.colorScheme.surfaceContainer
    val inset = 12.dp

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .testTag(testTag)
            .semantics {
                stateDescription = ThermalWindEngine.formatHour(hour.toDouble())
                setProgress { target ->
                    currentOnHour(target.coerceIn(first, last))
                    true
                }
            }
            .pointerInput(first, last) {
                val insetPx = inset.toPx()
                fun toHour(x: Float): Float {
                    val usable = (size.width - 2 * insetPx).coerceAtLeast(1f)
                    val f = ((x - insetPx) / usable).coerceIn(0f, 1f)
                    return first + f * (last - first).coerceAtLeast(0f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    currentOnHour(toHour(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        currentOnHour(toHour(change.position.x))
                    }
                }
            },
    ) {
        val insetPx = inset.toPx()
        val usable = size.width - 2 * insetPx
        val trackH = 6.dp.toPx()
        val trackY = 12.dp.toPx()
        // A collapsed span would divide by zero and hand NaN to every draw call below.
        val span = (last - first).takeIf { it > 0f } ?: 1f
        fun xOf(h: Float) = insetPx + (h - first) / span * usable

        // Background, then phase tint hour by hour.
        drawRoundRect(emptyTrack, Offset(insetPx, trackY - trackH / 2), Size(usable, trackH), CornerRadius(trackH / 2))
        val steps = ((last - first) * 2).toInt().coerceAtLeast(1)
        for (i in 0 until steps) {
            val h0 = first + i * (last - first) / steps
            val h1 = first + (i + 1) * (last - first) / steps
            val phase = phaseAt((h0 + h1) / 2f) ?: continue
            val passed = h1 <= hour
            drawRect(
                color = phaseColor(phase).copy(alpha = if (passed) 0.95f else 0.45f),
                topLeft = Offset(xOf(h0), trackY - trackH / 2),
                size = Size(xOf(h1) - xOf(h0) + 0.5f, trackH),
            )
        }
        // Switch ticks.
        switches.forEach { s ->
            val sx = xOf(s.toFloat().coerceIn(first, last))
            drawLine(Color.White, Offset(sx, trackY - 7.dp.toPx()), Offset(sx, trackY + 7.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
        // Thumb.
        val tx = xOf(hour.coerceIn(first, last))
        drawCircle(thumbRing, radius = 10.dp.toPx(), center = Offset(tx, trackY))
        drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(tx, trackY))
        // Labels centred under their hours.
        labels.filter { (h, _) -> h in first..last }.forEach { (h, text) ->
            val layout = measurer.measure(text, labelStyle)
            val lx = (xOf(h) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(lx, trackY + 10.dp.toPx()))
        }
    }
}

private val DEFAULT_LABELS = listOf(4f to "4a", 8f to "8a", 12f to "12p", 16f to "4p", 20f to "8p")
