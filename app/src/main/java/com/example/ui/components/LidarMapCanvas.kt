package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Paint as NativePaint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.TerrainVisionSession
import com.example.analysis.TerrainViewshed
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.data.TerrainPerformanceSession
import com.example.data.computeDigPriorityHeatmap
import com.example.data.survey.SurveyFeature
import com.example.data.survey.SurveyGeometryType
import com.example.geospatial.GeoSpatialLibrary
import kotlin.math.min
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged


enum class LidarCanvasMode { SURVEY, EXPLORE }

data class LidarOverlayTarget(
    val xPercent: Float,
    val yPercent: Float,
    val label: String,
    val colorHex: Long = 0xFF29B6F6,
)

private const val MAX_VISIBLE_TARGET_LABELS = 12
private const val FEET_TO_METERS = 0.3048f
private const val GRID_LINE_ALPHA = 0.75f

@OptIn(FlowPreview::class)
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
    onViewportChanged: (NormalizedRasterBounds, Float, Float, Float) -> Unit = { _, _, _, _ -> },
    /** Reports how far the current raster is being stretched (screen px per raster px). */
    onViewportStretch: (NormalizedRasterBounds, Float) -> Unit = { _, _ -> },
    initialZoom: Float = 1f,
    initialPanX: Float = 0f,
    initialPanY: Float = 0f,
    viewportRestoreToken: Int = 0,
    showHeatmap: Boolean = false,
    /** Binned HOMESITE_BINS x HOMESITE_BINS 0..1 homesite probabilities; null hides the overlay. */
    homesiteCells: FloatArray? = null,
    basemapBitmap: Bitmap? = null,
    showBasemap: Boolean = false,
    basemapOpacity: Float = 0.6f,
    basemapStatus: String? = null,
    deviceGridPosition: Pair<Float, Float>? = null,
    breadcrumbPaths: List<List<Pair<Float, Float>>> = emptyList(),
    overlayTargets: List<LidarOverlayTarget> = emptyList(),
    surveyFeatures: List<SurveyFeature> = emptyList(),
    inspectionPoint: Pair<Float, Float>? = null,
    profileStartPoint: Pair<Float, Float>? = null,
    profileEndPoint: Pair<Float, Float>? = null,
    onInspectPosition: ((Float, Float) -> Unit)? = null,
    viewshed: TerrainViewshed? = null,
    viewshedGridWidth: Int = 0,
    viewshedGridHeight: Int = 0,
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
    // Binned viewshed overlay: -1 = no grid data, 0 = analyzed but blocked, 1 = visible.
    val viewshedBins = remember(viewshed, viewshedGridWidth, viewshedGridHeight) {
        val shed = viewshed ?: return@remember null
        if (viewshedGridWidth <= 0 || viewshedGridHeight <= 0) return@remember null
        if (shed.visibility.isEmpty()) return@remember null
        val bins = IntArray(VIEWSHED_BINS * VIEWSHED_BINS) { -1 }
        for (binRow in 0 until VIEWSHED_BINS) {
            val y0 = binRow * viewshedGridHeight / VIEWSHED_BINS
            val y1 = ((binRow + 1) * viewshedGridHeight / VIEWSHED_BINS).coerceAtLeast(y0 + 1)
            for (binCol in 0 until VIEWSHED_BINS) {
                val x0 = binCol * viewshedGridWidth / VIEWSHED_BINS
                val x1 = ((binCol + 1) * viewshedGridWidth / VIEWSHED_BINS).coerceAtLeast(x0 + 1)
                var anyCell = false
                var anyVisible = false
                for (y in y0 until y1.coerceAtMost(viewshedGridHeight)) {
                    val rowOffset = y * viewshedGridWidth
                    for (x in x0 until x1.coerceAtMost(viewshedGridWidth)) {
                        val index = rowOffset + x
                        if (index < shed.visibility.size) {
                            anyCell = true
                            if (shed.visibility[index]) anyVisible = true
                        }
                    }
                }
                bins[binRow * VIEWSHED_BINS + binCol] = when {
                    anyVisible -> 1
                    anyCell -> 0
                    else -> -1
                }
            }
        }
        bins
    }
    val surveyGeometries = remember(surveyFeatures, geoMetadata) {
        surveyFeatures.mapNotNull { feature ->
            val points = feature.coordinates.mapNotNull { coordinate ->
                GeoSpatialLibrary.geographicToGrid(
                    coordinate.latitude,
                    coordinate.longitude,
                    geoMetadata,
                )
            }
            if (points.isEmpty()) null else CanvasSurveyGeometry(feature.geometryType, points)
        }
    }
    val gpuScene by TerrainPerformanceSession.gpuScene.collectAsStateWithLifecycle()
    var useGpuTerrain by rememberSaveable { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(initialZoom) }
    var pan by remember { mutableStateOf(Offset(initialPanX, initialPanY)) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val overlayLabelPaint = remember {
        NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val overlayMarkerNumberPaint = remember {
        NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 15f
            textAlign = NativePaint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val overlayLabelBackgroundPaint = remember {
        NativePaint(NativePaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(210, 13, 14, 18)
        }
    }

    LaunchedEffect(gpuScene) {
        if (gpuScene == null) useGpuTerrain = false
    }
    LaunchedEffect(viewportResetKey, mode) {
        zoom = 1f
        pan = Offset.Zero
    }
    // Restores a persisted viewport exactly once, when the caller signals settings finished
    // loading (token goes from 0 to a positive value). Not keyed to every zoom/pan change, so it
    // doesn't fight with live user interaction or with the reset-on-tab-switch behavior above.
    LaunchedEffect(viewportRestoreToken) {
        if (viewportRestoreToken > 0) {
            zoom = initialZoom.coerceIn(1f, 32f)
            pan = Offset(initialPanX, initialPanY)
        }
    }
    LaunchedEffect(bitmap) {
        if (bitmap == null) TerrainVisionSession.clear()
    }
    LaunchedEffect(imageBitmap, bitmap) {
        val image = imageBitmap ?: return@LaunchedEffect
        val sourceBitmap = bitmap ?: return@LaunchedEffect
        snapshotFlow { Triple(zoom, pan, viewportSize) }
            .distinctUntilChanged()
            .debounce(VIEWPORT_PUBLISH_DEBOUNCE_MS)
            .collect { (currentZoom, currentPan, currentSize) ->
                val viewportWidth = currentSize.width.toFloat().coerceAtLeast(1f)
                val viewportHeight = currentSize.height.toFloat().coerceAtLeast(1f)
                val fit = containScale(viewportWidth, viewportHeight, image.width.toFloat(), image.height.toFloat())
                val displayWidth = image.width * fit * currentZoom
                val displayHeight = image.height * fit * currentZoom
                val imageLeft = (viewportWidth - displayWidth) * 0.5f + currentPan.x
                val imageTop = (viewportHeight - displayHeight) * 0.5f + currentPan.y
                val bounds = NormalizedRasterBounds(
                    left = ((-imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
                    top = ((-imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
                    right = ((viewportWidth - imageLeft) / displayWidth).toDouble().coerceIn(0.0, 1.0),
                    bottom = ((viewportHeight - imageTop) / displayHeight).toDouble().coerceIn(0.0, 1.0),
                ).sanitized()
                TerrainVisionSession.publish(sourceBitmap, bounds, currentZoom)
                onViewportChanged(bounds, currentZoom, currentPan.x, currentPan.y)
                onViewportStretch(bounds, fit * currentZoom)
            }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        if (mode == LidarCanvasMode.EXPLORE) {
            val nextZoom = (zoom * zoomChange).coerceIn(1f, 32f)
            val viewportWidth = viewportSize.width.toFloat().coerceAtLeast(1f)
            val viewportHeight = viewportSize.height.toFloat().coerceAtLeast(1f)
            val sourceWidth = imageBitmap?.width?.toFloat()?.coerceAtLeast(1f) ?: viewportWidth
            val sourceHeight = imageBitmap?.height?.toFloat()?.coerceAtLeast(1f) ?: viewportHeight
            val fit = containScale(viewportWidth, viewportHeight, sourceWidth, sourceHeight)
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
            .clipToBounds()
            .testTag("lidar_map_canvas_container"),
    ) {
        val activeGpuScene = gpuScene
        if (useGpuTerrain && activeGpuScene != null) {
            GpuTerrainSurface(
                scene = activeGpuScene,
                modifier = Modifier.fillMaxSize().testTag("gpu_terrain_surface"),
            )
            Text(
                text = "GPU 3D · drag to pan · two-finger drag to rotate · pinch to zoom · double-tap to reset",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xC0000000))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
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
                        val fit = containScale(canvasWidth, canvasHeight, bitmap.width.toFloat(), bitmap.height.toFloat())
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
            val inspectionModifier = if (mode == LidarCanvasMode.EXPLORE && onInspectPosition != null) {
                Modifier.pointerInput(onInspectPosition, imageBitmap, zoom, pan, viewportSize) {
                    detectTapGestures { offset ->
                        val canvasWidth = size.width.toFloat().coerceAtLeast(1f)
                        val canvasHeight = size.height.toFloat().coerceAtLeast(1f)
                        val fit = containScale(
                            canvasWidth,
                            canvasHeight,
                            imageBitmap.width.toFloat(),
                            imageBitmap.height.toFloat(),
                        )
                        val displayWidth = imageBitmap.width * fit * zoom
                        val displayHeight = imageBitmap.height * fit * zoom
                        val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
                        val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y
                        val normalizedX = (offset.x - imageLeft) / displayWidth
                        val normalizedY = (offset.y - imageTop) / displayHeight
                        if (normalizedX in 0f..1f && normalizedY in 0f..1f) {
                            onInspectPosition?.invoke(normalizedX * 100f, normalizedY * 100f)
                        }
                    }
                }
            } else {
                Modifier
            }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { viewportSize = it }
                    .then(interactionModifier)
                    .then(inspectionModifier)
                    .testTag("lidar_canvas"),
            ) {
                val canvasWidth = size.width.coerceAtLeast(1f)
                val canvasHeight = size.height.coerceAtLeast(1f)
                val fit = containScale(canvasWidth, canvasHeight, imageBitmap.width.toFloat(), imageBitmap.height.toFloat())
                val displayWidth = imageBitmap.width * fit * zoom
                val displayHeight = imageBitmap.height * fit * zoom
                val imageLeft = (canvasWidth - displayWidth) * 0.5f + pan.x
                val imageTop = (canvasHeight - displayHeight) * 0.5f + pan.y

                drawImage(
                    image = imageBitmap,
                    dstOffset = IntOffset(imageLeft.toInt(), imageTop.toInt()),
                    dstSize = IntSize(displayWidth.toInt(), displayHeight.toInt()),
                )
                if (showBasemap && basemapImageBitmap != null) {
                    drawImage(
                        image = basemapImageBitmap,
                        dstOffset = IntOffset(imageLeft.toInt(), imageTop.toInt()),
                        dstSize = IntSize(displayWidth.toInt(), displayHeight.toInt()),
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
                if (homesiteCells != null) {
                    val cellWidth = displayWidth / HOMESITE_BINS
                    val cellHeight = displayHeight / HOMESITE_BINS
                    for (row in 0 until HOMESITE_BINS) {
                        for (col in 0 until HOMESITE_BINS) {
                            val intensity = homesiteCells[row * HOMESITE_BINS + col]
                            if (intensity <= 0.22f) continue
                            drawRect(
                                color = homesiteColor(intensity),
                                topLeft = Offset(imageLeft + col * cellWidth, imageTop + row * cellHeight),
                                size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                                alpha = 0.10f + intensity * 0.45f,
                            )
                        }
                    }
                }
                if (viewshedBins != null && viewshed != null) {
                    val cellWidth = displayWidth / VIEWSHED_BINS
                    val cellHeight = displayHeight / VIEWSHED_BINS
                    for (row in 0 until VIEWSHED_BINS) {
                        for (col in 0 until VIEWSHED_BINS) {
                            when (viewshedBins[row * VIEWSHED_BINS + col]) {
                                0 -> drawRect(
                                    color = Color(0xFF303A46),
                                    topLeft = Offset(imageLeft + col * cellWidth, imageTop + row * cellHeight),
                                    size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                                    alpha = 0.35f,
                                )
                                1 -> drawRect(
                                    color = Color(0xFF2ECC71),
                                    topLeft = Offset(imageLeft + col * cellWidth, imageTop + row * cellHeight),
                                    size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                                    alpha = 0.45f,
                                )
                            }
                        }
                    }
                    val observer = Offset(
                        imageLeft + (viewshed.observerXPercent.coerceIn(0f, 100f) / 100f) * displayWidth,
                        imageTop + (viewshed.observerYPercent.coerceIn(0f, 100f) / 100f) * displayHeight,
                    )
                    drawCircle(color = Color.Black, radius = 12f, center = observer, alpha = 0.65f)
                    drawCircle(color = Color(0xFF29B6F6), radius = 8f, center = observer)
                    drawCircle(color = Color.White, radius = 8f, center = observer, style = Stroke(2f))
                }
                if (gridSpacing >= 1f) {
                    // gridSpacing is a real-world spacing in feet (e.g. 3 ft or 10 ft survey
                    // squares), so the on-screen line count is derived from the site's actual
                    // width/height rather than a fixed percentage of the image.
                    val widthMeters = geoMetadata.widthMeters.toFloat().coerceAtLeast(0.01f)
                    val heightMeters = geoMetadata.heightMeters.toFloat().coerceAtLeast(0.01f)
                    val spacingMeters = (gridSpacing * FEET_TO_METERS).coerceAtLeast(0.01f)
                    val cols = (widthMeters / spacingMeters).toInt().coerceIn(1, 300)
                    val rows = (heightMeters / spacingMeters).toInt().coerceIn(1, 300)
                    for (i in 1 until cols) {
                        val px = imageLeft + (i.toFloat() / cols) * displayWidth
                        drawLine(
                            color = Color(0xFF29B6F6),
                            start = Offset(px, imageTop),
                            end = Offset(px, imageTop + displayHeight),
                            strokeWidth = 1.5f,
                            alpha = GRID_LINE_ALPHA,
                        )
                    }
                    for (i in 1 until rows) {
                        val py = imageTop + (i.toFloat() / rows) * displayHeight
                        drawLine(
                            color = Color(0xFF29B6F6),
                            start = Offset(imageLeft, py),
                            end = Offset(imageLeft + displayWidth, py),
                            strokeWidth = 1.5f,
                            alpha = GRID_LINE_ALPHA,
                        )
                    }
                }
                surveyGeometries.forEach { geometry ->
                    val points = geometry.points.map { point ->
                        Offset(
                            imageLeft + (point.first / 100f) * displayWidth,
                            imageTop + (point.second / 100f) * displayHeight,
                        )
                    }
                    when (geometry.type) {
                        SurveyGeometryType.POINT -> points.forEach { point ->
                            drawCircle(Color.Black, radius = 10f, center = point, alpha = 0.65f)
                            drawCircle(Color(0xFF00E5FF), radius = 7f, center = point)
                            drawCircle(Color.White, radius = 7f, center = point, style = Stroke(2f))
                        }
                        SurveyGeometryType.LINE -> points.zipWithNext().forEach { (start, end) ->
                            drawLine(Color.Black, start, end, strokeWidth = 7f, alpha = 0.6f)
                            drawLine(Color(0xFF00E5FF), start, end, strokeWidth = 3.5f)
                        }
                        SurveyGeometryType.POLYGON -> if (points.size >= 3) {
                            val path = Path().apply {
                                moveTo(points.first().x, points.first().y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                                close()
                            }
                            drawPath(path, Color(0x3300E5FF))
                            drawPath(path, Color(0xFF00E5FF), style = Stroke(3.5f))
                        }
                    }
                }
                breadcrumbPaths.forEach { path ->
                    if (path.size < 2) return@forEach
                    val points = path.map { point ->
                        Offset(
                            imageLeft + (point.first.coerceIn(0f, 100f) / 100f) * displayWidth,
                            imageTop + (point.second.coerceIn(0f, 100f) / 100f) * displayHeight,
                        )
                    }
                    points.zipWithNext().forEach { (start, end) ->
                        drawLine(Color.Black, start, end, strokeWidth = 8f, alpha = 0.52f)
                        drawLine(Color(0xFFFF9800), start, end, strokeWidth = 4f, alpha = 0.95f)
                    }
                    points.lastOrNull()?.let { last ->
                        drawCircle(Color.Black, radius = 11f, center = last, alpha = 0.6f)
                        drawCircle(Color(0xFFFF9800), radius = 7f, center = last)
                    }
                }
                for (signal in loggedSignals) {
                    val px = imageLeft + (signal.gridX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val py = imageTop + (signal.gridY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val pinColor = runCatching { Color(signal.metalType.colorHex) }.getOrDefault(Color(0xFFFFD700))
                    drawCircle(color = pinColor, radius = 12f, center = Offset(px, py), alpha = 0.5f)
                    drawCircle(color = Color.White, radius = 4f, center = Offset(px, py))
                    drawCircle(color = pinColor, radius = 18f, center = Offset(px, py), style = Stroke(width = 2f))
                }
                val occupiedTargetLabels = mutableListOf<RectF>()
                overlayTargets.forEachIndexed { targetIndex, target ->
                    val px = imageLeft + (target.xPercent.coerceIn(0f, 100f) / 100f) * displayWidth
                    val py = imageTop + (target.yPercent.coerceIn(0f, 100f) / 100f) * displayHeight
                    val pinColor = runCatching { Color(target.colorHex) }.getOrDefault(Color(0xFF29B6F6))
                    val marker = Offset(px, py)
                    drawCircle(color = Color.Black, radius = 15f, center = marker, alpha = 0.72f)
                    drawCircle(color = pinColor, radius = 12f, center = marker)
                    drawCircle(color = Color.White, radius = 12f, center = marker, style = Stroke(width = 2f))
                    drawContext.canvas.nativeCanvas.drawText(
                        "${targetIndex + 1}",
                        px,
                        py - (overlayMarkerNumberPaint.ascent() + overlayMarkerNumberPaint.descent()) / 2f,
                        overlayMarkerNumberPaint,
                    )

                    val label = target.label
                        .replace("Cloud AI ", "AI ")
                        .replace("Possible trash / refuse pit", "Refuse pit")
                        .replace("Foundation / building platform", "Foundation")
                        .take(36)
                    val textWidth = overlayLabelPaint.measureText(label)
                    val textHeight = overlayLabelPaint.textSize
                    val placements = listOf(
                        px + 18f to py - 13f,
                        px + 18f to py + textHeight + 17f,
                        px - textWidth - 18f to py - 13f,
                        px - textWidth - 18f to py + textHeight + 17f,
                    )
                    val placement = placements.takeIf { targetIndex < MAX_VISIBLE_TARGET_LABELS }
                        ?.firstNotNullOfOrNull { (candidateX, candidateBaseline) ->
                        val labelX = candidateX.coerceIn(
                            imageLeft + 6f,
                            (imageLeft + displayWidth - textWidth - 10f).coerceAtLeast(imageLeft + 6f),
                        )
                        val labelBaseline = candidateBaseline.coerceIn(
                            imageTop + textHeight + 7f,
                            imageTop + displayHeight - 7f,
                        )
                        val bounds = RectF(
                            labelX - 5f,
                            labelBaseline - textHeight - 4f,
                            labelX + textWidth + 5f,
                            labelBaseline + 5f,
                        )
                        if (occupiedTargetLabels.none { RectF.intersects(it, bounds) }) {
                            Triple(labelX, labelBaseline, bounds)
                        } else {
                            null
                        }
                        }
                    placement?.let { (labelX, labelBaseline, bounds) ->
                        occupiedTargetLabels += bounds
                        drawContext.canvas.nativeCanvas.drawRoundRect(
                            bounds,
                            6f,
                            6f,
                            overlayLabelBackgroundPaint,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            labelX,
                            labelBaseline,
                            overlayLabelPaint,
                        )
                    }
                }
                inspectionPoint?.let { point ->
                    val px = imageLeft + (point.first.coerceIn(0f, 100f) / 100f) * displayWidth
                    val py = imageTop + (point.second.coerceIn(0f, 100f) / 100f) * displayHeight
                    val marker = Offset(px, py)
                    drawCircle(color = Color.Black, radius = 18f, center = marker, alpha = 0.65f)
                    drawCircle(color = Color(0xFFFFC107), radius = 12f, center = marker, style = Stroke(width = 3f))
                    drawLine(
                        color = Color(0xFFFFC107),
                        start = Offset(px - 22f, py),
                        end = Offset(px + 22f, py),
                        strokeWidth = 2f,
                    )
                    drawLine(
                        color = Color(0xFFFFC107),
                        start = Offset(px, py - 22f),
                        end = Offset(px, py + 22f),
                        strokeWidth = 2f,
                    )
                }
                profileStartPoint?.let { start ->
                    val startOffset = Offset(
                        imageLeft + (start.first.coerceIn(0f, 100f) / 100f) * displayWidth,
                        imageTop + (start.second.coerceIn(0f, 100f) / 100f) * displayHeight,
                    )
                    profileEndPoint?.let { end ->
                        val endOffset = Offset(
                            imageLeft + (end.first.coerceIn(0f, 100f) / 100f) * displayWidth,
                            imageTop + (end.second.coerceIn(0f, 100f) / 100f) * displayHeight,
                        )
                        drawLine(Color.Black, startOffset, endOffset, strokeWidth = 8f, alpha = 0.65f)
                        drawLine(Color(0xFF00E5FF), startOffset, endOffset, strokeWidth = 3.5f)
                        drawCircle(Color.Black, radius = 13f, center = endOffset, alpha = 0.65f)
                        drawCircle(Color(0xFF00E5FF), radius = 8f, center = endOffset)
                    }
                    drawCircle(Color.Black, radius = 13f, center = startOffset, alpha = 0.65f)
                    drawCircle(Color(0xFF00E5FF), radius = 8f, center = startOffset)
                    drawCircle(Color.White, radius = 8f, center = startOffset, style = Stroke(2f))
                }
                if (showSurveyCursor) {
                    val sx = imageLeft + (sweepX.coerceIn(0f, 100f) / 100f) * displayWidth
                    val sy = imageTop + (sweepY.coerceIn(0f, 100f) / 100f) * displayHeight
                    val coil = Offset(sx, sy)
                    drawCircle(color = Color(0xFFFFD700), radius = 36f, center = coil, style = Stroke(width = 1.5f), alpha = 0.35f)
                    drawCircle(color = Color(0xFFFFD700), radius = 24f, center = coil, style = Stroke(width = 3.5f), alpha = 0.85f)
                    drawLine(color = Color(0xFFFFD700), start = Offset(sx - 10f, sy), end = Offset(sx + 10f, sy), strokeWidth = 2f, alpha = 0.8f)
                    drawLine(color = Color(0xFFFFD700), start = Offset(sx, sy - 10f), end = Offset(sx, sy + 10f), strokeWidth = 2f, alpha = 0.8f)
                    drawCircle(color = Color.White, radius = 3f, center = coil)
                }
                val devicePosition = deviceGridPosition
                if (devicePosition != null && devicePosition.first in 0f..100f && devicePosition.second in 0f..100f) {
                    val dx = imageLeft + (devicePosition.first / 100f) * displayWidth
                    val dy = imageTop + (devicePosition.second / 100f) * displayHeight
                    val here = Offset(dx, dy)
                    drawCircle(color = Color(0xFF2196F3), radius = 26f, center = here, alpha = 0.25f)
                    drawCircle(color = Color(0xFF2196F3), radius = 10f, center = here)
                    drawCircle(color = Color.White, radius = 10f, center = here, style = Stroke(width = 2.5f))
                }
            }

            if (showCoordinateHud) {
                val clipboard = LocalClipboardManager.current
                var copiedFlash by remember { mutableStateOf(false) }
                LaunchedEffect(copiedFlash) {
                    if (copiedFlash) {
                        kotlinx.coroutines.delay(1400)
                        copiedFlash = false
                    }
                }
                val decimalText = if (currentLat != null && currentLon != null) {
                    String.format(Locale.US, "%.6f, %.6f", currentLat, currentLon)
                } else {
                    "grid ${sweepX.toInt()}, ${sweepY.toInt()}"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE60D0E12))
                        .border(0.5.dp, Color(0xFF2C2E35), RoundedCornerShape(8.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(decimalText))
                            copiedFlash = true
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("coordinate_hud"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (currentLat != null && currentLon != null) {
                            val utm = runCatching {
                                GeoSpatialLibrary.geographicToUtm(currentLat, currentLon)
                            }.getOrNull()
                            Text(
                                text = "${GeoSpatialLibrary.formatDms(currentLat, true)}  ·  ${GeoSpatialLibrary.formatDms(currentLon, false)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = decimalText,
                                color = Color(0xFFB0BEC5),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
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
                        if (copiedFlash) {
                            Text(
                                text = "Copied",
                                color = Color(0xFF81C784),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy coordinates",
                        tint = Color(0xFF90A4AE),
                        modifier = Modifier.size(18.dp),
                    )
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
            Text(
                text = "Import a LAZ/LAS dataset to begin",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (activeGpuScene != null) {
            OutlinedButton(
                onClick = { useGpuTerrain = !useGpuTerrain },
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).testTag("toggle_gpu_terrain_button"),
            ) {
                Text(if (useGpuTerrain) "2D analysis" else "GPU 3D")
            }
        }

        if (isRendering && !useGpuTerrain) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private data class CanvasSurveyGeometry(
    val type: SurveyGeometryType,
    val points: List<Pair<Float, Float>>,
)

private fun containScale(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Float,
    imageHeight: Float,
): Float = min(
    viewportWidth / imageWidth.coerceAtLeast(1f),
    viewportHeight / imageHeight.coerceAtLeast(1f),
)

private const val HEATMAP_BINS = 24
private const val VIEWSHED_BINS = 64
internal const val HOMESITE_BINS = 96
private const val VIEWPORT_PUBLISH_DEBOUNCE_MS = 120L

private fun heatmapColor(intensity: Float): Color = if (intensity < 0.5f) {
    lerp(Color(0xFF1565C0), Color(0xFFFFC107), intensity / 0.5f)
} else {
    lerp(Color(0xFFFFC107), Color(0xFFE53935), (intensity - 0.5f) / 0.5f)
}

// Warm ramp (amber to brick red) so the homesite surface reads as 'occupation likelihood'
// and stays visually distinct from the blue-to-red dig-priority heatmap.
private fun homesiteColor(intensity: Float): Color = if (intensity < 0.55f) {
    lerp(Color(0xFFFFE082), Color(0xFFFF8F00), (intensity - 0.22f) / 0.33f)
} else {
    lerp(Color(0xFFFF8F00), Color(0xFFD84315), (intensity - 0.55f) / 0.45f)
}
