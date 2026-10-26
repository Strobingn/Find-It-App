package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.data.relief.LidarTerrainServer
import com.example.data.relief.LidarTerrainTiles
import com.example.data.survey.SurveyFeature
import com.example.geospatial.GeoSpatialLibrary
import com.example.ui.components.mapbox.HistoricTopoOverlay
import com.example.ui.components.mapbox.LidarReliefOverlay
import com.mapbox.bindgen.Value
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
import com.mapbox.maps.extension.style.layers.addLayerAbove
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
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext

private object MapboxIds {
    const val DEM_SOURCE = "findit-dem"
    const val LIDAR_DEM_SOURCE = "findit-dem-lidar"
    const val USGS_SOURCE = "findit-usgs-topo"
    const val USGS_LAYER = "findit-usgs-topo-layer"
    const val HILLSHADE_LAYER = "findit-hillshade"
    const val LIDAR_HILLSHADE_LAYER = "findit-hillshade-lidar"
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
    /** USGS lidar terrain tiles from [LidarTerrainServer]; null uses Mapbox's global DEM. */
    val lidarTileUrl: String?,
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
 * USGS 3DEP, auto-aligned historic USGS topo maps by era, 3D ground from USGS lidar, and the
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

    val controls = rememberSaveable(saver = MapboxControls.Saver) { MapboxControls() }
    val baseStyle by controls::baseStyle
    val terrain3d by controls::terrain3d
    val exaggeration by controls::exaggeration
    val hillshade by controls::hillshade
    val usgsTopo by controls::usgsTopo
    val showLidar by controls::showLidar
    val lidarOpacity by controls::lidarOpacity
    val reliefOn by controls::reliefOn
    val reliefOpacity by controls::reliefOpacity
    val reliefSettings = controls.reliefSettings

    val topoOn by controls::topoOn
    val topoOpacity by controls::topoOpacity
    var topoDecade by controls::topoDecade
    var topoDecades by remember { mutableStateOf<List<Int>>(emptyList()) }

