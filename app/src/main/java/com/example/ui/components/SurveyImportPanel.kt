package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.import.GpxParser
import com.example.data.import.KmlParser
import com.example.data.import.SurveyImportResult
import com.example.data.import.detectFileTypeByExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SurveyImportPanel(
    onSurveyImported: (SurveyImportResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isWorking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        
        val name = displayName(context, uri)
        val fileType = detectFileTypeByExtension(name)
        
        if (fileType != "gpx" && fileType != "kml") {
            isError = true
            message = "Please select a GPX or KML file"
            return@rememberLauncherForActivityResult
        }
        
        isWorking = true
        message = "Reading $name…"
        
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        when (fileType) {
                            "gpx" -> GpxParser.parse(input)
                            "kml" -> KmlParser.parse(input)
                            else -> SurveyImportResult(errors = listOf("Unsupported file type"))
                        }
                    }
                }.getOrNull() ?: SurveyImportResult(errors = listOf("Failed to parse file"))
            }
            
            withContext(Dispatchers.Main.immediate) {
                isWorking = false
                
                if (result.errors.isNotEmpty()) {
                    isError = true
                    message = result.errors.joinToString("; ")
                } else {
                    isError = false
                    onSurveyImported(result)
                    
                    val summary = buildString {
                        append("Imported ")
                        append(result.waypoints.size).append(" waypoints")
                        if (result.tracks.isNotEmpty()) {
                            append(", ").append(result.tracks.size).append(" tracks")
                        }
                        if (result.boundaries.isNotEmpty()) {
                            append(", ").append(result.boundaries.size).append(" boundaries")
                        }
                        result.bounds?.let { bounds ->
                            append("\nBounds: ${"%.6f".format(bounds.minLat)},${"%.6f".format(bounds.minLon)} to ${"%.6f".format(bounds.maxLat)},${"%.6f".format(bounds.maxLon)}")
                        }
                    }
                    message = summary
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Import Survey Data", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Load GPX or KML files containing waypoints, tracks, or survey boundaries. Imported points will be available for field reference.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { picker.launch(arrayOf("application/gpx+xml", "application/vnd.google-earth.kml+xml", "application/octet-stream")) },
                    enabled = !isWorking,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("import_survey_file_button"),
                ) {
                    androidx.compose.material3.Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose GPX/KML file")
                }

            }
        }

        if (isWorking) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        
        message?.let {
            Text(
                it,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "survey-file"
}
