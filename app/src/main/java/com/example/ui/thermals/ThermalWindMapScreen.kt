package com.example.ui.thermals

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.geospatial.GeoSpatialLibrary
import com.example.thermals.MapLoadIssue
import com.example.thermals.SiteTerrain
import com.example.thermals.ThermalHour
import com.example.thermals.ThermalMapGeometry
import com.example.thermals.ThermalPhase
import com.example.thermals.ThermalWindEngine
import com.example.thermals.ThermalWindViewModel
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosRow
import com.example.ui.ios.IosSection
import com.example.ui.ios.IosSegmentedControl
import com.example.ui.ios.IosSheet
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.ScreenCoordinate
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.FillLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.rasterDemSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.terrain.generated.removeTerrain
import com.mapbox.maps.extension.style.terrain.generated.setTerrain
import com.mapbox.maps.extension.style.terrain.generated.terrain
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

private const val MAP_FIRST_HOUR = 4f
private const val MAP_LAST_HOUR = 21f

/** Camera moves prefetch this many zoom levels of lower-detail tiles (SDK default 4). */
private const val PREFETCH_ZOOM_DELTA: Byte = 1

private object WindMapIds {
    const val DEM = "thermal-dem"
    const val CONE_SOURCE = "thermal-cone"
    const val CONE_FILL = "thermal-cone-fill"
    const val CONE_LINE = "thermal-cone-line"
    const val STAND_SOURCE = "thermal-stand"
    const val STAND_LAYER = "thermal-stand-layer"
    const val DEM_URL = "mapbox://mapbox.mapbox-terrain-dem-v1"
}

private enum class WindMapBase(val label: String, val uri: String) {
    SATELLITE("Satellite", Style.SATELLITE),
    LABELS("Labels", Style.SATELLITE_STREETS),
    OUTDOORS("Topo", Style.OUTDOORS),
}

private enum class ParticleMode(val label: String) { SCENT("Scent drift"), THERMAL("Thermal only") }

private enum class ParticleDensity(val label: String, val count: Int) { LOW("Light", 180), MEDIUM("Medium", 360), HIGH("Dense", 600) }

/** One sample of the map's ground under the screen, in screen pixels, with how many pixels one meter north/east spans there. */
private class GroundCell(
    val px: Float,
    val py: Float,
    val northX: Float,
    val northY: Float,
    val eastX: Float,
    val eastY: Float,
    val terrain: SiteTerrain,
)

private class GroundGrid(val cols: Int, val rows: Int, val width: Float, val height: Float, val cells: Array<GroundCell?>) {
    fun cellAt(x: Float, y: Float): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        val c = (x / width * cols).toInt().coerceIn(0, cols - 1)
        val r = (y / height * rows).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }
}

/** Per-cell motion for the current hour: unit screen direction, pixel speed and phase color. */
private class CellMotion(val dx: Float, val dy: Float, val speedPx: Float, val color: Int, val swirl: Boolean)

