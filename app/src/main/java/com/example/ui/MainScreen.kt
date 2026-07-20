package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompassCalibration
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.CustomFileLoader
import com.example.ui.components.LidarControlPanel
import com.example.ui.components.LidarMapCanvas
import com.example.ui.components.MagnetometerGauge
import com.example.ui.components.TargetLoggerPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: HillshadeViewModel,
    modifier: Modifier = Modifier
) {
    val currentSiteIndex by viewModel.currentSiteIndex.collectAsState()
    val sunAzimuth by viewModel.sunAzimuth.collectAsState()
    val sunAltitude by viewModel.sunAltitude.collectAsState()
    val vegetationFilter by viewModel.vegetationFilter.collectAsState()
    val paletteType by viewModel.paletteType.collectAsState()
    val contrast by viewModel.contrast.collectAsState()
    val visualizationMode by viewModel.visualizationMode.collectAsState()
    val overlayType by viewModel.overlayType.collectAsState()
    val overlayOpacity by viewModel.overlayOpacity.collectAsState()
    val gridSpacing by viewModel.gridSpacing.collectAsState()
    val hillshadeBitmap by viewModel.hillshadeBitmap.collectAsState()
    val isRendering by viewModel.isRendering.collectAsState()

    val sweepX by viewModel.sweepX.collectAsState()
    val sweepY by viewModel.sweepY.collectAsState()
    val loggedSignals by viewModel.loggedSignals.collectAsState()

    val isPhysicalSensorAvailable by viewModel.isPhysicalSensorAvailable.collectAsState()
    val usePhysicalSensor by viewModel.usePhysicalSensor.collectAsState()
    val detectorSignalStrength by viewModel.detectorSignalStrength.collectAsState()
    val detectedMetalType by viewModel.detectedMetalType.collectAsState()

    val audioPingEnabled by viewModel.audioPingEnabled.collectAsState()
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "LIDAR GROUND STACK",
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            letterSpacing = 1.5.sp,
                            color = Color(0xFFFFD700)
                        )
                        Text(
                            text = "Hillshade Foundation Profiler",
                            fontSize = 11.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.calibrateMagnetometer() },
                        modifier = Modifier.testTag("calibrate_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CompassCalibration,
                            contentDescription = "Calibrate baseline",
                            tint = Color(0xFF29B6F6)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF141518),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF0D0E12),
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- HEADER METRICS BAR ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141518))
                    .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "COIL COORDINATES",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "X: ${sweepX.toInt()}  |  Y: ${sweepY.toInt()}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .width(1.dp)
                        .background(Color(0xFF2C2E35))
                )

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "SURVEY MODE",
                        color = Color.Gray,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (currentSiteIndex == 3) "Custom Layer" else "Archaeological",
                        color = if (currentSiteIndex == 3) Color(0xFF29B6F6) else Color(0xFF00E676),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // --- 1. LIDAR ELEVATION CANVAS (HILLSHADE RELIEF) ---
            Text(
                text = "ACTIVE LIDAR RASTER",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            LidarMapCanvas(
                bitmap = hillshadeBitmap,
                isRendering = isRendering,
                sweepX = sweepX,
                sweepY = sweepY,
                loggedSignals = loggedSignals,
                onSweepPositionChanged = { x, y -> viewModel.setSweepPosition(x, y) },
                onStopSweeping = { viewModel.stopSweeping() },
                gridSpacing = gridSpacing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .testTag("map_canvas")
            )

            // Hint Text for Map Dragging
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2026)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TAP & DRAG the golden coil above to sweep the ground and search for anomalies.",
                        color = Color.LightGray,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            // --- 2. SENSOR & ANOMALY DETECTOR GAUGE ---
            Text(
                text = "COIL FEEDBACK & SIGNAL DISCRIMINATION",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            MagnetometerGauge(
                signalStrength = detectorSignalStrength,
                detectedMetal = detectedMetalType,
                isPhysicalSensor = usePhysicalSensor,
                modifier = Modifier.fillMaxWidth()
            )

            // --- 3. HARDWARE & DEVIATION SETTINGS ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141518)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Physical Magnetometer Toggle (Only enabled if physically present)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Use Hardware Magnetometer",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = if (isPhysicalSensorAvailable) {
                                    "Measure nearby physical magnetic metals with phone sensor"
                                } else {
                                    "No physical sensor detected. Simulating coil sweeps."
                                },
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }

                        Switch(
                            checked = usePhysicalSensor,
                            onCheckedChange = { viewModel.togglePhysicalSensor(it) },
                            enabled = isPhysicalSensorAvailable,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFFD700),
                                checkedTrackColor = Color(0xFFFFD700).copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.testTag("hardware_sensor_switch")
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Audio & Vibrate Quick Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.toggleAudioPing(!audioPingEnabled) }) {
                                Icon(
                                    imageVector = if (audioPingEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                    contentDescription = "Toggle Audio pings",
                                    tint = if (audioPingEnabled) Color(0xFFFFD700) else Color.Gray,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Text(
                                text = "Audio Pings",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.toggleVibration(!vibrationEnabled) }) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Toggle vibration",
                                    tint = if (vibrationEnabled) Color(0xFF00E676) else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Coil Vibration",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // --- 4. LIDAR CONTROL PANEL ---
            Text(
                text = "LIDAR VISUALIZATION FILTERS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            LidarControlPanel(
                selectedSiteIndex = currentSiteIndex,
                onSiteSelected = { viewModel.selectSite(it) },
                sunAzimuth = sunAzimuth,
                onSunAzimuthChanged = { viewModel.updateSunAzimuth(it) },
                sunAltitude = sunAltitude,
                onSunAltitudeChanged = { viewModel.updateSunAltitude(it) },
                vegetationFilter = vegetationFilter,
                onVegetationFilterChanged = { viewModel.updateVegetationFilter(it) },
                paletteType = paletteType,
                onPaletteTypeChanged = { viewModel.updatePalette(it) },
                contrast = contrast,
                onContrastChanged = { viewModel.updateContrast(it) },
                visualizationMode = visualizationMode,
                onVisualizationModeChanged = { viewModel.updateVisualizationMode(it) },
                overlayType = overlayType,
                onOverlayTypeChanged = { viewModel.updateOverlayType(it) },
                overlayOpacity = overlayOpacity,
                onOverlayOpacityChanged = { viewModel.updateOverlayOpacity(it) },
                gridSpacing = gridSpacing,
                onGridSpacingChanged = { viewModel.updateGridSpacing(it) },
                modifier = Modifier.fillMaxWidth()
            )

            // --- 5. TARGET LOGS LOGGER ---
            Text(
                text = "HISTORIC MARKS LOGS",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            TargetLoggerPanel(
                loggedSignals = loggedSignals,
                currentSweepX = sweepX,
                currentSweepY = sweepY,
                onLogSignal = { viewModel.logCurrentSignal() },
                onDeleteSignal = { viewModel.deleteLoggedSignal(it) },
                onUpdateSignal = { viewModel.updateLoggedSignal(it) },
                onClearAll = { viewModel.clearLoggedSignals() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
            )

            // --- 6. CUSTOM GRID LAYER IMPORTER ---
            Text(
                text = "GEO-SPATIAL GRID IMPORT",
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            CustomFileLoader(
                onCustomGridLoaded = { viewModel.setCustomGrid(it) },
                modifier = Modifier.fillMaxWidth()
            )

            // --- 7. EDUCATIONAL GUIDE CARD ---
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141518)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF2C2E35), RoundedCornerShape(12.dp))
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "LiDAR Archaeological Guide",
                        color = Color(0xFFFFD700),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. Under forest canopy (low ground classification filter), ruins are hidden by trees and vegetation spikes.\n\n" +
                               "2. Turn Ground Classification up to 100% to fully filter out vegetation and reveal bare soil relief features.\n\n" +
                               "3. Adjust the Azimuth (sun angle) and low Altitude (sun height) to cast long, dramatic shadows. foundations, cellars, and trenches will pop out vividly in the clay/grey palette!",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}
