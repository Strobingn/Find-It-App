package com.strobingn.findit.ui.team

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.team.TeamVisibility
import com.strobingn.findit.ui.map.HuntMapCanvas

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeamVisibilityScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val team by repository.team.collectAsStateWithLifecycle()
  val finds by repository.finds.collectAsStateWithLifecycle()
  val grids by repository.grids.collectAsStateWithLifecycle()
  val settings by repository.settings.collectAsStateWithLifecycle()
  val edges = remember(team) { TeamVisibility.pairwise(team) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Team visibility") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
      Text(
        "See who can see whom on open ground. Terrain-aware LOS will plug into the viewshed engine next.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Card(Modifier.fillMaxWidth().height(200.dp).padding(vertical = 12.dp)) {
        HuntMapCanvas(finds, grids, team, showTeam = true, modifier = Modifier.fillMaxSize())
      }
      LazyColumn {
        items(edges) { e ->
          ListItem(
            headlineContent = {
              Text(if (e.likelyVisible) "Likely visible" else "Probably blocked / far")
            },
            supportingContent = {
              Text("${e.note} · bearing ${e.bearingDeg.toInt()}°")
            },
          )
        }
        items(team) { m ->
          ListItem(
            headlineContent = { Text(m.name) },
            supportingContent = {
              Text("%.5f, %.5f · eye ${m.observerHeightM} m".format(m.location.lat, m.location.lng))
            },
          )
        }
      }
    }
  }
}
