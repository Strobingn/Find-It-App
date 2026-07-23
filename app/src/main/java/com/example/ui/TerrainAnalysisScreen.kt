package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.analysis.TerrainAnalysisType
import kotlin.math.roundToInt

@Composable
fun TerrainAnalysisScreen(
    terrainViewModel: HillshadeViewModel,
    analysisViewModel: TerrainAnalysisViewModel,
    padding: PaddingValues,
) {
    val grid by terrainViewModel.elevationGrid.collectAsStateWithLifecycle()
    val terrainSummary by terrainViewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    val selectedType by analysisViewModel.selectedType.collectAsStateWithLifecycle()
    val options by analysisViewModel.options.collectAsStateWithLifecycle()
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
    val menuExpanded = remember { mutableStateOf(false) }

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
            Column {
                Text(
                    text = "Phase 1 · Terrain Analysis",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${TerrainAnalysisType.phaseOneEntries.size} core LiDAR products",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (cacheEntryCount > 0) {
                TextButton(onClick = analysisViewModel::clearCache) {
                    Text("Clear ($cacheEntryCount)")
                }
            }
        }

        Text(
            text = terrainSummary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${grid.width}×${grid.height} cells · ${"%.2f".format(grid.cellSizeMeters)} m/cell",
            style = MaterialTheme.typography.labelLarge,
            fontFamily = FontFamily.Monospace,
        )

        Column {
            OutlinedButton(
                onClick = { menuExpanded.value = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedType.title)
            }
            DropdownMenu(
                expanded = menuExpanded.value,
                onDismissRequest = { menuExpanded.value = false },
            ) {
                TerrainAnalysisType.phaseOneEntries.forEach { type ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(type.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    type.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            analysisViewModel.selectType(type)
                            analysisViewModel.runAnalysis(grid)
                            menuExpanded.value = false
                        },
                    )
                }
            }
        }

        Text(text = selectedType.description, style = MaterialTheme.typography.bodyMedium)

        AnalysisParameterControls(
            selectedType = selectedType,
            localRadius = options.localRadiusMeters,
            horizonRadius = options.horizonRadiusMeters,
            directionCount = options.directionCount,
            onLocalRadiusChanged = analysisViewModel::updateLocalRadius,
            onHorizonRadiusChanged = analysisViewModel::updateHorizonRadius,
            onDirectionCountChanged = analysisViewModel::updateDirectionCount,
        )

        if (isRunning) {
            OutlinedButton(
                onClick = analysisViewModel::cancelAnalysis,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 10.dp),
                    strokeWidth = 2.dp,
                )
                Text("Cancel ${selectedType.title}")
            }
        } else {
            Button(
                onClick = { analysisViewModel.runAnalysis(grid) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Run ${selectedType.title}")
            }
        }

        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lastResultWasCached) {
            Text(
                text = "Cached result · no recalculation required",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        bitmap?.let { rendered ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column {
                    Image(
                        bitmap = rendered.asImageBitmap(),
                        contentDescription = "${selectedType.title} result",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(rendered.width.toFloat() / rendered.height.coerceAtLeast(1)),
                        contentScale = ContentScale.Fit,
                    )
                    AnalysisLegend(selectedType)
                }
            }
        }

        layer?.let { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Layer statistics", fontWeight = FontWeight.Bold)
                    Text("Valid cells: ${result.validCellCount} (${format(result.coveragePercent)}%)")
                    Text("Minimum: ${format(result.minimum)} ${result.type.unit}")
                    Text("Mean: ${format(result.mean)} ${result.type.unit}")
                    Text("Standard deviation: ${format(result.standardDeviation)} ${result.type.unit}")
                    Text("95th percentile: ${format(result.percentile95)} ${result.type.unit}")
                    Text("Maximum: ${format(result.maximum)} ${result.type.unit}")
                    HorizontalDivider()
                    Text(result.summary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = analysisViewModel::exportCurrentPng,
                    enabled = !isExporting,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (isExporting) "Saving…" else "Save PNG")
                }
                Button(
                    onClick = { analysisViewModel.requestAiInterpretation(terrainSummary) },
                    enabled = !isAiRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isAiRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (isAiRunning) "Analyzing…" else "ChatGPT")
                }
            }
        }

        exportStatus?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        aiInterpretation?.let { interpretation ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("ChatGPT interpretation", fontWeight = FontWeight.Bold)
                    Text(interpretation, style = MaterialTheme.typography.bodyMedium)
                }
            }
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
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Analysis parameters", fontWeight = FontWeight.Bold)
            if (selectedType.usesLocalRadius) {
                SliderControl(
                    title = "Local radius",
                    valueLabel = "${localRadius.roundToInt()} m",
                    value = localRadius,
                    valueRange = 1f..100f,
                    onValueChange = onLocalRadiusChanged,
                )
            }
            if (selectedType.usesHorizon) {
                SliderControl(
                    title = "Horizon radius",
                    valueLabel = "${horizonRadius.roundToInt()} m",
                    value = horizonRadius,
                    valueRange = 5f..250f,
                    onValueChange = onHorizonRadiusChanged,
                )
                SliderControl(
                    title = "Directions",
                    valueLabel = directionCount.toString(),
                    value = directionCount.toFloat(),
                    valueRange = 8f..24f,
                    steps = 15,
                    onValueChange = { onDirectionCountChanged(it.roundToInt()) },
                )
            }
            Text(
                text = "Tap Run after changing a parameter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnalysisLegend(type: TerrainAnalysisType) {
    val labels = when {
        type == TerrainAnalysisType.ASPECT -> "N · E · S · W · flat cells shown neutral"
        type.diverging -> "Negative / concave  ←  neutral  →  positive / convex"
        type == TerrainAnalysisType.MULTI_HILLSHADE -> "Shadow  ←  illumination  →  bright"
        else -> "Low  ←  ${type.unit}  →  high"
    }
    Text(
        text = labels,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title)
            Text(valueLabel, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

private fun format(value: Float): String = when {
    kotlin.math.abs(value) >= 1000f -> "%.0f".format(value)
    kotlin.math.abs(value) >= 10f -> "%.2f".format(value)
    else -> "%.4f".format(value)
}