@Composable
fun ThermalWindMapScreen(
    padding: PaddingValues,
    siteMetadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    modifier: Modifier = Modifier,
    viewModel: ThermalWindViewModel = viewModel(),
) {
    val token = BuildConfig.MAPBOX_ACCESS_TOKEN
    // A blank token — or a secret sk. token pasted by mistake — leaves a permanently blank map.
    // Fail loudly here instead of letting the hunter stare at an empty screen.
    val tokenProblem = when {
        token.isBlank() -> "Add your public Mapbox token (starts with pk.) as MAPBOX_ACCESS_TOKEN in local.properties, .env or the CI secrets, then rebuild."
        !token.startsWith("pk.") -> "MAPBOX_ACCESS_TOKEN must be a PUBLIC Mapbox token starting with \"pk.\". A secret (sk.) token cannot load maps in an app — create a public one at account.mapbox.com/access-tokens, then rebuild."
        else -> null
    }
    if (tokenProblem != null) {
        Column(modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            IosSection(header = "Wind map", footer = tokenProblem) {
                IosRow(
                    title = if (token.isBlank()) "Mapbox token needed" else "Mapbox token is invalid",
                    titleColor = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    remember(token) { MapboxOptions.accessToken = token }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current

    LaunchedEffect(siteMetadata?.bounds) {
        val bounds = siteMetadata?.bounds ?: return@LaunchedEffect
        viewModel.offerSiteLocation((bounds.minLat + bounds.maxLat) / 2.0, (bounds.minLon + bounds.maxLon) / 2.0, siteMetadata.siteName)
    }

    var base by rememberSaveable { mutableStateOf(WindMapBase.SATELLITE) }
    var terrain3d by rememberSaveable { mutableStateOf(true) }
    var showCone by rememberSaveable { mutableStateOf(true) }
    var mode by rememberSaveable { mutableStateOf(ParticleMode.SCENT) }
    var particleDensity by rememberSaveable { mutableStateOf(ParticleDensity.MEDIUM) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var hour by rememberSaveable { mutableStateOf(state.selectedHour.toFloat().coerceIn(MAP_FIRST_HOUR, MAP_LAST_HOUR)) }
    // The view model is shared with the Thermals tab. Keep the scrubber in sync both ways:
    // our own scrubs push down via selectHour (tracked by lastPushedHour), and hour picks made
    // on the Thermals tab pull this scrubber along.
    var lastPushedHour by remember { mutableIntStateOf(state.selectedHour) }

    val inputs = remember(state) { ThermalWindViewModel.buildInputs(state) }
    val field = remember(inputs) { inputs?.let(ThermalWindEngine::flowField) }
    val lat = state.latitude
    val lon = state.longitude

    // Stand reading at the scrubbed time (quantized so the map isn't rebuilt every frame).
    val hourKey = (hour * 10).roundToInt()
    val standFlow: ThermalHour? = remember(field, hourKey) { field?.at(hourKey / 10.0) }

    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            if (lat != null && lon != null) {
                center(Point.fromLngLat(lon, lat))
                zoom(14.8)
                pitch(58.0)
            } else {
                center(Point.fromLngLat(-98.35, 39.5))
                zoom(3.0)
            }
        }
    }
    fun recenter() {
        val la = state.latitude ?: return
        val lo = state.longitude ?: return
        mapViewportState.flyTo(
            CameraOptions.Builder()
                .center(Point.fromLngLat(lo, la))
                .zoom(14.8)
                .pitch(if (terrain3d) 58.0 else 0.0)
                .build(),
        )
    }
    LaunchedEffect(lat, lon) { if (lat != null && lon != null) recenter() }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapIssue by remember { mutableStateOf<MapLoadIssue?>(null) }
    var tileIssueAt by remember { mutableLongStateOf(0L) }
    // The base style's own elevation source when it has one, so DEM tiles aren't fetched twice.
    var demSourceId by remember { mutableStateOf(WindMapIds.DEM) }
    // Tile hiccups (rate limits, a dropped tile) clear themselves once tiles stop failing.
    LaunchedEffect(tileIssueAt) {
        if (tileIssueAt == 0L) return@LaunchedEffect
        delay(MapLoadIssue.TILE_NOTE_MS)
        if (mapIssue?.fatal == false) mapIssue = null
    }
    var idleTick by remember { mutableIntStateOf(0) }
    var lastMoveMs by remember { mutableLongStateOf(0L) }
    var grid by remember { mutableStateOf<GroundGrid?>(null) }

    // Re-sample the ground under the screen whenever the camera settles or the stand terrain changes.
    LaunchedEffect(idleTick, mapViewRef, inputs?.terrain) {
        val map = mapViewRef?.mapboxMap ?: return@LaunchedEffect
        val view = mapViewRef ?: return@LaunchedEffect
        if (view.width <= 0 || view.height <= 0) return@LaunchedEffect
        grid = sampleGround(map, view.width.toFloat(), view.height.toFloat(), fallback = inputs?.terrain)
    }

    // Motion per cell for the current time, recomputed when the time or field changes.
    val pxPerDp = density.density
    val motions: Array<CellMotion?> = remember(grid, field, hourKey, mode) {
        val g = grid
        val f = field
        if (g == null || f == null) {
            arrayOfNulls<CellMotion>(0)
        } else {
            Array(g.cells.size) { i -> g.cells[i]?.let { cell -> cellMotion(cell, f.at(hourKey / 10.0, cell.terrain), mode, pxPerDp) } }
        }
    }

    // Animate the clock while playing: one shooting day in ~20 seconds, looping.
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    var h = hour + dt * 0.85f
                    if (h > MAP_LAST_HOUR) h = MAP_FIRST_HOUR
                    hour = h
                }
                last = now
            }
        }
    }
    LaunchedEffect(hourKey / 10) {
        lastPushedHour = hourKey / 10
        viewModel.selectHour(hourKey / 10)
    }
    LaunchedEffect(state.selectedHour) {
        if (state.selectedHour != lastPushedHour) {
            lastPushedHour = state.selectedHour
            hour = state.selectedHour.toFloat().coerceIn(MAP_FIRST_HOUR, MAP_LAST_HOUR)
        }
    }

    val coneColor = standFlow?.let { phaseColor(it.phase) } ?: ScentColor
    val coneFeatures = remember(standFlow, lat, lon) { buildCone(lat, lon, standFlow) }
    val standFeatures = remember(lat, lon) { buildStand(lat, lon) }

    Box(modifier.fillMaxSize().padding(padding).testTag("wind_map_screen")) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            scaleBar = {},
            compass = {},
            onMapLongClickListener = { point ->
                viewModel.useLocation(point.latitude(), point.longitude(), "Map pin")
                true
            },
            style = {},
        ) {
            MapEffect(Unit) { mapView ->
                mapViewRef = mapView
                val subs = mutableListOf<Cancelable>()
                subs += mapView.mapboxMap.subscribeMapIdle { idleTick++ }
                subs += mapView.mapboxMap.subscribeCameraChangedCoalesced { lastMoveMs = System.currentTimeMillis() }
                // Fewer low-zoom placeholder tiles per camera move (default 4) — cuts tile requests.
                mapView.mapboxMap.setPrefetchZoomDelta(PREFETCH_ZOOM_DELTA)
                // A rejected token or offline style fetch otherwise fails silently to a blank map.
                subs += mapView.mapboxMap.subscribeMapLoadingError { event ->
                    val issue = MapLoadIssue.from(event.type.name, event.message, event.sourceId)
                    if (issue.fatal || mapIssue?.fatal != true) {
                        mapIssue = issue
                        if (!issue.fatal) tileIssueAt = System.currentTimeMillis()
                    }
                }
                try {
                    awaitCancellation()
                } finally {
                    subs.forEach { it.cancel() }
                }
            }
            MapEffect(base) { mapView ->
                mapView.mapboxMap.loadStyle(base.uri) { style ->
                    demSourceId = style.installWindLayers(coneFeatures, standFeatures, coneColor, terrain3d, showCone)
                    if (mapIssue?.fatal == true) mapIssue = null
                    idleTick++
                }
            }
            MapEffect(terrain3d) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    if (terrain3d) style.setTerrain(terrain(demSourceId) { exaggeration(1.4) }) else style.removeTerrain()
                }
            }
            MapEffect(coneFeatures, coneColor, showCone) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(WindMapIds.CONE_SOURCE)?.featureCollection(coneFeatures)
                    val hex = coneColor.toHex()
                    val vis = if (showCone) Visibility.VISIBLE else Visibility.NONE
                    style.getLayerAs<FillLayer>(WindMapIds.CONE_FILL)?.let { it.fillColor(hex); it.visibility(vis) }
                    style.getLayerAs<LineLayer>(WindMapIds.CONE_LINE)?.let { it.lineColor(hex); it.visibility(vis) }
                }
            }
            MapEffect(standFeatures) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(WindMapIds.STAND_SOURCE)?.featureCollection(standFeatures)
                }
            }
        }

        ParticleOverlay(
            grid = grid,
            motions = motions,
            count = particleDensity.count,
            lastMoveMs = { lastMoveMs },
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (lat == null || lon == null) {
                GlassCard {
                    Text(
                        "Long-press the map where you'll sit, or set a location on the Thermals tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            } else {
                standFlow?.let { HudCard(it, hour, mode) }
            }
            mapIssue?.let { issue ->
                GlassCard {
                    Text(
                        issue.text,
                        color = if (issue.fatal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = if (issue.fatal) 12.dp else 8.dp).testTag("wind_map_issue"),
                    )
                }
            }
            state.error?.let {
                GlassCard {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundGlassButton(onClick = { showSettings = true }, testTag = "wind_map_settings") {
                Icon(Icons.Default.Tune, contentDescription = "Map settings")
            }
            RoundGlassButton(onClick = { recenter() }, testTag = "wind_map_recenter") {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on stand")
            }
        }

        TimeScrubber(
            hour = hour,
            playing = playing,
            onHour = {
                playing = false
                hour = it
            },
            onTogglePlay = { playing = !playing },
            forecastSwitches = listOfNotNull(state.forecast?.morningSwitchHour, state.forecast?.eveningSwitchHour),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    if (showSettings) {
        IosSheet(title = "Wind map", onDismiss = { showSettings = false }) {
            IosSection(header = "Map") {
                IosSegmentedControl(
                    options = WindMapBase.entries.map { it.label },
                    selectedIndex = WindMapBase.entries.indexOf(base),
                    onSelect = { base = WindMapBase.entries[it] },
                    modifier = Modifier.padding(12.dp),
                )
                IosDivider(startInset = 16.dp)
                IosRow(title = "3D terrain", trailing = { Switch(checked = terrain3d, onCheckedChange = { terrain3d = it }) })
                IosDivider(startInset = 16.dp)
                IosRow(title = "Scent cone", subtitle = "Where scent goes in ~10 minutes", trailing = { Switch(checked = showCone, onCheckedChange = { showCone = it }) })
            }
            IosSection(
                header = "Flow particles",
                footer = "Scent drift = thermal + wind, what deer smell. Thermal only shows the slope breeze by itself.",
            ) {
                IosSegmentedControl(
                    options = ParticleMode.entries.map { it.label },
                    selectedIndex = ParticleMode.entries.indexOf(mode),
                    onSelect = { mode = ParticleMode.entries[it] },
                    modifier = Modifier.padding(12.dp),
                )
                IosDivider(startInset = 16.dp)
                IosSegmentedControl(
                    options = ParticleDensity.entries.map { it.label },
                    selectedIndex = ParticleDensity.entries.indexOf(particleDensity),
                    onSelect = { particleDensity = ParticleDensity.entries[it] },
                    modifier = Modifier.padding(12.dp),
                )
            }
            IosSection(footer = "Long-press anywhere on the map to move your stand there.") {
                IosRow(title = "Stand", value = state.locationLabel.ifBlank { if (state.hasLocation) "Custom" else "Not set" })
            }
        }
    }
}

// ───────────────────────── Overlays ─────────────────────────

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
        modifier = modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun RoundGlassButton(onClick: () -> Unit, testTag: String, content: @Composable () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
        modifier = Modifier.size(44.dp).testTag(testTag),
    ) {
        IconButton(onClick = onClick) { content() }
    }
}

@Composable
private fun HudCard(flow: ThermalHour, hour: Float, mode: ParticleMode) {
    GlassCard(Modifier.testTag("wind_map_hud")) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(phaseColor(flow.phase)))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${ThermalWindEngine.formatHour(hour.toDouble())} · %.0f°F".format(flow.temperatureF),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(flow.phase.label, color = phaseColor(flow.phase), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (mode == ParticleMode.SCENT) {
                        "Scent → ${ThermalWindEngine.compass(flow.scentTowardDegrees)} %.1f mph".format(flow.scentSpeedMph)
                    } else {
                        flow.thermalTowardDegrees?.let { "Thermal → ${ThermalWindEngine.compass(it)} %.1f mph".format(flow.thermalSpeedMph) }
                            ?: "Thermal %.1f mph, mostly vertical".format(flow.thermalSpeedMph)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Wind in cover %.1f mph → %s · confidence %d%%".format(
                        flow.groundWindMph,
                        ThermalWindEngine.compass(flow.windTowardDegrees),
                        (flow.confidence * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ScentDial(flow, Modifier.size(56.dp))
        }
    }
}

/** Small compass: grey wind arrow, phase-colored thermal arrow, red scent arrow. North is up. */
@Composable
private fun ScentDial(flow: ThermalHour, modifier: Modifier) {
    val ring = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 2.dp.toPx()
        drawCircle(ring, radius = r, center = c, style = Stroke(1.dp.toPx()))
        drawDirectionArrow(c, flow.windTowardDegrees, r * 0.5f, WindColor.copy(alpha = 0.7f), 2.dp.toPx())
        flow.thermalTowardDegrees?.let { drawDirectionArrow(c, it, r * 0.45f, phaseColor(flow.phase), 2.5.dp.toPx()) }
        drawDirectionArrow(c, flow.scentTowardDegrees, r * 0.85f, ScentColor, 3.dp.toPx())
    }
}

