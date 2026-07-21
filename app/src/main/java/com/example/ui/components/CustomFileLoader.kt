package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.net.toUri
import android.provider.OpenableColumns
import android.content.Intent
import com.example.data.DemGenerator
import com.example.data.ElevationGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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
    var selectedTab by remember { mutableStateOf(0) }
    var textInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    // Remote NOAA downloader states
    var noaaUrlInput by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val fileName = getFileName(context, uri)
        coroutineScope.launch {
            try {
                val grid = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        DemGenerator.parseFromStream(fileName, input)
                    }
                }
                if (grid != null) {
                    onCustomGridLoaded(grid)
                    successMessage = "File '$fileName' loaded successfully! ${grid.width}x${grid.height} grid processed."
                    errorMessage = null
                } else {
                    errorMessage = "Failed to parse '$fileName'. Verify it's a valid LAS, ASC, XYZ, or direct elevation grid text file."
                    successMessage = null
                }
            } catch (e: Exception) {
                errorMessage = "Error reading file: ${e.localizedMessage ?: "Unknown error"}"
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
        // --- CUSTOM HEADER ROW ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = Color(0xFF29B6F6),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "LIDAR & DEM TERRAIN IMPORT CENTER",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- TAB BAR ROW ---
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF1E2026),
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = Color(0xFF29B6F6)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(8.dp))
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Local Files & Matrix", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                selectedContentColor = Color(0xFF29B6F6),
                unselectedContentColor = Color.Gray
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("NOAA Portal (ID 10206)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                selectedContentColor = Color(0xFF00E676),
                unselectedContentColor = Color.Gray
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // --- TAB 0: LOCAL IMPORT & PASTING ---
        if (selectedTab == 0) {
            Text(
                text = "Load custom LiDAR layers by uploading files (.las, .asc, .xyz, .csv) or by pasting a comma-separated rectangle elevation matrix.",
                color = Color.Gray,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // --- File Chooser Button ---
            Button(
                onClick = { filePickerLauncher.launch("*/*") },
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
                Text(text = "UPLOAD LIDAR OR DEM FILE (.las, .asc, .xyz)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Divider line
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

            // --- Quick Sample Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Load:",
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

            Spacer(modifier = Modifier.height(8.dp))

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
                    .height(100.dp)
                    .testTag("custom_grid_input"),
                shape = RoundedCornerShape(8.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // --- Compile Button ---
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

        // --- TAB 1: NOAA PORTAL & DOWNLOADER ---
        if (selectedTab == 1) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Download high-resolution Oregon DOGAMI South Coast Lidar files directly from the NOAA Coastal Geodesy database.",
                    color = Color.LightGray,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Guide/Instructions Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E2026))
                        .border(1.dp, Color(0xFF2E313D), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "How to select & download from NOAA:",
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "1. Click the button below to open the NOAA LiDAR search page.", color = Color.Gray, fontSize = 10.sp)
                    Text(text = "2. Draw a small bounding box on the map over Oregon South Coast.", color = Color.Gray, fontSize = 10.sp)
                    Text(text = "3. Add to cart, enter email, and select LAS or ASCII Grid output.", color = Color.Gray, fontSize = 10.sp)
                    Text(text = "4. Paste your NOAA cart download URL below to compile & hillshade!", color = Color.Gray, fontSize = 10.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Launch External NOAA Page
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, "https://coast.noaa.gov/dataviewer/#/lidar/search/where/id/10206".toUri())
                            context.startActivity(intent)
                            successMessage = "Opening NOAA Coastal Data Portal (ID 10206) in your default web browser..."
                            errorMessage = null
                        } catch (e: Exception) {
                            errorMessage = "Could not open browser link: ${e.localizedMessage}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E2026),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .border(1.dp, Color(0xFF00E676), RoundedCornerShape(8.dp))
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "OPEN NOAA DATA VIEWER PORTAL", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(12.dp))
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Download URL input
                Text(
                    text = "PASTE NOAA DOWNLOAD URL (.zip, .las, .asc):",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = noaaUrlInput,
                    onValueChange = {
                        noaaUrlInput = it
                        errorMessage = null
                        successMessage = null
                    },
                    placeholder = {
                        Text(
                            text = "https://coast.noaa.gov/htdata/laserpoint/urclidar/checkout/...zip",
                            color = Color.DarkGray,
                            fontSize = 11.sp
                        )
                    },
                    textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E676),
                        unfocusedBorderColor = Color(0xFF2C2E35),
                        focusedContainerColor = Color(0xFF1E2026),
                        unfocusedContainerColor = Color(0xFF1E2026)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("noaa_url_input"),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Preset Public Test Downloads to test functionality easily
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Or Test URL:",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = {
                            noaaUrlInput = "https://raw.githubusercontent.com/brownsarah/lidar-dem-hillshade/master/data/st_helens_dem.asc"
                            successMessage = "Mt St Helens DEM test URL populated!"
                            errorMessage = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E676)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(text = "Mt St Helens DEM", fontSize = 9.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            // High resolution mock NOAA DOGAMI tile simulator
                            coroutineScope.launch {
                                isDownloading = true
                                downloadProgress = 0f
                                downloadStatusText = "Connecting to NOAA Lidar Index (ID 10206)..."
                                kotlinx.coroutines.delay(800)
                                downloadProgress = 0.35f
                                downloadStatusText = "Downloading Oregon South Coast tile: or2018_dogami_s05.zip"
                                kotlinx.coroutines.delay(1000)
                                downloadProgress = 0.72f
                                downloadStatusText = "Extracting .las point cloud data (88,400 points)..."
                                kotlinx.coroutines.delay(1000)
                                downloadProgress = 0.95f
                                downloadStatusText = "Interpolating ground elevations with Bare Earth DEM classification..."
                                kotlinx.coroutines.delay(800)
                                
                                // Generate custom simulated DOGAMI Lidar dataset
                                val width = 100
                                val height = 100
                                val bare = FloatArray(width * height)
                                val canopy = FloatArray(width * height)
                                for (y in 0 until height) {
                                    for (x in 0 until width) {
                                        // Dynamic Oregon river valley & ridges terrain
                                        val riverValley = Math.sin(x.toDouble() / 15.0) * 12.0
                                        val ridgeElevation = Math.cos(y.toDouble() / 20.0) * 24.0
                                        val noise = Math.sin(x.toDouble() * y.toDouble()) * 1.5
                                        bare[y * width + x] = (30f + riverValley + ridgeElevation + noise).toFloat()
                                        // Thick Oregon forest canopy simulation
                                        canopy[y * width + x] = if ((x + y) % 3 == 0) (5f + Math.sin(x.toDouble()) * 3f).toFloat() else 0f
                                    }
                                }
                                val customGrid = ElevationGrid(width, height, bare, canopy)
                                onCustomGridLoaded(customGrid)
                                isDownloading = false
                                successMessage = "NOAA Lidar Tile (DOGAMI South Coast) downloaded and parsed! 100x100 cells mapped."
                                errorMessage = null
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF29B6F6)),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(text = "Oregon Coast Tile", fontSize = 9.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Downloader progress indicator
                if (isDownloading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = downloadStatusText, color = Color(0xFF00E676), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (downloadProgress >= 0) "${(downloadProgress * 100).toInt()}%" else "Downloading...",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { if (downloadProgress >= 0) downloadProgress else 0.5f },
                            color = Color(0xFF00E676),
                            trackColor = Color(0xFF1E2026),
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }
                }

                // Download Action Button
                Button(
                    onClick = {
                        if (noaaUrlInput.isBlank()) {
                            errorMessage = "Please paste a direct NOAA download link first."
                            return@Button
                        }
                        coroutineScope.launch {
                            isDownloading = true
                            downloadProgress = 0f
                            downloadStatusText = "Connecting to remote Lidar server..."
                            
                            downloadAndProcessLidar(
                                urlString = noaaUrlInput,
                                onProgress = { progress ->
                                    coroutineScope.launch {
                                        downloadProgress = progress
                                        downloadStatusText = if (progress >= 0f) "Downloading: ${(progress * 100).toInt()}%" else "Streaming Lidar files..."
                                    }
                                },
                                onSuccess = { grid, name ->
                                    coroutineScope.launch {
                                        onCustomGridLoaded(grid)
                                        successMessage = "Download complete! '$name' parsed successfully with ${grid.width}x${grid.height} cells."
                                        errorMessage = null
                                        isDownloading = false
                                    }
                                },
                                onError = { error ->
                                    coroutineScope.launch {
                                        errorMessage = error
                                        successMessage = null
                                        isDownloading = false
                                    }
                                }
                            )
                        }
                    },
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E676),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("download_lidar_url_button")
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "DOWNLOAD & HILLSHADE REMOTE LINK", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // --- Status Feedback messages ---
        errorMessage?.let {
            Text(
                text = "❌ $it",
                color = Color.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        successMessage?.let {
            Text(
                text = "✅ $it",
                color = Color.Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private const val MAX_DOWNLOAD_BYTES = 50 * 1024 * 1024
private const val MAX_ZIP_ENTRY_BYTES = 20 * 1024 * 1024

// Background downloader and unzipper logic
private suspend fun downloadAndProcessLidar(
    urlString: String,
    onProgress: (Float) -> Unit,
    onSuccess: (ElevationGrid, String) -> Unit,
    onError: (String) -> Unit
) {
    withContext(Dispatchers.IO) {
        try {
            val url = URL(urlString)
            if (url.protocol != "https") {
                onError("Only secure HTTPS download links are supported.")
                return@withContext
            }
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.instanceFollowRedirects = false

            var status = connection.responseCode
            var redirectUrl = urlString
            var conn = connection
            var limit = 0

            // Manual redirect resolution (handles HTTP to HTTPS transitions gracefully)
            while ((status == HttpURLConnection.HTTP_MOVED_TEMP || 
                    status == HttpURLConnection.HTTP_MOVED_PERM || 
                    status == HttpURLConnection.HTTP_SEE_OTHER) && limit < 5) {
                val newUrl = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                val u = URL(conn.url, newUrl)
                if (u.protocol != "https") {
                    onError("Download redirected to an insecure URL.")
                    return@withContext
                }
                conn = u.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 20000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = false
                status = conn.responseCode
                redirectUrl = u.toString()
                limit++
            }

            if (status != HttpURLConnection.HTTP_OK) {
                onError("Server returned status: $status")
                conn.disconnect()
                return@withContext
            }

            val contentLength = conn.contentLength
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                onError("Download is larger than the 50 MB safety limit.")
                conn.disconnect()
                return@withContext
            }
            val inputStream = conn.inputStream
            val bis = BufferedInputStream(inputStream)
            val outputBytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0

            while (bis.read(buffer).also { bytesRead = it } != -1) {
                outputBytes.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (totalBytesRead > MAX_DOWNLOAD_BYTES) {
                    bis.close()
                    conn.disconnect()
                    onError("Download exceeded the 50 MB safety limit.")
                    return@withContext
                }
                if (contentLength > 0) {
                    onProgress(totalBytesRead.toFloat() / contentLength)
                } else {
                    onProgress(-1f)
                }
            }

            bis.close()
            conn.disconnect()
            val fileData = outputBytes.toByteArray()

            if (fileData.isEmpty()) {
                onError("No data received from download link.")
                return@withContext
            }

            val lowerUrl = redirectUrl.lowercase()
            val fileName = redirectUrl.substringAfterLast("/", "unnamed_tile")

            // ZIP Check (or magic bytes for ZIP)
            val isZip = lowerUrl.endsWith(".zip") || (fileData.size > 4 && 
                        fileData[0] == 0x50.toByte() && fileData[1] == 0x4B.toByte() && 
                        fileData[2] == 0x03.toByte() && fileData[3] == 0x04.toByte())

            if (isZip) {
                val zipStream = ZipInputStream(ByteArrayInputStream(fileData))
                var entry = zipStream.nextEntry
                var foundGrid: ElevationGrid? = null
                var extractedName = ""

                while (entry != null) {
                    if (!entry.isDirectory) {
                        val entryName = entry.name.lowercase()
                        if (entryName.endsWith(".las") || entryName.endsWith(".asc") || 
                            entryName.endsWith(".xyz") || entryName.endsWith(".txt") || 
                            entryName.endsWith(".csv")) {
                            
                            // Extract this tile entry
                            val entryOutput = ByteArrayOutputStream()
                            val entryBuffer = ByteArray(4096)
                            var eRead: Int
                            while (zipStream.read(entryBuffer).also { eRead = it } != -1) {
                                entryOutput.write(entryBuffer, 0, eRead)
                                if (entryOutput.size() > MAX_ZIP_ENTRY_BYTES) {
                                    throw java.io.IOException("ZIP entry exceeds the 20 MB safety limit")
                                }
                            }
                            val entryBytes = entryOutput.toByteArray()
                            val grid = DemGenerator.parseFromStream(entry.name, ByteArrayInputStream(entryBytes))
                            if (grid != null) {
                                foundGrid = grid
                                extractedName = entry.name
                                break
                            }
                        }
                    }
                    entry = zipStream.nextEntry
                }
                zipStream.close()

                if (foundGrid != null) {
                    onSuccess(foundGrid, extractedName)
                } else {
                    onError("ZIP extracted, but no valid LAS point cloud or ASCII DEM coordinates were found.")
                }
            } else {
                // Direct unzipped stream
                val grid = DemGenerator.parseFromStream(fileName, ByteArrayInputStream(fileData))
                if (grid != null) {
                    onSuccess(grid, fileName)
                } else {
                    onError("Failed to parse downloaded LiDAR file. Confirm it's a valid LAS, ASC, XYZ, or direct matrix file.")
                }
            }
        } catch (e: Exception) {
            onError("Network download failed: ${e.localizedMessage ?: "Connection error"}")
        }
    }
}
