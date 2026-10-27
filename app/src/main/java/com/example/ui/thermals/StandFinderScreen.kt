package com.example.ui.thermals

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.BuildConfig
import com.example.geospatial.GeoSpatialLibrary
import com.example.thermals.StandFinderViewModel
import com.example.thermals.StandPick
import com.example.thermals.ThermalMapGeometry
import com.example.thermals.ThermalWindEngine
import com.example.thermals.ThermalWindViewModel
import com.example.ui.components.mapbox.LidarReliefOverlay
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosIconRowDividerInset
import com.example.ui.ios.IosRow
import com.example.ui.ios.IosSection
import com.example.ui.ios.IosSegmentedControl
import com.example.ui.ios.IosSheet
import com.mapbox.common.Cancelable
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.RenderedQueryGeometry
import com.mapbox.maps.RenderedQueryOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.rasterDemSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.terrain.generated.removeTerrain
import com.mapbox.maps.extension.style.terrain.generated.setTerrain
import com.mapbox.maps.extension.style.terrain.generated.terrain
import kotlinx.coroutines.awaitCancellation

private val StandPinColor = Color(0xFFFF9F0A)
private val AreaPinColor = Color(0xFF30D158)

/** LiDAR relief auto-shows at this zoom and closer (unless the user disabled it). */
private const val LIDAR_AUTO_ZOOM = 12.0

private object StandMapIds {
    const val DEM = "stands-dem"
    const val DEM_URL = "mapbox://mapbox.mapbox-terrain-dem-v1"
    const val AREA_SOURCE = "stands-areas"
    const val AREA_FILL = "stands-area-fill"
    const val AREA_OUTLINE = "stands-area-outline"
    const val SCENT_SOURCE = "stands-scent"
    const val SCENT_LAYER = "stands-scent-line"
    const val CENTER_SOURCE = "stands-center"
    const val CENTER_LAYER = "stands-center-dot"
    const val PIN_SOURCE = "stands-pins"
    const val PIN_CIRCLE = "stands-pin-circle"
    const val PIN_LABEL = "stands-pin-label"
}

private enum class StandMapBase(val label: String, val uri: String) {
    SATELLITE("Satellite", Style.SATELLITE),
    LABELS("Labels", Style.SATELLITE_STREETS),
    OUTDOORS("Topo", Style.OUTDOORS),
}

/**
 * AI stand finder on a Mapbox map: numbered pins for the best stand sites, circles for the best
 * hunting areas, a red dashed line showing which way scent drifts from each stand during its best
 * sit. Tap a pin (or a card) for the recommendation; long-press the map to search somewhere else.
 * Base style and 3D terrain (on by default) are user options; the 1 m USGS LiDAR relief overlay
 * draws itself automatically past zoom 12 and can be disabled in Map options.
 */