@Composable
private fun TimeScrubber(
    hour: Float,
    playing: Boolean,
    onHour: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    forecastSwitches: List<Double>,
    modifier: Modifier = Modifier,
) {
    GlassCard(modifier.testTag("wind_map_scrubber")) {
        Column(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onTogglePlay, modifier = Modifier.testTag("wind_map_play")) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play the day",
                    )
                }
                Column(Modifier.weight(1f)) {
                    Slider(
                        value = hour,
                        onValueChange = onHour,
                        valueRange = MAP_FIRST_HOUR..MAP_LAST_HOUR,
                        modifier = Modifier.height(32.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("4a", "8a", "12p", "4p", "9p").forEach {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (forecastSwitches.isNotEmpty()) {
                Text(
                    "Thermal switches: " + forecastSwitches.joinToString(" · ") { ThermalWindEngine.formatHour(it) },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/**
 * Streaks that flow with the local scent/thermal motion of the ground under them. Particles
 * dim while the map is being dragged (the field is re-sampled when the camera settles).
 */
@Composable
private fun ParticleOverlay(
    grid: GroundGrid?,
    motions: Array<CellMotion?>,
    count: Int,
    lastMoveMs: () -> Long,
    modifier: Modifier = Modifier,
) {
    val currentGrid by rememberUpdatedState(grid)
    val currentMotions by rememberUpdatedState(motions)
    val particles = remember(count) { ParticleBuffer(count) }
    var frame by remember { mutableLongStateOf(0L) }
    val strokePx = with(LocalDensity.current) { 2.2.dp.toPx() }

    LaunchedEffect(particles) {
        var last = 0L
        while (true) {
            withInfiniteAnimationFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                val g = currentGrid
                if (g != null) particles.step(dt, g, currentMotions)
                frame = now
            }
        }
    }

    BoxWithConstraints(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            if (frame < 0L) return@Canvas // reading the frame clock redraws every frame
            if (currentGrid == null) return@Canvas
            val moving = System.currentTimeMillis() - lastMoveMs() < 350L
            val dimming = if (moving) 0.25f else 1f
            for (i in 0 until particles.size) {
                val cellIndex = particles.cell[i]
                if (cellIndex < 0) continue
                val motion = currentMotions.getOrNull(cellIndex) ?: continue
                val life = particles.age[i] / particles.life[i]
                val fade = (if (life < 0.15f) life / 0.15f else if (life > 0.75f) (1f - life) / 0.25f else 1f).coerceIn(0f, 1f)
                val tail = (6f + motion.speedPx * 0.22f).coerceAtMost(40f)
                val x = particles.x[i]
                val y = particles.y[i]
                drawLine(
                    color = Color(motion.color).copy(alpha = 0.9f * fade * dimming),
                    start = Offset(x - particles.vx[i] * tail, y - particles.vy[i] * tail),
                    end = Offset(x, y),
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** Struct-of-arrays particle store (no per-frame allocation). */
private class ParticleBuffer(val size: Int) {
    val x = FloatArray(size)
    val y = FloatArray(size)
    val vx = FloatArray(size)
    val vy = FloatArray(size)
    val age = FloatArray(size)
    val life = FloatArray(size) { 1f }
    val cell = IntArray(size) { -1 }
    private val rnd = Random(42)

    fun step(dt: Float, grid: GroundGrid, motions: Array<CellMotion?>) {
        for (i in 0 until size) {
            age[i] += dt
            var c = cell[i]
            if (c < 0 || age[i] >= life[i]) {
                respawn(i, grid, motions)
                continue
            }
            val m = motions.getOrNull(c)
            if (m == null) {
                respawn(i, grid, motions)
                continue
            }
            var dx = m.dx
            var dy = m.dy
            if (m.swirl) {
                // Switching thermals: scent eddies around instead of travelling.
                val a = age[i] * 2.4f + i
                dx = cos(a)
                dy = sin(a)
            }
            vx[i] = dx
            vy[i] = dy
            x[i] += dx * m.speedPx * dt
            y[i] += dy * m.speedPx * dt
            c = grid.cellAt(x[i], y[i])
            cell[i] = c
            if (c < 0 || grid.cells[c] == null) respawn(i, grid, motions)
        }
    }

    private fun respawn(i: Int, grid: GroundGrid, motions: Array<CellMotion?>) {
        repeat(6) {
            val px = rnd.nextFloat() * grid.width
            val py = rnd.nextFloat() * grid.height
            val c = grid.cellAt(px, py)
            if (c >= 0 && grid.cells[c] != null && motions.getOrNull(c) != null) {
                x[i] = px
                y[i] = py
                cell[i] = c
                age[i] = 0f
                life[i] = 2f + rnd.nextFloat() * 2.5f
                return
            }
        }
        cell[i] = -1
        age[i] = 0f
    }
}

// ───────────────────────── Field sampling ─────────────────────────

private const val GRID_COLS = 9
private const val GRID_ROWS = 15
private const val SLOPE_SPACING_M = 60.0

/**
 * Samples the ground under a grid of screen points: where each is on the map, which way is north
 * and east on screen there (handles pitch and rotation), and the local slope from map elevation.
 * Cells that land in the sky (high pitch) are left empty. If elevation isn't available yet the
 * stand's own terrain is used everywhere.
 */
private fun sampleGround(map: MapboxMap, width: Float, height: Float, fallback: SiteTerrain?): GroundGrid {
    val cells = arrayOfNulls<GroundCell>(GRID_COLS * GRID_ROWS)
    for (r in 0 until GRID_ROWS) {
        for (c in 0 until GRID_COLS) {
            val px = (c + 0.5f) * width / GRID_COLS
            val py = (r + 0.5f) * height / GRID_ROWS
            val point = runCatching { map.coordinateForPixel(ScreenCoordinate(px.toDouble(), py.toDouble())) }.getOrNull() ?: continue
            val back = runCatching { map.pixelForCoordinate(point) }.getOrNull() ?: continue
            if (hypot(back.x - px, back.y - py) > 30.0) continue // sky / off-globe
            val lat = point.latitude()
            val lon = point.longitude()
            val (nLat, nLon) = ThermalMapGeometry.destination(lat, lon, 0.0, SLOPE_SPACING_M)
            val (eLat, eLon) = ThermalMapGeometry.destination(lat, lon, 90.0, SLOPE_SPACING_M)
            val (sLat, sLon) = ThermalMapGeometry.destination(lat, lon, 180.0, SLOPE_SPACING_M)
            val (wLat, wLon) = ThermalMapGeometry.destination(lat, lon, 270.0, SLOPE_SPACING_M)
            val pN = map.pixelForCoordinate(Point.fromLngLat(nLon, nLat))
            val pE = map.pixelForCoordinate(Point.fromLngLat(eLon, eLat))
            val terrain = run {
                val zN = map.getElevation(Point.fromLngLat(nLon, nLat))
                val zS = map.getElevation(Point.fromLngLat(sLon, sLat))
                val zE = map.getElevation(Point.fromLngLat(eLon, eLat))
                val zW = map.getElevation(Point.fromLngLat(wLon, wLat))
                if (zN != null && zS != null && zE != null && zW != null) {
                    val (uphill, slope) = ThermalMapGeometry.slopeFromSamples(zN, zS, zE, zW, SLOPE_SPACING_M)
                    ThermalMapGeometry.cellTerrain(uphill, slope)
                } else {
                    fallback ?: ThermalMapGeometry.cellTerrain(null, 0.0)
                }
            }
            cells[r * GRID_COLS + c] = GroundCell(
                px = px,
                py = py,
                northX = ((pN.x - px) / SLOPE_SPACING_M).toFloat(),
                northY = ((pN.y - py) / SLOPE_SPACING_M).toFloat(),
                eastX = ((pE.x - px) / SLOPE_SPACING_M).toFloat(),
                eastY = ((pE.y - py) / SLOPE_SPACING_M).toFloat(),
                terrain = terrain,
            )
        }
    }
    return GroundGrid(GRID_COLS, GRID_ROWS, width, height, cells)
}

private fun cellMotion(cell: GroundCell, flow: ThermalHour, mode: ParticleMode, pxPerDp: Float): CellMotion {
    val (bearing, mph) = when (mode) {
        ParticleMode.SCENT -> flow.scentTowardDegrees to flow.scentSpeedMph
        ParticleMode.THERMAL -> (flow.thermalTowardDegrees ?: flow.scentTowardDegrees) to
            (if (flow.thermalTowardDegrees == null) 0.15 else flow.thermalSpeedMph)
    }
    val rad = Math.toRadians(bearing)
    // Screen direction of a ground bearing = east·sin + north·cos (perspective-correct).
    var sx = (cell.eastX * sin(rad) + cell.northX * cos(rad)).toFloat()
    var sy = (cell.eastY * sin(rad) + cell.northY * cos(rad)).toFloat()
    val len = hypot(sx, sy)
    val perspective = len
    if (len > 1e-6f) {
        sx /= len
        sy /= len
    } else {
        sx = 0f
        sy = -1f
    }
    // Pixels per second: visibly faster for stronger flow; nearer ground (bigger px/m) moves faster.
    val depth = (perspective / 0.35f).coerceIn(0.45f, 1.6f)
    val speed = (14f + mph.toFloat() * 22f) * pxPerDp * depth
    val swirl = flow.phase == ThermalPhase.SWITCHING && abs(flow.tendency) < 0.03 && mph < 1.0
    return CellMotion(sx, sy, speed, phaseColor(flow.phase).toArgb(), swirl)
}

// ───────────────────────── Map layers ─────────────────────────

private fun buildCone(lat: Double?, lon: Double?, flow: ThermalHour?): FeatureCollection {
    if (lat == null || lon == null || flow == null) return FeatureCollection.fromFeatures(emptyList())
    val ring = ThermalMapGeometry.scentCone(
        lat = lat,
        lon = lon,
        towardDeg = flow.scentTowardDegrees,
        lengthMeters = ThermalMapGeometry.driftMeters(flow.scentSpeedMph),
        halfAngleDeg = ThermalMapGeometry.coneHalfAngle(flow.phase, flow.confidence),
    )
    val polygon = Polygon.fromLngLats(listOf(ring.map { (la, lo) -> Point.fromLngLat(lo, la) }))
    return FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(polygon)))
}

private fun buildStand(lat: Double?, lon: Double?): FeatureCollection =
    if (lat == null || lon == null) {
        FeatureCollection.fromFeatures(emptyList())
    } else {
        FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(lon, lat))))
    }

private fun Color.toHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

private fun Style.installWindLayers(
    cone: FeatureCollection,
    stand: FeatureCollection,
    coneColor: Color,
    terrain3d: Boolean,
    showCone: Boolean,
): String {
    // Outdoors/satellite styles may already carry an elevation source; reuse it rather than
    // pulling the same DEM tiles a second time at a different tile size.
    val demId = styleSources.firstOrNull { it.type == "raster-dem" }?.id ?: run {
        if (!styleSourceExists(WindMapIds.DEM)) {
            addSource(
                rasterDemSource(WindMapIds.DEM) {
                    url(WindMapIds.DEM_URL)
                    tileSize(514)
                    maxzoom(14)
                },
            )
        }
        WindMapIds.DEM
    }
    if (terrain3d) setTerrain(terrain(demId) { exaggeration(1.4) })
    addSource(geoJsonSource(WindMapIds.CONE_SOURCE) { featureCollection(cone) })
    addSource(geoJsonSource(WindMapIds.STAND_SOURCE) { featureCollection(stand) })
    val vis = if (showCone) Visibility.VISIBLE else Visibility.NONE
    addLayer(
        fillLayer(WindMapIds.CONE_FILL, WindMapIds.CONE_SOURCE) {
            fillColor(coneColor.toHex())
            fillOpacity(0.28)
            visibility(vis)
        },
    )
    addLayer(
        lineLayer(WindMapIds.CONE_LINE, WindMapIds.CONE_SOURCE) {
            lineColor(coneColor.toHex())
            lineWidth(2.0)
            lineOpacity(0.9)
            visibility(vis)
        },
    )
    addLayer(
        circleLayer(WindMapIds.STAND_LAYER, WindMapIds.STAND_SOURCE) {
            circleRadius(8.0)
            circleColor("#FFFFFF")
            circleStrokeColor("#000000")
            circleStrokeWidth(3.0)
        },
    )
    return demId
}
