package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.data.ElevationGrid
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.FieldWaypoint
import com.example.data.historicmap.HistoricTopoEras
import com.example.data.relief.ReliefMode
import com.example.data.relief.ReliefSettings
import com.example.data.relief.TerrainModel
import com.example.data.relief.TerrainModelBuilder
import com.example.data.relief.ThreeDepElevationClient
import com.example.data.survey.SurveyFeature
import com.example.geospatial.GeoSpatialLibrary
import com.example.ui.components.mapbox.HistoricTopoOverlay
import com.example.ui.components.mapbox.LidarReliefOverlay
import com.mapbox.common.MapboxOptions
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapEffect
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.RasterLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.fillLayer
import com.mapbox.maps.extension.style.layers.generated.hillshadeLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.generated.rasterLayer
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.getLayerAs
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.ImageSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.generated.imageSource
import com.mapbox.maps.extension.style.sources.generated.rasterDemSource
import com.mapbox.maps.extension.style.sources.generated.rasterSource
import com.mapbox.maps.extension.style.sources.getSourceAs
import com.mapbox.maps.extension.style.sources.updateImage
import com.mapbox.maps.extension.style.terrain.generated.removeTerrain
import com.mapbox.maps.extension.style.terrain.generated.setTerrain
import com.mapbox.maps.extension.style.terrain.generated.terrain
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.viewport.data.OverviewViewportStateOptions
import com.mapbox.maps.toCameraOptions
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Base map styles offered on the Mapbox tab. */
private enum class MapboxBaseStyle(val label: String, val uri: String) {
    SATELLITE("Satellite", Style.SATELLITE_STREETS),
    SATELLITE_CLEAN("Satellite (no labels)", Style.SATELLITE),
    OUTDOORS("Outdoors", Style.OUTDOORS),
}

/** Control panels on the Mapbox tab. */
private enum class MapboxPanel(val label: String) {
    MAP("Map"),
    LIDAR("LiDAR"),
    TOPO("Old topo"),
    MODEL("3D model"),
}

private object MapboxIds {
    const val DEM_SOURCE = "findit-dem"
    const val MODEL_DEM_SOURCE = "findit-dem-1m"
    const val USGS_SOURCE = "findit-usgs-topo"
    const val USGS_LAYER = "findit-usgs-topo-layer"
    const val HILLSHADE_LAYER = "findit-hillshade"
    const val LIDAR_SOURCE = "findit-lidar"
    const val LIDAR_LAYER = "findit-lidar-layer"
    const val SURVEY_POLYGON_SOURCE = "findit-survey-polygons"
    const val SURVEY_POLYGON_FILL = "findit-survey-polygon-fill"
    const val SURVEY_POLYGON_OUTLINE = "findit-survey-polygon-outline"
    const val SURVEY_LINE_SOURCE = "findit-survey-lines"
    const val SURVEY_LINE_LAYER = "findit-survey-line-layer"
    const val SURVEY_POINT_SOURCE = "findit-survey-points"
    const val SURVEY_POINT_LAYER = "findit-survey-point-layer"
    const val BREADCRUMB_SOURCE = "findit-breadcrumbs"
    const val BREADCRUMB_LAYER = "findit-breadcrumb-layer"
    const val ROUTE_LINE_SOURCE = "findit-route-line"
    const val ROUTE_LINE_LAYER = "findit-route-line-layer"
    const val ROUTE_STOP_SOURCE = "findit-route-stops"
    const val ROUTE_STOP_LAYER = "findit-route-stop-layer"

    /** Mapbox's global terrain DEM; drives both 3D terrain and hillshade. */
    const val DEM_URL = "mapbox://mapbox.mapbox-terrain-dem-v1"

    /** USGS National Map topo tiles (public domain, US coverage). Same service as the offline basemap. */
    const val USGS_TOPO_TILES =
        "https://basemap.nationalmap.gov/arcgis/rest/services/USGSTopo/MapServer/tile/{z}/{y}/{x}"
}

/** Toggles and sliders that change layer appearance without changing data. */
private data class MapboxDisplayState(
    val terrain3d: Boolean,
    val exaggeration: Float,
    val hillshade: Boolean,
    val usgsTopo: Boolean,
    val showLidar: Boolean,
    val lidarOpacity: Float,
    /** Use the 1 m model's elevation for 3D terrain instead of Mapbox's global DEM. */
    val useModel: Boolean,
)

/** The LiDAR hillshade bitmap and where it sits. Identity equality: rebuilt only when inputs change. */
private class MapboxLidarImage(val bitmap: Bitmap?, val corners: List<List<Double>>?)

/** GeoJSON for every vector layer. Identity equality: rebuilt only when inputs change. */
private class MapboxVectorData(
    val surveyPoints: FeatureCollection,
    val surveyLines: FeatureCollection,
    val surveyPolygons: FeatureCollection,
    val breadcrumbs: FeatureCollection,
    val routeLine: FeatureCollection,
    val routeStops: FeatureCollection,
)

