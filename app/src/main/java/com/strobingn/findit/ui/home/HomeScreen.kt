package com.strobingn.findit.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HistoryEdu
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.strobingn.findit.ArOverlay
import com.strobingn.findit.Export
import com.strobingn.findit.Finds
import com.strobingn.findit.GridPlanner
import com.strobingn.findit.HistoricalMaps
import com.strobingn.findit.LogFind
import com.strobingn.findit.Settings
import com.strobingn.findit.TeamVisibility
import com.strobingn.findit.TerrainTools
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.location.LocationTracker
import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.ui.common.rememberLocationPermissionState
import com.strobingn.findit.ui.map.HuntMapCanvas
import kotlinx.coroutines.launch

private data class Tool(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val key: NavKey,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  repository: HuntRepository,
  onNavigate: (NavKey) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val tracker = remember { LocationTracker.get(context) }
  val finds by repository.finds.collectAsStateWithLifecycle()
  val grids by repository.grids.collectAsStateWithLifecycle()
  val team by repository.team.collectAsStateWithLifecycle()
  val settings by repository.settings.collectAsStateWithLifecycle()
  val locPerm = rememberLocationPermissionState(requestOnLaunch = true)

  var myLocation by remember { mutableStateOf<GeoPoint?>(null) }
  var accuracyM by remember { mutableStateOf<Float?>(null) }
  var gpsLabel by remember { mutableStateOf("GPS off") }

  LaunchedEffect(locPerm.granted, settings.lowPowerMode) {
    if (!locPerm.granted) {
      gpsLabel = "Tap pin for location"
      return@LaunchedEffect
    }
    tracker.lastKnownOrNull()?.let { fix ->
      myLocation = fix.point
      accuracyM = fix.accuracyM
      gpsLabel = fix.accuracyM?.let { "GPS ±${it.toInt()} m" } ?: "GPS"
      repository.updateSelfLocation(fix.point)
    }
    tracker.updates(lowPower = settings.lowPowerMode).collect { fix ->
      myLocation = fix.point
      accuracyM = fix.accuracyM
      gpsLabel = fix.accuracyM?.let { "GPS ±${it.toInt()} m" } ?: "GPS"
      repository.updateSelfLocation(fix.point)
    }
  }

  val tools =
    listOf(
      Tool("Finds", "${finds.size} logged", Icons.AutoMirrored.Filled.List, Finds),
      Tool("Grid planner", "${grids.size} grids", Icons.Default.GridOn, GridPlanner),
      Tool("Terrain", "SVF · hillshade · pits", Icons.Default.Terrain, TerrainTools),
      Tool("Historical", "Topo · Sanborn · air", Icons.Default.HistoryEdu, HistoricalMaps),
      Tool("Team LOS", "${team.size} hunters", Icons.Default.Groups, TeamVisibility),
      Tool("AR overlay", "Live ground cues", Icons.Default.CameraAlt, ArOverlay),
      Tool("Export", "GPX · KML · CSV", Icons.Default.Share, Export),
      Tool(
        "Settings",
        if (settings.lowPowerMode) "Low power ON" else "Power · GPS · session",
        Icons.Default.Settings,
        Settings,
      ),
    )

  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Find It", fontWeight = FontWeight.Bold)
            Text(
              settings.activeHuntSessionName?.let { "Hunt: $it · $gpsLabel" }
                ?: "Metal detecting · $gpsLabel",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        actions = {
          IconButton(
            onClick = {
              if (!locPerm.granted) locPerm.request()
              else {
                scope.launch {
                  tracker.lastKnownOrNull()?.let {
                    myLocation = it.point
                    accuracyM = it.accuracyM
                    repository.updateSelfLocation(it.point)
                  }
                }
              }
            },
          ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Center GPS")
          }
        },
      )
    },
    floatingActionButton = {
      FloatingActionButton(onClick = { onNavigate(LogFind) }) {
        Icon(Icons.Default.Add, contentDescription = "Log find")
      }
    },
  ) { padding ->
    Column(
      Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 16.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
          Spacer(Modifier.size(8.dp))
          Text("Hunt map", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        FilterChip(
          selected = settings.followMeOnMap,
          onClick = {
            scope.launch {
              repository.updateSettings { it.copy(followMeOnMap = !it.followMeOnMap) }
            }
          },
          label = { Text(if (settings.followMeOnMap) "Follow me" else "Overview") },
        )
      }
      Spacer(Modifier.height(8.dp))
      Card(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        elevation = CardDefaults.cardElevation(2.dp),
      ) {
        HuntMapCanvas(
          finds = finds,
          grids = grids,
          team = team,
          showTeam = settings.showTeamOnMap,
          myLocation = myLocation,
          followMe = settings.followMeOnMap && myLocation != null,
          accuracyM = accuracyM,
          modifier = Modifier.fillMaxSize(),
        )
      }
      Spacer(Modifier.height(16.dp))
      Text("Field tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(8.dp))
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(tools) { tool ->
          Card(
            onClick = { onNavigate(tool.key) },
            modifier = Modifier.fillMaxWidth().height(100.dp),
          ) {
            Column(
              Modifier.padding(12.dp).fillMaxSize(),
              verticalArrangement = Arrangement.SpaceBetween,
            ) {
              Icon(tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
              Column {
                Text(tool.title, fontWeight = FontWeight.SemiBold)
                Text(
                  tool.subtitle,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }
    }
  }
}
