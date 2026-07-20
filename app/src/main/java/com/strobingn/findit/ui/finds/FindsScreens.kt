package com.strobingn.findit.ui.finds

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.location.LocationTracker
import com.strobingn.findit.data.model.FindRecord
import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.data.model.MetalType
import com.strobingn.findit.data.photo.FindPhotoStore
import com.strobingn.findit.ui.common.rememberCameraPermissionState
import com.strobingn.findit.ui.common.rememberLocationPermissionState
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
  val settings by repository.settings.collectAsStateWithLifecycle()
  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text("Find log")
            settings.activeHuntSessionName?.let {
              Text(it, style = MaterialTheme.typography.bodySmall)
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          Button(onClick = onLog, modifier = Modifier.padding(end = 8.dp)) { Text("Log") }
        },
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
          trailingContent = {
            if (!find.photoUri.isNullOrBlank()) {
              AsyncImage(
                model = Uri.parse(find.photoUri),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
              )
            }
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
        find.photoUri?.let { uri ->
          AsyncImage(
            model = Uri.parse(uri),
            contentDescription = find.title,
            modifier =
              Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
          )
          Spacer(Modifier.height(12.dp))
        }
        DetailRow("Metal", find.metalType.name)
        DetailRow("Depth", find.depthInches?.let { "$it in" } ?: "—")
        DetailRow("Lat", "%.6f".format(find.location.lat))
        DetailRow("Lng", "%.6f".format(find.location.lng))
        find.location.elevM?.let { DetailRow("Elev", "%.1f m".format(it)) }
        DetailRow("Session", find.huntSessionId ?: "—")
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
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val tracker = remember { LocationTracker.get(context) }
  val photoStore = remember { FindPhotoStore(context) }
  val settings by repository.settings.collectAsStateWithLifecycle()
  val locPerm = rememberLocationPermissionState(requestOnLaunch = true)
  val camPerm = rememberCameraPermissionState()

  var title by remember { mutableStateOf("") }
  var notes by remember { mutableStateOf("") }
  var depth by remember { mutableStateOf("") }
  var lat by remember { mutableStateOf("") }
  var lng by remember { mutableStateOf("") }
  var elev by remember { mutableStateOf("") }
  var metal by remember { mutableStateOf(MetalType.UNKNOWN) }
  var photoUri by remember { mutableStateOf<String?>(null) }
  var expanded by remember { mutableStateOf(false) }
  var gpsStatus by remember { mutableStateOf("Waiting for GPS…") }
  var pendingCapture by remember { mutableStateOf<Uri?>(null) }

  val takePicture =
    rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
      if (ok) {
        photoUri = pendingCapture?.toString()
      } else {
        pendingCapture = null
      }
    }

  fun applyFix(fix: LocationTracker.LocationFix) {
    lat = "%.6f".format(fix.point.lat)
    lng = "%.6f".format(fix.point.lng)
    elev = fix.point.elevM?.let { "%.1f".format(it) }.orEmpty()
    val acc = fix.accuracyM?.let { " ±${it.toInt()} m" }.orEmpty()
    gpsStatus = "GPS locked$acc"
    scope.launch { repository.updateSelfLocation(fix.point) }
  }

  LaunchedEffect(locPerm.granted, settings.lowPowerMode) {
    if (!locPerm.granted) {
      gpsStatus = "Location permission needed"
      return@LaunchedEffect
    }
    tracker.lastKnownOrNull()?.let { applyFix(it) }
    tracker.updates(lowPower = settings.lowPowerMode).collect { applyFix(it) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Log find") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          IconButton(
            onClick = {
              if (!locPerm.granted) {
                locPerm.request()
                return@IconButton
              }
              scope.launch {
                tracker.lastKnownOrNull()?.let { applyFix(it) }
                  ?: run { gpsStatus = "No fix yet — move outdoors" }
              }
            },
          ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Use GPS")
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
      Text(gpsStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
      settings.activeHuntSessionName?.let {
        Text("Session: $it", style = MaterialTheme.typography.labelLarge)
      }
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
        value = elev,
        onValueChange = { elev = it },
        label = { Text("Elev m (optional)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
      )

      photoUri?.let { uri ->
        AsyncImage(
          model = Uri.parse(uri),
          contentDescription = "Find photo",
          modifier =
            Modifier
              .fillMaxWidth()
              .height(180.dp)
              .clip(RoundedCornerShape(12.dp)),
          contentScale = ContentScale.Crop,
        )
      }
      OutlinedButton(
        onClick = {
          if (!camPerm.granted) {
            camPerm.request()
            return@OutlinedButton
          }
          val (uri, _) = photoStore.createCaptureUri()
          pendingCapture = uri
          takePicture.launch(uri)
        },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(if (photoUri == null) "Take photo" else "Retake photo")
      }

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
              photoUri = photoUri,
              location =
                GeoPoint(
                  lat = lat.toDoubleOrNull() ?: 0.0,
                  lng = lng.toDoubleOrNull() ?: 0.0,
                  elevM = elev.toDoubleOrNull(),
                ),
              huntSessionId = settings.activeHuntSessionId,
            )
          scope.launch {
            repository.upsertFind(record)
            onSaved()
          }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = lat.toDoubleOrNull() != null && lng.toDoubleOrNull() != null,
      ) {
        Text("Save find")
      }
    }
  }
}