/**
 * Mapbox-powered site map: satellite/outdoor base maps, 3D terrain, live 1 m LiDAR relief from
 * USGS 3DEP, auto-aligned historic USGS topo maps by era, a 1 m 3D model of the view, and the
 * active LiDAR hillshade placed with the same saved alignment as the Google map tab.
 */
@OptIn(FlowPreview::class)
@Composable
fun MapboxTerrainMapScreen(
    terrainBitmap: Bitmap?,
    grid: ElevationGrid,
    metadata: GeoSpatialLibrary.GeoSpatialMetadata,
    terrainKey: String,
    surveyFeatures: List<SurveyFeature> = emptyList(),
    breadcrumbTracks: List<BreadcrumbTrack> = emptyList(),
    /** Supplied when the host can act on a search box; omitting it hides the search control. */
    onFindLidarTiles: ((GeoSpatialLibrary.GeographicBounds) -> Unit)? = null,
    routeWaypoints: List<FieldWaypoint> = emptyList(),
    routeTotalMeters: Float = 0f,
    onClearRoute: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val token = BuildConfig.MAPBOX_ACCESS_TOKEN
    if (token.isBlank()) {
        MapboxTokenMissing(modifier)
        return
    }
    // Must be set before the first MapView is created.
    remember(token) { MapboxOptions.accessToken = token }

    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val alignmentStore = remember(context) { TerrainMapAlignmentStore(context.applicationContext) }

    // --- Map panel -------------------------------------------------------------------------
    var baseStyle by rememberSaveable { mutableStateOf(MapboxBaseStyle.SATELLITE) }
    var terrain3d by rememberSaveable { mutableStateOf(true) }
    var exaggeration by rememberSaveable { mutableFloatStateOf(1.5f) }
    var hillshade by rememberSaveable { mutableStateOf(false) }
    var usgsTopo by rememberSaveable { mutableStateOf(false) }
    var showLidar by rememberSaveable { mutableStateOf(true) }
    var lidarOpacity by rememberSaveable { mutableFloatStateOf(0.72f) }
    var controlsExpanded by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf(MapboxPanel.MAP) }

    // --- LiDAR relief panel ----------------------------------------------------------------
    var reliefOn by rememberSaveable { mutableStateOf(false) }
    var reliefOpacity by rememberSaveable { mutableFloatStateOf(0.85f) }
    var reliefMode by rememberSaveable { mutableStateOf(ReliefMode.HILLSHADE) }
    var sunAzimuth by rememberSaveable { mutableFloatStateOf(315f) }
    var sunAltitude by rememberSaveable { mutableFloatStateOf(35f) }
    var zFactor by rememberSaveable { mutableFloatStateOf(2f) }
    var multiDirection by rememberSaveable { mutableStateOf(true) }
    var multiColour by rememberSaveable { mutableStateOf(false) }
    var radiusMeters by rememberSaveable { mutableFloatStateOf(10f) }
    var brightness by rememberSaveable { mutableFloatStateOf(0f) }
    var contrast by rememberSaveable { mutableFloatStateOf(1f) }
    val reliefSettings = ReliefSettings(
        mode = reliefMode,
        azimuthDeg = sunAzimuth,
        altitudeDeg = sunAltitude,
        zFactor = zFactor,
        multiDirection = multiDirection,
        multiColour = multiColour,
        radiusMeters = radiusMeters,
        brightness = brightness,
        contrast = contrast,
    )

    // --- Old topo panel --------------------------------------------------------------------
    var topoOn by rememberSaveable { mutableStateOf(false) }
    var topoOpacity by rememberSaveable { mutableFloatStateOf(0.85f) }
    var topoDecade by rememberSaveable { mutableStateOf<Int?>(null) }
    var topoDecades by remember { mutableStateOf<List<Int>>(emptyList()) }

    // --- 3D model panel --------------------------------------------------------------------
    var model by remember { mutableStateOf<TerrainModel?>(null) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    var modelBusy by remember { mutableStateOf(false) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var lastFramedTerrainKey by remember { mutableStateOf<String?>(null) }

    val reliefOverlay = remember(context) { LidarReliefOverlay(context, scope) }
    val topoOverlay = remember(context) { HistoricTopoOverlay(context, scope) }
    val reliefLoading by reliefOverlay.loading.collectAsState()
    val reliefResolution by reliefOverlay.resolutionMeters.collectAsState()
    val topoRecords by topoOverlay.records.collectAsState()
    val topoSheets by topoOverlay.sheets.collectAsState()
    val topoStatus by topoOverlay.status.collectAsState()

    // Same placement as the Google map tab: the user's saved alignment, else the LAS bounds.
    val corners = remember(terrainKey, metadata.bounds, grid.width, grid.height, grid.cellSizeMeters) {
        val alignment = alignmentStore.load(terrainKey) ?: metadata.bounds?.toDefaultAlignment()
        alignment?.let {
            val size = naturalOverlaySize(metadata, grid)
            MapboxOverlayGeometry.overlayCorners(
                centerLat = it.center.latitude,
                centerLon = it.center.longitude,
                widthMeters = (size.widthMeters * it.widthScale).toDouble(),
                heightMeters = (size.heightMeters * it.heightScale).toDouble(),
                bearingDegrees = it.bearingDegrees.toDouble(),
            )
        }
    }
    val lidarImage = remember(terrainBitmap, corners) {
        MapboxLidarImage(terrainBitmap?.takeIf { !it.isRecycled }, corners)
    }
    val vectorData = remember(surveyFeatures, breadcrumbTracks, routeWaypoints) {
        MapboxVectorData(
            surveyPoints = MapboxOverlayGeometry.surveyPoints(surveyFeatures),
            surveyLines = MapboxOverlayGeometry.surveyLines(surveyFeatures),
            surveyPolygons = MapboxOverlayGeometry.surveyPolygons(surveyFeatures),
            breadcrumbs = MapboxOverlayGeometry.breadcrumbLines(breadcrumbTracks),
            routeLine = MapboxOverlayGeometry.routeLine(routeWaypoints),
            routeStops = MapboxOverlayGeometry.routeStops(routeWaypoints),
        )
    }
    val display = MapboxDisplayState(
        terrain3d, exaggeration, hillshade, usgsTopo, showLidar, lidarOpacity, useModel = model != null,
    )

    // Style reloads wipe runtime layers; the load callback re-applies whatever is current.
    val latestDisplay by rememberUpdatedState(display)
    val latestLidar by rememberUpdatedState(lidarImage)
    val latestVectors by rememberUpdatedState(vectorData)
    val latestModel by rememberUpdatedState(model)
    val latestReliefOn by rememberUpdatedState(reliefOn)
    val latestReliefOpacity by rememberUpdatedState(reliefOpacity)
    val latestTopoOn by rememberUpdatedState(topoOn)
    val latestTopoOpacity by rememberUpdatedState(topoOpacity)

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasLocationPermission = granted }

    val mapViewportState = rememberMapViewportState {
        setCameraOptions {
            center(Point.fromLngLat(-98.35, 39.5))
            zoom(3.0)
        }
    }

    fun viewBounds(): GeoSpatialLibrary.GeographicBounds? {
        val mapboxMap = mapViewRef?.mapboxMap ?: return null
        // Flatten the camera so the box is the ground footprint, not the horizon.
        val flat = mapboxMap.cameraState.toCameraOptions().toBuilder().pitch(0.0).build()
        val b = mapboxMap.coordinateBoundsForCamera(flat)
        return GeoSpatialLibrary.GeographicBounds(
            minLat = b.southwest.latitude(),
            maxLat = b.northeast.latitude(),
            minLon = b.southwest.longitude(),
            maxLon = b.northeast.longitude(),
        )
    }

    fun frameCorners(points: List<Point>) {
        val padding = with(density) { 72.dp.toPx().toDouble() }
        mapViewportState.transitionToOverviewState(
            OverviewViewportStateOptions.Builder()
                .geometry(Polygon.fromLngLats(listOf(points + points.first())))
                .padding(EdgeInsets(padding, padding, padding, padding))
                .pitch(if (terrain3d) 50.0 else 0.0)
                .build(),
        )
    }

    fun frameTerrain() {
        val ring = corners ?: return
        frameCorners(ring.map { Point.fromLngLat(it[0], it[1]) })
    }

    fun refreshTopo() {
        if (!topoOn) return
        val camera = mapViewportState.cameraState ?: return
        if (camera.zoom < 9.0) return
        val b = viewBounds() ?: return
        topoOverlay.search(b.minLat, b.maxLat, b.minLon, b.maxLon)
        val lat = camera.center.latitude()
        val lon = camera.center.longitude()
        val decades = HistoricTopoEras.decades(topoRecords, lat, lon)
        topoDecades = decades
        val decade = topoDecade?.takeIf { it in decades } ?: decades.firstOrNull()
        if (decade != topoDecade) topoDecade = decade
        decade?.let { topoOverlay.showEra(it, b.minLat, b.maxLat, b.minLon, b.maxLon, lat, lon) }
    }

    fun buildModel() {
        val camera = mapViewportState.cameraState ?: return
        val lat = camera.center.latitude()
        val lon = camera.center.longitude()
        val view = viewBounds() ?: return
        // Keep the model to about 3 km across so it builds in a reasonable time.
        val halfLat = minOf((view.maxLat - view.minLat) / 2, 1500.0 / 111_320.0)
        val halfLon = minOf((view.maxLon - view.minLon) / 2, 1500.0 / (111_320.0 * cos(Math.toRadians(lat))))
        modelBusy = true
        modelStatus = "Fetching 1 m elevation…"
        scope.launch {
            val builder = TerrainModelBuilder(
                ThreeDepElevationClient(File(context.cacheDir, "3dep-elevation")),
                File(context.filesDir, "terrain-models"),
            )
            val built = withContext(Dispatchers.IO) {
                runCatching {
                    builder.build(lon - halfLon, lat - halfLat, lon + halfLon, lat + halfLat) { done, total ->
                        scope.launch(Dispatchers.Main) { modelStatus = "Fetching 1 m elevation… $done of $total" }
                    }
                }.getOrNull()
            }
            modelBusy = false
            if (built == null) {
                modelStatus = "No USGS 1 m elevation here, or the download failed."
            } else {
                model = built
                modelStatus = null
                terrain3d = true
                if (exaggeration < 2f) exaggeration = 2.5f
                frameCorners(
                    listOf(
                        Point.fromLngLat(built.minLon, built.maxLat),
                        Point.fromLngLat(built.maxLon, built.maxLat),
                        Point.fromLngLat(built.maxLon, built.minLat),
                        Point.fromLngLat(built.minLon, built.minLat),
                    ),
                )
            }
        }
    }

    LaunchedEffect(terrainKey, corners) {
        if (corners != null && lastFramedTerrainKey != terrainKey) {
            lastFramedTerrainKey = terrainKey
            frameTerrain()
        }
    }

    // Old topo follows the camera: search and pick sheets after the map settles.
    LaunchedEffect(topoOn) {
        if (!topoOn) {
            topoOverlay.clear()
            return@LaunchedEffect
        }
        snapshotFlow { mapViewportState.cameraState?.let { Triple(it.center.latitude(), it.center.longitude(), it.zoom) } }
            .debounce(800)
            .collect { refreshTopo() }
    }
    LaunchedEffect(topoRecords, topoDecade) { refreshTopo() }

    Box(modifier = modifier.testTag("mapbox_map_screen")) {
        MapboxMap(
            modifier = Modifier.fillMaxSize(),
            mapViewportState = mapViewportState,
            scaleBar = { ScaleBar(alignment = Alignment.BottomStart) },
            // Styles are loaded imperatively below so runtime layers can be re-applied on each load.
            style = {},
        ) {
            MapEffect(Unit) { mapView -> mapViewRef = mapView }
            MapEffect(baseStyle) { mapView ->
                mapView.mapboxMap.loadStyle(baseStyle.uri) { style ->
                    style.applyTerrainModel(latestModel)
                    style.installFindItLayers(latestDisplay, latestLidar, latestVectors)
                    reliefOverlay.install(
                        style,
                        // Under the user's own LAS hillshade when present, else under the survey layers.
                        belowLayerId = if (style.styleLayerExists(MapboxIds.LIDAR_LAYER)) {
                            MapboxIds.LIDAR_LAYER
                        } else {
                            MapboxIds.SURVEY_POLYGON_FILL
                        },
                        opacity = latestReliefOpacity.toDouble(),
                        visible = latestReliefOn,
                    )
                    topoOverlay.apply(style, LidarReliefOverlay.LAYER_ID, latestTopoOpacity.toDouble(), latestTopoOn)
                }
            }
            MapEffect(model) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.applyTerrainModel(model)
                    style.applyDisplay(latestDisplay)
                }
            }
            MapEffect(display) { mapView ->
                mapView.mapboxMap.getStyle { style -> style.applyDisplay(display) }
            }
            MapEffect(lidarImage) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    style.applyLidarImage(lidarImage)
                    style.applyDisplay(latestDisplay)
                }
            }
            MapEffect(vectorData) { mapView ->
                mapView.mapboxMap.getStyle { style -> style.applyVectorData(vectorData) }
            }
            MapEffect(reliefSettings) { _ -> reliefOverlay.updateSettings(reliefSettings) }
            MapEffect(reliefOn, reliefOpacity) { _ ->
                reliefOverlay.setAppearance(reliefOpacity.toDouble(), reliefOn)
            }
            MapEffect(topoSheets, topoOn, topoOpacity) { mapView ->
                mapView.mapboxMap.getStyle { style ->
                    topoOverlay.apply(style, LidarReliefOverlay.LAYER_ID, topoOpacity.toDouble(), topoOn)
                }
            }
            MapEffect(hasLocationPermission) { mapView ->
                if (hasLocationPermission) {
                    mapView.location.updateSettings {
                        enabled = true
                        puckBearingEnabled = true
                        locationPuck = createDefault2DPuck(withBearing = true)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .widthIn(max = 520.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
                ),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MapboxPanel.entries.forEach { option ->
                                FilterChip(
                                    selected = panel == option && controlsExpanded,
                                    onClick = {
                                        panel = option
                                        controlsExpanded = true
                                    },
                                    label = { Text(option.label) },
                                    modifier = Modifier.testTag("mapbox_panel_${option.name.lowercase(Locale.US)}"),
                                )
                            }
                        }
                        IconButton(onClick = { controlsExpanded = !controlsExpanded }) {
                            Icon(
                                if (controlsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (controlsExpanded) "Hide map controls" else "Show map controls",
                            )
                        }
                    }
                    if (controlsExpanded) {
                        Column(
                            Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            when (panel) {
                                MapboxPanel.MAP -> {
                                    ChipRow {
                                        MapboxBaseStyle.entries.forEach { option ->
                                            FilterChip(
                                                selected = baseStyle == option,
                                                onClick = { baseStyle = option },
                                                label = { Text(option.label) },
                                                modifier = Modifier.testTag("mapbox_style_${option.name.lowercase(Locale.US)}"),
                                            )
                                        }
                                    }
                                    ChipRow {
                                        FilterChip(
                                            selected = terrain3d,
                                            onClick = {
                                                terrain3d = !terrain3d
                                                mapViewportState.easeTo(
                                                    CameraOptions.Builder().pitch(if (terrain3d) 50.0 else 0.0).build(),
                                                )
                                            },
                                            label = { Text("3D terrain") },
                                        )
                                        FilterChip(selected = hillshade, onClick = { hillshade = !hillshade }, label = { Text("Hillshade") })
                                        FilterChip(selected = usgsTopo, onClick = { usgsTopo = !usgsTopo }, label = { Text("USGS topo") })
                                        if (lidarImage.bitmap != null && lidarImage.corners != null) {
                                            FilterChip(selected = showLidar, onClick = { showLidar = !showLidar }, label = { Text("My LiDAR") })
                                        }
                                    }
                                    if (terrain3d) {
                                        LabeledSlider("Exaggeration", String.format(Locale.US, "%.1fx", exaggeration), exaggeration, { exaggeration = it }, 1f..5f)
                                    }
                                    if (showLidar && lidarImage.bitmap != null && lidarImage.corners != null) {
                                        LabeledSlider("My LiDAR opacity", "${(lidarOpacity * 100).toInt()}%", lidarOpacity, { lidarOpacity = it }, 0.1f..1f)
                                    }
                                    if (terrainBitmap != null && corners == null) {
                                        Text(
                                            "This terrain isn't georeferenced yet, so your LiDAR layer can't be placed. " +
                                                "Position it on the Google map tab first.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }

                                MapboxPanel.LIDAR -> {
                                    ChipRow {
                                        FilterChip(
                                            selected = reliefOn,
                                            onClick = { reliefOn = !reliefOn },
                                            label = { Text(if (reliefOn) "1 m LiDAR on" else "1 m LiDAR off") },
                                            modifier = Modifier.testTag("mapbox_relief_toggle"),
                                        )
                                    }
                                    ChipRow {
                                        ReliefMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = reliefMode == mode,
                                                onClick = {
                                                    reliefMode = mode
                                                    reliefOn = true
                                                },
                                                label = { Text(mode.label) },
                                            )
                                        }
                                    }
                                    Text(reliefMode.description, style = MaterialTheme.typography.bodySmall)
                                    if (reliefMode == ReliefMode.HILLSHADE) {
                                        ChipRow {
                                            FilterChip(selected = !multiColour, onClick = { multiColour = false }, label = { Text("Grey") })
                                            FilterChip(selected = multiColour, onClick = { multiColour = true }, label = { Text("Multi-colour") })
                                            FilterChip(selected = !multiDirection, onClick = { multiDirection = false }, label = { Text("One sun") })
                                            FilterChip(selected = multiDirection, onClick = { multiDirection = true }, label = { Text("Four suns") })
                                        }
                                    }
                                    val usesLight = reliefMode in setOf(ReliefMode.HILLSHADE, ReliefMode.VAT, ReliefMode.MULTI_LIGHT_PCA)
                                    val usesZ = reliefMode in setOf(
                                        ReliefMode.HILLSHADE, ReliefMode.SLOPE, ReliefMode.RED_RELIEF, ReliefMode.VAT,
                                        ReliefMode.MULTI_LIGHT_PCA, ReliefMode.SVF_SLOPE,
                                    )
                                    val usesRadius = reliefMode !in setOf(ReliefMode.HILLSHADE, ReliefMode.SLOPE, ReliefMode.MULTI_LIGHT_PCA)
                                    if (usesLight && reliefMode != ReliefMode.MULTI_LIGHT_PCA) {
                                        CommitSlider("Sun direction", { "${it.roundToInt()}° ${compass(it)}" }, sunAzimuth, 0f..360f) { sunAzimuth = it }
                                    }
                                    if (usesLight) {
                                        CommitSlider("Sun height", { "${it.roundToInt()}°" }, sunAltitude, 5f..80f) { sunAltitude = it }
                                    }
                                    if (usesZ) {
                                        CommitSlider("Z factor", { String.format(Locale.US, "×%.1f", it) }, zFactor, 0.5f..10f) { zFactor = it }
                                    }
                                    if (usesRadius) {
                                        CommitSlider("Search radius", { "${it.roundToInt()} m" }, radiusMeters, 2f..30f) { radiusMeters = it }
                                    }
                                    CommitSlider("Brightness", { signedPercent(it * 2) }, brightness, -0.5f..0.5f) { brightness = it }
                                    CommitSlider("Contrast", { String.format(Locale.US, "×%.1f", it) }, contrast, 0.5f..2f) { contrast = it }
                                    LabeledSlider("Opacity", "${(reliefOpacity * 100).toInt()}%", reliefOpacity, { reliefOpacity = it }, 0.1f..1f)
                                    val zoom = mapViewportState.cameraState?.zoom ?: 0.0
                                    val note = when {
                                        !reliefOn -> null
                                        zoom < LidarReliefOverlay.MIN_ZOOM -> "Zoom in further to draw LiDAR."
                                        reliefLoading > 0 -> "Loading LiDAR… ($reliefLoading)"
                                        reliefResolution != null -> String.format(Locale.US, "USGS 3DEP elevation, ≈%.1f m per pixel here.", reliefResolution)
                                        else -> null
                                    }
                                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = {
                                        sunAzimuth = 315f
                                        sunAltitude = 35f
                                        zFactor = 2f
                                        multiDirection = true
                                        multiColour = false
                                        radiusMeters = 10f
                                        brightness = 0f
                                        contrast = 1f
                                    }) { Text("Reset look") }
                                }

                                MapboxPanel.TOPO -> {
                                    ChipRow {
                                        FilterChip(
                                            selected = topoOn,
                                            onClick = { topoOn = !topoOn },
                                            label = { Text(if (topoOn) "Old topo on" else "Old topo off") },
                                            modifier = Modifier.testTag("mapbox_topo_toggle"),
                                        )
                                    }
                                    val decade = topoDecade
                                    if (topoOn && topoDecades.isNotEmpty() && decade != null) {
                                        val index = topoDecades.indexOf(decade).coerceAtLeast(0)
                                        Text(
                                            "Topo era ${decade}s",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (topoDecades.size > 1) {
                                            Slider(
                                                value = index.toFloat(),
                                                onValueChange = { topoDecade = topoDecades[it.roundToInt().coerceIn(0, topoDecades.lastIndex)] },
                                                valueRange = 0f..topoDecades.lastIndex.toFloat(),
                                                steps = (topoDecades.size - 2).coerceAtLeast(0),
                                            )
                                            Row {
                                                Text("${topoDecades.first()}s", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                                Text("${topoDecades.last()}s", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                        LabeledSlider("Opacity", "${(topoOpacity * 100).toInt()}%", topoOpacity, { topoOpacity = it }, 0.1f..1f)
                                        val shown = topoRecords.filter { r -> topoSheets.any { it.recordId == r.id } }
                                        shown.forEach { r ->
                                            Text(
                                                "${r.editionYear ?: ""} · ${r.title.substringAfter("Quadrangle for ", r.title)} · 1:${HistoricTopoEras.scaleOf(r)}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    } else if (topoOn) {
                                        val zoom = mapViewportState.cameraState?.zoom ?: 0.0
                                        Text(
                                            if (zoom < 9.0) "Zoom in to a town or farm to find its old topo maps." else "Looking for old topo maps here…",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    topoStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    Text(
                                        "USGS Historical Topographic Maps, cut to their map edges and placed by their own " +
                                            "survey coordinates. Each sheet downloads once (about 8–20 MB).",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }

                                MapboxPanel.MODEL -> {
                                    Text(
                                        "Builds a 3D model of the ground in view (up to about 3 km across) from USGS 1 m lidar. " +
                                            "Raise the exaggeration to make low banks, pits and hollows easier to see. Any layer, " +
                                            "including 1 m LiDAR relief and old topo, drapes over it.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { buildModel() }, enabled = !modelBusy) {
                                            Text(if (model == null) "Build 3D model here" else "Rebuild here")
                                        }
                                        if (model != null) {
                                            OutlinedButton(onClick = { model = null }) { Text("Exit model") }
                                        }
                                    }
                                    modelStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                    if (model != null) {
                                        LabeledSlider("Exaggeration", String.format(Locale.US, "%.1fx", exaggeration), exaggeration, { exaggeration = it }, 1f..8f)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (routeWaypoints.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Route: ${routeWaypoints.size} stops, " +
                                String.format(Locale.US, "%.0f m", routeTotalMeters),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onClearRoute) { Text("Clear") }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (onFindLidarTiles != null) {
                SmallFloatingActionButton(
                    onClick = { viewBounds()?.let(onFindLidarTiles) },
                    modifier = Modifier.testTag("mapbox_find_lidar"),
                ) { Icon(Icons.Default.Search, contentDescription = "Find LiDAR tiles in this view") }
            }
            if (corners != null) {
                SmallFloatingActionButton(onClick = { frameTerrain() }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Zoom to terrain")
                }
            }
            SmallFloatingActionButton(
                onClick = {
                    if (hasLocationPermission) {
                        mapViewportState.transitionToFollowPuckState()
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                modifier = Modifier.testTag("mapbox_my_location"),
            ) { Icon(Icons.Default.MyLocation, contentDescription = "My location") }
        }
    }
}

private fun compass(degrees: Float): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[((degrees / 45f).roundToInt() % 8 + 8) % 8]
}

private fun signedPercent(v: Float): String {
    val p = (v * 100).roundToInt()
    return when {
        p == 0 -> "Normal"
        p > 0 -> "+$p%"
        else -> "$p%"
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

/** A slider that only commits when the finger lifts, so expensive re-renders run once per change. */
@Composable
private fun CommitSlider(
    label: String,
    valueLabel: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(valueLabel(draft), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { onCommit(draft) },
            valueRange = range,
        )
    }
}

@Composable
private fun MapboxTokenMissing(modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mapbox token needed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Add your public Mapbox token (starts with pk.) as MAPBOX_ACCESS_TOKEN in " +
                        "local.properties or .env, then rebuild the app.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Create one free at account.mapbox.com/access-tokens.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// --- Runtime style layers -------------------------------------------------------------------

/** Adds (or replaces) the 1 m terrain model's elevation source, or removes it. */
private fun Style.applyTerrainModel(model: TerrainModel?) {
    if (model == null) {
        if (styleSourceExists(MapboxIds.MODEL_DEM_SOURCE)) {
            removeTerrain()
            removeStyleSource(MapboxIds.MODEL_DEM_SOURCE)
        }
        return
    }
    if (styleSourceExists(MapboxIds.MODEL_DEM_SOURCE)) {
        removeTerrain()
        removeStyleSource(MapboxIds.MODEL_DEM_SOURCE)
    }
    addSource(
        rasterDemSource(MapboxIds.MODEL_DEM_SOURCE) {
            tiles(listOf(model.tileUrlTemplate))
            tileSize(256)
            minzoom(model.minZoom.toLong())
            maxzoom(model.maxZoom.toLong())
            bounds(listOf(model.minLon, model.minLat, model.maxLon, model.maxLat))
        },
    )
}

/** Adds every Find It source and layer to a freshly loaded style, bottom to top. */
private fun Style.installFindItLayers(
    display: MapboxDisplayState,
    lidar: MapboxLidarImage,
    vectors: MapboxVectorData,
) {
    if (!styleSourceExists(MapboxIds.DEM_SOURCE)) {
        addSource(
            rasterDemSource(MapboxIds.DEM_SOURCE) {
                url(MapboxIds.DEM_URL)
                tileSize(514)
                maxzoom(14)
            },
        )
    }
    if (!styleSourceExists(MapboxIds.USGS_SOURCE)) {
        addSource(
            rasterSource(MapboxIds.USGS_SOURCE) {
                tiles(listOf(MapboxIds.USGS_TOPO_TILES))
                tileSize(256)
                maxzoom(16)
            },
        )
    }
    addLayer(rasterLayer(MapboxIds.USGS_LAYER, MapboxIds.USGS_SOURCE) { rasterOpacity(0.85) })
    addLayer(
        hillshadeLayer(MapboxIds.HILLSHADE_LAYER, MapboxIds.DEM_SOURCE) {
            hillshadeExaggeration(0.6)
            hillshadeShadowColor("#000000")
            hillshadeHighlightColor("#FFFFFF")
        },
    )

    addSource(geoJsonSource(MapboxIds.SURVEY_POLYGON_SOURCE) { featureCollection(vectors.surveyPolygons) })
    addSource(geoJsonSource(MapboxIds.SURVEY_LINE_SOURCE) { featureCollection(vectors.surveyLines) })
    addSource(geoJsonSource(MapboxIds.SURVEY_POINT_SOURCE) { featureCollection(vectors.surveyPoints) })
    addSource(geoJsonSource(MapboxIds.BREADCRUMB_SOURCE) { featureCollection(vectors.breadcrumbs) })
    addSource(geoJsonSource(MapboxIds.ROUTE_LINE_SOURCE) { featureCollection(vectors.routeLine) })
    addSource(geoJsonSource(MapboxIds.ROUTE_STOP_SOURCE) { featureCollection(vectors.routeStops) })

    addLayer(
        fillLayer(MapboxIds.SURVEY_POLYGON_FILL, MapboxIds.SURVEY_POLYGON_SOURCE) {
            fillColor("#FFB300")
            fillOpacity(0.18)
        },
    )
    addLayer(
        lineLayer(MapboxIds.SURVEY_POLYGON_OUTLINE, MapboxIds.SURVEY_POLYGON_SOURCE) {
            lineColor("#FFB300")
            lineWidth(2.0)
        },
    )
    addLayer(
        lineLayer(MapboxIds.SURVEY_LINE_LAYER, MapboxIds.SURVEY_LINE_SOURCE) {
            lineColor("#FFB300")
            lineWidth(3.0)
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
        },
    )
    addLayer(
        lineLayer(MapboxIds.BREADCRUMB_LAYER, MapboxIds.BREADCRUMB_SOURCE) {
            lineColor("#00E5FF")
            lineWidth(3.0)
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.ROUND)
        },
    )
    addLayer(
        lineLayer(MapboxIds.ROUTE_LINE_LAYER, MapboxIds.ROUTE_LINE_SOURCE) {
            lineColor("#E040FB")
            lineWidth(3.0)
            lineDasharray(listOf(2.0, 1.5))
        },
    )
    addLayer(
        circleLayer(MapboxIds.SURVEY_POINT_LAYER, MapboxIds.SURVEY_POINT_SOURCE) {
            circleRadius(5.0)
            circleColor("#FF7043")
            circleStrokeColor("#FFFFFF")
            circleStrokeWidth(1.5)
        },
    )
    addLayer(
        circleLayer(MapboxIds.ROUTE_STOP_LAYER, MapboxIds.ROUTE_STOP_SOURCE) {
            circleRadius(6.0)
            circleColor("#E040FB")
            circleStrokeColor("#FFFFFF")
            circleStrokeWidth(1.5)
        },
    )

    applyLidarImage(lidar)
    applyDisplay(display)
}

/** Adds, moves or refreshes the LiDAR image overlay. It sits just under the survey layers. */
private fun Style.applyLidarImage(lidar: MapboxLidarImage) {
    val bitmap = lidar.bitmap
    val corners = lidar.corners
    if (bitmap == null || corners == null) {
        if (styleLayerExists(MapboxIds.LIDAR_LAYER)) removeStyleLayer(MapboxIds.LIDAR_LAYER)
        if (styleSourceExists(MapboxIds.LIDAR_SOURCE)) removeStyleSource(MapboxIds.LIDAR_SOURCE)
        return
    }
    val existing = getSourceAs<ImageSource>(MapboxIds.LIDAR_SOURCE)
    if (existing == null) {
        addSource(imageSource(MapboxIds.LIDAR_SOURCE) { coordinates(corners) })
    } else {
        existing.coordinates(corners)
    }
    getSourceAs<ImageSource>(MapboxIds.LIDAR_SOURCE)?.updateImage(bitmap)
    if (!styleLayerExists(MapboxIds.LIDAR_LAYER)) {
        addLayerBelow(rasterLayer(MapboxIds.LIDAR_LAYER, MapboxIds.LIDAR_SOURCE) {}, MapboxIds.SURVEY_POLYGON_FILL)
    }
}

/** Applies toggles and sliders: terrain, layer visibility and opacity. */
private fun Style.applyDisplay(display: MapboxDisplayState) {
    val demSource = if (display.useModel && styleSourceExists(MapboxIds.MODEL_DEM_SOURCE)) {
        MapboxIds.MODEL_DEM_SOURCE
    } else {
        MapboxIds.DEM_SOURCE
    }
    if (display.terrain3d && styleSourceExists(demSource)) {
        setTerrain(terrain(demSource) { exaggeration(display.exaggeration.toDouble()) })
    } else {
        removeTerrain()
    }
    getLayer(MapboxIds.HILLSHADE_LAYER)?.visibility(display.hillshade.toVisibility())
    getLayer(MapboxIds.USGS_LAYER)?.visibility(display.usgsTopo.toVisibility())
    getLayerAs<RasterLayer>(MapboxIds.LIDAR_LAYER)?.let { layer ->
        layer.visibility(display.showLidar.toVisibility())
        layer.rasterOpacity(display.lidarOpacity.toDouble())
    }
}

/** Pushes fresh GeoJSON into the existing vector sources. */
private fun Style.applyVectorData(vectors: MapboxVectorData) {
    getSourceAs<GeoJsonSource>(MapboxIds.SURVEY_POLYGON_SOURCE)?.featureCollection(vectors.surveyPolygons)
    getSourceAs<GeoJsonSource>(MapboxIds.SURVEY_LINE_SOURCE)?.featureCollection(vectors.surveyLines)
    getSourceAs<GeoJsonSource>(MapboxIds.SURVEY_POINT_SOURCE)?.featureCollection(vectors.surveyPoints)
    getSourceAs<GeoJsonSource>(MapboxIds.BREADCRUMB_SOURCE)?.featureCollection(vectors.breadcrumbs)
    getSourceAs<GeoJsonSource>(MapboxIds.ROUTE_LINE_SOURCE)?.featureCollection(vectors.routeLine)
    getSourceAs<GeoJsonSource>(MapboxIds.ROUTE_STOP_SOURCE)?.featureCollection(vectors.routeStops)
}

private fun Boolean.toVisibility(): Visibility = if (this) Visibility.VISIBLE else Visibility.NONE