    var lidarTerrain by controls::lidarTerrain
    var lidarNote by controls::lidarNote
    var lidarServer by remember { mutableStateOf<LidarTerrainServer?>(null) }
    val lidarStatsFlow = remember(lidarServer) { lidarServer?.stats ?: MutableStateFlow(LidarTerrainServer.Stats()) }
    val lidarStats by lidarStatsFlow.collectAsState()

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
        terrain3d, exaggeration, hillshade, usgsTopo, showLidar, lidarOpacity,
        lidarTileUrl = lidarServer?.tileUrl?.takeIf { lidarTerrain },
    )

    // Style reloads wipe runtime layers; the load callback re-applies whatever is current.
    val latestDisplay by rememberUpdatedState(display)
    val latestLidar by rememberUpdatedState(lidarImage)
    val latestVectors by rememberUpdatedState(vectorData)
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
                .pitch(if (terrain3d) TILT_DEGREES else 0.0)
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

    fun tilt(degrees: Double) {
        mapViewportState.easeTo(CameraOptions.Builder().pitch(degrees).build())
    }

    // The tile server binds a socket, so start it off the main thread the first time it's wanted.
    LaunchedEffect(lidarTerrain) {
        if (lidarTerrain && lidarServer == null) {
            lidarServer = withContext(Dispatchers.IO) { runCatching { LidarTerrainServer.get(context) }.getOrNull() }
            if (lidarServer == null) {
                lidarTerrain = false
                lidarNote = "Couldn't start USGS lidar elevation, so 3D uses Mapbox elevation."
            }
        }
    }
    // Safety net: if Mapbox never manages to read lidar tiles, fall back rather than leave 3D flat.
    LaunchedEffect(lidarTerrain, terrain3d, lidarServer) {
        val server = lidarServer ?: return@LaunchedEffect
        if (!lidarTerrain || !terrain3d) return@LaunchedEffect
        delay(LIDAR_CHECK_MS)
        // Counted since the server started, so tiles Mapbox already holds can't look like a failure.
        val now = server.stats.value
        val zoom = mapViewportState.cameraState?.zoom ?: 0.0
        if (zoom < 4.0) return@LaunchedEffect
        lidarNote = when {
            now.requested == 0 -> "The map didn't load USGS lidar elevation, so 3D uses Mapbox elevation."
            now.served == 0 && now.noData >= 4 -> "USGS lidar covers the US only, so 3D uses Mapbox elevation here."
            now.served + now.noData == 0 && now.failed >= 4 ->
                "USGS lidar elevation isn't loading (no signal, or the USGS server is busy), so 3D uses Mapbox elevation."
            else -> return@LaunchedEffect
        }
        lidarTerrain = false
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
                .padding(6.dp)
                .widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapboxControlCard(
                controls = controls,
                status = MapboxControlStatus(
                    zoom = { mapViewportState.cameraState?.zoom ?: 0.0 },
                    lidarStats = { lidarStats },
                    lidarStarting = lidarTerrain && lidarServer == null,
                    hasMyLidar = lidarImage.bitmap != null && lidarImage.corners != null,
                    myLidarUnplaced = terrainBitmap != null && corners == null,
                    reliefLoading = reliefLoading,
                    reliefResolution = reliefResolution,
                    reliefMinZoom = LidarReliefOverlay.MIN_ZOOM,
                    topoDecades = topoDecades,
                    topoSheetLines = topoRecords.filter { r -> topoSheets.any { it.recordId == r.id } }.map { r ->
                        "${r.editionYear ?: ""} · ${r.title.substringAfter("Quadrangle for ", r.title)} · 1:${HistoricTopoEras.scaleOf(r)}"
                    },
                    topoStatus = topoStatus,
                ),
                onTilt = ::tilt,
            )

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

/**
 * Adds the USGS lidar elevation source and its hillshade once per style. The tile server lives as
 * long as the app, so its URL never changes under a loaded style.
 */
private fun Style.ensureLidarDem(tileUrl: String) {
    if (!styleSourceExists(MapboxIds.LIDAR_DEM_SOURCE)) {
        addSource(
            rasterDemSource(MapboxIds.LIDAR_DEM_SOURCE) {
                tiles(listOf(tileUrl))
                tileSize(LidarTerrainTiles.TILE.toLong())
                maxzoom(LidarTerrainTiles.MAX_ZOOM.toLong())
                attribution("Elevation: USGS 3DEP")
            },
        )
    }
    // Wait for the Find It layers: the lidar hillshade sits just above Mapbox's.
    if (styleLayerExists(MapboxIds.LIDAR_HILLSHADE_LAYER) || !styleLayerExists(MapboxIds.HILLSHADE_LAYER)) return
    addLayerAbove(
        hillshadeLayer(MapboxIds.LIDAR_HILLSHADE_LAYER, MapboxIds.LIDAR_DEM_SOURCE) {
            hillshadeExaggeration(0.6)
            hillshadeShadowColor("#000000")
            hillshadeHighlightColor("#FFFFFF")
            visibility(Visibility.NONE)
        },
        MapboxIds.HILLSHADE_LAYER,
    )
}

/** Applies toggles and sliders: terrain, layer visibility and opacity. */
private fun Style.applyDisplay(display: MapboxDisplayState) {
    display.lidarTileUrl?.let { ensureLidarDem(it) }
    val useLidar = display.lidarTileUrl != null && styleSourceExists(MapboxIds.LIDAR_DEM_SOURCE)
    val demSource = if (useLidar) MapboxIds.LIDAR_DEM_SOURCE else MapboxIds.DEM_SOURCE
    if (display.terrain3d && styleSourceExists(demSource)) {
        val exaggeration = display.exaggeration.toDouble()
        if (getStyleTerrainProperty("source").value.contents == demSource) {
            // Same ground, new height: change the one property instead of rebuilding the terrain.
            setStyleTerrainProperty("exaggeration", Value(exaggeration))
        } else {
            setTerrain(terrain(demSource) { exaggeration(exaggeration) })
        }
    } else {
        removeTerrain()
    }
    // Hillshade follows whichever elevation the 3D ground uses, so shading and shape agree.
    getLayer(MapboxIds.HILLSHADE_LAYER)?.visibility((display.hillshade && !useLidar).toVisibility())
    getLayer(MapboxIds.LIDAR_HILLSHADE_LAYER)?.visibility((display.hillshade && useLidar).toVisibility())
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

/** How long lidar elevation gets to show up before 3D falls back to Mapbox elevation. */
private const val LIDAR_CHECK_MS = 25_000L
