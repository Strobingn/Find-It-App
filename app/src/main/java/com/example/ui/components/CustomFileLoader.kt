package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.DemGenerator
import com.example.data.ElevationGrid

private fun getFileName(context: android.content.Context, uri: Uri): String {
    var result: String? = null
    if (uri.scheme == "content") {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    result = cursor.getString(index)
                }
            }
        } finally {
            cursor?.close()
        }
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result ?: "Unknown_File"
}

@Composable
fun CustomFileLoader(
    onCustomGridLoaded: (ElevationGrid) -> Unit,
    modifier: Modifier = Modifier
) {
    var textInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                val fileName = getFileName(context, it)
                val inputStream = context.contentResolver.openInputStream(it)
                if (inputStream != null) {
                    val grid = DemGenerator.parseFromStream(fileName, inputStream)
                    if (grid != null) {
                        onCustomGridLoaded(grid)
                        successMessage = "File '$fileName' loaded successfully! ${grid.width}x${grid.height} grid processed."
                        errorMessage = null
                    } else {
                        errorMessage = "Failed to parse '$fileName'. Verify it's a valid LAS, ASC, XYZ, or direct elevation grid text file."
                        successMessage = null
                    }
                } else {
                    errorMessage = "Could not open selected file."
                    successMessage = null
                }
            } catch (e: Exception) {
                errorMessage = "Error reading file: ${e.localizedMessage}"
                successMessage = null
            }
        }
    }

    val sampleCellarGrid = """
        12 12 12 12 12 12 12 12 12 12
        12 12 12 12 12 12 12 12 12 12
        12 12  9  9  9  9  9  9 12 12
        12 12  9  9  9  9  9  9 12 12
        12 12  9  9 14 14  9  9 12 12
        12 12  9  9 14 14  9  9 12 12
        12 12  9  9  9  9  9  9 12 12
        12 12  9  9  9  9  9  9 12 12
        12 12 12 12 12 12 12 12 12 12
        12 12 12 12 12 12 12 12 12 12
    """.trimIndent()

    val sampleFortGrid = """
        10 10 10 10 10 10 10 10 10 10
        10 10 10 14 14 14 10 10 10 10
        10 10 14  7  7  7 14 10 10 10
        10 14  7  7  7  7  7 14 10 10
        10 14  7  7 12 12  7 14 10 10
        10 14  7  7 12 12  7 14 10 10
        10 14  7  7  7  7  7 14 10 10
        10 10 14  7  7  7 14 10 10 10
        10 10 10 14 14 14 10 10 10 10
        10 10 10 10 10 10 10 10 10 10
    """.trimIndent()

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141518))
            .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("custom_file_loader_container")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.UploadFile,
                contentDescription = null,
                tint = Color(0xFF29B6F6),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "IMPORT CUSTOM GROUND POINTS / LAYER",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Load custom LiDAR layers by pasting a space/comma-separated grid matrix of elevations (X x Y ground points). The hillshading engine will automatically process it.",
            color = Color.Gray,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        // --- File Chooser Button ---
        Button(
            onClick = {
                filePickerLauncher.launch("*/*")
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF00E676),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("choose_lidar_file_button")
        ) {
            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "UPLOAD LIDAR OR DEM FILE (.las, .asc, .xyz, .csv, .txt)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Divider-like helper
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFF2C2E35)))
            Text(
                text = " OR PASTE ELEVATION MATRIX ",
                color = Color.Gray,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Box(modifier = Modifier.weight(1f).height(1.dp).background(Color(0xFF2C2E35)))
        }

        Spacer(modifier = Modifier.height(6.dp))

        // --- Sample Quick Load Buttons ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Load Sample:",
                color = Color.LightGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(
                onClick = {
                    textInput = sampleCellarGrid
                    errorMessage = null
                    successMessage = "Sample Cellar grid pasted!"
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF29B6F6)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(text = "Cellar Hole", fontSize = 10.sp)
            }

            OutlinedButton(
                onClick = {
                    textInput = sampleFortGrid
                    errorMessage = null
                    successMessage = "Sample Fort grid pasted!"
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF29B6F6)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(text = "Earthen Fort", fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // --- Text Area Input ---
        OutlinedTextField(
            value = textInput,
            onValueChange = {
                textInput = it
                errorMessage = null
                successMessage = null
            },
            placeholder = {
                Text(
                    text = "10.1 10.5 11.2\n10.3 10.2 11.5\n9.8  9.5  11.8",
                    color = Color.DarkGray,
                    fontSize = 11.sp
                )
            },
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color.White
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF29B6F6),
                unfocusedBorderColor = Color(0xFF2C2E35),
                focusedContainerColor = Color(0xFF1E2026),
                unfocusedContainerColor = Color(0xFF1E2026)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .testTag("custom_grid_input"),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // --- Status Feedback messages ---
        errorMessage?.let {
            Text(
                text = "❌ $it",
                color = Color.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        successMessage?.let {
            Text(
                text = "✅ $it",
                color = Color.Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // --- Import Button ---
        Button(
            onClick = {
                if (textInput.isBlank()) {
                    errorMessage = "Please paste some elevation data first"
                    return@Button
                }
                val grid = DemGenerator.parseCustomGrid(textInput)
                if (grid != null) {
                    onCustomGridLoaded(grid)
                    successMessage = "LiDAR layer loaded successfully! ${grid.width}x${grid.height} grid."
                    errorMessage = null
                } else {
                    errorMessage = "Failed to parse coordinates. Check that dimensions are rectangular."
                    successMessage = null
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF29B6F6),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .testTag("parse_custom_grid_button")
        ) {
            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "COMPILE & HILLSHADE LAYER", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
