package com.strobingn.findit.ui.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.data.model.SearchGrid
import com.strobingn.findit.ui.map.HuntMapCanvas
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridPlannerScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val grids by repository.grids.collectAsStateWithLifecycle()
  val finds by repository.finds.collectAsStateWithLifecycle()
  val team by repository.team.collectAsStateWithLifecycle()
  val settings by repository.settings.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  var name by remember { mutableStateOf("Beach lane A") }
  var swLat by remember { mutableStateOf("39.8273") }
  var swLng by remember { mutableStateOf("-98.5805") }
  var neLat by remember { mutableStateOf("39.8293") }
  var neLng by remember { mutableStateOf("-98.5785") }
  var cellM by remember { mutableStateOf("5") }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Search grid planner") },
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
        .padding(16.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Text(
        "Lay a grid over fields or beaches, then mark covered cells as you swing.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Card(Modifier.fillMaxWidth().height(180.dp)) {
        HuntMapCanvas(finds, grids, team, settings.showTeamOnMap, Modifier.fillMaxSize())
      }
      OutlinedTextField(name, { name = it }, label = { Text("Grid name") }, modifier = Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          swLat,
          { swLat = it },
          label = { Text("SW lat") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          swLng,
          { swLng = it },
          label = { Text("SW lng") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f),
        )
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          neLat,
          { neLat = it },
          label = { Text("NE lat") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          neLng,
          { neLng = it },
          label = { Text("NE lng") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f),
        )
      }
      OutlinedTextField(
        cellM,
        { cellM = it },
        label = { Text("Cell size (m)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
      )
      Button(
        onClick = {
          val grid =
            SearchGrid(
              name = name.ifBlank { "Grid" },
              sw = GeoPoint(swLat.toDoubleOrNull() ?: 0.0, swLng.toDoubleOrNull() ?: 0.0),
              ne = GeoPoint(neLat.toDoubleOrNull() ?: 0.0, neLng.toDoubleOrNull() ?: 0.0),
              cellSizeMeters = cellM.toDoubleOrNull() ?: 5.0,
            )
          scope.launch { repository.upsertGrid(grid) }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Create / update grid")
      }
      grids.forEach { g ->
        ListItem(
          headlineContent = { Text(g.name) },
          supportingContent = {
            Text(
              "${g.rowCount()}×${g.colCount()} cells · ${g.cellSizeMeters} m · covered ${g.coveredCellIds.size}",
            )
          },
          trailingContent = {
            Button(
              onClick = {
                // mark first free cell as covered for demo wiring
                val r = g.coveredCellIds.size / g.colCount().coerceAtLeast(1)
                val c = g.coveredCellIds.size % g.colCount().coerceAtLeast(1)
                scope.launch { repository.markGridCell(g.id, g.cellId(r, c)) }
              },
            ) {
              Text("Mark next")
            }
          },
        )
      }
    }
  }
}
