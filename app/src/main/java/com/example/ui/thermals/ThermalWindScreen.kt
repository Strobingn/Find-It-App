package com.example.ui.thermals

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.geospatial.GeoSpatialLibrary
import com.example.thermals.GroundCover
import com.example.thermals.ManualReading
import com.example.thermals.SiteTerrain
import com.example.thermals.TerrainSetting
import com.example.thermals.ThermalForecast
import com.example.thermals.ThermalHour
import com.example.thermals.ThermalPhase
import com.example.thermals.ThermalTerrainMode
import com.example.thermals.ThermalUiState
import com.example.thermals.ThermalWeatherSource
import com.example.thermals.ThermalWindEngine
import com.example.thermals.ThermalWindViewModel
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosIconRowDividerInset
import com.example.ui.ios.IosRow
import com.example.ui.ios.IosSection
import com.example.ui.ios.IosSegmentedControl
import com.example.ui.ios.IosSheet
import kotlin.math.roundToInt

/** Chart window: 4 AM through 9 PM covers every legal shooting hour. */
private const val FIRST_HOUR = 4
private const val LAST_HOUR = 21

// Data colors for thermal direction (functional, not chrome).
internal val RisingColor = Color(0xFFE8833A)
internal val DrainingColor = Color(0xFF3A7BD5)
internal val SwitchingColor = Color(0xFF8E8E93)
internal val WindColor = Color(0xFF8E5BC8)
internal val ScentColor = Color(0xFFD63A3A)

internal fun phaseColor(phase: ThermalPhase): Color = when (phase) {
    ThermalPhase.RISING -> RisingColor
    ThermalPhase.DRAINING -> DrainingColor
    ThermalPhase.SWITCHING -> SwitchingColor
    ThermalPhase.WIND_DOMINATED -> WindColor
}

/** Which pop-up settings sheet is open. */
private enum class ThermalSheet { LOCATION, WEATHER, TERRAIN, AI }

