package com.strobingn.findit.ui.historical

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoricalMapsScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val overlays by repository.overlays.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Historical & relief layers") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize()) {
      Text(
        "Toggle layers to plan digs over vanished houses, roads, and foundations. " +
          "Tile downloads cache offline when a source URL is available.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
      )
      LazyColumn {
        items(overlays, key = { it.id }) { layer ->
          ListItem(
            headlineContent = { Text(layer.name) },
            supportingContent = { Text("${layer.kind} · ${layer.description}") },
            trailingContent = {
              Switch(
                checked = layer.enabled,
                onCheckedChange = { on ->
                  scope.launch { repository.setOverlayEnabled(layer.id, on) }
                },
              )
            },
          )
        }
      }
    }
  }
}
