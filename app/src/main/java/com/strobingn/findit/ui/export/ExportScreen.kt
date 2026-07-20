package com.strobingn.findit.ui.export

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.export.HuntExporter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val finds by repository.finds.collectAsStateWithLifecycle()
  val grids by repository.grids.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var status by remember { mutableStateOf("Ready · ${finds.size} finds, ${grids.size} grids") }

  fun share(file: File, mime: String) {
    val uri =
      FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(Intent.createChooser(intent, "Share hunt export"))
  }

  fun export(ext: String, mime: String, body: String) {
    val dir = repository.offlineCache.exportsDir
    val file = File(dir, "findit-export-${System.currentTimeMillis()}.$ext")
    file.writeText(body)
    status = "Wrote ${file.name} (${file.length()} bytes)"
    share(file, mime)
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Export") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Share with hunting partners or archive digs — GPX, KML, CSV (with photo paths).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(status, style = MaterialTheme.typography.bodySmall)
      Button(
        onClick = { export("gpx", "application/gpx+xml", HuntExporter.toGpx(finds)) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Export GPX waypoints")
      }
      Button(
        onClick = { export("kml", "application/vnd.google-earth.kml+xml", HuntExporter.toKml(finds, grids)) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Export KML (finds + grids)")
      }
      Button(
        onClick = { export("csv", "text/csv", HuntExporter.toCsv(finds)) },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Export CSV")
      }
    }
  }
}