@Composable
fun ThermalWindScreen(
    padding: PaddingValues,
    siteMetadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    modifier: Modifier = Modifier,
    viewModel: ThermalWindViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openSheet by rememberSaveable { mutableStateOf<String?>(null) }
    val sheet = openSheet?.let { runCatching { ThermalSheet.valueOf(it) }.getOrNull() }

    // Prefill from the loaded terrain site once, without clobbering anything typed.
    LaunchedEffect(siteMetadata?.bounds) {
        val bounds = siteMetadata?.bounds ?: return@LaunchedEffect
        viewModel.offerSiteLocation(
            latitude = (bounds.minLat + bounds.maxLat) / 2.0,
            longitude = (bounds.minLon + bounds.maxLon) / 2.0,
            label = siteMetadata.siteName,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.useDeviceLocation()
    }
    val requestGps = {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("thermal_screen"),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (state.loading) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        SetupSection(state = state, onOpen = { openSheet = it.name })

        val forecast = state.forecast
        if (forecast == null) {
            IosSection(footer = "Thermals depend on where the sun hits your slope, so a location is needed first.") {
                IosRow(
                    title = "Use my GPS location",
                    icon = Icons.Default.GpsFixed,
                    showChevron = true,
                    onClick = requestGps,
                )
                IosDivider(startInset = IosIconRowDividerInset)
                IosRow(
                    title = "Enter coordinates",
                    icon = Icons.Default.Place,
                    showChevron = true,
                    onClick = { openSheet = ThermalSheet.LOCATION.name },
                )
            }
        } else {
            ChartSection(
                forecast = forecast,
                dateLabel = ThermalWindViewModel.dateLabel(state),
                selectedHour = state.selectedHour,
                onSelectHour = viewModel::selectHour,
            )
            forecast.hour(state.selectedHour)?.let { HourSection(it) }
            SummarySection(forecast)
            AiSection(state = state, onPlan = { viewModel.askAi(null) }, onOpenChat = { openSheet = ThermalSheet.AI.name })
        }
        Spacer(Modifier.height(8.dp))
    }

    when (sheet) {
        ThermalSheet.LOCATION -> IosSheet(title = "Location", onDismiss = { openSheet = null }) {
            LocationSheetContent(
                state = state,
                onLat = viewModel::setLatitude,
                onLon = viewModel::setLongitude,
                onGps = requestGps,
                onRefresh = {
                    viewModel.refreshFromNetwork()
                    openSheet = null
                },
            )
        }
        ThermalSheet.WEATHER -> IosSheet(title = "Weather", onDismiss = { openSheet = null }) {
            WeatherSheetContent(state = state, viewModel = viewModel)
        }
        ThermalSheet.TERRAIN -> IosSheet(title = "Terrain", onDismiss = { openSheet = null }) {
            TerrainSheetContent(state = state, viewModel = viewModel)
        }
        ThermalSheet.AI -> IosSheet(title = "AI hunt plan", onDismiss = { openSheet = null }) {
            AiSheetContent(state = state, onAsk = viewModel::askAi, onClear = viewModel::clearAi)
        }
        null -> Unit
    }
}

// ───────────────────────── Setup (summary rows that open sheets) ─────────────────────────

@Composable
private fun SetupSection(state: ThermalUiState, onOpen: (ThermalSheet) -> Unit) {
    IosSection(header = "Setup") {
        IosRow(
            title = "Location",
            value = locationValue(state),
            icon = Icons.Default.Place,
            showChevron = true,
            onClick = { onOpen(ThermalSheet.LOCATION) },
            modifier = Modifier.testTag("thermal_row_location"),
        )
        IosDivider(startInset = IosIconRowDividerInset)
        IosRow(
            title = "Weather",
            value = weatherValue(state),
            icon = Icons.Default.Cloud,
            showChevron = true,
            onClick = { onOpen(ThermalSheet.WEATHER) },
            modifier = Modifier.testTag("thermal_row_weather"),
        )
        IosDivider(startInset = IosIconRowDividerInset)
        IosRow(
            title = "Terrain",
            value = terrainShort(state.activeTerrain) + " · " + state.groundCover.label,
            icon = Icons.Default.Landscape,
            showChevron = true,
            onClick = { onOpen(ThermalSheet.TERRAIN) },
            modifier = Modifier.testTag("thermal_row_terrain"),
        )
    }
}

private fun locationValue(state: ThermalUiState): String = when {
    state.locationLabel.isNotBlank() -> state.locationLabel
    state.hasLocation -> "%.3f, %.3f".format(state.latitude, state.longitude)
    else -> "Not set"
}

private fun weatherValue(state: ThermalUiState): String =
    if (state.source == ThermalWeatherSource.FORECAST && state.selectedDay != null) {
        "Live · " + dayChipLabel(state.selectedDayIndex, state.selectedDay!!.date)
    } else {
        val temps = state.manualReadings.sortedBy { it.hour }
        val range = if (temps.size >= 2) "%.0f°→%.0f°".format(temps.first().temperatureF, temps.last().temperatureF) else "%.0f°".format(temps.first().temperatureF)
        "$range · ${ThermalWindEngine.compass(state.manualWindFromDegrees)} %.0f".format(state.manualWindMph)
    }

private fun terrainShort(t: SiteTerrain): String = buildString {
    append(t.setting.label)
    t.uphillBearingDegrees?.let { append(" ↑").append(ThermalWindEngine.compass(it)) }
}

private fun terrainDescription(t: SiteTerrain): String = buildString {
    append(t.setting.label)
    t.uphillBearingDegrees?.let {
        append(" · uphill toward ${ThermalWindEngine.compass(it)}")
        append(" · %.0f° slope".format(t.slopeDegrees))
    }
    t.elevationFeet?.let { append(" · %.0f ft".format(it)) }
}

private fun dayChipLabel(index: Int, isoDate: String): String = when (index) {
    0 -> "Today"
    1 -> "Tomorrow"
    else -> ThermalWindViewModel.parseIsoDate(isoDate)?.let {
        java.text.SimpleDateFormat("EEE d", java.util.Locale.US).format(it.time)
    } ?: isoDate
}

// ───────────────────────── Sheets ─────────────────────────

@Composable
private fun LocationSheetContent(
    state: ThermalUiState,
    onLat: (String) -> Unit,
    onLon: (String) -> Unit,
    onGps: () -> Unit,
    onRefresh: () -> Unit,
) {
    IosSection(header = "Stand", footer = state.locationLabel.ifBlank { null }) {
        IosRow(title = "Use my GPS location", icon = Icons.Default.GpsFixed, showChevron = true, onClick = onGps)
    }
    IosSection(header = "Coordinates") {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.latitudeText,
                onValueChange = onLat,
                label = { Text("Latitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("thermal_lat"),
            )
            OutlinedTextField(
                value = state.longitudeText,
                onValueChange = onLon,
                label = { Text("Longitude") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("thermal_lon"),
            )
        }
    }
    IosSection(footer = "Free Open-Meteo hourly forecast and a 90 m elevation model of the slope around you.") {
        IosRow(
            title = "Get forecast + terrain",
            icon = Icons.Default.Refresh,
            titleColor = if (state.hasLocation) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            showChevron = state.hasLocation,
            onClick = if (state.hasLocation && !state.loading) onRefresh else null,
        )
    }
}

@Composable
private fun WeatherSheetContent(state: ThermalUiState, viewModel: ThermalWindViewModel) {
    IosSegmentedControl(
        options = ThermalWeatherSource.entries.map { it.label },
        selectedIndex = ThermalWeatherSource.entries.indexOf(state.source),
        onSelect = { viewModel.setSource(ThermalWeatherSource.entries[it]) },
    )

    if (state.source == ThermalWeatherSource.FORECAST) {
        if (state.forecastDays.isEmpty()) {
            IosSection(footer = "Set a location, then tap “Get forecast + terrain” in Location.") {
                IosRow(title = "No forecast loaded", titleColor = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            IosSection(header = "Day", footer = "Open-Meteo hourly forecast") {
                state.forecastDays.forEachIndexed { i, day ->
                    if (i > 0) IosDivider(startInset = 16.dp)
                    val lo = day.hourly.minOf { it.temperatureF }
                    val hi = day.hourly.maxOf { it.temperatureF }
                    IosRow(
                        title = dayChipLabel(i, day.date),
                        value = "%.0f° / %.0f°".format(lo, hi),
                        onClick = { viewModel.selectDay(i) },
                        trailing = { CheckMark(visible = state.selectedDayIndex == i) },
                    )
                }
            }
        }
    } else {
        IosSection(
            header = "Temperatures you know",
            footer = "e.g. 32° at 6 AM and 66° at noon — the rest of the day follows a normal daily curve.",
        ) {
            state.manualReadings.forEachIndexed { index, reading ->
                if (index > 0) IosDivider(startInset = 16.dp)
                ReadingRow(
                    reading = reading,
                    canRemove = state.manualReadings.size > 1,
                    onChange = { viewModel.setReading(index, it) },
                    onRemove = { viewModel.removeReading(index) },
                )
            }
            IosDivider(startInset = 16.dp)
            IosRow(
                title = "Add temperature",
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = viewModel::addReading,
                trailing = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            )
        }
        IosSection(header = "Wind") {
            IosRow(
                title = "From",
                value = ThermalWindEngine.compass(state.manualWindFromDegrees),
            )
            CompassPicker(
                selectedDegrees = state.manualWindFromDegrees,
                onSelect = { viewModel.setManualWind(fromDegrees = it) },
            )
            IosDivider(startInset = 16.dp)
            IosRow(title = "Speed", value = "%.0f mph".format(state.manualWindMph))
            Slider(
                value = state.manualWindMph.toFloat(),
                onValueChange = { viewModel.setManualWind(mph = it.roundToInt().toDouble()) },
                valueRange = 0f..25f,
                steps = 24,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            IosDivider(startInset = 16.dp)
            IosRow(title = "Cloud cover", value = "%.0f%%".format(state.manualCloudPercent))
            Slider(
                value = state.manualCloudPercent.toFloat(),
                onValueChange = { viewModel.setManualWind(cloudPercent = (it / 10f).roundToInt() * 10.0) },
                valueRange = 0f..100f,
                steps = 9,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun CheckMark(visible: Boolean) {
    Text(
        if (visible) "✓" else "",
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.width(18.dp),
    )
}

@Composable
private fun ReadingRow(
    reading: ManualReading,
    canRemove: Boolean,
    onChange: (ManualReading) -> Unit,
    onRemove: () -> Unit,
) {
    var tempText by remember(reading.hour) { mutableStateOf(reading.temperatureF.roundToInt().toString()) }
    Row(
        Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.RemoveCircle, contentDescription = "Remove reading", tint = MaterialTheme.colorScheme.error)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        IconButton(onClick = { onChange(reading.copy(hour = (reading.hour - 1).coerceAtLeast(0))) }) { Text("–") }
        Text(
            ThermalWindEngine.formatHour(reading.hour.toDouble()),
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = { onChange(reading.copy(hour = (reading.hour + 1).coerceAtMost(23))) }) { Text("+") }
        OutlinedTextField(
            value = tempText,
            onValueChange = { text ->
                tempText = text
                text.toDoubleOrNull()?.takeIf { it in -40.0..130.0 }?.let { onChange(reading.copy(temperatureF = it)) }
            },
            suffix = { Text("°F") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
}

private val COMPASS_8 = listOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)

/** Eight-way direction picker as an iOS segmented control. */
@Composable
private fun CompassPicker(selectedDegrees: Double, onSelect: (Double) -> Unit) {
    val index = COMPASS_8.indexOfFirst { (_, deg) ->
        kotlin.math.abs(((selectedDegrees - deg + 540.0) % 360.0) - 180.0) < 22.5
    }.coerceAtLeast(0)
    IosSegmentedControl(
        options = COMPASS_8.map { it.first },
        selectedIndex = index,
        onSelect = { onSelect(COMPASS_8[it].second) },
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    )
}

@Composable
private fun TerrainSheetContent(state: ThermalUiState, viewModel: ThermalWindViewModel) {
    IosSegmentedControl(
        options = ThermalTerrainMode.entries.map { it.label },
        selectedIndex = ThermalTerrainMode.entries.indexOf(state.terrainMode),
        onSelect = { viewModel.setTerrainMode(ThermalTerrainMode.entries[it]) },
    )
    if (state.terrainMode == ThermalTerrainMode.AUTO) {
        val auto = state.autoTerrain
        IosSection(
            header = "From the elevation model",
            footer = "A 90 m model — check it against what you see on the ground and switch to “Set it myself” if it's off.",
        ) {
            if (auto == null) {
                IosRow(title = "Not loaded yet", subtitle = "Location → Get forecast + terrain", titleColor = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                IosRow(title = "Spot", value = auto.setting.label)
                auto.uphillBearingDegrees?.let {
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Uphill toward", value = ThermalWindEngine.compass(it))
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Steepness", value = "%.0f°".format(auto.slopeDegrees))
                }
                auto.elevationFeet?.let {
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Elevation", value = "%.0f ft".format(it))
                }
            }
        }
    } else {
        IosSection(header = "Where's your stand?") {
            TerrainSetting.entries.forEachIndexed { i, setting ->
                if (i > 0) IosDivider(startInset = 16.dp)
                IosRow(
                    title = setting.label,
                    onClick = { viewModel.setManualTerrain(setting = setting) },
                    trailing = { CheckMark(visible = state.manualSetting == setting) },
                )
            }
        }
        if (state.manualSetting != TerrainSetting.FLAT) {
            IosSection(
                header = if (state.manualSetting == TerrainSetting.VALLEY) "Valley rises toward" else "Uphill is toward",
                footer = if (state.manualSetting == TerrainSetting.VALLEY) null else "Slope faces ${ThermalWindEngine.compass(state.manualUphillDegrees + 180)}.",
            ) {
                Spacer(Modifier.height(12.dp))
                CompassPicker(
                    selectedDegrees = state.manualUphillDegrees,
                    onSelect = { viewModel.setManualTerrain(uphillDegrees = it) },
                )
                IosDivider(startInset = 16.dp)
                IosRow(title = "Steepness", value = "%.0f°".format(state.manualSlopeDegrees))
                Slider(
                    value = state.manualSlopeDegrees.toFloat(),
                    onValueChange = { viewModel.setManualTerrain(slopeDegrees = it.roundToInt().toDouble()) },
                    valueRange = 1f..40f,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
    IosSection(header = "You're sitting in", footer = "Woods block more of the wind, so thermals matter more there.") {
        GroundCover.entries.forEachIndexed { i, cover ->
            if (i > 0) IosDivider(startInset = 16.dp)
            IosRow(
                title = cover.label,
                onClick = { viewModel.setGroundCover(cover) },
                trailing = { CheckMark(visible = state.groundCover == cover) },
            )
        }
    }
    Text(
        "Using: ${terrainDescription(state.activeTerrain)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun AiSheetContent(state: ThermalUiState, onAsk: (String?) -> Unit, onClear: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    IosSection(
        footer = "Try: “deer bed on the north point — where do I sit for the evening?”",
    ) {
        OutlinedTextField(
            value = question,
            onValueChange = { question = it },
            placeholder = { Text("Ask about this hunt") },
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            minLines = 2,
        )
        IosDivider(startInset = 16.dp)
        IosRow(
            title = "Ask",
            titleColor = if (question.isNotBlank() && !state.aiBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = if (question.isNotBlank() && !state.aiBusy) {
                {
                    onAsk(question)
                    question = ""
                }
            } else {
                null
            },
        )
    }
    AiStatus(state)
    state.aiStreamingText?.let { LiveAnswer(it) }
    state.aiExchanges.asReversed().forEach { exchange -> AiAnswer(exchange) }
    if (state.aiExchanges.isNotEmpty()) {
        IosSection {
            IosRow(title = "Clear conversation", titleColor = MaterialTheme.colorScheme.error, onClick = onClear)
        }
    }
    Text(
        state.aiProviderStatus,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun AiStatus(state: ThermalUiState) {
    if (state.aiBusy) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(state.aiStage ?: "Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    state.aiError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun AiAnswer(exchange: com.example.thermals.ThermalAiExchange) {
    IosSection(header = exchange.question, footer = "via ${exchange.provider}") {
        Text(
            exchange.answer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(16.dp),
        )
    }
}

// ───────────────────────── Main content sections ─────────────────────────

@Composable
private fun ChartSection(forecast: ThermalForecast, dateLabel: String, selectedHour: Int, onSelectHour: (Int) -> Unit) {
    IosSection(header = "Thermal wind · $dateLabel") {
        Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp)) {
            ThermalChart(
                forecast = forecast,
                selectedHour = selectedHour,
                onSelectHour = onSelectHour,
                modifier = Modifier.fillMaxWidth().height(240.dp).testTag("thermal_chart"),
            )
            Slider(
                value = selectedHour.toFloat(),
                onValueChange = { onSelectHour(it.roundToInt()) },
                valueRange = FIRST_HOUR.toFloat()..LAST_HOUR.toFloat(),
                steps = LAST_HOUR - FIRST_HOUR - 1,
                modifier = Modifier.padding(horizontal = 8.dp).testTag("thermal_hour_slider"),
            )
            Legend()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            "Rising" to RisingColor,
            "Falling" to DrainingColor,
            "Switching" to SwitchingColor,
            "Wind rules" to WindColor,
            "Scent" to ScentColor,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HourSection(hour: ThermalHour) {
    IosSection(
        header = "${ThermalWindEngine.formatHour(hour.hour.toDouble())} · %.0f°F".format(hour.temperatureF),
        footer = ThermalWindEngine.adviceFor(hour),
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WindCompass(hour, Modifier.size(118.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(ThermalWindEngine.phaseText(hour), color = phaseColor(hour.phase), style = MaterialTheme.typography.titleMedium)
                StatLine(
                    "Thermal",
                    hour.thermalTowardDegrees?.let { "%.1f mph → %s".format(hour.thermalSpeedMph, ThermalWindEngine.compass(it)) }
                        ?: "%.1f mph, vertical".format(hour.thermalSpeedMph),
                )
                StatLine("Wind in cover", "%.1f mph → %s".format(hour.groundWindMph, ThermalWindEngine.compass(hour.windTowardDegrees)))
                StatLine(
                    "Scent",
                    if (hour.scentSpeedMph < ThermalWindEngine.CALM_SCENT_MPH) {
                        "nearly still"
                    } else {
                        "%.1f mph → %s".format(hour.scentSpeedMph, ThermalWindEngine.compass(hour.scentTowardDegrees))
                    },
                    emphasize = true,
                )
                StatLine("Confidence", "%d%%".format((hour.confidence * 100).roundToInt()))
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, emphasize: Boolean = false) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun SummarySection(forecast: ThermalForecast) {
    IosSection(header = "Thermal read", footer = "Calculated on your phone — works with no signal.") {
        ThermalWindEngine.summarize(forecast).forEachIndexed { i, line ->
            if (i > 0) IosDivider(startInset = 16.dp)
            Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun AiSection(state: ThermalUiState, onPlan: () -> Unit, onOpenChat: () -> Unit) {
    IosSection(header = "AI hunt plan") {
        IosRow(
            title = if (state.aiBusy) state.aiStage ?: "Thinking…" else "Build my hunt plan",
            subtitle = "Stand side, entry and exit, when to move",
            icon = Icons.Default.AutoAwesome,
            onClick = if (state.aiBusy) null else onPlan,
            trailing = { if (state.aiBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
            modifier = Modifier.testTag("thermal_ai_plan"),
        )
        IosDivider(startInset = IosIconRowDividerInset)
        IosRow(
            title = "Ask a question",
            value = state.aiExchanges.size.takeIf { it > 0 }?.let { "$it" },
            icon = Icons.Default.QuestionAnswer,
            showChevron = true,
            onClick = onOpenChat,
        )
    }
    state.aiError?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
    }
    val live = state.aiStreamingText
    if (live != null) {
        LiveAnswer(live)
    } else {
        state.aiExchanges.lastOrNull()?.let { AiAnswer(it) }
    }
}

@Composable
private fun LiveAnswer(text: String) {
    IosSection(header = "Writing…") {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
    }
}

@Composable
internal fun ThermalChart(
    forecast: ThermalForecast,
    selectedHour: Int,
    onSelectHour: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val nightColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)
    val tempColor = MaterialTheme.colorScheme.onSurface
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisColor)
    val hours = forecast.hours.filter { it.hour in FIRST_HOUR..LAST_HOUR }
    val count = LAST_HOUR - FIRST_HOUR + 1

    Canvas(
        modifier = modifier.pointerInput(forecast) {
            detectTapGestures { offset ->
                val left = 30.dp.toPx()
                val right = size.width - 30.dp.toPx()
                val slot = (right - left) / count
                val index = ((offset.x - left) / slot).toInt().coerceIn(0, count - 1)
                onSelectHour(FIRST_HOUR + index)
            }
        },
    ) {
        val left = 30.dp.toPx()
        val right = size.width - 30.dp.toPx()
        val arrowRowTop = 0f
        val arrowRowHeight = 30.dp.toPx()
        val plotTop = arrowRowHeight + 8.dp.toPx()
        val plotBottom = size.height - 18.dp.toPx()
        val plotHeight = plotBottom - plotTop
        val slot = (right - left) / count
        fun xFor(hour: Double) = left + (hour - FIRST_HOUR + 0.5) * slot

        // Night shading before sunrise / after sunset.
        forecast.sunriseLocalHour?.let { sr ->
            val x = xFor(sr).toFloat().coerceIn(left, right)
            drawRect(nightColor, topLeft = Offset(left, plotTop), size = Size(x - left, plotHeight))
        }
        forecast.sunsetLocalHour?.let { ss ->
            val x = xFor(ss).toFloat().coerceIn(left, right)
            drawRect(nightColor, topLeft = Offset(x, plotTop), size = Size(right - x, plotHeight))
        }

        // Selected hour highlight.
        val selIndex = selectedHour - FIRST_HOUR
        if (selIndex in 0 until count) {
            drawRect(highlight, topLeft = Offset(left + selIndex * slot, 0f), size = Size(slot, size.height))
        }

        // Thermal bars around a zero line in the middle of the plot (±6 mph full scale).
        val zeroY = plotTop + plotHeight / 2f
        val mphScale = (plotHeight / 2f) / 6f
        drawLine(gridColor, Offset(left, zeroY), Offset(right, zeroY), strokeWidth = 1.dp.toPx())
        hours.forEach { h ->
            val i = h.hour - FIRST_HOUR
            val barWidth = slot * 0.6f
            val x0 = left + i * slot + (slot - barWidth) / 2f
            val signed = if (h.tendency >= 0) h.thermalSpeedMph else -h.thermalSpeedMph
            val barHeight = (signed * mphScale).toFloat()
            val top = if (barHeight >= 0) zeroY - barHeight else zeroY
            drawRect(
                phaseColor(h.phase).copy(alpha = if (h.phase == ThermalPhase.WIND_DOMINATED) 0.55f else 0.9f),
                topLeft = Offset(x0, top),
                size = Size(barWidth, kotlin.math.abs(barHeight).coerceAtLeast(1.5f)),
            )
        }

        // Temperature line on its own scale (right axis).
        val tMin = hours.minOf { it.temperatureF }
        val tMax = hours.maxOf { it.temperatureF }.let { if (it - tMin < 4) tMin + 4 else it }
        fun yForTemp(t: Double) = (plotBottom - (t - tMin) / (tMax - tMin) * plotHeight * 0.9 - plotHeight * 0.05).toFloat()
        val path = Path()
        hours.forEachIndexed { idx, h ->
            val p = Offset(xFor(h.hour.toDouble()).toFloat(), yForTemp(h.temperatureF))
            if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, tempColor, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawText(measurer, "%.0f°".format(tMax), Offset(right + 3.dp.toPx(), yForTemp(tMax) - 6.dp.toPx()), labelStyle)
        drawText(measurer, "%.0f°".format(tMin), Offset(right + 3.dp.toPx(), yForTemp(tMin) - 6.dp.toPx()), labelStyle)
        drawText(measurer, "↑up", Offset(2.dp.toPx(), plotTop), labelStyle)
        drawText(measurer, "↓dn", Offset(2.dp.toPx(), plotBottom - 12.dp.toPx()), labelStyle)

        // Switch markers.
        val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        listOfNotNull(forecast.morningSwitchHour, forecast.eveningSwitchHour).forEach { sw ->
            if (sw in FIRST_HOUR.toDouble()..LAST_HOUR.toDouble()) {
                val x = xFor(sw).toFloat()
                drawLine(axisColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
            }
        }

        // Scent-drift arrows across the top.
        hours.forEach { h ->
            val center = Offset(xFor(h.hour.toDouble()).toFloat(), arrowRowTop + arrowRowHeight / 2f)
            if (h.scentSpeedMph >= ThermalWindEngine.CALM_SCENT_MPH) {
                val len = (arrowRowHeight * 0.42f) * (0.55f + (h.scentSpeedMph / 6.0).coerceIn(0.0, 1.0).toFloat() * 0.45f)
                drawDirectionArrow(center, h.scentTowardDegrees, len, ScentColor.copy(alpha = 0.35f + 0.6f * h.confidence.toFloat()), 2.dp.toPx())
            }
        }

        // Hour labels every 2 hours.
        for (hour in FIRST_HOUR..LAST_HOUR step 2) {
            val label = when {
                hour == 12 -> "12p"
                hour > 12 -> "${hour - 12}p"
                else -> "${hour}a"
            }
            drawText(measurer, label, Offset(xFor(hour.toDouble()).toFloat() - 7.dp.toPx(), plotBottom + 3.dp.toPx()), labelStyle)
        }
    }
}

/** Arrow pointing toward [towardDegrees] (0 = up/north on screen). */
internal fun DrawScope.drawDirectionArrow(center: Offset, towardDegrees: Double, halfLength: Float, color: Color, width: Float) {
    rotate(degrees = towardDegrees.toFloat(), pivot = center) {
        val tail = Offset(center.x, center.y + halfLength)
        val tip = Offset(center.x, center.y - halfLength)
        drawLine(color, tail, tip, strokeWidth = width, cap = StrokeCap.Round)
        val head = halfLength * 0.55f
        drawLine(color, tip, Offset(tip.x - head * 0.6f, tip.y + head), strokeWidth = width, cap = StrokeCap.Round)
        drawLine(color, tip, Offset(tip.x + head * 0.6f, tip.y + head), strokeWidth = width, cap = StrokeCap.Round)
    }
}


@Composable
private fun WindCompass(hour: ThermalHour, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val ring = MaterialTheme.colorScheme.outline
    val label = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 12.dp.toPx()
        drawCircle(ring, radius = r, center = c, style = Stroke(1.dp.toPx()))
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (text, deg) ->
            val rad = Math.toRadians(deg.toDouble())
            val p = Offset(
                (c.x + (r + 7.dp.toPx()) * kotlin.math.sin(rad)).toFloat() - 4.dp.toPx(),
                (c.y - (r + 7.dp.toPx()) * kotlin.math.cos(rad)).toFloat() - 7.dp.toPx(),
            )
            drawText(measurer, text, p, label)
        }
        drawDirectionArrow(c, hour.windTowardDegrees, r * 0.55f, WindColor.copy(alpha = 0.7f), 3.dp.toPx())
        hour.thermalTowardDegrees?.let {
            drawDirectionArrow(c, it, r * 0.45f, phaseColor(hour.phase), 3.dp.toPx())
        }
        if (hour.scentSpeedMph >= ThermalWindEngine.CALM_SCENT_MPH) {
            drawDirectionArrow(c, hour.scentTowardDegrees, r * 0.85f, ScentColor, 4.dp.toPx())
        }
        drawCircle(ScentColor, radius = 3.dp.toPx(), center = c)
    }
}

