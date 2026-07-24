package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.analysis.AnalysisPalette
import com.example.analysis.TerrainAnalysisEngine
import com.example.analysis.TerrainAnalysisLayer
import com.example.analysis.TerrainAnalysisRenderer
import com.example.analysis.TerrainAnalysisType
import com.example.analysis.TerrainRenderOptions
import com.example.data.ElevationGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt


enum class AnalysisDisplayMode(val title: String) {
    TERRAIN("Terrain"),
    ANALYSIS("Analysis"),
    BLENDED("Blended"),
}

private data class InspectedCell(
    val column: Int,
    val row: Int,
    val elevationMeters: Float,
    val primaryValue: Float,
    val comparisonValue: Float?,
    val primaryValid: Boolean,
    val comparisonValid: Boolean,
    val localXMeters: Float,
    val localYMeters: Float,
)

@Composable
fun TerrainAnalysisScreen(
    terrainViewModel: HillshadeViewModel,
    analysisViewModel: TerrainAnalysisViewModel,
    padding: PaddingValues,
) {
    val grid by terrainViewModel.elevationGrid.collectAsStateWithLifecycle()
    val terrainBitmap by terrainViewModel.hillshadeBitmap.collectAsStateWithLifecycle()
    val terrainSummary by terrainViewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val selectedType by analysisViewModel.selectedType.collectAsStateWithLifecycle()
    val options by analysisViewModel.options.collectAsStateWithLifecycle()
    val renderOptions by analysisViewModel.renderOptions.collectAsStateWithLifecycle()
    val layer by analysisViewModel.layer.collectAsStateWithLifecycle()
    val bitmap by analysisViewModel.bitmap.collectAsStateWithLifecycle()
    val isRunning by analysisViewModel.isRunning.collectAsStateWithLifecycle()
    val status by analysisViewModel.status.collectAsStateWithLifecycle()
    val cacheEntryCount by analysisViewModel.cacheEntryCount.collectAsStateWithLifecycle()
    val lastResultWasCached by analysisViewModel.lastResultWasCached.collectAsStateWithLifecycle()
    val exportStatus by analysisViewModel.exportStatus.collectAsStateWithLifecycle()
    val isExporting by analysisViewModel.isExporting.collectAsStateWithLifecycle()
    val aiInterpretation by analysisViewModel.aiInterpretation.collectAsStateWithLifecycle()
    val isAiRunning by analysisViewModel.isAiRunning.collectAsStateWithLifecycle()

    val primaryMenuExpanded = remember { mutableStateOf(false) }
    val comparisonMenuExpanded = remember { mutableStateOf(false) }
    val displayMode = remember { mutableStateOf(AnalysisDisplayMode.BLENDED) }
    val comparisonEnabled = remember { mutableStateOf(false) }
    val comparisonType = remember { mutableStateOf(TerrainAnalysisType.SLOPE) }
    val comparisonLayer = remember { mutableStateOf<TerrainAnalysisLayer?>(null) }
    val comparisonBitmap = remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val comparisonRunning = remember { mutableStateOf(false) }
    val comparisonError = remember { mutableStateOf<String?>(null) }
    val inspectedCell = remember(layer, comparisonLayer.value, grid) { mutableStateOf<InspectedCell?>(null) }

    LaunchedEffect(comparisonEnabled.value, comparisonType.value, grid, options, renderOptions) {
        if (!comparisonEnabled.value) {
            comparisonLayer.value = null
            comparisonBitmap.value = null
            comparisonError.value = null
            comparisonRunning.value = false
            return@LaunchedEffect
        }
        comparisonRunning.value = true
        comparisonError.value = null
        try {
            val result = withContext(Dispatchers.Default) {
                TerrainAnalysisEngine.analyze(
                    grid,
                    comparisonType.value,
                    options.normalized(grid.cellSizeMeters),
                )
            }
            val rendered = withContext(Dispatchers.Default) {
                TerrainAnalysisRenderer.render(result, renderOptions.sanitized())
            }
            comparisonLayer.value = result
            comparisonBitmap.value = rendered
        } catch (error: Exception) {
            comparisonLayer.value = null
            comparisonBitmap.value = null
            comparisonError.value = error.message ?: "Comparison analysis failed."
        } finally {
            comparisonRunning.value = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Phase 4 · Professional Analysis",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Synchronized layer comparison, precise inspection, and per-cell differences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (cacheEntryCount > 0) {
                TextButton(onClick = analysisViewModel::clearCache) { Text("Clear ($cacheEntryCount)") }
            }
        }

        Text(terrainSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "${grid.width}×${grid.height} cells · ${"%.2f".format(grid.cellSizeMeters)} m/cell",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
        )

        LayerSelector(
            title = "Primary layer",
            selected = selectedType,
            expanded = primaryMenuExpanded.value,
            onExpandedChanged = { primaryMenuExpanded.value = it },
            onSelected = { type ->
                analysisViewModel.selectType(type)
                analysisViewModel.runAnalysis(grid)
                inspectedCell.value = null
                primaryMenuExpanded.value = false
            },
        )

        Text(selectedType.description, style = MaterialTheme.typography.bodyMedium)

        AnalysisParameterControls(
            selectedType = selectedType,
            localRadius = options.localRadiusMeters,
            horizonRadius = options.horizonRadiusMeters,
            directionCount = options.directionCount,
            onLocalRadiusChanged = analysisViewModel::updateLocalRadius,
            onHorizonRadiusChanged = analysisViewModel::updateHorizonRadius,
            onDirectionCountChanged = analysisViewModel::updateDirectionCount,
        )

        AnalysisVisualizationControls(
            options = renderOptions,
            displayMode = displayMode.value,
            onDisplayModeChanged = { displayMode.value = it },
            onPaletteChanged = analysisViewModel::updateAnalysisPalette,
            onContrastChanged = analysisViewModel::updateAnalysisContrast,
            onBrightnessChanged = analysisViewModel::updateAnalysisBrightness,
            onOpacityChanged = analysisViewModel::updateAnalysisOpacity,
            onInvertedChanged = analysisViewModel::setAnalysisPaletteInverted,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Phase 4 layer comparison", fontWeight = FontWeight.Bold)
                        Text(
                            "Tap either view to inspect the same cell in both layers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = comparisonEnabled.value,
                        onCheckedChange = {
                            comparisonEnabled.value = it
                            inspectedCell.value = null
                        },
                    )
                }
                if (comparisonEnabled.value) {
                    LayerSelector(
                        title = "Comparison layer",
                        selected = comparisonType.value,
                        expanded = comparisonMenuExpanded.value,
                        onExpandedChanged = { comparisonMenuExpanded.value = it },
                        onSelected = { type ->
                            comparisonType.value = type
                            comparisonMenuExpanded.value = false
                            inspectedCell.value = null
                        },
                    )
                    if (comparisonRunning.value) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                            Text("Calculating ${comparisonType.value.title}…")
                        }
                    }
                    comparisonError.value?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        if (isRunning) {
            OutlinedButton(onClick = analysisViewModel::cancelAnalysis, modifier = Modifier.fillMaxWidth()) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), strokeWidth = 2.dp)
                Text("Cancel ${selectedType.title}")
            }
        } else {
            Button(
                onClick = {
                    inspectedCell.value = null
                    analysisViewModel.runAnalysis(grid)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run ${selectedType.title}")
            }
        }

        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (lastResultWasCached) {
            Text("Cached result · no recalculation required", color = MaterialTheme.colorScheme.primary)
        }

        bitmap?.let { primaryBitmap ->
            val primaryLayer = layer
            AnalysisPreview(
                title = "Primary · ${selectedType.title}",
                terrainBitmap = terrainBitmap.safeAsImageBitmap(),
                analysisBitmap = primaryBitmap.asImageBitmap(),
                displayMode = displayMode.value,
                layer = primaryLayer,
                grid = grid,
                inspected = inspectedCell.value,
                onCellSelected = { column, row ->
                    inspectedCell.value = inspectCell(
                        column = column,
                        row = row,
                        primaryLayer = primaryLayer,
                        comparisonLayer = comparisonLayer.value,
                        grid = grid,
                    )
                },
            )
            AnalysisLegend(selectedType, renderOptions)
        }

        if (comparisonEnabled.value) {
            comparisonBitmap.value?.let { secondaryBitmap ->
                AnalysisPreview(
                    title = "Comparison · ${comparisonType.value.title}",
                    terrainBitmap = terrainBitmap.safeAsImageBitmap(),
                    analysisBitmap = secondaryBitmap.asImageBitmap(),
                    displayMode = displayMode.value,
                    layer = comparisonLayer.value,
                    grid = grid,
                    inspected = inspectedCell.value,
                    onCellSelected = { column, row ->
                        inspectedCell.value = inspectCell(
                            column = column,
                            row = row,
                            primaryLayer = layer,
                            comparisonLayer = comparisonLayer.value,
                            grid = grid,
                        )
                    },
                )
                AnalysisLegend(comparisonType.value, renderOptions)
            }
        }

        inspectedCell.value?.let { inspected ->
            InspectionCard(
                inspected = inspected,
                primaryLayer = layer,
                comparisonLayer = comparisonLayer.value,
            )
        }

        layer?.let { result ->
            LayerStatisticsCard("Primary statistics", result)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = analysisViewModel::exportCurrentPng,
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isExporting) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(if (isExporting) "Saving…" else "Save PNG")
                }
                Button(
                    onClick = { analysisViewModel.requestAiInterpretation(terrainSummary) },
                    enabled = !isAiRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isAiRunning) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(if (isAiRunning) "Analyzing…" else "ChatGPT")
                }
            }
        }

        if (comparisonEnabled.value) {
            comparisonLayer.value?.let { LayerStatisticsCard("Comparison statistics", it) }
        }

        exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        aiInterpretation?.let {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ChatGPT interpretation", fontWeight = FontWeight.Bold)
                    Text(it)
                }
            }
        }
    }
}

