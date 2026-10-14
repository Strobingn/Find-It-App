package com.example.gps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.gps.GpsSettings
import com.example.gps.ntrip.NtripMountpoint
import com.example.gps.ntrip.NtripPresets
import com.example.gps.source.BondedReceiver

/**
 * External RTK receiver + NTRIP settings. Credentials are entered by the user (NYSNet accounts
 * are free at cors.dot.ny.gov); nothing is bundled.
 */
@Composable
fun GpsSettingsCard(
    initial: GpsSettings,
    bondedReceivers: () -> List<BondedReceiver>,
    onRequestBluetoothPermission: () -> Unit,
    onFetchMountpoints: (GpsSettings, (Result<List<NtripMountpoint>>) -> Unit) -> Unit,
    onSave: (GpsSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var s by remember(initial) { mutableStateOf(initial) }
    var receivers by remember { mutableStateOf(bondedReceivers()) }
    var mountpoints by remember { mutableStateOf<List<NtripMountpoint>>(emptyList()) }
    var mountMessage by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val preset = NtripPresets.byId(s.presetId)
    Column(modifier.testTag("gps_settings_card"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("External GNSS/RTK receiver (Bluetooth NMEA)", Modifier.weight(1f))
            Switch(checked = s.useExternalReceiver, onCheckedChange = { s = s.copy(useExternalReceiver = it); saved = false })
        }
        Text(
            "Or run Lefebure NTRIP / SW Maps / your receiver app with Android mock location — this app " +
                "uses mock fixes automatically and labels them.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (s.useExternalReceiver) {
            if (receivers.isEmpty()) {
                OutlinedButton(onClick = { onRequestBluetoothPermission(); receivers = bondedReceivers() }) {
                    Text("Find paired receivers")
                }
            }
            receivers.forEach { r ->
                FilterChip(
                    selected = r.address == s.receiverAddress,
                    onClick = { s = s.copy(receiverAddress = r.address, receiverName = r.name); saved = false },
                    label = { Text("${r.name} · ${r.address}") },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NTRIP corrections → receiver", Modifier.weight(1f))
                Switch(checked = s.ntripEnabled, onCheckedChange = { s = s.copy(ntripEnabled = it); saved = false })
            }
            if (s.ntripEnabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NtripPresets.ALL.forEach { p ->
                        FilterChip(
                            selected = p.id == s.presetId,
                            onClick = {
                                s = if (p.host.isNotBlank()) s.copy(presetId = p.id, host = p.host, port = p.port) else s.copy(presetId = p.id)
                                saved = false
                            },
                            label = { Text(if (p.id == "nysnet") "NYSNet" else p.label) },
                        )
                    }
                }
                Text(preset.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(s.host, { s = s.copy(host = it); saved = false }, label = { Text("Caster host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    s.port.toString(), { v -> v.toIntOrNull()?.let { s = s.copy(port = it); saved = false } },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(s.username, { s = s.copy(username = it); saved = false }, label = { Text("NTRIP user name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("ntrip_user"))
                OutlinedTextField(
                    s.password, { s = s.copy(password = it); saved = false }, label = { Text("NTRIP password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(s.mountpoint, { s = s.copy(mountpoint = it); saved = false }, label = { Text("Mountpoint") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    mountMessage = "Fetching…"
                    onFetchMountpoints(s) { result ->
                        result.onSuccess { mountpoints = it; mountMessage = if (it.isEmpty()) "No mountpoints listed" else null }
                            .onFailure { mountMessage = "Could not fetch: ${it.message}" }
                    }
                }) { Text("Fetch mountpoints") }
                mountMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                mountpoints.take(30).forEach { m ->
                    FilterChip(
                        selected = m.name == s.mountpoint,
                        onClick = { s = s.copy(mountpoint = m.name); saved = false },
                        label = { Text("${m.name} · ${m.format}${if (m.requiresGga) " · GGA" else ""}") },
                    )
                }
            }
        }
        OutlinedButton(onClick = { onSave(s); saved = true }, modifier = Modifier.testTag("gps_settings_save")) {
            Text(if (saved) "Saved" else "Save GPS settings")
        }
    }
}