@Composable
fun StandFinderScreen(
    padding: PaddingValues,
    siteMetadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    modifier: Modifier = Modifier,
    thermalViewModel: ThermalWindViewModel = viewModel(),
    finderViewModel: StandFinderViewModel = viewModel(),
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
            IosSection(header = "Stands map", footer = tokenProblem) {
                IosRow(
                    title = if (token.isBlank()) "Mapbox token needed" else "Mapbox token is invalid",
                    titleColor = MaterialTheme.colorScheme.error,
                )
            }
        }
        return
    }
    remember(token) { MapboxOptions.accessToken = token }

    val thermal by thermalViewModel.state.collectAsStateWithLifecycle()
    val finder by finderViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(siteMetadata?.bounds) {
        val bounds = siteMetadata?.bounds ?: return@LaunchedEffect
        thermalViewModel.offerSiteLocation((bounds.minLat + bounds.maxLat) / 2.0, (bounds.minLon + bounds.maxLon) / 2.0, siteMetadata.siteName)
    }

    // Map options.
    var base by rememberSaveable { mutableStateOf(StandMapBase.SATELLITE) }
    var terrain3d by rememberSaveable { mutableStateOf(true) }
    var lidarEnabled by rememberSaveable { mutableStateOf(true) }
    var lidarForcedOff by rememberSaveable { mutableStateOf(false) }
    var reliefOpacity by rememberSaveable { mutableStateOf(0.55f) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val reliefOverlay = remember(context) { LidarReliefOverlay(context, scope) }
    val reliefLoading by reliefOverlay.loading.collectAsState()
    val reliefResolution by reliefOverlay.resolutionMeters.collectAsState()
    var cameraZoom by remember { mutableStateOf(3.5) }
    // Auto at zoom 12+, but never on when the user explicitly disabled the overlay this session.
    val reliefVisible = lidarEnabled && !lidarForcedOff && cameraZoom >= LIDAR_AUTO_ZOOM

    val lat = thermal.latitude
    val lon = thermal.longitude

    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            if (lat != null && lon != null) {
                center(Point.fromLngLat(lon, lat))
                zoom(15.0)
                pitch(45.0)
            } else {
                center(Point.fromLngLat(-98.35, 39.5))
                zoom(3.5)
            }
        }
    }
    fun recenter() {
        val la = thermal.latitude ?: return
        val lo = thermal.longitude ?: return
        mapViewportState.flyTo(
            CameraOptions.Builder()
                .center(Point.fromLngLat(lo, la))
                .zoom(15.0)
                .pitch(if (terrain3d) 45.0 else 0.0)
                .build(),
        )
    }
    LaunchedEffect(lat, lon) { if (lat != null && lon != null) recenter() }
    LaunchedEffect(finder.selectedRank) {
        finder.selected?.let { pick ->
            mapViewportState.easeTo(
                CameraOptions.Builder().center(Point.fromLngLat(pick.longitude, pick.latitude)).build(),
            )
        }
    }

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var mapLoadError by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        val la = thermal.latitude ?: return
        val lo = thermal.longitude ?: return
        finderViewModel.find(
            lat = la,
            lon = lo,
            inputs = ThermalWindViewModel.buildInputs(thermal),
            forecast = thermal.forecast,
            locationLabel = thermal.locationLabel,
            dateLabel = ThermalWindViewModel.dateLabel(thermal),
        )
    }

    // GeoJSON payloads, rebuilt only when their inputs change.
    val pinFeatures = remember(finder.picks) {
        FeatureCollection.fromFeatures(
            finder.picks.map { pick ->
                Feature.fromGeometry(Point.fromLngLat(pick.longitude, pick.latitude)).apply {
                    addStringProperty("rank", pick.rank.toString())
                    addStringProperty(
                        "color",
                        (if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor).toHex(),
                    )
                }
            },
        )
    }
    val areaFeatures = remember(finder.picks) {
        FeatureCollection.fromFeatures(
            finder.picks.filter { it.kind == StandPick.Kind.AREA }.map { pick ->
                val ring = (0..32).map { i ->
                    val (la, lo) = ThermalMapGeometry.destination(pick.latitude, pick.longitude, i * 360.0 / 32.0, 140.0)
                    Point.fromLngLat(lo, la)
                }
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring)))
            },
        )
    }
    val scentFeatures = remember(finder.picks) {
        FeatureCollection.fromFeatures(
            finder.picks.mapNotNull { pick ->
                pick.scentToward?.let { toward ->
                    val (eLat, eLon) = ThermalMapGeometry.destination(pick.latitude, pick.longitude, toward, 180.0)
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            listOf(
                                Point.fromLngLat(pick.longitude, pick.latitude),
                                Point.fromLngLat(eLon, eLat),
                            ),
                        ),
                    )
                }
            },
        )
    }
    val centerFeatures = remember(lat, lon) {
        if (lat == null || lon == null) {
            FeatureCollection.fromFeatures(emptyList())
        } else {
            FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(Point.fromLngLat(lon, lat))))
        }
    }

    Box(modifier.fillMaxSize().padding(padding).testTag("stand_finder_screen")) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            scaleBar = {},
            onMapLongClickListener = { point ->
                thermalViewModel.useLocation(point.latitude(), point.longitude(), "Map pin")
                true
            },
            style = {},
        ) {
            MapEffect(Unit) { mapView ->
                mapViewRef = mapView
                val subs = mutableListOf<Cancelable>()
                // A rejected token or offline style fetch otherwise fails silently to a blank map.
                subs += mapView.mapboxMap.subscribeMapLoadingError { event ->
                    mapLoadError = buildString {
                        append("Map failed to load: ").append(event.message)
                        if (event.message.contains("token", ignoreCase = true) ||
                            event.message.contains("401") || event.message.contains("403")
                        ) {
                            append(" — check that MAPBOX_ACCESS_TOKEN is a valid public (pk.) token, then rebuild.")
                        }
                    }
                }
                // Track zoom so the LiDAR relief overlay auto-shows from level 12 in.
                subs += mapView.mapboxMap.subscribeCameraChangedCoalesced { event ->
                    event.cameraState.zoom.let { cameraZoom = it }
                }
                // Pin taps: query the pin layers at the tap point and select that stand.
                val clickListener = com.mapbox.maps.plugin.gestures.OnMapClickListener { point ->
                    val screen = mapView.mapboxMap.pixelForCoordinate(point)
                    var handled = false
                    mapView.mapboxMap.queryRenderedFeatures(
                        RenderedQueryGeometry.valueOf(screen),
                        RenderedQueryOptions(listOf(StandMapIds.PIN_CIRCLE, StandMapIds.PIN_LABEL), null),
                    ) { result ->
                        val rank = result.value?.firstOrNull()
                            ?.queriedFeature
                            ?.feature
                            ?.getStringProperty("rank")
                            ?.toIntOrNull()
                        if (rank != null) {
                            finderViewModel.select(rank)
                            handled = true
                        }
                    }
                    handled
                }
                mapView.mapboxMap.addOnMapClickListener(clickListener)
                try {
                    awaitCancellation()
                } finally {
                    subs.forEach { it.cancel() }
                    mapView.mapboxMap.removeOnMapClickListener(clickListener)
                }
            }
            MapEffect(base) { mapView ->
                mapView.mapboxMap.loadStyle(base.uri) { style ->
                    style.installStandLayers(pinFeatures, areaFeatures, scentFeatures, centerFeatures, terrain3d)
                    reliefOverlay.install(
                        style,
                        belowLayerId = StandMapIds.AREA_FILL,
                        opacity = reliefOpacity.toDouble(),
                        visible = reliefVisible,
                    )
                }
            }
            MapEffect(terrain3d) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    if (terrain3d) style.setTerrain(terrain(StandMapIds.DEM) { exaggeration(1.3) }) else style.removeTerrain()
                }
            }
            MapEffect(pinFeatures) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(StandMapIds.PIN_SOURCE)?.featureCollection(pinFeatures)
                }
            }
            MapEffect(areaFeatures) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(StandMapIds.AREA_SOURCE)?.featureCollection(areaFeatures)
                }
            }
            MapEffect(scentFeatures) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(StandMapIds.SCENT_SOURCE)?.featureCollection(scentFeatures)
                }
            }
            MapEffect(centerFeatures) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.getSourceAs<GeoJsonSource>(StandMapIds.CENTER_SOURCE)?.featureCollection(centerFeatures)
                }
            }
            MapEffect(reliefVisible, reliefOpacity) { _ ->
                reliefOverlay.setAppearance(reliefOpacity.toDouble(), reliefVisible)
            }
        }

        // Top: search button + status.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPanel {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Best stands & hunt areas", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    lat == null || lon == null -> "Long-press the map on your property, or set a location in Thermals."
                                    finder.running -> finder.stage ?: "Working…"
                                    finder.source != null -> "${finder.picks.size} picks · chosen by ${finder.source}"
                                    else -> "Scans 1.6 km of terrain, thermals and wind, then the AI picks spots."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (finder.running) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = ::runSearch,
                                enabled = lat != null && lon != null,
                                modifier = Modifier.testTag("stand_find_button"),
                            ) { Text(if (finder.picks.isEmpty()) "Find" else "Again") }
                        }
                    }
                    finder.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    mapLoadError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (reliefVisible && reliefLoading > 0) {
                        Text(
                            "Loading 1 m LiDAR relief…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Side: map options + recenter.
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RoundGlassButton(onClick = { showSettings = true }, testTag = "stands_map_settings") {
                Icon(Icons.Default.Tune, contentDescription = "Map options")
            }
            RoundGlassButton(onClick = { recenter() }, testTag = "stands_map_recenter") {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on search point")
            }
        }

        // Bottom: swipeable pick cards.
        if (finder.picks.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp).testTag("stand_pick_cards"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(finder.picks, key = { it.rank }) { pick ->
                    PickCard(pick, selected = finder.selectedRank == pick.rank) { finderViewModel.select(pick.rank) }
                }
            }
        }
    }

    if (showSettings) {
        IosSheet(title = "Stands map", onDismiss = { showSettings = false }, peek = true) {
            IosSection(header = "Base map") {
                IosSegmentedControl(
                    options = StandMapBase.entries.map { it.label },
                    selectedIndex = StandMapBase.entries.indexOf(base),
                    onSelect = { base = StandMapBase.entries[it] },
                    modifier = Modifier.padding(12.dp),
                )
                IosDivider(startInset = 16.dp)
                IosRow(title = "3D terrain", trailing = { Switch(checked = terrain3d, onCheckedChange = { terrain3d = it }) })
            }
            IosSection(
                header = "LiDAR relief",
                footer = "USGS 3DEP ≈1 m bare-earth hillshade. Draws itself automatically at zoom 12+ while enabled." +
                    (reliefResolution?.let { " Now showing ≈%.1f m cells.".format(it) } ?: ""),
            ) {
                IosRow(
                    title = "LiDAR relief overlay",
                    subtitle = if (reliefVisible) {
                        if (cameraZoom >= LIDAR_AUTO_ZOOM) "On (auto at this zoom)" else "On — zoom in to level 12+ to see it"
                    } else {
                        "Off"
                    },
                    trailing = {
                        Switch(
                            checked = lidarEnabled && !lidarForcedOff,
                            onCheckedChange = { on ->
                                lidarEnabled = on
                                lidarForcedOff = !on
                            },
                        )
                    },
                    modifier = Modifier.testTag("stands_lidar_toggle"),
                )
                if (lidarEnabled && !lidarForcedOff) {
                    IosDivider(startInset = 16.dp)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            "Opacity ${(reliefOpacity * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = reliefOpacity,
                            onValueChange = { reliefOpacity = it },
                            valueRange = 0.15f..1f,
                        )
                    }
                }
            }
            IosSection(footer = "Long-press anywhere on the map to search for stands there.") {
                IosRow(title = "Search point", value = thermal.locationLabel.ifBlank { if (lat != null) "Custom" else "Not set" })
            }
        }
    }

    finder.selected?.let { pick ->
        IosSheet(title = "${pick.rank}. ${pick.title}", onDismiss = { finderViewModel.select(null) }, peek = true) {
            IosSection(header = if (pick.kind == StandPick.Kind.AREA) "Hunting area" else "Stand", footer = finder.source?.let { "Picked by $it" }) {
                if (pick.bestTime.isNotBlank()) IosRow(title = "When", value = pick.bestTime)
                if (pick.wind.isNotBlank()) {
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Wind", subtitle = pick.wind)
                }
                pick.scentToward?.let {
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Scent drifts", value = "→ ${ThermalWindEngine.compass(it)}")
                }
            }
            if (pick.why.isNotBlank()) {
                IosSection(header = "Why here") {
                    Text(pick.why, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            if (pick.entry.isNotBlank()) {
                IosSection(header = "Getting in and out") {
                    Text(pick.entry, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            IosSection {
                IosRow(
                    title = "Navigate here",
                    icon = Icons.Default.Directions,
                    showChevron = true,
                    onClick = {
                        val uri = Uri.parse("google.navigation:q=${pick.latitude},${pick.longitude}&mode=w")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${pick.latitude},${pick.longitude}?q=${pick.latitude},${pick.longitude}"))
                        runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(fallback) } }
                    },
                )
                IosDivider(startInset = IosIconRowDividerInset)
                IosRow(
                    title = "Use as my stand",
                    subtitle = "Thermals and Wind Map switch to this spot",
                    icon = Icons.Default.Place,
                    showChevron = true,
                    onClick = {
                        thermalViewModel.useLocation(pick.latitude, pick.longitude, "${pick.rank}. ${pick.title}")
                        finderViewModel.select(null)
                    },
                )
                IosDivider(startInset = IosIconRowDividerInset)
                IosRow(
                    title = "%.5f, %.5f".format(pick.latitude, pick.longitude),
                    icon = Icons.Default.Forest,
                    titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GlassPanel(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth(),
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
private fun PickCard(pick: StandPick, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (selected) 1f else 0.9f),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(230.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(11.dp), color = if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor) {
                    Text(
                        "${pick.rank}",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(pick.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pick.bestTime.isNotBlank()) {
                Text(pick.bestTime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(pick.why, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun Color.toHex(): String = String.format("#%06X", 0xFFFFFF and toArgb())

/** Adds every stand-finder source and layer to a freshly loaded style, bottom to top. */
private fun Style.installStandLayers(
    pins: FeatureCollection,
    areas: FeatureCollection,
    scent: FeatureCollection,
    center: FeatureCollection,
    terrain3d: Boolean,
) {
    if (!styleSourceExists(StandMapIds.DEM)) {
        addSource(
            rasterDemSource(StandMapIds.DEM) {
                url(StandMapIds.DEM_URL)
                tileSize(514)
                maxzoom(14)
            },
        )
    }
    if (terrain3d) setTerrain(terrain(StandMapIds.DEM) { exaggeration(1.3) })

    addSource(geoJsonSource(StandMapIds.AREA_SOURCE) { featureCollection(areas) })
    addSource(geoJsonSource(StandMapIds.SCENT_SOURCE) { featureCollection(scent) })
    addSource(geoJsonSource(StandMapIds.CENTER_SOURCE) { featureCollection(center) })
    addSource(geoJsonSource(StandMapIds.PIN_SOURCE) { featureCollection(pins) })

    addLayer(
        fillLayer(StandMapIds.AREA_FILL, StandMapIds.AREA_SOURCE) {
            fillColor(AreaPinColor.toHex())
            fillOpacity(0.22)
        },
    )
    addLayer(
        lineLayer(StandMapIds.AREA_OUTLINE, StandMapIds.AREA_SOURCE) {
            lineColor(AreaPinColor.toHex())
            lineWidth(2.5)
        },
    )
    addLayer(
        lineLayer(StandMapIds.SCENT_LAYER, StandMapIds.SCENT_SOURCE) {
            lineColor(ScentColor.toHex())
            lineWidth(4.0)
            lineDasharray(listOf(2.0, 1.4))
        },
    )
    addLayer(
        circleLayer(StandMapIds.CENTER_LAYER, StandMapIds.CENTER_SOURCE) {
            circleRadius(6.0)
            circleColor("#FFFFFF")
            circleStrokeColor("#000000")
            circleStrokeWidth(2.5)
        },
    )
    addLayer(
        circleLayer(StandMapIds.PIN_CIRCLE, StandMapIds.PIN_SOURCE) {
            circleRadius(11.0)
            circleColor(Expression.get("color"))
            circleStrokeColor("#000000")
            circleStrokeWidth(2.0)
        },
    )
    addLayer(
        symbolLayer(StandMapIds.PIN_LABEL, StandMapIds.PIN_SOURCE) {
            textField(Expression.get("rank"))
            textSize(12.0)
            textColor("#000000")
            textAllowOverlap(true)
            textIgnorePlacement(true)
        },
    )
}
