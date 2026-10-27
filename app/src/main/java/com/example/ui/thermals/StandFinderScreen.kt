package com.example.ui.thermals

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import com.example.thermals.LidarStandDem
import com.example.thermals.SiteDem
import com.example.thermals.StandFinder
import com.example.thermals.ElevationGrid
import com.example.data.ElevationGrid as TerrainRaster
import com.example.thermals.SiteTerrain
import com.example.thermals.ThermalHour
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.google.android.gms.maps.Projection
import kotlin.math.hypot
import kotlin.math.roundToInt
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import com.example.geospatial.GeoSpatialLibrary
import com.example.thermals.StandFinderViewModel
import com.example.thermals.StandPick
import com.example.thermals.ThermalMapGeometry
import com.example.thermals.ThermalWindEngine
import com.example.thermals.ThermalWindViewModel
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosIconRowDividerInset
import com.example.ui.ios.IosRow
import com.example.ui.ios.IosSection
import com.example.ui.ios.IosSheet
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

private val StandPinColor = Color(0xFFFF9F0A)
private val AreaPinColor = Color(0xFF30D158)

/**
 * AI stand finder on a real Google Map: numbered pins for the best stand sites, circles for the
 * best hunting areas, a red line showing which way scent drifts from each stand during its best
 * sit. Tap a pin (or a card) for the recommendation; long-press the map to search somewhere else.
 */
