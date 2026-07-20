package com.strobingn.findit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.BuildConfig
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.backend.SharedBackendClient
import com.strobingn.findit.data.power.BatteryOptimizer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val settings by repository.settings.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val cacheBytes = repository.offlineCache.usageBytes()
  var urlDraft by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
  var backendStatus by remember { mutableStateOf("") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Settings") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier
        .padding(padding)
        .verticalScroll(rememberScrollState()),
    ) {
      Text(
        "Oracle cloud backend",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        "Same host as Viewshade — terrain analyze + elevation sample.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      ListItem(
        headlineContent = { Text("Use cloud backend") },
        supportingContent = { Text(if (settings.useCloudBackend) "On" else "Local-only demos") },
        trailingContent = {
          Switch(
            checked = settings.useCloudBackend,
            onCheckedChange = { on ->
              scope.launch { repository.updateSettings { it.copy(useCloudBackend = on) } }
            },
          )
        },
      )
      OutlinedTextField(
        value = urlDraft,
        onValueChange = { urlDraft = it },
        label = { Text("Backend URL") },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        singleLine = true,
      )
      Spacer(Modifier.height(8.dp))
      Column(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = {
            val url = SharedBackendClient.normalizeUrl(urlDraft)
            scope.launch {
              backendStatus = "Testing…"
              try {
                val ok = SharedBackendClient(url).health()
                backendStatus = if (ok) "Healthy: $url" else "Unhealthy"
                if (ok) {
                  urlDraft = url
                  repository.updateSettings { it.copy(backendUrl = url, useCloudBackend = true) }
                }
              } catch (e: Exception) {
                backendStatus = "Failed: ${e.message?.take(60)}"
              }
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Test backend")
        }
        OutlinedButton(
          onClick = {
            val url = SharedBackendClient.normalizeUrl(urlDraft)
            urlDraft = url
            scope.launch {
              repository.updateSettings { it.copy(backendUrl = url, useCloudBackend = true) }
              backendStatus = "Saved $url"
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Save URL")
        }
        OutlinedButton(
          onClick = {
            urlDraft = BuildConfig.DEFAULT_BACKEND_URL
            scope.launch {
              repository.updateSettings {
                it.copy(backendUrl = BuildConfig.DEFAULT_BACKEND_URL, useCloudBackend = true)
              }
              backendStatus = "Reset to default Oracle IP"
            }
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Reset to Oracle default")
        }
      }
      if (backendStatus.isNotBlank()) {
        Text(
          backendStatus,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(16.dp),
          color = MaterialTheme.colorScheme.tertiary,
        )
      }

      Spacer(Modifier.height(8.dp))
      Text(
        "Hunt session",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        settings.activeHuntSessionName?.let { "Active: $it" }
          ?: "No active session — finds won’t group",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
      )
      Spacer(Modifier.height(8.dp))
      if (settings.activeHuntSessionId == null) {
        Button(
          onClick = { scope.launch { repository.startHuntSession("") } },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
          Text("Start hunt session")
        }
      } else {
        OutlinedButton(
          onClick = { scope.launch { repository.endHuntSession() } },
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
          Text("End hunt session")
        }
      }

      Spacer(Modifier.height(16.dp))
      Text(
        "Map & power",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.primary,
      )
      ListItem(
        headlineContent = { Text("Follow me on map") },
        supportingContent = { Text("Center hunt map on live GPS (~150 m)") },
        trailingContent = {
          Switch(
            checked = settings.followMeOnMap,
            onCheckedChange = { on ->
              scope.launch { repository.updateSettings { it.copy(followMeOnMap = on) } }
            },
          )
        },
      )
      ListItem(
        headlineContent = { Text("Low power mode") },
        supportingContent = {
          Text(
            "Long hunts: slower GPS/terrain (${BatteryOptimizer.refreshIntervalMs(settings)} ms)",
          )
        },
        trailingContent = {
          Switch(
            checked = settings.lowPowerMode,
            onCheckedChange = { on ->
              scope.launch { repository.updateSettings { it.copy(lowPowerMode = on) } }
            },
          )
        },
      )
      ListItem(
        headlineContent = { Text("Show team on map") },
        trailingContent = {
          Switch(
            checked = settings.showTeamOnMap,
            onCheckedChange = { on ->
              scope.launch { repository.updateSettings { it.copy(showTeamOnMap = on) } }
            },
          )
        },
      )
      ListItem(
        headlineContent = { Text("Offline cache") },
        supportingContent = {
          Text("%.1f KB under app files".format(cacheBytes / 1024.0))
        },
      )
      Text(
        "com.strobingn.findit · v0.3.0 · ${BuildConfig.DEFAULT_BACKEND_URL}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
      )
    }
  }
}
