package com.example.ui.thermals

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.thermals.TerrainSetting
import com.example.thermals.ThermalForecast
import com.example.thermals.ThermalHour
import com.example.thermals.ThermalPhase
import com.example.thermals.ThermalTerrainMode
import com.example.thermals.ThermalUiState
import com.example.thermals.ThermalWeatherSource
import com.example.thermals.ThermalWindEngine
import com.example.thermals.ThermalWindViewModel
import kotlin.math.roundToInt

/** Chart window: 4 AM through 9 PM covers every legal shooting hour. */
private const val FIRST_HOUR = 4
private const val LAST_HOUR = 21

internal val RisingColor = Color(0xFFE8833A)
internal val DrainingColor = Color(0xFF3A7BD5)
internal val SwitchingColor = Color(0xFF9E9E9E)
internal val WindColor = Color(0xFF8E5BC8)
internal val ScentColor = Color(0xFFD63A3A)

internal fun phaseColor(phase: ThermalPhase): Color = when (phase) {
    ThermalPhase.RISING -> RisingColor
    ThermalPhase.DRAINING -> DrainingColor
    ThermalPhase.SWITCHING -> SwitchingColor
    ThermalPhase.WIND_DOMINATED -> WindColor
}

@Composable
fun ThermalWindScreen(
    padding: PaddingValues,
    siteMetadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    modifier: Modifier = Modifier,
    viewModel: ThermalWindViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("thermal_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocationCard(
            state = state,
            onLat = viewModel::setLatitude,
            onLon = viewModel::setLongitude,
            onGps = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
            onRefresh = viewModel::refreshFromNetwork,
        )

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        WeatherCard(state = state, viewModel = viewModel)
        TerrainCard(state = state, viewModel = viewModel)

        val forecast = state.forecast
        if (forecast == null) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Set a location (GPS, the loaded terrain site, or type lat/lon) to build the thermal chart.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            ChartCard(forecast = forecast, selectedHour = state.selectedHour, onSelectHour = viewModel::selectHour)
            forecast.hour(state.selectedHour)?.let { HourDetailCard(it) }
            SummaryCard(forecast)
            AiCard(state = state, onAsk = viewModel::askAi, onClear = viewModel::clearAi)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun LocationCard(
    state: ThermalUiState,
    onLat: (String) -> Unit,
    onLon: (String) -> Unit,
    onGps: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Stand location")
            if (state.locationLabel.isNotBlank()) {
                Text(state.locationLabel, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGps, enabled = !state.loading) {
                    Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Use GPS")
                }
                OutlinedButton(onClick = onRefresh, enabled = state.hasLocation && !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Get forecast + terrain")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherCard(state: ThermalUiState, viewModel: ThermalWindViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Weather")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThermalWeatherSource.entries.forEachIndexed { i, source ->
                    SegmentedButton(
                        selected = state.source == source,
                        onClick = { viewModel.setSource(source) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThermalWeatherSource.entries.size),
                    ) { Text(source.label) }
                }
            }

            if (state.source == ThermalWeatherSource.FORECAST) {
                if (state.forecastDays.isEmpty()) {
                    Text(
                        "No forecast loaded yet — tap “Get forecast + terrain”.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.forecastDays.forEachIndexed { i, day ->
                            FilterChip(
                                selected = state.selectedDayIndex == i,
                                onClick = { viewModel.selectDay(i) },
                                label = { Text(dayChipLabel(i, day.date)) },
                            )
                        }
                    }
                    state.selectedDay?.let { day ->
                        val lo = day.hourly.minOf { it.temperatureF }
                        val hi = day.hourly.maxOf { it.temperatureF }
                        Text(
                            "%.0f°F low · %.0f°F high · Open-Meteo hourly forecast".format(lo, hi),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                Text(
                    "Enter the temps you know (e.g. 32° at 6 AM, 66° at noon). The rest of the day is filled in on a normal daily curve.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.manualReadings.forEachIndexed { index, reading ->
                    ReadingRow(
                        reading = reading,
                        canRemove = state.manualReadings.size > 1,
                        onChange = { viewModel.setReading(index, it) },
                        onRemove = { viewModel.removeReading(index) },
                    )
                }
                TextButton(onClick = viewModel::addReading) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Add a temperature")
                }
                Text(
                    "Wind from ${ThermalWindEngine.compass(state.manualWindFromDegrees)} at %.0f mph".format(state.manualWindMph),
                    style = MaterialTheme.typography.bodyMedium,
                )
                DirectionPicker(
                    selectedDegrees = state.manualWindFromDegrees,
                    onSelect = { viewModel.setManualWind(fromDegrees = it) },
                )
                Slider(
                    value = state.manualWindMph.toFloat(),
                    onValueChange = { viewModel.setManualWind(mph = it.roundToInt().toDouble()) },
                    valueRange = 0f..25f,
                    steps = 24,
                )
                Text("Cloud cover %.0f%%".format(state.manualCloudPercent), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = state.manualCloudPercent.toFloat(),
                    onValueChange = { viewModel.setManualWind(cloudPercent = (it / 10f).roundToInt() * 10.0) },
                    valueRange = 0f..100f,
                    steps = 9,
                )
            }

            Text("Where you sit", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroundCover.entries.forEach { cover ->
                    FilterChip(
                        selected = state.groundCover == cover,
                        onClick = { viewModel.setGroundCover(cover) },
                        label = { Text(cover.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingRow(
    reading: ManualReading,
    canRemove: Boolean,
    onChange: (ManualReading) -> Unit,
    onRemove: () -> Unit,
) {
    var tempText by remember(reading.hour) { mutableStateOf(reading.temperatureF.roundToInt().toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = { onChange(reading.copy(hour = (reading.hour - 1).coerceAtLeast(0))) }) { Text("–") }
        Text(
            ThermalWindEngine.formatHour(reading.hour.toDouble()),
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = { onChange(reading.copy(hour = (reading.hour + 1).coerceAtMost(23))) }) { Text("+") }
        OutlinedTextField(
            value = tempText,
            onValueChange = { text ->
                tempText = text
                text.toDoubleOrNull()?.takeIf { it in -40.0..130.0 }?.let { onChange(reading.copy(temperatureF = it)) }
            },
            label = { Text("°F") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        if (canRemove) {
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove reading") }
        }
    }
}

private val COMPASS_8 = listOf("N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0, "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0)

@Composable
private fun DirectionPicker(selectedDegrees: Double, onSelect: (Double) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        COMPASS_8.forEach { (label, deg) ->
            FilterChip(
                selected = ((selectedDegrees - deg + 540.0) % 360.0 - 180.0).let { kotlin.math.abs(it) } < 22.5,
                onClick = { onSelect(deg) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerrainCard(state: ThermalUiState, viewModel: ThermalWindViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Landscape, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                SectionTitle("Terrain at the stand")
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThermalTerrainMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.terrainMode == mode,
                        onClick = { viewModel.setTerrainMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThermalTerrainMode.entries.size),
                    ) { Text(mode.label) }
                }
            }
            val terrain = state.activeTerrain
            if (state.terrainMode == ThermalTerrainMode.AUTO) {
                val auto = state.autoTerrain
                if (auto == null) {
                    Text("Tap “Get forecast + terrain” to read the slope from the elevation model.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(terrainDescription(auto), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "From a 90 m elevation model — check it against what you see on the ground and switch to “Set it myself” if it's off.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TerrainSetting.entries.forEach { setting ->
                        FilterChip(
                            selected = state.manualSetting == setting,
                            onClick = { viewModel.setManualTerrain(setting = setting) },
                            label = { Text(setting.label) },
                        )
                    }
                }
                if (state.manualSetting != TerrainSetting.FLAT) {
                    Text(
                        if (state.manualSetting == TerrainSetting.VALLEY) {
                            "Valley rises toward ${ThermalWindEngine.compass(state.manualUphillDegrees)}"
                        } else {
                            "Uphill is toward ${ThermalWindEngine.compass(state.manualUphillDegrees)} (slope faces ${ThermalWindEngine.compass(state.manualUphillDegrees + 180)})"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DirectionPicker(
                        selectedDegrees = state.manualUphillDegrees,
                        onSelect = { viewModel.setManualTerrain(uphillDegrees = it) },
                    )
                    Text("Steepness %.0f°".format(state.manualSlopeDegrees), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = state.manualSlopeDegrees.toFloat(),
                        onValueChange = { viewModel.setManualTerrain(slopeDegrees = it.roundToInt().toDouble()) },
                        valueRange = 1f..40f,
                    )
                }
            }
            if (state.terrainMode == ThermalTerrainMode.MANUAL || state.autoTerrain != null) {
                Text("Using: ${terrainDescription(terrain)}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun terrainDescription(t: com.example.thermals.SiteTerrain): String = buildString {
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

@Composable
private fun ChartCard(forecast: ThermalForecast, selectedHour: Int, onSelectHour: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Thermal wind chart")
            Text(
                "Bars: thermal strength (orange rises uphill, blue falls downhill). Line: temperature. " +
                    "Red arrows: which way your scent drifts each hour. Tap an hour for details.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ThermalChart(
                forecast = forecast,
                selectedHour = selectedHour,
                onSelectHour = onSelectHour,
                modifier = Modifier.fillMaxWidth().height(260.dp).testTag("thermal_chart"),
            )
            Slider(
                value = selectedHour.toFloat(),
                onValueChange = { onSelectHour(it.roundToInt()) },
                valueRange = FIRST_HOUR.toFloat()..LAST_HOUR.toFloat(),
                steps = LAST_HOUR - FIRST_HOUR - 1,
                modifier = Modifier.testTag("thermal_hour_slider"),
            )
            Legend()
        }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            "Rising" to RisingColor,
            "Falling" to DrainingColor,
            "Switching" to SwitchingColor,
            "Wind rules" to WindColor,
            "Scent" to ScentColor,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) { drawCircle(color) }
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
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
    val nightColor = MaterialTheme.colorScheme.surfaceVariant
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
            val len = (arrowRowHeight * 0.42f) * (0.55f + (h.scentSpeedMph / 6.0).coerceIn(0.0, 1.0).toFloat() * 0.45f)
            drawDirectionArrow(center, h.scentTowardDegrees, len, ScentColor.copy(alpha = 0.35f + 0.6f * h.confidence.toFloat()), 2.dp.toPx())
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
private fun HourDetailCard(hour: ThermalHour) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WindCompass(hour, Modifier.size(132.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "${ThermalWindEngine.formatHour(hour.hour.toDouble())} · %.0f°F".format(hour.temperatureF),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(hour.phase.label, color = phaseColor(hour.phase), fontWeight = FontWeight.SemiBold)
                Text(
                    hour.thermalTowardDegrees?.let {
                        "Thermal %.1f mph → %s".format(hour.thermalSpeedMph, ThermalWindEngine.compass(it))
                    } ?: "Thermal %.1f mph (mostly vertical here)".format(hour.thermalSpeedMph),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Wind in cover %.1f mph → %s".format(hour.groundWindMph, ThermalWindEngine.compass(hour.windTowardDegrees)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Scent %.1f mph → %s · confidence %d%%".format(
                        hour.scentSpeedMph,
                        ThermalWindEngine.compass(hour.scentTowardDegrees),
                        (hour.confidence * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            ThermalWindEngine.adviceFor(hour),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
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
        drawDirectionArrow(c, hour.scentTowardDegrees, r * 0.85f, ScentColor, 4.dp.toPx())
        drawCircle(ScentColor, radius = 3.dp.toPx(), center = c)
    }
}

@Composable
private fun SummaryCard(forecast: ThermalForecast) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionTitle("Thermal read (on-device)")
            ThermalWindEngine.summarize(forecast).forEach { line ->
                Text("• $line", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AiCard(state: ThermalUiState, onAsk: (String?) -> Unit, onClear: () -> Unit) {
    var question by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                SectionTitle("AI hunt plan")
            }
            Text(
                "The AI reads this chart and plans stand side, entry/exit and timing. Ask follow-ups like " +
                    "“deer bed on the north point — where do I sit for the evening?”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onAsk(null) },
                enabled = !state.aiBusy,
                modifier = Modifier.testTag("thermal_ai_plan"),
            ) { Text("Build my hunt plan") }
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Ask about this hunt") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onAsk(question)
                        question = ""
                    },
                    enabled = !state.aiBusy && question.isNotBlank(),
                ) { Text("Ask") }
                if (state.aiExchanges.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear") }
                }
            }
            if (state.aiBusy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(state.aiStage ?: "Thinking…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.aiError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.aiExchanges.asReversed().forEach { exchange ->
                Box(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(exchange.question, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(exchange.answer, style = MaterialTheme.typography.bodyMedium)
                        Text("via ${exchange.provider}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(state.aiProviderStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
