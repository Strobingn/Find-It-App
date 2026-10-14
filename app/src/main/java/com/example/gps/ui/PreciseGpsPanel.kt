package com.example.gps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.gps.FixQuality
import com.example.gps.FixSource
import com.example.gps.GnssFix
import com.example.gps.PreciseGpsState
import java.util.Locale

internal fun sourceLabel(fix: GnssFix?, state: PreciseGpsState): String = when (fix?.source) {
    FixSource.PHONE -> "Phone GNSS"
    FixSource.EXTERNAL_RECEIVER -> "External receiver"
    FixSource.MOCK_PROVIDER -> "Mock location (external app)"
    FixSource.AVERAGED -> "Averaged fix"
    null -> if (state.externalReceiver) "Waiting for receiver…" else "Waiting for GNSS…"
}

internal fun satelliteLine(state: PreciseGpsState): String {
    val fix = state.fix
    if (state.externalReceiver) {
        val sats = fix?.satellitesUsed?.let { "Sats $it" } ?: "Sats –"
        val rtk = when {
            state.rtk.connected -> "corrections ${state.rtk.bytesReceived / 1024} kB"
            state.rtk.message != null -> state.rtk.message
            else -> "no NTRIP"
        }
        return "$sats · $rtk"
    }
    val s = state.status
    val l5 = when {
        s.l5SatellitesUsed > 0 -> "L5 ✓ (${s.l5SatellitesUsed})"
        s.dualFrequencyCapable -> "L5 seen, not used"
        else -> "L1 only"
    }
    val raw = if (s.rawMeasurementsActive) " · raw GNSS" else ""
    return "Sats ${s.satellitesUsed}/${s.satellitesVisible} · $l5$raw"
}

/** Live accuracy, constellation and RTK status plus the "Precise fix" averaging control. */
@Composable
fun PreciseGpsPanel(
    state: PreciseGpsState,
    onPreciseFix: () -> Unit,
    onCancelPreciseFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.testTag("precise_gps_panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val fix = state.fix
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    fix?.accuracyLabel() ?: "No fix yet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (fix?.quality) {
                        FixQuality.RTK_FIXED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.testTag("gps_accuracy_label"),
                )
                Text(sourceLabel(fix, state), style = MaterialTheme.typography.labelMedium)
            }
            Text(satelliteLine(state), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gps_satellite_line"))
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            val avg = state.averaging
            if (avg != null && !avg.done) {
                LinearProgressIndicator(
                    progress = { (avg.elapsedMs / 60_000f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Averaging ${avg.elapsedMs / 1000} s · ${avg.acceptedFixes} fixes" +
                        (if (avg.rejectedFixes > 0) " (${avg.rejectedFixes} outliers dropped)" else "") +
                        (avg.estimate?.let { " · ${it.accuracyLabel()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onCancelPreciseFix, modifier = Modifier.testTag("precise_fix_cancel")) { Text("Cancel") }
            } else {
                state.preciseFix?.let { p ->
                    Text(
                        "Precise fix ${p.accuracyLabel()} · " +
                            String.format(Locale.US, "%.8f, %.8f", p.latitude, p.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("precise_fix_result"),
                    )
                }
                Button(onClick = onPreciseFix, modifier = Modifier.testTag("precise_fix_button")) {
                    Text("Precise fix (30–60 s)")
                }
            }
            if (fix?.source == FixSource.PHONE || fix?.source == FixSource.AVERAGED) {
                Text(
                    "Phone accuracy is Android's estimate, not a guarantee. True ≤1 m / cm needs an RTK receiver.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
