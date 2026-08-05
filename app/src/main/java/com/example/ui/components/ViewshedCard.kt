package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.analysis.TerrainViewshed
import java.util.Locale

/**
 * Compact floating card for viewshed status. The green/blocked mask lives on the terrain
 * canvas — this card never expands into a full-screen sheet that covers the map.
 */
@Composable
fun ViewshedCard(
    viewshed: TerrainViewshed?,
    isComputing: Boolean,
    cellSizeMeters: Float,
    onClear: () -> Unit,
    onDismiss: () -> Unit = onClear,
    modifier: Modifier = Modifier,
) {
    if (viewshed == null && !isComputing) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        ),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        modifier = modifier
            .widthIn(max = 260.dp)
            .testTag("viewshed_card"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (isComputing) "Computing viewshed…" else "Viewshed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (isComputing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Line-of-sight on the terrain grid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (viewshed != null) {
                val visibleArea = viewshed.visibleCells * cellSizeMeters * cellSizeMeters
                Text(
                    "${(viewshed.visibilityRatio * 100f).toInt()}% of grid visible · ${areaText(visibleArea)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Green = visible · dark = blocked · blue = observer",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("viewshed_clear_button"),
                    ) { Text("Clear") }
                    TextButton(onClick = onDismiss) { Text("Hide") }
                }
            }
        }
    }
}

private fun areaText(areaSquareMeters: Float): String =
    if (areaSquareMeters >= 1_000_000f) {
        String.format(Locale.US, "%.2f km²", areaSquareMeters / 1_000_000f)
    } else if (areaSquareMeters >= 10_000f) {
        String.format(Locale.US, "%.1f ha", areaSquareMeters / 10_000f)
    } else {
        "${areaSquareMeters.toInt()} m²"
    }