@Composable
fun StandFinderScreen(
    padding: PaddingValues,
    siteMetadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    /** Loaded LiDAR / site raster. Used when the search center falls inside it. */
    siteRaster: TerrainRaster? = null,
    modifier: Modifier = Modifier,
    thermalViewModel: ThermalWindViewModel = viewModel(),
    finderViewModel: StandFinderViewModel = viewModel(),
) {
    val thermal by thermalViewModel.state.collectAsStateWithLifecycle()
    val finder by finderViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(siteMetadata?.bounds) {
        val bounds = siteMetadata?.bounds ?: return@LaunchedEffect
        thermalViewModel.offerSiteLocation((bounds.minLat + bounds.maxLat) / 2.0, (bounds.minLon + bounds.maxLon) / 2.0, siteMetadata.siteName)
    }

    // Make sure the Maps SDK (and BitmapDescriptorFactory, used for the numbered pins) is ready.
    remember(context) { runCatching { MapsInitializer.initialize(context.applicationContext) } }

    // Location: open on the phone's GPS position, with the blue dot and a my-location button.
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var locationGranted by remember { mutableStateOf(hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        locationGranted = grants.values.any { it }
        // Same rule as offerSiteLocation: a site or typed pin already on the thermal tab stays put.
        if (locationGranted && !thermalViewModel.state.value.hasLocation) thermalViewModel.useDeviceLocation()
    }
    // First time Stands opens this session: GPS only when Thermals doesn't already have a location.
    var openedOnGps by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (openedOnGps) return@LaunchedEffect
        openedOnGps = true
        if (thermalViewModel.state.value.hasLocation) return@LaunchedEffect
        if (locationGranted) {
            thermalViewModel.useDeviceLocation()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val siteDem: SiteDem? = remember(siteRaster, siteMetadata) {
        if (siteRaster == null || siteMetadata == null) null else LidarStandDem.from(siteRaster, siteMetadata)
    }

    val lat = thermal.latitude
    val lon = thermal.longitude
    // Camera animations only start after the map has loaded: maps-compose throws (and kills the
    // app) if a pending animation sees the map detach before it was ever attached.
    var mapLoaded by remember { mutableStateOf(false) }
    val cameraPositionState = rememberCameraPositionState {
        position = if (lat != null && lon != null) {
            CameraPosition.fromLatLngZoom(LatLng(lat, lon), SEARCH_ZOOM)
        } else {
            CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3.5f)
        }
    }
    LaunchedEffect(lat, lon, mapLoaded) {
        if (mapLoaded && lat != null && lon != null) {
            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), SEARCH_ZOOM), 800) }
        }
    }
    LaunchedEffect(finder.selectedRank, mapLoaded) {
        if (!mapLoaded) return@LaunchedEffect
        finder.selected?.let { pick ->
            runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLng(LatLng(pick.latitude, pick.longitude)), 500) }
        }
    }

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
            siteDem = siteDem,
        )
    }

    // ── Wind on the map: the same terrain-aware scent particles as the Wind tab, at the hour picked here.
    var hour by rememberSaveable { mutableFloatStateOf(thermal.selectedHour.toFloat().coerceIn(STAND_FIRST_HOUR, STAND_LAST_HOUR)) }
    LaunchedEffect(thermal.selectedHour) {
        if (hour.roundToInt() != thermal.selectedHour) hour = thermal.selectedHour.toFloat().coerceIn(STAND_FIRST_HOUR, STAND_LAST_HOUR)
    }
    val inputs = remember(thermal) { ThermalWindViewModel.buildInputs(thermal) }
    val field = remember(inputs) { inputs?.let(ThermalWindEngine::flowField) }
    val hourKey = (hour * 4).roundToInt()
    val flowHere: ThermalHour? = remember(field, hourKey) { field?.at(hourKey / 4.0) }

    // Seconds since the AI was asked, ticking while it works.
    var aiElapsed by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(finder.aiStartedAtMs) {
        val started = finder.aiStartedAtMs
        if (started == null) {
            aiElapsed = null
            return@LaunchedEffect
        }
        while (true) {
            aiElapsed = (System.currentTimeMillis() - started) / 1000
            kotlinx.coroutines.delay(1_000)
        }
    }

    val density = LocalDensity.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var headerHeight by remember { mutableStateOf(0) }
    var cardsHeight by remember { mutableStateOf(0) }
    var lastMoveMs by remember { mutableLongStateOf(0L) }
    var settleTick by remember { mutableStateOf(0) }
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }.collect { moving ->
            lastMoveMs = System.currentTimeMillis()
            if (!moving) settleTick++
        }
    }
    var ground by remember { mutableStateOf<GroundGrid?>(null) }
    LaunchedEffect(settleTick, mapLoaded, boxSize, finder.grid, inputs?.terrain) {
        val projection = cameraPositionState.projection
        if (!mapLoaded || projection == null || boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        ground = sampleGoogleGround(projection, boxSize.width.toFloat(), boxSize.height.toFloat(), finder.grid, inputs?.terrain)
    }
    val pxPerDp = density.density
    val motions: Array<CellMotion?> = remember(ground, field, hourKey) {
        val g = ground
        val f = field
        if (g == null || f == null) {
            arrayOfNulls(0)
        } else {
            Array(g.cells.size) { i -> g.cells[i]?.let { cell -> cellMotion(cell, f.at(hourKey / 4.0, cell.terrain), ParticleMode.SCENT, pxPerDp) } }
        }
    }

    Box(modifier.fillMaxSize().padding(padding).onSizeChanged { boxSize = it }.testTag("stand_finder_screen")) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.HYBRID, isMyLocationEnabled = locationGranted),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = true,
                myLocationButtonEnabled = locationGranted,
            ),
            // Keeps Google's compass, my-location button and logo clear of the overlays.
            contentPadding = with(density) {
                PaddingValues(
                    top = headerHeight.toDp() + 16.dp,
                    bottom = if (finder.picks.isEmpty()) 12.dp else cardsHeight.toDp() + 16.dp,
                )
            },
            onMapLoaded = { mapLoaded = true },
            onMapLongClick = { latLng -> thermalViewModel.useLocation(latLng.latitude, latLng.longitude, "Map pin") },
        ) {
            // Search center and the 750-yard reach of the search.
            if (lat != null && lon != null) {
                Circle(
                    center = LatLng(lat, lon),
                    radius = StandFinder.SEARCH_RADIUS_M,
                    fillColor = Color.Transparent,
                    strokeColor = Color.White.copy(alpha = 0.55f),
                    strokeWidth = 3f,
                )
                val centerIcon = remember { runCatching { dotIcon(Color.White, 22) }.getOrNull() }
                Marker(
                    state = remember(lat, lon) { MarkerState(position = LatLng(lat, lon)) },
                    title = "Search center",
                    snippet = "Long-press the map to search somewhere else",
                    icon = centerIcon,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                )
            }
            finder.picks.forEach { pick -> key(pick.rank, pick.latitude, pick.longitude) {
                val position = LatLng(pick.latitude, pick.longitude)
                if (pick.kind == StandPick.Kind.AREA) {
                    Circle(
                        center = position,
                        radius = 75.0, // ~250 ft
                        fillColor = AreaPinColor.copy(alpha = 0.22f),
                        strokeColor = AreaPinColor,
                        strokeWidth = 4f,
                    )
                }
                pick.scentToward?.let { toward ->
                    // Arrow showing where scent drifts from the stand during its best sit (~400 ft).
                    val (eLat, eLon) = ThermalMapGeometry.destination(pick.latitude, pick.longitude, toward, 120.0)
                    val tip = LatLng(eLat, eLon)
                    val (lLat, lLon) = ThermalMapGeometry.destination(eLat, eLon, toward + 150.0, 28.0)
                    val (rLat, rLon) = ThermalMapGeometry.destination(eLat, eLon, toward - 150.0, 28.0)
                    val arrow = listOf(position, tip, LatLng(lLat, lLon), tip, LatLng(rLat, rLon))
                    Polyline(points = arrow, color = Color.White.copy(alpha = 0.85f), width = 13f, zIndex = 1f)
                    Polyline(points = arrow, color = ScentColor, width = 7f, zIndex = 2f)
                }
                val icon = remember(pick.rank, pick.kind) {
                    runCatching { numberedPin(pick.rank, if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor) }.getOrNull()
                }
                Marker(
                    state = remember(pick.latitude, pick.longitude) { MarkerState(position = position) },
                    title = "${pick.rank}. ${pick.title}",
                    snippet = pick.bestTime,
                    icon = icon,
                    onClick = {
                        finderViewModel.select(pick.rank)
                        sitHour(pick.bestTime)?.let { h ->
                            hour = h
                            thermalViewModel.selectHour(h.roundToInt())
                        }
                        true
                    },
                )
            } }
        }

        if (flowHere != null) {
            ParticleOverlay(
                grid = ground,
                motions = motions,
                count = STAND_PARTICLES,
                lastMoveMs = { lastMoveMs },
                alpha = 0.85f,
                modifier = Modifier.fillMaxSize().testTag("stand_wind_particles"),
            )
        }

        // Top: wind at the chosen hour, status, search.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp).onSizeChanged { headerHeight = it.height },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassCard {
                Column(Modifier.padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (flowHere != null) {
                            ScentDial(flowHere, Modifier.size(46.dp).testTag("stand_wind_dial"), mapBearing = cameraPositionState.position.bearing.toDouble())
                            Spacer(Modifier.width(10.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            if (flowHere != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(8.dp).clip(CircleShape).background(phaseColor(flowHere.phase)))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "${ThermalWindEngine.formatHour(hourKey / 4.0)} · ${ThermalWindEngine.phaseText(flowHere)}",
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    if (flowHere.scentSpeedMph < ThermalWindEngine.CALM_SCENT_MPH) {
                                        "Scent nearly still · wind in cover %.0f mph".format(flowHere.groundWindMph)
                                    } else {
                                        "Scent → %s %.1f mph · wind in cover from %s %.0f mph".format(
                                            ThermalWindEngine.compass(flowHere.scentTowardDegrees),
                                            flowHere.scentSpeedMph,
                                            ThermalWindEngine.compass(ThermalWindEngine.normalize(flowHere.windTowardDegrees + 180.0)),
                                            flowHere.groundWindMph,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("stand_wind_summary"),
                                )
                            } else {
                                Text("Best stands & hunt areas", style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                when {
                                    lat == null || lon == null -> "Long-press the map on your property, or set a location in Thermals."
                                    finder.running && finder.picks.isNotEmpty() ->
                                        "${finder.picks.size} on-device picks · ${(finder.stage ?: "AI refining…").removeSuffix("…")}${aiElapsed?.let { " ${it}s" } ?: "…"}"
                                    finder.running -> (finder.stage ?: "Working…") + (aiElapsed?.let { " ${it}s" } ?: "")
                                    finder.source != null -> "${finder.picks.size} picks · ${finder.source}"
                                    else -> "Scans 750 yards around you — terrain, thermals and wind — then the AI picks spots."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = {
                                if (locationGranted) {
                                    thermalViewModel.useDeviceLocation()
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                }
                            },
                            modifier = Modifier.testTag("stand_use_my_location"),
                        ) { Icon(Icons.Default.MyLocation, contentDescription = "Search where I am") }
                        if (finder.running) {
                            // Spinner doubles as Stop: the on-device picks stay on the map.
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable(onClickLabel = "Stop waiting for the AI") { finderViewModel.stopAi() }
                                    .testTag("stand_stop_ai"),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                Icon(Icons.Default.Close, contentDescription = "Stop", modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Button(
                                onClick = ::runSearch,
                                enabled = lat != null && lon != null,
                                modifier = Modifier.testTag("stand_find_button"),
                            ) { Text(if (finder.picks.isEmpty()) "Find" else "Again") }
                        }
                    }
                    if (flowHere != null) {
                        HourTrack(
                            hour = hour,
                            onHour = {
                                hour = it
                                thermalViewModel.selectHour(it.roundToInt())
                            },
                            first = STAND_FIRST_HOUR,
                            last = STAND_LAST_HOUR,
                            phaseAt = { h -> field?.at(h.toDouble())?.phase },
                            switches = listOfNotNull(thermal.forecast?.morningSwitchHour, thermal.forecast?.eveningSwitchHour),
                            height = 36.dp,
                            testTag = "stand_hour_track",
                        )
                    }
                    finder.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }

        // Bottom: swipeable pick cards.
        if (finder.picks.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .onSizeChanged { cardsHeight = it.height }
                    .testTag("stand_pick_cards"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(finder.picks, key = { it.rank }) { pick ->
                    PickCard(pick, selected = finder.selectedRank == pick.rank) {
                        finderViewModel.select(pick.rank)
                        // Show the wind for that sit.
                        sitHour(pick.bestTime)?.let { h ->
                            hour = h
                            thermalViewModel.selectHour(h.roundToInt())
                        }
                    }
                }
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
                if (finder.centerLat != null && finder.centerLon != null) {
                    IosDivider(startInset = 16.dp)
                    IosRow(
                        title = "From search center",
                        value = StandFinder.formatDistance(
                            StandFinder.distanceMeters(finder.centerLat!!, finder.centerLon!!, pick.latitude, pick.longitude),
                        ),
                    )
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
private fun PickCard(pick: StandPick, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (selected) 0.98f else 0.88f),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor) else null,
        modifier = Modifier.width(250.dp).clickable(onClick = onClick).testTag("stand_pick_${pick.rank}"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .padding(0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(11.dp), color = if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor) {
                        Text(
                            "${pick.rank}",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(pick.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(
                pick.bestTime.ifBlank { null },
                pick.scentToward?.let { "scent → ${ThermalWindEngine.compass(it)}" },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(pick.why, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round pin with a rank number, drawn once per rank. */
private fun numberedPin(number: Int, color: Color): BitmapDescriptor {
    val size = 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = color.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)
    paint.color = android.graphics.Color.BLACK
    paint.textSize = 40f
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(number.toString(), size / 2f, y, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun dotIcon(color: Color, sizePx: Int): BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
    paint.color = color.toArgb()
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

/** Fits the 750-yard search circle on a phone screen. */
private const val SEARCH_ZOOM = 14.6f
private const val STAND_FIRST_HOUR = 4f
private const val STAND_LAST_HOUR = 21f
private const val STAND_PARTICLES = 260
private const val DIRECTION_SPACING_M = 200.0

/** Hour to show for a pick's "best time" — morning sits at 7 AM, midday at noon, evening at 5 PM. */
internal fun sitHour(bestTime: String): Float? = when {
    bestTime.contains("morning", ignoreCase = true) || bestTime.contains("dawn", ignoreCase = true) -> 7f
    bestTime.contains("evening", ignoreCase = true) || bestTime.contains("dusk", ignoreCase = true) -> 17f
    bestTime.contains("midday", ignoreCase = true) || bestTime.contains("noon", ignoreCase = true) -> 12f
    else -> null
}

/**
 * The ground under a grid of screen points on the Google map: which way north/east run on screen
 * there, and the slope from the stand search's elevation grid (the stand's own terrain outside it).
 */
private fun sampleGoogleGround(
    projection: Projection,
    width: Float,
    height: Float,
    elevation: ElevationGrid?,
    fallback: SiteTerrain?,
): GroundGrid {
    val cells = arrayOfNulls<GroundCell>(FLOW_GRID_COLS * FLOW_GRID_ROWS)
    for (r in 0 until FLOW_GRID_ROWS) {
        for (c in 0 until FLOW_GRID_COLS) {
            val px = (c + 0.5f) * width / FLOW_GRID_COLS
            val py = (r + 0.5f) * height / FLOW_GRID_ROWS
            val here = runCatching { projection.fromScreenLocation(android.graphics.Point(px.roundToInt(), py.roundToInt())) }.getOrNull() ?: continue
            val (nLat, nLon) = ThermalMapGeometry.destination(here.latitude, here.longitude, 0.0, DIRECTION_SPACING_M)
            val (eLat, eLon) = ThermalMapGeometry.destination(here.latitude, here.longitude, 90.0, DIRECTION_SPACING_M)
            val pN = runCatching { projection.toScreenLocation(LatLng(nLat, nLon)) }.getOrNull() ?: continue
            val pE = runCatching { projection.toScreenLocation(LatLng(eLat, eLon)) }.getOrNull() ?: continue
            val northX = ((pN.x - px) / DIRECTION_SPACING_M).toFloat()
            val northY = ((pN.y - py) / DIRECTION_SPACING_M).toFloat()
            if (hypot(northX, northY) < 1e-4f) continue
            val terrain = elevation?.terrainAt(here.latitude, here.longitude)
                ?: fallback
                ?: ThermalMapGeometry.cellTerrain(null, 0.0)
            cells[r * FLOW_GRID_COLS + c] = GroundCell(
                px = px,
                py = py,
                northX = northX,
                northY = northY,
                eastX = ((pE.x - px) / DIRECTION_SPACING_M).toFloat(),
                eastY = ((pE.y - py) / DIRECTION_SPACING_M).toFloat(),
                terrain = terrain,
            )
        }
    }
    return GroundGrid(FLOW_GRID_COLS, FLOW_GRID_ROWS, width, height, cells)
}
