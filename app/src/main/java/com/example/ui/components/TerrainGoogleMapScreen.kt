package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.data.ElevationGrid
import com.example.data.HistoricMapOverlay
import com.example.data.HistoricMapOverlayRepository
import com.example.data.historicmap.GeoReferenceConfidence
import com.example.data.historicmap.GeoReferenceTransform
import com.example.data.historicmap.GeoReferencedMap
import com.example.data.historicmap.GeoReferencer
import com.example.data.historicmap.HistoricMapAgreementScorer
import com.example.data.historicmap.HistoricMapControlPoint
import com.example.data.historicmap.HistoricMapFeature
import com.example.ai.GeminiConversationTurn
import com.example.ai.TerrainAiGateway
import com.example.data.historicmap.HistoricMapGeoreference
import com.example.data.historicmap.MapFeatureAgreement
import com.example.data.historicmap.MapFeatureType
import com.example.data.historicmap.MapVectorizationGateway
import com.example.data.field.BoundaryVertex
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.FieldWaypoint
import com.example.data.field.SweepCoverageGrid
import com.example.data.field.SweepCoverageTracker
import com.example.data.local.AppDatabase
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.survey.SurveyFeature
import com.example.data.survey.SurveyGeometryType
import com.example.geospatial.GeoSpatialLibrary
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import java.io.File
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import kotlin.math.cos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerrainGoogleMapScreen(
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
    /**
     * Publishes the latest historic-map vs terrain agreement score (0..1) for ranking adjustment,
     * or null when no overlay is active / scoring failed.
     */
    onHistoricMapAgreementScore: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = rememberManagedMapView()
    val alignmentStore = remember(context) { TerrainMapAlignmentStore(context.applicationContext) }
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var overlay by remember { mutableStateOf<GroundOverlay?>(null) }
    var surveyMapObjects by remember { mutableStateOf<List<Any>>(emptyList()) }
    var breadcrumbPolylines by remember { mutableStateOf<List<Polyline>>(emptyList()) }
    var cameraCenter by remember { mutableStateOf(LatLng(39.5, -98.35)) }
    val naturalSize = remember(metadata.bounds, grid.width, grid.height, grid.cellSizeMeters) {
        naturalOverlaySize(metadata, grid)
    }
    val defaultAlignment = remember(metadata.bounds) { metadata.bounds?.toDefaultAlignment() }
    var alignment by remember(terrainKey, defaultAlignment) {
        mutableStateOf(alignmentStore.load(terrainKey) ?: defaultAlignment)
    }
    var hasSavedAlignment by remember(terrainKey) { mutableStateOf(alignmentStore.contains(terrainKey)) }
    var opacity by rememberSaveable { mutableFloatStateOf(0.72f) }
    var mapType by rememberSaveable { mutableStateOf(GoogleMap.MAP_TYPE_HYBRID) }
    var alignmentMode by rememberSaveable(terrainKey) { mutableStateOf(false) }
    var editBounds by rememberSaveable { mutableStateOf(false) }
    var lastFramedTerrainKey by remember { mutableStateOf<String?>(null) }
    val surveyPoints = remember(surveyFeatures) {
        surveyFeatures.flatMap { feature ->
            feature.coordinates.map { LatLng(it.latitude, it.longitude) }
        }
    }

    val scope = rememberCoroutineScope()
    val historicMapRepository = remember(context) { HistoricMapOverlayRepository(context.applicationContext) }
    var historicMaps by remember { mutableStateOf(historicMapRepository.list()) }
    val historicBitmaps = remember { mutableStateMapOf<String, Bitmap>() }
    var historicOverlayObjects by remember { mutableStateOf<Map<String, GroundOverlay>>(emptyMap()) }
    var activeHistoricMapId by rememberSaveable { mutableStateOf<String?>(null) }
    var historicPanelExpanded by rememberSaveable { mutableStateOf(false) }
    var historicMapMessage by remember { mutableStateOf<String?>(null) }
    var controlPointMode by rememberSaveable { mutableStateOf(false) }
    var pendingImageXFraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var pendingImageYFraction by rememberSaveable { mutableFloatStateOf(0.5f) }
    var swipeBlend by rememberSaveable { mutableFloatStateOf(1f) }
    var showSideBySide by rememberSaveable { mutableStateOf(false) }
    var controlPointMarkers by remember { mutableStateOf<List<Marker>>(emptyList()) }
    val historicMapDao = remember(context) { AppDatabase.get(context).historicMapDao() }
    val historicMapFeatureDao = remember(context) { AppDatabase.get(context).historicMapFeatureDao() }
    var historicFeatures by remember { mutableStateOf<List<HistoricMapFeature>>(emptyList()) }
    var selectedFeatureType by rememberSaveable { mutableStateOf(MapFeatureType.ROAD.name) }
    var lastMapTapLatLng by remember { mutableStateOf<LatLng?>(null) }

    // Live terrain-agreement feedback for the map being aligned: a relief-contrast evidence
    // layer is built once per terrain grid, then the active map's ink is scored against it.
    val reliefEvidence by produceState<HistoricMapAgreementScorer.EvidenceGrid?>(null, grid) {
        value = withContext(Dispatchers.Default) {
            runCatching { HistoricMapAgreementScorer.buildReliefEvidence(grid) }.getOrNull()
        }
    }
    // Search coverage: ground swept so far, derived from GPS breadcrumb tracks and rendered
    // as a translucent layer under the terrain overlay.
    var showCoverage by rememberSaveable(terrainKey) { mutableStateOf(false) }
    var sweepWidthMeters by rememberSaveable(terrainKey) {
        mutableFloatStateOf(SweepCoverageTracker.DEFAULT_SWEEP_WIDTH_METERS)
    }
    var coverageOverlay by remember { mutableStateOf<GroundOverlay?>(null) }
    var routePolyline by remember { mutableStateOf<Polyline?>(null) }
    val sweepCoverage by produceState<SweepCoverageGrid?>(
        null,
        showCoverage,
        breadcrumbTracks,
        metadata.bounds,
        sweepWidthMeters,
    ) {
        val bounds = metadata.bounds
        if (!showCoverage || bounds == null || breadcrumbTracks.isEmpty()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            runCatching {
                SweepCoverageTracker.build(
                    tracks = breadcrumbTracks,
                    sweepWidthMeters = sweepWidthMeters,
                    minLatitude = bounds.minLat,
                    maxLatitude = bounds.maxLat,
                    minLongitude = bounds.minLon,
                    maxLongitude = bounds.maxLon,
                )
            }.getOrNull()
        }
    }
    LaunchedEffect(googleMap, sweepCoverage) {
        coverageOverlay?.remove()
        coverageOverlay = null
        val map = googleMap ?: return@LaunchedEffect
        val coverage = sweepCoverage ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.Default) { renderSweepCoverageBitmap(coverage) }
        coverageOverlay = map.addGroundOverlay(
            GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .positionFromBounds(
                    LatLngBounds(
                        LatLng(coverage.minLatitude, coverage.minLongitude),
                        LatLng(coverage.maxLatitude, coverage.maxLongitude),
                    ),
                )
                .transparency(0.2f)
                .zIndex(2f),
        )
    }
    LaunchedEffect(googleMap, routeWaypoints) {
        routePolyline?.remove()
        routePolyline = null
        val map = googleMap ?: return@LaunchedEffect
        if (routeWaypoints.size < 2) return@LaunchedEffect
        routePolyline = map.addPolyline(
            PolylineOptions()
                .addAll(routeWaypoints.map { LatLng(it.latitude, it.longitude) })
                .color(0xFFFFB300.toInt())
                .width(7f)
                .zIndex(5f),
        )
    }

    val activeHistoricMap = historicMaps.firstOrNull { it.id == activeHistoricMapId }
    val activeHistoricBitmap = activeHistoricMap?.let { historicBitmaps[it.id] }

    LaunchedEffect(activeHistoricMapId, historicMapFeatureDao) {
        val mapId = activeHistoricMapId
        if (mapId == null) {
            historicFeatures = emptyList()
            return@LaunchedEffect
        }
        historicMapFeatureDao.observeByMapId(mapId).collectLatest { rows ->
            historicFeatures = rows.map { it.toDomain() }
        }
    }
    val historicAgreement by produceState<MapFeatureAgreement?>(
        null,
        reliefEvidence,
        activeHistoricMap,
        activeHistoricBitmap,
        metadata.bounds,
    ) {
        val evidence = reliefEvidence
        val record = activeHistoricMap
        val bounds = metadata.bounds
        val bitmap = activeHistoricBitmap?.takeIf { !it.isRecycled }
        if (evidence == null || record == null || bounds == null || bitmap == null) {
            value = null
            return@produceState
        }
        // Debounce slider drags: every alignment change restarts this effect, and only the
        // configuration that stays put briefly gets scored.
        kotlinx.coroutines.delay(250)
        value = withContext(Dispatchers.Default) {
            runCatching {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                HistoricMapAgreementScorer.scoreOverlay(
                    pixels = pixels,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    centerLatitude = record.latitude,
                    centerLongitude = record.longitude,
                    widthMeters = record.widthMeters,
                    heightMeters = record.heightMeters,
                    bearingDegrees = record.bearingDegrees,
                    gridMinLatitude = bounds.minLat,
                    gridMaxLatitude = bounds.maxLat,
                    gridMinLongitude = bounds.minLon,
                    gridMaxLongitude = bounds.maxLon,
                    evidence = evidence,
                )
            }.getOrNull()
        }
    }
    // Publish agreement into ranking so MetalDetectingTargetRefiner / ExplainableRanker can
    // apply the capped MapTerrainAgreement adjustment (null clears the prior score).
    LaunchedEffect(historicAgreement?.score) {
        onHistoricMapAgreementScore(historicAgreement?.score)
    }

    fun refreshHistoricMaps() {
        historicMaps = historicMapRepository.list()
    }

    val historicMapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = historicMapDisplayName(context, uri)
        val center = googleMap?.cameraPosition?.target ?: cameraCenter
        val visibleWidthMeters = googleMap?.projection?.visibleRegion?.latLngBounds?.let { bounds ->
            GeoSpatialLibrary.calculateGeodesicDistance(
                bounds.southwest.latitude,
                bounds.southwest.longitude,
                bounds.southwest.latitude,
                bounds.northeast.longitude,
            ).toFloat()
        }
        val defaultWidth = ((visibleWidthMeters ?: 400f) * 0.5f).coerceIn(20f, 5_000f)
        historicMapMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    historicMapRepository.importImage(
                        context = context,
                        uri = uri,
                        requestedName = name,
                        defaultLatitude = center.latitude,
                        defaultLongitude = center.longitude,
                        defaultBaseWidthMeters = defaultWidth,
                    )
                }
            }
            result.onSuccess { imported ->
                refreshHistoricMaps()
                activeHistoricMapId = imported.id
                historicPanelExpanded = true
            }.onFailure { error ->
                historicMapMessage = error.localizedMessage ?: "Could not import historic map image"
            }
        }
    }

    fun updateHistoricMap(updated: HistoricMapOverlay) {
        historicMapRepository.update(updated)
        refreshHistoricMaps()
    }

    fun persistGeoReferencedMap(overlay: HistoricMapOverlay) {
        val now = System.currentTimeMillis()
        val domain = GeoReferencedMap(
            id = overlay.id,
            terrainKey = terrainKey,
            displayName = overlay.displayName,
            imageUri = overlay.file.absolutePath,
            sourceAttribution = overlay.sourceAttribution.ifBlank { "Imported historic map" },
            controlPoints = overlay.controlPoints,
            transform = overlay.transformStorage?.let { GeoReferenceTransform.fromStorage(it) },
            rmseMeters = overlay.rmseMeters,
            maxResidualMeters = overlay.maxResidualMeters,
            confidence = overlay.confidence,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        scope.launch(Dispatchers.IO) {
            historicMapDao.upsert(domain.toEntity())
        }
    }

    fun applyControlPointFit(record: HistoricMapOverlay) {
        val bitmap = historicBitmaps[record.id]?.takeIf { !it.isRecycled }
        if (bitmap == null) {
            historicMapMessage = "Wait for the map image to load before fitting."
            return
        }
        if (record.controlPoints.size < 2) {
            historicMapMessage = "Add at least two control points (map tap + image crosshair)."
            return
        }
        val fit = GeoReferencer.fit(record.controlPoints)
        if (fit.transform == null) {
            historicMapMessage = fit.note
            return
        }
        val placement = HistoricMapGeoreference.placementFromFit(fit, bitmap.width, bitmap.height)
        if (placement == null) {
            historicMapMessage = "Fit produced an unusable placement — spread control points."
            return
        }
        val naturalHeight = record.baseWidthMeters / record.aspectRatio.coerceAtLeast(0.01f)
        val updated = record.copy(
            latitude = placement.centerLatitude,
            longitude = placement.centerLongitude,
            widthScale = (placement.widthMeters / record.baseWidthMeters).coerceIn(0.05f, 20f),
            heightScale = (placement.heightMeters / naturalHeight.coerceAtLeast(1f)).coerceIn(0.05f, 20f),
            bearingDegrees = placement.bearingDegrees.coerceIn(-180f, 180f),
            confidence = fit.confidence,
            rmseMeters = fit.rmseMeters,
            maxResidualMeters = fit.maxResidualMeters,
            transformStorage = fit.transform.toStorage(),
        )
        updateHistoricMap(updated)
        persistGeoReferencedMap(updated)
        historicMapMessage = buildString {
            append(fit.confidence.label)
            fit.rmseMeters?.let { append(" · RMSE ${"%.1f".format(Locale.US, it)} m") }
            append(" · ${fit.note}")
        }
    }

    fun addControlPointAt(latLng: LatLng) {
        val active = historicMaps.firstOrNull { it.id == activeHistoricMapId } ?: return
        val bitmap = historicBitmaps[active.id]?.takeIf { !it.isRecycled } ?: run {
            historicMapMessage = "Wait for the map image to load."
            return
        }
        val point = HistoricMapControlPoint(
            imageX = pendingImageXFraction.coerceIn(0f, 1f) * (bitmap.width - 1).coerceAtLeast(1),
            imageY = pendingImageYFraction.coerceIn(0f, 1f) * (bitmap.height - 1).coerceAtLeast(1),
            latitude = latLng.latitude,
            longitude = latLng.longitude,
        )
        val updated = active.copy(
            controlPoints = active.controlPoints + point,
            confidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
            rmseMeters = null,
            maxResidualMeters = null,
            transformStorage = null,
        )
        updateHistoricMap(updated)
        historicMapMessage = "Control point ${updated.controlPoints.size} added. " +
            if (updated.controlPoints.size >= 2) "Fit when ready." else "Add another point."
    }

    fun updateAlignment(updated: TerrainMapAlignment) {
        alignment = updated
        alignmentStore.save(terrainKey, updated)
        hasSavedAlignment = true
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            googleMap = map
            map.mapType = mapType
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isMapToolbarEnabled = false
            map.uiSettings.isZoomControlsEnabled = false
            map.setOnCameraIdleListener { cameraCenter = map.cameraPosition.target }
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasLocationPermission) {
                runCatching {
                    map.isMyLocationEnabled = true
                    map.uiSettings.isMyLocationButtonEnabled = true
                }
            }
        }
        onDispose {
            overlay?.remove()
            surveyMapObjects.forEach(::removeMapObject)
            breadcrumbPolylines.forEach { it.remove() }
            coverageOverlay?.remove()
            routePolyline?.remove()
            historicOverlayObjects.values.forEach { it.remove() }
            controlPointMarkers.forEach { it.remove() }
            googleMap = null
        }
    }

    LaunchedEffect(googleMap, mapType) {
        googleMap?.mapType = mapType
    }

    LaunchedEffect(historicMaps) {
        val currentIds = historicMaps.map { it.id }.toSet()
        historicBitmaps.keys.filterNot { it in currentIds }.forEach { historicBitmaps.remove(it) }
        historicMaps.forEach { record ->
            if (historicBitmaps.containsKey(record.id)) return@forEach
            val bitmap = withContext(Dispatchers.IO) {
                runCatching { decodeSampledBitmap(record.file, maxDimension = 2048) }.getOrNull()
            }
            if (bitmap != null) historicBitmaps[record.id] = bitmap
        }
    }

    LaunchedEffect(googleMap, historicMaps, historicBitmaps.toMap(), swipeBlend, activeHistoricMapId) {
        val map = googleMap ?: return@LaunchedEffect
        // Rebuild wholesale rather than diffing. Anything left on the map but absent from the
        // replacement tracking map below becomes unreachable - onDispose only removes what it
        // holds - so a record that is visible but whose bitmap is not ready yet would otherwise
        // strand its previous overlay on screen permanently.
        historicOverlayObjects.values.forEach { it.remove() }
        val updated = mutableMapOf<String, GroundOverlay>()
        historicMaps.forEach { record ->
            if (!record.visible) return@forEach
            val bitmap = historicBitmaps[record.id]?.takeIf { !it.isRecycled } ?: return@forEach
            // Swipe blend multiplies only the active map so compare mode leaves others alone.
            val opacityScale = if (record.id == activeHistoricMapId) swipeBlend.coerceIn(0f, 1f) else 1f
            val effectiveOpacity = (record.opacity * opacityScale).coerceIn(0f, 1f)
            val added = map.addGroundOverlay(
                GroundOverlayOptions()
                    .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                    .position(LatLng(record.latitude, record.longitude), record.widthMeters, record.heightMeters)
                    .bearing(record.bearingDegrees)
                    .transparency(1f - effectiveOpacity)
                    .zIndex(3f),
            ) ?: return@forEach
            updated[record.id] = added
        }
        historicOverlayObjects = updated
    }

    // Keep the map-click handler on current control-point state (mapAsync captures once).
    // Always remember the latest tap for point-feature placement when a historic map is active.
    LaunchedEffect(googleMap, controlPointMode, activeHistoricMapId, pendingImageXFraction, pendingImageYFraction, historicMaps) {
        val map = googleMap ?: return@LaunchedEffect
        if (activeHistoricMapId != null) {
            map.setOnMapClickListener { latLng ->
                lastMapTapLatLng = latLng
                if (controlPointMode) addControlPointAt(latLng)
            }
        } else {
            map.setOnMapClickListener(null)
        }
    }

    fun addHistoricFeatureFromControlPoints(type: MapFeatureType) {
        val active = historicMaps.firstOrNull { it.id == activeHistoricMapId } ?: return
        if (active.controlPoints.size < 2) {
            historicMapMessage = "Need at least 2 control points to build a polyline feature."
            return
        }
        val points = active.controlPoints.takeLast(2).map {
            BoundaryVertex(it.latitude, it.longitude)
        }
        val feature = HistoricMapFeature(
            id = UUID.randomUUID().toString(),
            mapId = active.id,
            type = type,
            points = points,
            confidence = 0.7f,
            note = "Manual · last 2 control points",
            createdAtMillis = System.currentTimeMillis(),
        )
        scope.launch(Dispatchers.IO) {
            historicMapFeatureDao.upsert(feature.toEntity())
        }
        historicMapMessage = "Added ${type.label} from last 2 control points."
    }

    fun addHistoricPointFeatureAtLastTap(type: MapFeatureType) {
        val active = historicMaps.firstOrNull { it.id == activeHistoricMapId } ?: return
        val tap = lastMapTapLatLng
        if (tap == null) {
            historicMapMessage = "Tap the map first to place a point feature."
            return
        }
        val feature = HistoricMapFeature(
            id = UUID.randomUUID().toString(),
            mapId = active.id,
            type = type,
            points = listOf(BoundaryVertex(tap.latitude, tap.longitude)),
            confidence = 0.6f,
            note = "Manual · map tap",
            createdAtMillis = System.currentTimeMillis(),
        )
        scope.launch(Dispatchers.IO) {
            historicMapFeatureDao.upsert(feature.toEntity())
        }
        historicMapMessage = "Added ${type.label} at last map tap."
    }

    fun deleteHistoricFeature(featureId: String) {
        scope.launch(Dispatchers.IO) {
            historicMapFeatureDao.deleteById(featureId)
        }
    }

    fun autoExtractHistoricFeatures(cloudEnhance: Boolean = false) {
        val active = historicMaps.firstOrNull { it.id == activeHistoricMapId } ?: return
        val bitmap = historicBitmaps[active.id]?.takeIf { !it.isRecycled }
        if (bitmap == null) {
            historicMapMessage = "Wait for the map image to load before auto-extract."
            return
        }
        val transform = active.transformStorage?.let { GeoReferenceTransform.fromStorage(it) }
            ?: active.controlPoints.takeIf { it.size >= 2 }?.let { GeoReferencer.fit(it).transform }
        if (transform == null) {
            historicMapMessage = "Fit control points first — auto-extract needs a georeference transform."
            return
        }
        historicMapMessage = if (cloudEnhance) "Local ink + cloud enhance…" else "Auto-extracting (local ink)…"
        val appCtx = context.applicationContext
        scope.launch(Dispatchers.Default) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            var result = MapVectorizationGateway.extractLocal(
                pixels = pixels,
                width = w,
                height = h,
                mapId = active.id,
                transform = transform,
            )
            if (cloudEnhance) {
                runCatching {
                    withContext(Dispatchers.Main) { historicMapMessage = "Cloud map vector enhance…" }
                    val gateway = TerrainAiGateway(appCtx)
                    val answer = gateway.generate(
                        conversation = listOf(
                            GeminiConversationTurn(
                                role = "user",
                                text = MapVectorizationGateway.cloudUserPrompt(
                                    result,
                                    "site map ${active.displayName} · ${active.confidence.label}",
                                ),
                            ),
                        ),
                        systemContext = MapVectorizationGateway.cloudSystemPrompt(),
                        image = null,
                    )
                    result = MapVectorizationGateway.mergeCloudAnswer(
                        local = result,
                        answerText = answer.text,
                        mapId = active.id,
                        providerLabel = answer.provider.label,
                        fallbackReason = answer.fallbackReason,
                    )
                }.onFailure { err ->
                    withContext(Dispatchers.Main) {
                        historicMapMessage =
                            "Cloud enhance failed — kept local drafts. ${err.localizedMessage ?: ""}"
                    }
                }
            }
            withContext(Dispatchers.IO) {
                result.features.forEach { historicMapFeatureDao.upsert(it.toEntity()) }
            }
            withContext(Dispatchers.Main) {
                val mode = if (result.mode == MapVectorizationGateway.Mode.CLOUD_ENHANCE) {
                    "Mode: Cloud"
                } else {
                    "Mode: Local"
                }
                historicMapMessage = "$mode · ${result.note}"
            }
        }
    }

    LaunchedEffect(googleMap, activeHistoricMap?.controlPoints, controlPointMode) {
        val map = googleMap ?: return@LaunchedEffect
        controlPointMarkers.forEach { it.remove() }
        val points = activeHistoricMap?.controlPoints.orEmpty()
        if (!controlPointMode && points.isEmpty()) {
            controlPointMarkers = emptyList()
            return@LaunchedEffect
        }
        controlPointMarkers = points.mapIndexedNotNull { index, point ->
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(point.latitude, point.longitude))
                    .title("CP ${index + 1}")
                    .snippet(
                        "Image ${point.imageX.toInt()}, ${point.imageY.toInt()}",
                    )
                    .zIndex(8f),
            )
        }
    }

    LaunchedEffect(googleMap, terrainBitmap, alignment, naturalSize, opacity, terrainKey) {
        val map = googleMap ?: return@LaunchedEffect
        overlay?.remove()
        overlay = null
        val bitmap = terrainBitmap?.takeIf { !it.isRecycled } ?: return@LaunchedEffect
        val placement = alignment ?: return@LaunchedEffect
        val widthMeters = naturalSize.widthMeters * placement.widthScale
        val heightMeters = naturalSize.heightMeters * placement.heightScale
        overlay = map.addGroundOverlay(
            GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .position(placement.center, widthMeters, heightMeters)
                .bearing(placement.bearingDegrees)
                .transparency(1f - opacity.coerceIn(0.1f, 1f))
                .zIndex(4f),
        )
        if (lastFramedTerrainKey != terrainKey) {
            lastFramedTerrainKey = terrainKey
            val frameBounds = boundsCenteredAt(placement.center, widthMeters, heightMeters).toLatLngBounds()
            mapView.post {
                runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(frameBounds, 72)) }
                    .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(placement.center, 16f)) }
            }
        }
    }

    LaunchedEffect(googleMap, surveyFeatures) {
        val map = googleMap ?: return@LaunchedEffect
        surveyMapObjects.forEach(::removeMapObject)
        surveyMapObjects = surveyFeatures.mapNotNull { feature ->
            val points = feature.coordinates.map { LatLng(it.latitude, it.longitude) }
            when (feature.geometryType) {
                SurveyGeometryType.POINT -> points.firstOrNull()?.let { point ->
                    map.addMarker(
                        MarkerOptions()
                            .position(point)
                            .title(feature.name ?: "Survey waypoint"),
                    )
                }
                SurveyGeometryType.LINE -> if (points.size >= 2) {
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(points)
                            .color(android.graphics.Color.CYAN)
                            .width(6f)
                            .zIndex(6f),
                    )
                } else {
                    null
                }
                SurveyGeometryType.POLYGON -> if (points.size >= 3) {
                    map.addPolygon(
                        PolygonOptions()
                            .addAll(points)
                            .strokeColor(android.graphics.Color.CYAN)
                            .fillColor(android.graphics.Color.argb(42, 0, 229, 255))
                            .strokeWidth(5f)
                            .zIndex(5f),
                    )
                } else {
                    null
                }
            }
        }
    }

    LaunchedEffect(googleMap, breadcrumbTracks) {
        val map = googleMap ?: return@LaunchedEffect
        breadcrumbPolylines.forEach { it.remove() }
        breadcrumbPolylines = breadcrumbTracks.mapNotNull { track ->
            val points = track.points.map { point -> LatLng(point.latitude, point.longitude) }
            if (points.size < 2) return@mapNotNull null
            map.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .color(android.graphics.Color.rgb(255, 152, 0))
                    .width(7f)
                    .zIndex(7f),
            )
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        OverlayHeader(
            mapType = mapType,
            onMapTypeChanged = { mapType = it },
            status = when {
                BuildConfig.MAPS_API_KEY.isBlank() -> "MAPS_API_KEY is missing from .env/local.properties"
                terrainBitmap == null -> "Render or import a terrain layer first"
                alignment == null -> "Pan the map, then place the LAZ image at the center crosshair"
                hasSavedAlignment -> "Alignment saved for this terrain file"
                else -> "Using geographic bounds from the terrain file"
            },
            isError = BuildConfig.MAPS_API_KEY.isBlank(),
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(0.96f),
        )

        OverlayControls(
            opacity = opacity,
            onOpacityChanged = { opacity = it },
            alignmentMode = alignmentMode,
            onAlignmentModeChanged = { alignmentMode = it },
            alignment = alignment,
            canPlace = terrainBitmap != null,
            onPlaceAtCenter = {
                updateAlignment(
                    (alignment ?: TerrainMapAlignment(cameraCenter)).copy(center = cameraCenter),
                )
            },
            onWidthScaleChanged = { value ->
                alignment?.let { updateAlignment(it.copy(widthScale = value)) }
            },
            onHeightScaleChanged = { value ->
                alignment?.let { updateAlignment(it.copy(heightScale = value)) }
            },
            onBearingChanged = { value ->
                alignment?.let { updateAlignment(it.copy(bearingDegrees = value)) }
            },
            onNudge = { eastFraction, northFraction ->
                alignment?.let {
                    updateAlignment(
                        it.copy(
                            center = nudgeCenter(
                                center = it.center,
                                eastMeters = naturalSize.widthMeters * it.widthScale * eastFraction,
                                northMeters = naturalSize.heightMeters * it.heightScale * northFraction,
                            ),
                        ),
                    )
                }
            },
            onEditBounds = { editBounds = true },
            canShowSurvey = surveyPoints.isNotEmpty(),
            onShowSurvey = {
                val map = googleMap ?: return@OverlayControls
                val first = surveyPoints.firstOrNull() ?: return@OverlayControls
                mapView.post {
                    if (surveyPoints.size == 1) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(first, 17f))
                    } else {
                        val bounds = LatLngBounds.builder().apply {
                            surveyPoints.forEach(::include)
                        }.build()
                        runCatching {
                            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 88))
                        }.onFailure {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 16f))
                        }
                    }
                }
            },
            canReset = hasSavedAlignment,
            onReset = {
                alignmentStore.clear(terrainKey)
                alignment = defaultAlignment
                hasSavedAlignment = false
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(0.96f),
        )

        if (alignmentMode) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Icon(
                    Icons.Default.CenterFocusStrong,
                    contentDescription = "Map center",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(7.dp).size(28.dp),
                )
            }
        }

        if (historicPanelExpanded) {
            HistoricMapPanel(
                historicMaps = historicMaps,
                activeId = activeHistoricMapId,
                message = historicMapMessage,
                agreement = historicAgreement,
                controlPointMode = controlPointMode,
                pendingImageXFraction = pendingImageXFraction,
                pendingImageYFraction = pendingImageYFraction,
                swipeBlend = swipeBlend,
                onImport = { historicMapPicker.launch(arrayOf("image/*")) },
                onSelect = { activeHistoricMapId = it },
                onToggleVisible = { record -> updateHistoricMap(record.copy(visible = !record.visible)) },
                onDelete = { record ->
                    historicMapRepository.delete(record)
                    scope.launch(Dispatchers.IO) { historicMapDao.deleteById(record.id) }
                    refreshHistoricMaps()
                    if (activeHistoricMapId == record.id) activeHistoricMapId = null
                },
                onWidthScaleChanged = { record, value -> updateHistoricMap(record.copy(widthScale = value)) },
                onHeightScaleChanged = { record, value -> updateHistoricMap(record.copy(heightScale = value)) },
                onBearingChanged = { record, value -> updateHistoricMap(record.copy(bearingDegrees = value)) },
                onOpacityChanged = { record, value -> updateHistoricMap(record.copy(opacity = value)) },
                onNudge = { record, eastFraction, northFraction ->
                    val moved = nudgeCenter(
                        center = LatLng(record.latitude, record.longitude),
                        eastMeters = record.widthMeters * eastFraction,
                        northMeters = record.heightMeters * northFraction,
                    )
                    updateHistoricMap(record.copy(latitude = moved.latitude, longitude = moved.longitude))
                },
                onCenterHere = { record ->
                    val center = googleMap?.cameraPosition?.target ?: cameraCenter
                    updateHistoricMap(record.copy(latitude = center.latitude, longitude = center.longitude))
                },
                onControlPointModeChanged = { controlPointMode = it },
                onPendingImageXChanged = { pendingImageXFraction = it },
                onPendingImageYChanged = { pendingImageYFraction = it },
                onSwipeBlendChanged = { swipeBlend = it },
                onFitControlPoints = { applyControlPointFit(it) },
                onClearControlPoints = { record ->
                    updateHistoricMap(
                        record.copy(
                            controlPoints = emptyList(),
                            confidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
                            rmseMeters = null,
                            maxResidualMeters = null,
                            transformStorage = null,
                        ),
                    )
                },
                onRemoveLastControlPoint = { record ->
                    if (record.controlPoints.isEmpty()) return@HistoricMapPanel
                    updateHistoricMap(
                        record.copy(
                            controlPoints = record.controlPoints.dropLast(1),
                            confidence = GeoReferenceConfidence.INSUFFICIENT_POINTS,
                            rmseMeters = null,
                            maxResidualMeters = null,
                            transformStorage = null,
                        ),
                    )
                },
                onOpenSideBySide = { showSideBySide = true },
                onClose = {
                    historicPanelExpanded = false
                    controlPointMode = false
                },
                mapFeatures = historicFeatures,
                selectedFeatureTypeName = selectedFeatureType,
                onFeatureTypeSelected = { selectedFeatureType = it },
                onAddFeatureFromControlPoints = {
                    val type = MapFeatureType.entries.firstOrNull { it.name == selectedFeatureType }
                        ?: MapFeatureType.ROAD
                    addHistoricFeatureFromControlPoints(type)
                },
                onAddPointFeatureAtLastTap = {
                    val type = MapFeatureType.entries.firstOrNull { it.name == selectedFeatureType }
                        ?: MapFeatureType.ROAD
                    addHistoricPointFeatureAtLastTap(type)
                },
                onAutoExtractFeatures = { autoExtractHistoricFeatures(cloudEnhance = false) },
                onCloudEnhanceFeatures = { autoExtractHistoricFeatures(cloudEnhance = true) },
                onDeleteMapFeature = { deleteHistoricFeature(it) },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 12.dp).width(280.dp),
            )
        }

        if (showSideBySide) {
            HistoricMapSideBySideDialog(
                terrainBitmap = terrainBitmap,
                historicBitmap = activeHistoricMap?.let { historicBitmaps[it.id] },
                historicName = activeHistoricMap?.displayName ?: "Historic map",
                confidence = activeHistoricMap?.confidence,
                rmseMeters = activeHistoricMap?.rmseMeters,
                onDismiss = { showSideBySide = false },
            )
        }

        Column(
            modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val findTiles = onFindLidarTiles
            if (findTiles != null) {
                SmallFloatingActionButton(
                    onClick = { visibleSearchBounds(googleMap)?.let(findTiles) },
                    modifier = Modifier.testTag("find_lidar_tiles_in_view"),
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Find LiDAR tiles in this view")
                }
            }
            SmallFloatingActionButton(onClick = { showCoverage = !showCoverage }) {
                Icon(
                    Icons.Default.GridOn,
                    contentDescription = if (showCoverage) "Hide search coverage" else "Show search coverage",
                    tint = if (showCoverage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            if (showCoverage) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    ),
                    modifier = Modifier.widthIn(max = 240.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Search coverage",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        sweepCoverage?.let { coverage ->
                            Text(
                                "Swept ${(coverage.coverageRatio * 100f).toInt()}% · " + sweptAreaText(coverage),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        } ?: Text(
                            "Record a GPS trail to build the coverage layer.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text("Sweep width", style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1f, 2f, 3f, 4f).forEach { width ->
                                FilterChip(
                                    selected = sweepWidthMeters == width,
                                    onClick = { sweepWidthMeters = width },
                                    label = { Text("${width.toInt()} m") },
                                )
                            }
                        }
                    }
                }
            }
            if (routeWaypoints.size >= 2) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    ),
                    modifier = Modifier.widthIn(max = 240.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Route active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${routeWaypoints.size} stops · ${routeDistanceText(routeTotalMeters)} total",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        TextButton(onClick = onClearRoute) { Text("Clear") }
                    }
                }
            }
            if (!historicPanelExpanded) {
                SmallFloatingActionButton(onClick = { historicPanelExpanded = true }) {
                    Icon(Icons.Default.History, contentDescription = "Historic maps")
                }
            }
        }
    }

    if (editBounds) {
        BoundsEditorDialog(
            initial = alignment?.toBounds(naturalSize),
            onDismiss = { editBounds = false },
            onApply = {
                updateAlignment(it.toAlignment(naturalSize, alignment?.bearingDegrees ?: 0f))
                editBounds = false
            },
        )
    }
}

