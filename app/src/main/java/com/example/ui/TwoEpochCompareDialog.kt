package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.analysis.TerrainFeatureCandidate
import com.example.analysis.TerrainFeatureType
import com.example.analysis.epoch.CandidateDelta
import com.example.analysis.epoch.DemAligner
import com.example.analysis.epoch.SurfaceChangeDetector
import com.example.data.ElevationGrid
import com.example.data.local.AnalyzedDatasetEntity
import com.example.data.local.SavedTarget
import com.example.data.local.parseTargets

/**
 * Two-epoch bare-earth surface change + candidate delta.
 * Uses analyzed dataset snapshots for dimensions/targets; optional live grids for residual map.
 */
@Composable
fun TwoEpochCompareDialog(
    datasets: List<AnalyzedDatasetEntity>,
    /** Optional: live A/B grids when both open in session; otherwise residual is skipped. */
    liveGridA: ElevationGrid? = null,
    liveGridB: ElevationGrid? = null,
    onDismiss: () -> Unit,
) {
    var keyA by remember { mutableStateOf(datasets.getOrNull(0)?.datasetKey) }
    var keyB by remember { mutableStateOf(datasets.getOrNull(1)?.datasetKey) }
    val dsA = datasets.firstOrNull { it.datasetKey == keyA }
    val dsB = datasets.firstOrNull { it.datasetKey == keyB }

    val candA = remember(dsA?.datasetKey) {
        dsA?.parseTargets()?.map { it.toTerrainCandidate() }.orEmpty()
    }
    val candB = remember(dsB?.datasetKey) {
        dsB?.parseTargets()?.map { it.toTerrainCandidate() }.orEmpty()
    }
    val delta = remember(candA, candB) {
        if (candA.isEmpty() && candB.isEmpty()) null
        else CandidateDelta.compare(candA, candB)
    }

    val surface = remember(liveGridA, liveGridB) {
        val a = liveGridA
        val b = liveGridB
        if (a == null || b == null) null
        else {
            val align = DemAligner.alignBToA(a, b)
            val aligned = align.alignedB ?: return@remember null to align
            SurfaceChangeDetector.detect(a, aligned) to align
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Two-epoch change") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.testTag("two_epoch_dialog"),
            ) {
                Text(
                    "Bare-earth surface Δ and candidate appeared/disappeared/score-changed. " +
                        "Relative Z only — LiDAR does not prove buried metal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EpochPicker("Epoch A (reference)", datasets, keyA) { keyA = it }
                EpochPicker("Epoch B (compare)", datasets, keyB) { keyB = it }

                when {
                    dsA == null || dsB == null -> Text("Pick two analyzed datasets.")
                    dsA.datasetKey == dsB.datasetKey -> Text(
                        "Pick two different epochs.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {
                        Text(
                            "A: ${dsA.displayName} · ${dsA.width}x${dsA.height}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "B: ${dsB.displayName} · ${dsB.width}x${dsB.height}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        surface?.let { (change, align) ->
                            Text(
                                align.note,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("two_epoch_align_note"),
                            )
                            if (change != null) {
                                Text(
                                    change.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.testTag("two_epoch_surface_note"),
                                )
                                Text(
                                    "Top zones: ${change.zones.take(5).joinToString { z ->
                                        "#${z.id} |Δ|=${"%.2f".format(z.meanAbsDeltaMeters)}m @ ${z.xPercent.toInt()}%,${z.yPercent.toInt()}%"
                                    }.ifBlank { "none above threshold" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        } ?: Text(
                            "Surface residual map needs two live elevation grids open in session. " +
                                "Candidate delta still works from saved snapshots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        delta?.let { d ->
                            Text(
                                d.note,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("two_epoch_delta_note"),
                            )
                            Text(
                                d.honestyLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .testTag("two_epoch_delta_list"),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(d.items.take(40)) { item ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainer,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.padding(8.dp)) {
                                            Text(
                                                "${item.kind.name} · ${item.typeLabel}",
                                                fontWeight = FontWeight.SemiBold,
                                                style = MaterialTheme.typography.labelMedium,
                                            )
                                            Text(
                                                "xy ${item.xPercent.toInt()}%,${item.yPercent.toInt()}% · ${item.note}",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                                }
                            }
                        } ?: Text(
                            "No candidates on either snapshot — run local analysis and save datasets.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("two_epoch_close")) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun EpochPicker(
    label: String,
    datasets: List<AnalyzedDatasetEntity>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = datasets.firstOrNull { it.datasetKey == selectedKey }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true },
            ) {
                Row(
                    Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        selected?.displayName ?: "Select…",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                datasets.forEach { ds ->
                    DropdownMenuItem(
                        text = { Text(ds.displayName.take(48)) },
                        onClick = {
                            onSelect(ds.datasetKey)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

private fun SavedTarget.toTerrainCandidate(): TerrainFeatureCandidate {
    val type = TerrainFeatureType.entries.firstOrNull { it.name == this.type.name }
        ?: when (this.type) {
            com.example.analysis.MetalDetectingTargetType.FOUNDATION -> TerrainFeatureType.FOUNDATION
            com.example.analysis.MetalDetectingTargetType.ROAD_TRAIL -> TerrainFeatureType.ROAD_TRAIL
            com.example.analysis.MetalDetectingTargetType.CELLAR_HOLE -> TerrainFeatureType.CELLAR_HOLE
            com.example.analysis.MetalDetectingTargetType.STONE_WALL -> TerrainFeatureType.STONE_WALL
            com.example.analysis.MetalDetectingTargetType.OLD_HOMESITE -> TerrainFeatureType.OLD_HOMESITE
            else -> TerrainFeatureType.DIG_RECOMMENDATION
        }
    return TerrainFeatureCandidate(
        id = "saved-${type.name}-${xPercent}-${yPercent}",
        type = type,
        xPercent = xPercent,
        yPercent = yPercent,
        score = score,
        radiusMeters = radiusMeters,
        evidence = evidence,
    )
}
