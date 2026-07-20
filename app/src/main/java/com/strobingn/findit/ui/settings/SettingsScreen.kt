package com.strobingn.findit.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
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
    Column(Modifier.padding(padding)) {
      ListItem(
        headlineContent = { Text("Low power mode") },
        supportingContent = {
          Text(
            "Long hunts: slower terrain refresh (${BatteryOptimizer.refreshIntervalMs(settings)} ms), " +
              "smaller DEM, lower AR fps",
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
        headlineContent = { Text("Cache tiles offline") },
        trailingContent = {
          Switch(
            checked = settings.cacheTiles,
            onCheckedChange = { on ->
              scope.launch { repository.updateSettings { it.copy(cacheTiles = on) } }
            },
          )
        },
      )
      ListItem(
        headlineContent = { Text("Imperial units") },
        trailingContent = {
          Switch(
            checked = settings.preferImperial,
            onCheckedChange = { on ->
              scope.launch { repository.updateSettings { it.copy(preferImperial = on) } }
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
          Text("%.1f KB under app files".format(cacheBytes / 1024.0), modifier = Modifier.padding(top = 2.dp))
        },
      )
    }
  }
}