@Composable
private fun OverlayHeader(
    mapType: Int,
    onMapTypeChanged: (Int) -> Unit,
    status: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Rendered LiDAR overlay", fontWeight = FontWeight.Bold)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Map" to GoogleMap.MAP_TYPE_NORMAL,
                    "Satellite" to GoogleMap.MAP_TYPE_SATELLITE,
                    "Hybrid" to GoogleMap.MAP_TYPE_HYBRID,
                    "Terrain" to GoogleMap.MAP_TYPE_TERRAIN,
                ).forEach { (label, type) ->
                    FilterChip(
                        selected = mapType == type,
                        onClick = { onMapTypeChanged(type) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayControls(
    opacity: Float,
    onOpacityChanged: (Float) -> Unit,
    alignmentMode: Boolean,
    onAlignmentModeChanged: (Boolean) -> Unit,
    alignment: TerrainMapAlignment?,
    canPlace: Boolean,
    onPlaceAtCenter: () -> Unit,
    onWidthScaleChanged: (Float) -> Unit,
    onHeightScaleChanged: (Float) -> Unit,
    onBearingChanged: (Float) -> Unit,
    onNudge: (eastFraction: Float, northFraction: Float) -> Unit,
    onEditBounds: () -> Unit,
    canShowSurvey: Boolean,
    onShowSurvey: () -> Unit,
    canReset: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Aligning is a visual task: the controls must not sit on top of the thing being aligned.
    // Collapsing leaves a single slim row, and only one field's slider is shown at a time.
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var field by rememberSaveable { mutableStateOf(AlignField.WIDTH) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Button(onClick = { onAlignmentModeChanged(!alignmentMode) }, enabled = canPlace) {
                        Icon(Icons.Default.EditLocationAlt, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (alignmentMode) "Done" else "Align LAZ")
                    }
                    OutlinedButton(onClick = onPlaceAtCenter, enabled = canPlace) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Center here")
                    }
                    OutlinedButton(onClick = onShowSurvey, enabled = canShowSurvey) {
                        Icon(Icons.Default.Layers, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Survey")
                    }
                    if (canReset) {
                        OutlinedButton(onClick = onReset) {
                            Icon(Icons.Default.MyLocation, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Reset")
                        }
                    }
                }
                IconButton(onClick = { collapsed = !collapsed }) {
                    Icon(
                        if (collapsed) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (collapsed) "Show overlay controls" else "Hide overlay controls to see the map",
                    )
                }
            }

            if (!collapsed) {
                if (alignmentMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AlignField.entries.forEach { candidate ->
                            FilterChip(
                                selected = field == candidate,
                                onClick = { field = candidate },
                                label = { Text(candidate.label) },
                            )
                        }
                    }
                }

                when {
                    alignmentMode && field == AlignField.WIDTH -> AlignmentSlider(
                        label = "Width",
                        valueLabel = "${((alignment?.widthScale ?: 1f) * 100).toInt()}%",
                        value = alignment?.widthScale ?: 1f,
                        onValueChange = onWidthScaleChanged,
                        range = 0.2f..5f,
                        enabled = alignment != null,
                    )
                    alignmentMode && field == AlignField.HEIGHT -> AlignmentSlider(
                        label = "Height",
                        valueLabel = "${((alignment?.heightScale ?: 1f) * 100).toInt()}%",
                        value = alignment?.heightScale ?: 1f,
                        onValueChange = onHeightScaleChanged,
                        range = 0.2f..5f,
                        enabled = alignment != null,
                    )
                    alignmentMode && field == AlignField.ROTATION -> AlignmentSlider(
                        label = "Rotation",
                        valueLabel = "${(alignment?.bearingDegrees ?: 0f).toInt()}°",
                        value = alignment?.bearingDegrees ?: 0f,
                        onValueChange = onBearingChanged,
                        range = -180f..180f,
                        enabled = alignment != null,
                    )
                    else -> AlignmentSlider(
                        label = "Opacity",
                        valueLabel = "${(opacity * 100).toInt()}%",
                        value = opacity,
                        onValueChange = onOpacityChanged,
                        range = 0.1f..1f,
                        enabled = true,
                    )
                }

                if (alignmentMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "←" to (-0.02f to 0f),
                            "↑" to (0f to 0.02f),
                            "↓" to (0f to -0.02f),
                            "→" to (0.02f to 0f),
                        ).forEach { (label, direction) ->
                            OutlinedButton(
                                onClick = { onNudge(direction.first, direction.second) },
                                enabled = alignment != null,
                                contentPadding = PaddingValues(horizontal = 14.dp),
                            ) { Text(label) }
                        }
                        OutlinedButton(
                            onClick = onEditBounds,
                            enabled = alignment != null,
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) { Text("Exact bounds") }
                    }
                }
            }
        }
    }
}

