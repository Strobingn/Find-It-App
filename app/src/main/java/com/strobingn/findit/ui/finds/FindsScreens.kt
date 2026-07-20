package com.strobingn.findit.ui.finds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.model.FindRecord
import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.data.model.MetalType
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindListScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
  onOpen: (String) -> Unit,
  onLog: () -> Unit,
) {
  val finds by repository.finds.collectAsStateWithLifecycle()
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Find log") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = { Button(onClick = onLog, modifier = Modifier.padding(end = 8.dp)) { Text("Log") } },
      )
    },
  ) { padding ->
    LazyColumn(Modifier.fillMaxSize().padding(padding)) {
      items(finds, key = { it.id }) { find ->
        ListItem(
          headlineContent = { Text(find.title, fontWeight = FontWeight.SemiBold) },
          supportingContent = {
            Text(
              "${find.metalType} · ${find.depthInches ?: "?"} in · ${
                SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(find.detectedAtEpochMs))
              }",
            )
          },
          modifier = Modifier.clickable { onOpen(find.id) },
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindDetailScreen(
  repository: HuntRepository,
  findId: String,
  onBack: () -> Unit,
) {
  val finds by repository.finds.collectAsStateWithLifecycle()
  val find = finds.find { it.id == findId }
  val scope = rememberCoroutineScope()
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(find?.title ?: "Find") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (find != null) {
            IconButton(
              onClick = {
                scope.launch {
                  repository.deleteFind(find.id)
                  onBack()
                }
              },
            ) {
              Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
          }
        },
      )
    },
  ) { padding ->
    if (find == null) {
      Text("Find not found", Modifier.padding(padding).padding(16.dp))
    } else {
      Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
        DetailRow("Metal", find.metalType.name)
        DetailRow("Depth", find.depthInches?.let { "$it in" } ?: "—")
        DetailRow("Lat", "%.6f".format(find.location.lat))
        DetailRow("Lng", "%.6f".format(find.location.lng))
        DetailRow("Photo", find.photoUri ?: "none")
        DetailRow("Notes", find.notes.ifBlank { "—" })
      }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String) {
  Column(Modifier.padding(vertical = 6.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(value, style = MaterialTheme.typography.bodyLarge)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogFindScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
  onSaved: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  var title by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("") }
  var depth by remember { mutableStateOf("") }
  var lat by remember { mutableStateOf("39.8283") }
  var lng by remember { mutableStateOf("-98.5795") }
  var metal by remember { mutableStateOf(MetalType.UNKNOWN) }
  var photo by remember { mutableStateOf("") }
  var expanded by remember { mutableStateOf(false) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Log find") },
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
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Title") },
        modifier = Modifier.fillMaxWidth(),
      )
      ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
          value = metal.name,
          onValueChange = {},
          readOnly = true,
          label = { Text("Metal type") },
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
          modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          MetalType.entries.forEach { type ->
            DropdownMenuItem(
              text = { Text(type.name) },
              onClick = {
                metal = type
                expanded = false
              },
            )
          }
        }
      }
      OutlinedTextField(
        value = depth,
        onValueChange = { depth = it },
        label = { Text("Depth (inches)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = lat,
          onValueChange = { lat = it },
          label = { Text("Lat") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
          value = lng,
          onValueChange = { lng = it },
          label = { Text("Lng") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
          modifier = Modifier.weight(1f),
        )
      }
      OutlinedTextField(
        value = photo,
        onValueChange = { photo = it },
        label = { Text("Photo URI (optional)") },
        modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
        value = notes,
        onValueChange = { notes = it },
        label = { Text("Notes") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(8.dp))
      Button(
        onClick = {
          val record =
            FindRecord(
              title = title.ifBlank { "Untitled find" },
              metalType = metal,
              depthInches = depth.toDoubleOrNull(),
              notes = notes,
              photoUri = photo.ifBlank { null },
              location =
                GeoPoint(
                  lat = lat.toDoubleOrNull() ?: 39.8283,
                  lng = lng.toDoubleOrNull() ?: -98.5795,
                ),
            )
          scope.launch {
            repository.upsertFind(record)
            onSaved()
          }
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Save find")
      }
    }
  }
}