@Composable
private fun LayerSelector(
    title: String,
    selected: TerrainAnalysisType,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    onSelected: (TerrainAnalysisType) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium)
        OutlinedButton(onClick = { onExpandedChanged(true) }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.title)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChanged(false) }) {
            TerrainAnalysisType.phaseOneEntries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(type.title, fontWeight = FontWeight.SemiBold)
                            Text(type.description, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = { onSelected(type) },
                )
            }
        }
    }
}

@Composable
private fun AnalysisPreview(
    title: String,
    terrainBitmap: ImageBitmap,
    analysisBitmap: ImageBitmap,
    displayMode: AnalysisDisplayMode,
    layer: TerrainAnalysisLayer?,
    grid: ElevationGrid,
    inspected: InspectedCell?,
    onCellSelected: (Int, Int) -> Unit,
) {
    val width = layer?.width ?: analysisBitmap.width.coerceAtLeast(1)
    val height = layer?.height ?: analysisBitmap.height.coerceAtLeast(1)
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column {
            Text(title, modifier = Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(width.toFloat() / height.coerceAtLeast(1))
                    .pointerInput(layer, grid) {
                        detectTapGestures { tap ->
                            val active = layer ?: return@detectTapGestures
                            val column = ((tap.x / size.width.coerceAtLeast(1)) * active.width)
                                .toInt().coerceIn(0, active.width - 1)
                            val row = ((tap.y / size.height.coerceAtLeast(1)) * active.height)
                                .toInt().coerceIn(0, active.height - 1)
                            onCellSelected(column, row)
                        }
                    },
            ) {
                if (displayMode != AnalysisDisplayMode.ANALYSIS) {
                    Image(
                        bitmap = terrainBitmap,
                        contentDescription = "Terrain hillshade",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (displayMode != AnalysisDisplayMode.TERRAIN) {
                    Image(
                        bitmap = analysisBitmap,
                        contentDescription = "$title analysis",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                inspected?.let { selected ->
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(
                            x = ((selected.column + 0.5f) / width) * size.width,
                            y = ((selected.row + 0.5f) / height) * size.height,
                        )
                        drawCircle(Color.Black, 15f, center, style = Stroke(6f))
                        drawCircle(Color.White, 15f, center, style = Stroke(3f))
                        drawLine(Color.Black, Offset(center.x - 24f, center.y), Offset(center.x + 24f, center.y), 6f)
                        drawLine(Color.White, Offset(center.x - 24f, center.y), Offset(center.x + 24f, center.y), 2.5f)
                        drawLine(Color.Black, Offset(center.x, center.y - 24f), Offset(center.x, center.y + 24f), 6f)
                        drawLine(Color.White, Offset(center.x, center.y - 24f), Offset(center.x, center.y + 24f), 2.5f)
                    }
                }
            }
            Text(
                "Synchronized tap inspection",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun inspectCell(
    column: Int,
    row: Int,
    primaryLayer: TerrainAnalysisLayer?,
    comparisonLayer: TerrainAnalysisLayer?,
    grid: ElevationGrid,
): InspectedCell? {
    val primary = primaryLayer ?: return null
    val safeColumn = column.coerceIn(0, primary.width - 1)
    val safeRow = row.coerceIn(0, primary.height - 1)
    val primaryIndex = safeRow * primary.width + safeColumn
    val gridColumn = ((safeColumn.toFloat() / primary.width) * grid.width).toInt().coerceIn(0, grid.width - 1)
    val gridRow = ((safeRow.toFloat() / primary.height) * grid.height).toInt().coerceIn(0, grid.height - 1)
    val gridIndex = gridRow * grid.width + gridColumn

    val comparisonValue = comparisonLayer?.let { second ->
        val secondColumn = ((safeColumn.toFloat() / primary.width) * second.width).toInt().coerceIn(0, second.width - 1)
        val secondRow = ((safeRow.toFloat() / primary.height) * second.height).toInt().coerceIn(0, second.height - 1)
        second.values[secondRow * second.width + secondColumn]
    }
    val comparisonValid = comparisonLayer?.let { second ->
        val secondColumn = ((safeColumn.toFloat() / primary.width) * second.width).toInt().coerceIn(0, second.width - 1)
        val secondRow = ((safeRow.toFloat() / primary.height) * second.height).toInt().coerceIn(0, second.height - 1)
        second.validData[secondRow * second.width + secondColumn]
    } ?: false

    return InspectedCell(
        column = safeColumn,
        row = safeRow,
        elevationMeters = grid.bareEarth[gridIndex],
        primaryValue = primary.values[primaryIndex],
        comparisonValue = comparisonValue,
        primaryValid = primary.validData[primaryIndex] && grid.validData[gridIndex],
        comparisonValid = comparisonValid && grid.validData[gridIndex],
        localXMeters = (gridColumn + 0.5f) * grid.cellSizeMeters,
        localYMeters = (gridRow + 0.5f) * grid.cellSizeMeters,
    )
}

@Composable
private fun InspectionCard(
    inspected: InspectedCell,
    primaryLayer: TerrainAnalysisLayer?,
    comparisonLayer: TerrainAnalysisLayer?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Selected terrain cell", fontWeight = FontWeight.Bold)
            Text("Column ${inspected.column} · row ${inspected.row}", fontFamily = FontFamily.Monospace)
            Text(
                "Local X ${format(inspected.localXMeters)} m · Y ${format(inspected.localYMeters)} m",
                fontFamily = FontFamily.Monospace,
            )
            if (!inspected.primaryValid) {
                Text("Primary NoData cell", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            Text("Elevation: ${format(inspected.elevationMeters)} m")
            primaryLayer?.let {
                Text("${it.type.title}: ${format(inspected.primaryValue)} ${it.type.unit}")
            }
            if (comparisonLayer != null && inspected.comparisonValue != null) {
                if (inspected.comparisonValid) {
                    Text("${comparisonLayer.type.title}: ${format(inspected.comparisonValue)} ${comparisonLayer.type.unit}")
                    val primary = primaryLayer
                    if (primary != null) {
                        val primaryZ = standardized(inspected.primaryValue, primary)
                        val comparisonZ = standardized(inspected.comparisonValue, comparisonLayer)
                        val difference = comparisonZ - primaryZ
                        Text(
                            "Standardized difference (comparison − primary): ${if (difference >= 0f) "+" else ""}${format(difference)} σ",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (primary.type.unit == comparisonLayer.type.unit) {
                            val rawDifference = inspected.comparisonValue - inspected.primaryValue
                            Text(
                                "Raw difference: ${if (rawDifference >= 0f) "+" else ""}${format(rawDifference)} ${primary.type.unit}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    Text("Comparison NoData cell", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun standardized(value: Float, layer: TerrainAnalysisLayer): Float {
    val standardDeviation = layer.standardDeviation
    return if (standardDeviation <= 0.000001f) 0f else (value - layer.mean) / standardDeviation
}

@Composable
private fun LayerStatisticsCard(title: String, result: TerrainAnalysisLayer) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text("${result.type.title} · ${result.validCellCount} valid cells (${format(result.coveragePercent)}%)")
            Text("Minimum: ${format(result.minimum)} ${result.type.unit}")
            Text("Mean: ${format(result.mean)} ${result.type.unit}")
            Text("Standard deviation: ${format(result.standardDeviation)} ${result.type.unit}")
            Text("95th percentile: ${format(result.percentile95)} ${result.type.unit}")
            Text("Maximum: ${format(result.maximum)} ${result.type.unit}")
            HorizontalDivider()
            Text(result.summary)
        }
    }
}

@Composable
private fun AnalysisParameterControls(
    selectedType: TerrainAnalysisType,
    localRadius: Float,
    horizonRadius: Float,
    directionCount: Int,
    onLocalRadiusChanged: (Float) -> Unit,
    onHorizonRadiusChanged: (Float) -> Unit,
    onDirectionCountChanged: (Int) -> Unit,
) {
    if (!selectedType.usesLocalRadius && !selectedType.usesHorizon) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Analysis parameters", fontWeight = FontWeight.Bold)
            if (selectedType.usesLocalRadius) {
                SliderControl("Local radius", "${localRadius.roundToInt()} m", localRadius, 1f..100f, onValueChange = onLocalRadiusChanged)
            }
            if (selectedType.usesHorizon) {
                SliderControl("Horizon radius", "${horizonRadius.roundToInt()} m", horizonRadius, 5f..250f, onValueChange = onHorizonRadiusChanged)
                SliderControl("Directions", directionCount.toString(), directionCount.toFloat(), 8f..24f, 15) {
                    onDirectionCountChanged(it.roundToInt())
                }
            }
            Text("Tap Run after changing an analysis parameter.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnalysisVisualizationControls(
    options: TerrainRenderOptions,
    displayMode: AnalysisDisplayMode,
    onDisplayModeChanged: (AnalysisDisplayMode) -> Unit,
    onPaletteChanged: (AnalysisPalette) -> Unit,
    onContrastChanged: (Float) -> Unit,
    onBrightnessChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onInvertedChanged: (Boolean) -> Unit,
) {
    val paletteMenuExpanded = remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Visualization", fontWeight = FontWeight.Bold)
            Text("Preview mode", style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalysisDisplayMode.entries.forEach { mode ->
                    if (mode == displayMode) {
                        Button(onClick = { onDisplayModeChanged(mode) }, modifier = Modifier.weight(1f)) { Text(mode.title) }
                    } else {
                        OutlinedButton(onClick = { onDisplayModeChanged(mode) }, modifier = Modifier.weight(1f)) { Text(mode.title) }
                    }
                }
            }
            Column {
                Text("Palette", style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { paletteMenuExpanded.value = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(options.palette.title)
                }
                DropdownMenu(expanded = paletteMenuExpanded.value, onDismissRequest = { paletteMenuExpanded.value = false }) {
                    AnalysisPalette.entries.forEach { palette ->
                        DropdownMenuItem(text = { Text(palette.title) }, onClick = {
                            onPaletteChanged(palette)
                            paletteMenuExpanded.value = false
                        })
                    }
                }
            }
            SliderControl("Contrast", "${"%.1f".format(options.contrast)}×", options.contrast, 0.5f..3f, onValueChange = onContrastChanged)
            SliderControl("Brightness", "${"%.2f".format(options.brightness)}×", options.brightness, 0.5f..1.75f, onValueChange = onBrightnessChanged)
            SliderControl("Overlay opacity", "${(options.opacity * 100f).roundToInt()}%", options.opacity, 0.1f..1f, onValueChange = onOpacityChanged)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Invert palette")
                    Text("Changes colors only; terrain values are unchanged.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = options.inverted, onCheckedChange = onInvertedChanged)
            }
        }
    }
}

@Composable
private fun AnalysisLegend(type: TerrainAnalysisType, options: TerrainRenderOptions) {
    val colors = legendColors(type, options)
    val labels = when {
        options.palette == AnalysisPalette.GRAYSCALE -> "Dark  ←  ${type.unit}  →  light"
        type == TerrainAnalysisType.ASPECT -> "N · E · S · W · flat cells neutral"
        type == TerrainAnalysisType.CURVATURE -> "Convex / raised  ←  neutral  →  concave / depressed"
        type.diverging -> "Negative  ←  neutral  →  positive"
        type == TerrainAnalysisType.MULTI_HILLSHADE -> "Shadow  ←  illumination  →  bright"
        type == TerrainAnalysisType.SKY_VIEW_FACTOR -> "Enclosed  ←  sky visibility  →  open"
        type == TerrainAnalysisType.POSITIVE_OPENNESS -> "Enclosed  ←  exposed ridges / mounds  →  open"
        type == TerrainAnalysisType.NEGATIVE_OPENNESS -> "Open  ←  enclosed pits / ditches  →  strong"
        else -> "Low  ←  ${type.unit}  →  high"
    }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(Brush.horizontalGradient(if (options.inverted) colors.reversed() else colors), RoundedCornerShape(7.dp)),
        )
        Text(if (options.inverted) "$labels · inverted" else labels, fontFamily = FontFamily.Monospace)
    }
}

private fun legendColors(type: TerrainAnalysisType, options: TerrainRenderOptions): List<Color> {
    if (options.palette == AnalysisPalette.GRAYSCALE) return listOf(Color(0xFF141414), Color(0xFF888888), Color.White)
    if (options.palette == AnalysisPalette.HIGH_CONTRAST) {
        return if (type.diverging || type == TerrainAnalysisType.CURVATURE) {
            listOf(Color.Blue, Color.White, Color.Red)
        } else {
            listOf(Color.Black, Color(0xFF23005F), Color(0xFF0050E6), Color(0xFF00E6B9), Color.Yellow, Color.White)
        }
    }
    return when (type) {
        TerrainAnalysisType.ASPECT -> listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
        TerrainAnalysisType.CURVATURE,
        TerrainAnalysisType.LOCAL_RELIEF_MODEL,
        TerrainAnalysisType.EROSION_SIMULATION,
        -> listOf(Color(0xFF1952CD), Color(0xFFE1E1DA), Color(0xFFDC2A26))
        TerrainAnalysisType.SKY_VIEW_FACTOR -> listOf(Color(0xFF0F1630), Color(0xFF225CA0), Color(0xFF46BECD), Color(0xFFFAFAE1))
        TerrainAnalysisType.POSITIVE_OPENNESS,
        TerrainAnalysisType.NEGATIVE_OPENNESS,
        -> listOf(Color(0xFF1E1232), Color(0xFF6E2D87), Color(0xFFEB7337), Color(0xFFFFF4B4))
        TerrainAnalysisType.DEPRESSION_DEPTH,
        TerrainAnalysisType.ANCIENT_STREAM_LIKELIHOOD,
        -> listOf(Color(0xFF141C3A), Color(0xFF2878BE), Color(0xFFFFC107), Color(0xFFDC2D2D))
        TerrainAnalysisType.MULTI_HILLSHADE -> listOf(Color(0xFF232323), Color(0xFF888888), Color(0xFFFAFAFA))
        else -> listOf(Color(0xFF141C3A), Color(0xFF195E91), Color(0xFF23AAA0), Color(0xFFD5D25A), Color(0xFFFAF5DC))
    }
}

@Composable
private fun SliderControl(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(valueLabel, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps)
    }
}

private fun format(value: Float): String = when {
    abs(value) >= 1000f -> "%.0f".format(value)
    abs(value) >= 10f -> "%.2f".format(value)
    else -> "%.4f".format(value)
}