private enum class AlignField(val label: String) {
    WIDTH("Width"),
    HEIGHT("Height"),
    ROTATION("Rotate"),
    OPACITY("Opacity"),
}

@Composable
private fun AlignmentSlider(
    label: String,
    valueLabel: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Text(valueLabel, style = MaterialTheme.typography.labelMedium)
    }
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onValueChange,
        valueRange = range,
        enabled = enabled,
    )
}

@Composable
private fun HistoricMapPanel(
    historicMaps: List<HistoricMapOverlay>,
    activeId: String?,
    message: String?,
    agreement: MapFeatureAgreement?,
    controlPointMode: Boolean,
    pendingImageXFraction: Float,
    pendingImageYFraction: Float,
    swipeBlend: Float,
    onImport: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleVisible: (HistoricMapOverlay) -> Unit,
    onDelete: (HistoricMapOverlay) -> Unit,
    onWidthScaleChanged: (HistoricMapOverlay, Float) -> Unit,
    onHeightScaleChanged: (HistoricMapOverlay, Float) -> Unit,
    onBearingChanged: (HistoricMapOverlay, Float) -> Unit,
    onOpacityChanged: (HistoricMapOverlay, Float) -> Unit,
    onNudge: (HistoricMapOverlay, Float, Float) -> Unit,
    onCenterHere: (HistoricMapOverlay) -> Unit,
    onControlPointModeChanged: (Boolean) -> Unit,
    onPendingImageXChanged: (Float) -> Unit,
    onPendingImageYChanged: (Float) -> Unit,
    onSwipeBlendChanged: (Float) -> Unit,
    onFitControlPoints: (HistoricMapOverlay) -> Unit,
    onClearControlPoints: (HistoricMapOverlay) -> Unit,
    onRemoveLastControlPoint: (HistoricMapOverlay) -> Unit,
    onOpenSideBySide: () -> Unit,
    onClose: () -> Unit,
    mapFeatures: List<HistoricMapFeature> = emptyList(),
    selectedFeatureTypeName: String = MapFeatureType.ROAD.name,
    onFeatureTypeSelected: (String) -> Unit = {},
    onAddFeatureFromControlPoints: () -> Unit = {},
    onAddPointFeatureAtLastTap: () -> Unit = {},
    onAutoExtractFeatures: () -> Unit = {},
    onCloudEnhanceFeatures: () -> Unit = {},
    onDeleteMapFeature: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val active = historicMaps.firstOrNull { it.id == activeId }
    // Same constraint as the LAZ controls: keep the footprint small, show one slider at a time, and
    // let the list of imported maps collapse away once the user is actually aligning one.
    var listExpanded by rememberSaveable { mutableStateOf(true) }
    var field by rememberSaveable { mutableStateOf(AlignField.WIDTH) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.heightIn(max = 440.dp).testTag("historic_map_panel"),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Historic maps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { listExpanded = !listExpanded }) {
                    Icon(
                        if (listExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (listExpanded) "Hide map list" else "Show map list",
                    )
                }
                TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Close") }
            }
            message?.let {
                Text(
                    it,
                    color = if (it.contains("Good") || it.contains("Fair") || it.contains("added")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (listExpanded) {
                OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Import map image")
                }
                if (historicMaps.isEmpty()) {
                    Text(
                        "Import a scanned plat, survey, or old topographic map, then align with " +
                            "manual controls or control-point georeferencing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                historicMaps.forEach { record ->
                    HistoricMapRow(
                        overlay = record,
                        selected = record.id == activeId,
                        onSelect = { onSelect(record.id) },
                        onToggleVisible = { onToggleVisible(record) },
                        onDelete = { onDelete(record) },
                    )
                }
            }
            if (active != null) {
                if (listExpanded) HorizontalDivider()
                Text(
                    active.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
                // Georeference confidence is always visible when a fit exists so low-confidence
                // alignments stay labeled (Phase 6 exit criterion).
                Text(
                    buildString {
                        append(active.confidence.label)
                        active.rmseMeters?.let { append(" · RMSE ${"%.1f".format(Locale.US, it)} m") }
                        if (active.controlPoints.isNotEmpty()) {
                            append(" · ${active.controlPoints.size} CP")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = when (active.confidence) {
                        GeoReferenceConfidence.GOOD -> MaterialTheme.colorScheme.primary
                        GeoReferenceConfidence.FAIR -> MaterialTheme.colorScheme.onSurface
                        GeoReferenceConfidence.LOW_CONFIDENCE,
                        GeoReferenceConfidence.INSUFFICIENT_POINTS,
                        -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.testTag("historic_georef_confidence"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AlignField.entries.forEach { candidate ->
                        FilterChip(
                            selected = field == candidate,
                            onClick = { field = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
                when (field) {
                    AlignField.WIDTH -> AlignmentSlider(
                        label = "Width",
                        valueLabel = "${(active.widthScale * 100).toInt()}%",
                        value = active.widthScale,
                        onValueChange = { onWidthScaleChanged(active, it) },
                        range = 0.2f..5f,
                        enabled = true,
                    )
                    AlignField.HEIGHT -> AlignmentSlider(
                        label = "Height",
                        valueLabel = "${(active.heightScale * 100).toInt()}%",
                        value = active.heightScale,
                        onValueChange = { onHeightScaleChanged(active, it) },
                        range = 0.2f..5f,
                        enabled = true,
                    )
                    AlignField.ROTATION -> AlignmentSlider(
                        label = "Rotation",
                        valueLabel = "${active.bearingDegrees.toInt()}°",
                        value = active.bearingDegrees,
                        onValueChange = { onBearingChanged(active, it) },
                        range = -180f..180f,
                        enabled = true,
                    )
                    AlignField.OPACITY -> AlignmentSlider(
                        label = "Opacity",
                        valueLabel = "${(active.opacity * 100).toInt()}%",
                        value = active.opacity,
                        onValueChange = { onOpacityChanged(active, it) },
                        range = 0.1f..1f,
                        enabled = true,
                    )
                }
                AlignmentSlider(
                    label = "Swipe blend",
                    valueLabel = "${(swipeBlend * 100).toInt()}%",
                    value = swipeBlend,
                    onValueChange = onSwipeBlendChanged,
                    range = 0f..1f,
                    enabled = true,
                )
                Text(
                    "Swipe 0% = terrain only · 100% = full historic overlay (active map).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        "←" to (-0.02f to 0f),
                        "↑" to (0f to 0.02f),
                        "↓" to (0f to -0.02f),
                        "→" to (0.02f to 0f),
                    ).forEach { (label, direction) ->
                        OutlinedButton(
                            onClick = { onNudge(active, direction.first, direction.second) },
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) { Text(label) }
                    }
                    OutlinedButton(
                        onClick = { onCenterHere(active) },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text("Center here") }
                    OutlinedButton(
                        onClick = onOpenSideBySide,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        modifier = Modifier.testTag("historic_side_by_side_button"),
                    ) {
                        Icon(Icons.Default.Compare, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Side-by-side")
                    }
                }
                HorizontalDivider()
                Text("Control-point georeference", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Set the image crosshair, then tap the matching real-world location on the map. " +
                        "Two points fit (low confidence); three+ produce a least-squares affine with RMSE.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (controlPointMode) {
                    Button(
                        onClick = { onControlPointModeChanged(false) },
                        modifier = Modifier.fillMaxWidth().testTag("control_point_mode_toggle"),
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Tap map to add · ${active.controlPoints.size} points")
                    }
                    AlignmentSlider(
                        label = "Image X",
                        valueLabel = "${(pendingImageXFraction * 100).toInt()}%",
                        value = pendingImageXFraction,
                        onValueChange = onPendingImageXChanged,
                        range = 0f..1f,
                        enabled = true,
                    )
                    AlignmentSlider(
                        label = "Image Y",
                        valueLabel = "${(pendingImageYFraction * 100).toInt()}%",
                        value = pendingImageYFraction,
                        onValueChange = onPendingImageYChanged,
                        range = 0f..1f,
                        enabled = true,
                    )
                } else {
                    OutlinedButton(
                        onClick = { onControlPointModeChanged(true) },
                        modifier = Modifier.fillMaxWidth().testTag("control_point_mode_toggle"),
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add control points (${active.controlPoints.size})")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onFitControlPoints(active) },
                        enabled = active.controlPoints.size >= 2,
                        modifier = Modifier.weight(1f).testTag("fit_control_points_button"),
                    ) { Text("Fit") }
                    OutlinedButton(
                        onClick = { onRemoveLastControlPoint(active) },
                        enabled = active.controlPoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Undo CP") }
                    OutlinedButton(
                        onClick = { onClearControlPoints(active) },
                        enabled = active.controlPoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Clear") }
                }
                if (agreement != null) {
                    HorizontalDivider()
                    Text(
                        "Terrain agreement: ${(agreement.score * 100f).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = when {
                            agreement.score >= 0.66f -> MaterialTheme.colorScheme.primary
                            agreement.score >= 0.4f -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                    Text(
                        agreement.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Terrain agreement appears once this map overlaps georeferenced terrain detail. " +
                            "Higher percentages mean the map's drawn features sit on real terrain evidence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Text("Map features", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Auto-extract ink regions after Fit, or add features manually from control points / map tap. Drafts only — review before trusting.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onAutoExtractFeatures,
                    enabled = active.controlPoints.size >= 2 || active.transformStorage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("historic_feature_auto_extract"),
                ) {
                    Text("Auto-extract (local ink)")
                }
                OutlinedButton(
                    onClick = onCloudEnhanceFeatures,
                    enabled = active.controlPoints.size >= 2 || active.transformStorage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("historic_feature_cloud_enhance"),
                ) {
                    Text("Enhance online (cloud optional)")
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MapFeatureType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedFeatureTypeName == type.name,
                            onClick = { onFeatureTypeSelected(type.name) },
                            label = { Text(type.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = onAddFeatureFromControlPoints,
                    enabled = active.controlPoints.size >= 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("historic_feature_add"),
                ) {
                    Text("Add feature from 2 latest control points")
                }
                OutlinedButton(
                    onClick = onAddPointFeatureAtLastTap,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add point feature at last map tap")
                }
                if (mapFeatures.isEmpty()) {
                    Text(
                        "No features yet for this map.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    mapFeatures.forEach { feature ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${feature.type.name} · ${feature.points.size} pt" +
                                        if (feature.points.size == 1) "" else "s",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (feature.note.isNotBlank()) {
                                    Text(
                                        feature.note,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            TextButton(onClick = { onDeleteMapFeature(feature.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricMapSideBySideDialog(
    terrainBitmap: Bitmap?,
    historicBitmap: Bitmap?,
    historicName: String,
    confidence: GeoReferenceConfidence?,
    rmseMeters: Double?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Side-by-side alignment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    buildString {
                        append("Compare terrain hillshade with “$historicName”.")
                        confidence?.let { append(" ${it.label}.") }
                        rmseMeters?.let { append(" RMSE ${"%.1f".format(Locale.US, it)} m.") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Terrain", style = MaterialTheme.typography.labelMedium)
                        if (terrainBitmap != null && !terrainBitmap.isRecycled) {
                            Image(
                                bitmap = terrainBitmap.asImageBitmap(),
                                contentDescription = "Terrain hillshade",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { Text("No terrain") }
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Historic", style = MaterialTheme.typography.labelMedium)
                        if (historicBitmap != null && !historicBitmap.isRecycled) {
                            Image(
                                bitmap = historicBitmap.asImageBitmap(),
                                contentDescription = "Historic map",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) { Text("No historic") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun HistoricMapRow(
    overlay: HistoricMapOverlay,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (selected) {
            Button(onClick = onSelect, modifier = Modifier.weight(1f)) {
                Text(overlay.displayName, maxLines = 1)
            }
        } else {
            OutlinedButton(onClick = onSelect, modifier = Modifier.weight(1f)) {
                Text(overlay.displayName, maxLines = 1)
            }
        }
        IconButton(onClick = onToggleVisible) {
            Icon(
                if (overlay.visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (overlay.visible) "Hide ${overlay.displayName}" else "Show ${overlay.displayName}",
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete ${overlay.displayName}")
        }
    }
}

@Composable
private fun BoundsEditorDialog(
    initial: GeoSpatialLibrary.GeographicBounds?,
    onDismiss: () -> Unit,
    onApply: (GeoSpatialLibrary.GeographicBounds) -> Unit,
) {
    var south by remember(initial) { mutableStateOf(initial?.minLat?.toString().orEmpty()) }
    var north by remember(initial) { mutableStateOf(initial?.maxLat?.toString().orEmpty()) }
    var west by remember(initial) { mutableStateOf(initial?.minLon?.toString().orEmpty()) }
    var east by remember(initial) { mutableStateOf(initial?.maxLon?.toString().orEmpty()) }
    val bounds = remember(south, north, west, east) {
        val s = south.toDoubleOrNull()
        val n = north.toDoubleOrNull()
        val w = west.toDoubleOrNull()
        val e = east.toDoubleOrNull()
        if (s != null && n != null && w != null && e != null &&
            s in -90.0..90.0 && n in -90.0..90.0 &&
            w in -180.0..180.0 && e in -180.0..180.0 && n > s && e > w
        ) GeoSpatialLibrary.GeographicBounds(s, n, w, e) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Align LAZ overlay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the WGS84 south, north, west, and east footprint for the LAZ image.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField("South", south, { south = it }, Modifier.weight(1f))
                    CoordinateField("North", north, { north = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField("West", west, { west = it }, Modifier.weight(1f))
                    CoordinateField("East", east, { east = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { bounds?.let(onApply) }, enabled = bounds != null) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CoordinateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(16)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun rememberManagedMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) { MapView(context).apply { onCreate(Bundle()) } }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }
    return mapView
}

private fun GeoSpatialLibrary.GeographicBounds.toLatLngBounds(): LatLngBounds =
    LatLngBounds(LatLng(minLat, minLon), LatLng(maxLat, maxLon))

/**
 * The currently visible map box, as a tile-search area.
 *
 * A view straddling the antimeridian reports a south-west longitude greater than its north-east
 * one, which would read as an inverted box downstream. No LiDAR source this app queries spans that
 * line, so such a view is reported as unsearchable rather than silently mangled.
 */
private fun visibleSearchBounds(map: GoogleMap?): GeoSpatialLibrary.GeographicBounds? {
    val bounds = map?.projection?.visibleRegion?.latLngBounds ?: return null
    if (bounds.southwest.longitude > bounds.northeast.longitude) return null
    if (bounds.southwest.latitude >= bounds.northeast.latitude) return null
    return GeoSpatialLibrary.GeographicBounds(
        minLat = bounds.southwest.latitude,
        maxLat = bounds.northeast.latitude,
        minLon = bounds.southwest.longitude,
        maxLon = bounds.northeast.longitude,
    )
}

private fun boundsCenteredAt(
    center: LatLng,
    widthMeters: Float,
    heightMeters: Float,
): GeoSpatialLibrary.GeographicBounds {
    val halfLat = heightMeters.coerceAtLeast(1f) / 111_320.0 / 2.0
    val metersPerLongitudeDegree = (111_320.0 * cos(Math.toRadians(center.latitude))).coerceAtLeast(10_000.0)
    val halfLon = widthMeters.coerceAtLeast(1f) / metersPerLongitudeDegree / 2.0
    return GeoSpatialLibrary.GeographicBounds(
        minLat = (center.latitude - halfLat).coerceAtLeast(-90.0),
        maxLat = (center.latitude + halfLat).coerceAtMost(90.0),
        minLon = (center.longitude - halfLon).coerceAtLeast(-180.0),
        maxLon = (center.longitude + halfLon).coerceAtMost(180.0),
    )
}

private data class NaturalOverlaySize(
    val widthMeters: Float,
    val heightMeters: Float,
)

private data class TerrainMapAlignment(
    val center: LatLng,
    val widthScale: Float = 1f,
    val heightScale: Float = 1f,
    val bearingDegrees: Float = 0f,
)

private fun naturalOverlaySize(
    metadata: GeoSpatialLibrary.GeoSpatialMetadata,
    grid: ElevationGrid,
): NaturalOverlaySize {
    val bounds = metadata.bounds
    if (bounds != null) {
        val centerLat = (bounds.minLat + bounds.maxLat) / 2.0
        val centerLon = (bounds.minLon + bounds.maxLon) / 2.0
        return NaturalOverlaySize(
            widthMeters = GeoSpatialLibrary.calculateGeodesicDistance(
                centerLat,
                bounds.minLon,
                centerLat,
                bounds.maxLon,
            ).toFloat().coerceAtLeast(1f),
            heightMeters = GeoSpatialLibrary.calculateGeodesicDistance(
                bounds.minLat,
                centerLon,
                bounds.maxLat,
                centerLon,
            ).toFloat().coerceAtLeast(1f),
        )
    }
    return NaturalOverlaySize(
        widthMeters = ((grid.width - 1).coerceAtLeast(1) * grid.cellSizeMeters).coerceAtLeast(1f),
        heightMeters = ((grid.height - 1).coerceAtLeast(1) * grid.cellSizeMeters).coerceAtLeast(1f),
    )
}

private fun GeoSpatialLibrary.GeographicBounds.toDefaultAlignment() = TerrainMapAlignment(
    center = LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0),
)

private fun TerrainMapAlignment.toBounds(size: NaturalOverlaySize): GeoSpatialLibrary.GeographicBounds =
    boundsCenteredAt(
        center = center,
        widthMeters = size.widthMeters * widthScale,
        heightMeters = size.heightMeters * heightScale,
    )

private fun GeoSpatialLibrary.GeographicBounds.toAlignment(
    naturalSize: NaturalOverlaySize,
    bearingDegrees: Float,
): TerrainMapAlignment {
    val center = LatLng((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
    val requestedSize = NaturalOverlaySize(
        widthMeters = GeoSpatialLibrary.calculateGeodesicDistance(
            center.latitude,
            minLon,
            center.latitude,
            maxLon,
        ).toFloat(),
        heightMeters = GeoSpatialLibrary.calculateGeodesicDistance(
            minLat,
            center.longitude,
            maxLat,
            center.longitude,
        ).toFloat(),
    )
    return TerrainMapAlignment(
        center = center,
        widthScale = (requestedSize.widthMeters / naturalSize.widthMeters).coerceIn(0.2f, 5f),
        heightScale = (requestedSize.heightMeters / naturalSize.heightMeters).coerceIn(0.2f, 5f),
        bearingDegrees = bearingDegrees,
    )
}

private fun nudgeCenter(center: LatLng, eastMeters: Float, northMeters: Float): LatLng {
    val latitude = (center.latitude + northMeters / 111_320.0).coerceIn(-90.0, 90.0)
    val metersPerLongitudeDegree =
        (111_320.0 * cos(Math.toRadians(center.latitude))).coerceAtLeast(10_000.0)
    val longitude = (center.longitude + eastMeters / metersPerLongitudeDegree).coerceIn(-180.0, 180.0)
    return LatLng(latitude, longitude)
}

private class TerrainMapAlignmentStore(context: Context) {
    private val preferences = context.getSharedPreferences("terrain_map_alignments", Context.MODE_PRIVATE)

    fun contains(terrainKey: String): Boolean {
        val prefix = prefix(terrainKey)
        return preferences.getString("$prefix.terrainKey", null) == terrainKey &&
            preferences.contains("$prefix.latitude")
    }

    fun load(terrainKey: String): TerrainMapAlignment? {
        val prefix = prefix(terrainKey)
        if (preferences.getString("$prefix.terrainKey", null) != terrainKey) return null
        val latitude = preferences.getString("$prefix.latitude", null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString("$prefix.longitude", null)?.toDoubleOrNull() ?: return null
        val widthScale = preferences.getFloat("$prefix.widthScale", 1f)
        val heightScale = preferences.getFloat("$prefix.heightScale", 1f)
        val bearing = preferences.getFloat("$prefix.bearing", 0f)
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return TerrainMapAlignment(
            center = LatLng(latitude, longitude),
            widthScale = widthScale.coerceIn(0.2f, 5f),
            heightScale = heightScale.coerceIn(0.2f, 5f),
            bearingDegrees = bearing.coerceIn(-180f, 180f),
        )
    }

    fun save(terrainKey: String, alignment: TerrainMapAlignment) {
        val prefix = prefix(terrainKey)
        preferences.edit()
            .putString("$prefix.terrainKey", terrainKey)
            .putString("$prefix.latitude", alignment.center.latitude.toString())
            .putString("$prefix.longitude", alignment.center.longitude.toString())
            .putFloat("$prefix.widthScale", alignment.widthScale)
            .putFloat("$prefix.heightScale", alignment.heightScale)
            .putFloat("$prefix.bearing", alignment.bearingDegrees)
            .apply()
    }

    fun clear(terrainKey: String) {
        val prefix = prefix(terrainKey)
        preferences.edit()
            .remove("$prefix.terrainKey")
            .remove("$prefix.latitude")
            .remove("$prefix.longitude")
            .remove("$prefix.widthScale")
            .remove("$prefix.heightScale")
            .remove("$prefix.bearing")
            .apply()
    }

    private fun prefix(terrainKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(terrainKey.toByteArray())
        return "alignment_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }
}

private fun removeMapObject(value: Any) {
    when (value) {
        is Marker -> value.remove()
        is Polyline -> value.remove()
        is Polygon -> value.remove()
    }
}

private fun historicMapDisplayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "historic-map.jpg"
}

/** Decodes [file] downsampled so neither dimension exceeds [maxDimension], keeping large scans off the Java heap. */
private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    // Test the current sample size, not the next one: comparing against sampleSize * 2 stops one
    // step early and lets the decoded bitmap come back at up to twice the requested ceiling.
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}


/** Translucent green for swept cells; transparent elsewhere. Rendered north-up. */
private fun renderSweepCoverageBitmap(coverage: SweepCoverageGrid): Bitmap {
    val sweptColor = 0x5934C759.toInt()
    val pixels = IntArray(coverage.width * coverage.height)
    for (row in 0 until coverage.height) {
        // Coverage row 0 is the southern edge; bitmap row 0 is north.
        val sourceRow = coverage.height - 1 - row
        for (column in 0 until coverage.width) {
            if (coverage.covered[sourceRow * coverage.width + column]) {
                pixels[row * coverage.width + column] = sweptColor
            }
        }
    }
    return Bitmap.createBitmap(pixels, coverage.width, coverage.height, Bitmap.Config.ARGB_8888)
}

private fun sweptAreaText(coverage: SweepCoverageGrid): String =
    if (coverage.coveredAreaSquareMeters >= 10_000f) {
        String.format(Locale.US, "%.1f ha", coverage.coveredAreaSquareMeters / 10_000f)
    } else {
        "${coverage.coveredAreaSquareMeters.toInt()} m²"
    }

private fun routeDistanceText(totalMeters: Float): String =
    if (totalMeters >= 1000f) {
        String.format(Locale.US, "%.2f km", totalMeters / 1000f)
    } else {
        "${totalMeters.toInt()} m"
    }
