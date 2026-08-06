package com.example.ui

import android.app.Application
import android.app.ActivityManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.analysis.FeatureTypeCalibration
import com.example.analysis.MetalDetectingTargetType
import com.example.analysis.TerrainDerivedLayerCache
import com.example.analysis.TerrainDerivedLayers
import com.example.analysis.TerrainIntelligenceEngine
import com.example.data.BoundaryFocusMapper
import com.example.data.DemGenerator
import com.example.data.DetectionSource
import com.example.data.ElevationGrid
import com.example.data.GroundSurfaceMode
import com.example.data.TerrainQuality
import com.example.data.basemap.OfflineBasemapRegion
import com.example.data.basemap.OfflineBasemapStatus
import com.example.data.AppTerrainStorage
import com.example.data.field.BoundaryProximity
import com.example.data.field.BoundaryProximityAlert
import com.example.data.field.BoundaryVertex
import com.example.data.field.NavigationTarget
import com.example.data.field.BreadcrumbPoint
import com.example.data.field.BreadcrumbTrack
import com.example.data.field.ExcavationLogEntry
import com.example.data.field.FieldSyncQueue
import com.example.data.field.OptimizedFieldRoute
import com.example.data.field.PendingSyncEntry
import com.example.data.field.SurveyBoundary
import com.example.data.field.SyncEntityType
import com.example.data.field.SyncOperation
import com.example.data.LazDatasetStore
import com.example.data.LazTerrainCache
import com.example.data.LazTerrainDiskCache
import com.example.data.LazTerrainMemoryCache
import com.example.data.LazSpatialIndex
import com.example.data.LazTerrainReader
import com.example.data.LidarImportOptions
import com.example.data.MetalType
import com.example.data.NormalizedRasterBounds
import com.example.data.PointClassPreset
import com.example.data.SiteSurfaceSampler
import com.example.data.SurfaceZSample
import com.example.data.TerrainDecodeCoordinator
import com.example.data.TerrainGpuSceneBuilder
import com.example.data.TerrainImportSource
import com.example.data.TerrainPerformanceSession
import com.example.data.TerrainSessionStore
import com.example.data.TargetSignal
import com.example.data.VerificationOutcome
import com.example.data.gridForHillshadePreview
import com.example.data.hillshadeDebounceMs
import com.example.data.previewMaxSideForZoom
import com.example.data.export.AnnotatedMapBundle
import com.example.data.export.ClippedLasWriter
import com.example.data.export.DEFAULT_ETHICS_FOOTER
import com.example.data.export.GeoPackageWriter
import com.example.data.export.ProjectExportFiles
import com.example.data.export.ProjectExportRenderer
import com.example.data.export.ProjectExportSnapshot
import com.example.data.export.SitePackageExporter
import com.example.data.export.SitePackageInput
import com.example.data.targetsForTerrain
import com.example.data.survey.SurveyLayer
import com.example.data.local.AnalyzedDatasetEntity
import com.example.data.local.AppDatabase
import com.example.data.local.PendingSyncEntity
import com.example.data.local.SettingsRepository
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.data.export.GeoTiffWriter
import com.example.data.export.ProjectArchiveFile
import com.example.data.export.ProjectArchiveWriter
import com.example.data.export.buildCsv
import com.example.data.export.buildGeoJson
import com.example.data.export.buildGpx
import com.example.data.export.buildKml
import com.example.data.export.buildQgisBundle
import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import com.example.geospatial.CompassHeadingTracker
import com.example.geospatial.LocationTracker
import com.example.geospatial.BasemapTileRepository
import com.example.geospatial.BasemapDownloadProgress
import com.example.geospatial.BasemapPlan
import com.example.geospatial.SlippyTileMath
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TerrainRefinementProgress(
    val fraction: Float,
    val message: String,
)

internal fun chooseAiRefineResolution(
    memoryClassMb: Int,
    isLowRamDevice: Boolean,
    availableProcessors: Int,
    totalRamMb: Int = memoryClassMb,
): Int = when {
    isLowRamDevice || memoryClassMb < 256 -> 768
    totalRamMb >= 3_072 -> 1_536
    else -> 1_024
}

internal fun isEffectivelyWholeTerrain(bounds: NormalizedRasterBounds): Boolean {
    val sanitized = bounds.sanitized()
    val area = (sanitized.right - sanitized.left) * (sanitized.bottom - sanitized.top)
    return area >= 0.90
}

class HillshadeViewModel(application: Application) : AndroidViewModel(application) {
    private val signalDao = AppDatabase.get(application).targetSignalDao()
    private val settingsRepo = SettingsRepository(AppDatabase.get(application).settingDao())
    private val analyzedDatasetDao = AppDatabase.get(application).analyzedDatasetDao()
    private val surveyLayerDao = AppDatabase.get(application).surveyLayerDao()
    private val offlineBasemapRegionDao = AppDatabase.get(application).offlineBasemapRegionDao()
    private val breadcrumbTrackDao = AppDatabase.get(application).breadcrumbTrackDao()
    private val excavationLogDao = AppDatabase.get(application).excavationLogDao()
    private val surveyBoundaryDao = AppDatabase.get(application).surveyBoundaryDao()
    private val pendingSyncDao = AppDatabase.get(application).pendingSyncDao()
    private val refinementMemoryCache = LazTerrainMemoryCache()
    private val refinementDiskCache = AppTerrainStorage.decodedTerrainCache(application)
    private val terrainSessionStore = TerrainSessionStore(application)
    // Shares its directory with AiTerrainViewModel so layers analyzed on the AI tab
    // are visible here (homesite overlay) without re-running the extraction.
    private val terrainDerivedLayerCache = TerrainDerivedLayerCache(File(application.cacheDir, "terrain-intelligence-v2"))

    // Guard flag to prevent saveSettings() from overwriting DB values with defaults before loading completes
    private var isSettingsLoaded = false
    private var restoreImportedTerrainOnStart = false

    /** 3 = imported LiDAR/custom; no built-in Homestead/Fort/Villa demos. */
    private val _currentSiteIndex = MutableStateFlow(3)
    val currentSiteIndex: StateFlow<Int> = _currentSiteIndex.asStateFlow()
    private val _activeTerrainKey = MutableStateFlow("empty")
    val activeTerrainKey: StateFlow<String> = _activeTerrainKey.asStateFlow()
    // Keep ViewModel construction cheap so Compose can produce its first frame immediately.
    // Real terrain is restored from the last LiDAR import (or stays empty until Import).
    private val _elevationGrid = MutableStateFlow(
        ElevationGrid(
            width = 2,
            height = 2,
            bareEarth = FloatArray(4),
            canopySpikes = FloatArray(4),
        ),
    )
    val elevationGrid: StateFlow<ElevationGrid> = _elevationGrid.asStateFlow()
    private var customGrid: ElevationGrid? = null

    private val _sunAzimuth = MutableStateFlow(315f)
    val sunAzimuth = _sunAzimuth.asStateFlow()
    private val _sunAltitude = MutableStateFlow(35f)
    val sunAltitude = _sunAltitude.asStateFlow()
    private val _vegetationFilter = MutableStateFlow(0.8f)
    val vegetationFilter = _vegetationFilter.asStateFlow()
    private val _paletteType = MutableStateFlow(1)
    val paletteType = _paletteType.asStateFlow()
    private val _contrast = MutableStateFlow(1.5f)
    val contrast = _contrast.asStateFlow()
    private val _visualizationMode = MutableStateFlow(0)
    val visualizationMode = _visualizationMode.asStateFlow()
    private val _overlayType = MutableStateFlow(0)
    val overlayType = _overlayType.asStateFlow()
    private val _overlayOpacity = MutableStateFlow(0.4f)
    val overlayOpacity = _overlayOpacity.asStateFlow()
    private val _gridSpacing = MutableStateFlow(0f)
    val gridSpacing = _gridSpacing.asStateFlow()
    private val _zScale = MutableStateFlow(1f)
    val zScale = _zScale.asStateFlow()
    private val _featureScaleMeters = MutableStateFlow(6f)
    val featureScaleMeters = _featureScaleMeters.asStateFlow()
    private val _analysisSensitivity = MutableStateFlow(1.2f)
    val analysisSensitivity = _analysisSensitivity.asStateFlow()
    private val _contourIntervalMeters = MutableStateFlow(0f)
    val contourIntervalMeters = _contourIntervalMeters.asStateFlow()
    private val _activeTerrainSummary = MutableStateFlow("Import a LAZ/LAS tile to begin")
    val activeTerrainSummary = _activeTerrainSummary.asStateFlow()
    private val _canRefineTerrain = MutableStateFlow(false)
    val canRefineTerrain = _canRefineTerrain.asStateFlow()
    private val _isRefiningTerrain = MutableStateFlow(false)
    val isRefiningTerrain = _isRefiningTerrain.asStateFlow()
    private val _terrainRefinementProgress = MutableStateFlow<TerrainRefinementProgress?>(null)
    val terrainRefinementProgress = _terrainRefinementProgress.asStateFlow()
    private val _isDetailedTerrain = MutableStateFlow(false)
    val isDetailedTerrain = _isDetailedTerrain.asStateFlow()
    private val _terrainDetailMessage = MutableStateFlow<String?>(null)
    val terrainDetailMessage = _terrainDetailMessage.asStateFlow()
    private var terrainSource: TerrainImportSource? = null
    private var overviewTerrain: DemGenerator.TerrainLoadResult? = null
    private var currentSourceBounds = NormalizedRasterBounds.Full

    /** Dual-surface / class-filter state for the open LiDAR project. */
    private val _activeGroundMode = MutableStateFlow(GroundSurfaceMode.SOURCE_CLASSIFIED)
    val activeGroundMode: StateFlow<GroundSurfaceMode> = _activeGroundMode.asStateFlow()
    private val _activeClassPreset = MutableStateFlow(PointClassPreset.ALL)
    val activeClassPreset: StateFlow<PointClassPreset> = _activeClassPreset.asStateFlow()
    private val _isReloadingSurface = MutableStateFlow(false)
    val isReloadingSurface: StateFlow<Boolean> = _isReloadingSurface.asStateFlow()
    private val _surfaceReloadMessage = MutableStateFlow<String?>(null)
    val surfaceReloadMessage: StateFlow<String?> = _surfaceReloadMessage.asStateFlow()
    private val _boundaryProximityAlert = MutableStateFlow<BoundaryProximityAlert?>(null)
    val boundaryProximityAlert: StateFlow<BoundaryProximityAlert?> = _boundaryProximityAlert.asStateFlow()
    private val _lastExportMessage = MutableStateFlow<String?>(null)
    val lastExportMessage: StateFlow<String?> = _lastExportMessage.asStateFlow()
    private var surfaceReloadJob: Job? = null

    private val _hillshadeBitmap = MutableStateFlow<Bitmap?>(null)
    val hillshadeBitmap = _hillshadeBitmap.asStateFlow()
    // Starts true because the lightweight placeholder is replaced and rendered during init.
    private val _isRendering = MutableStateFlow(true)
    val isRendering = _isRendering.asStateFlow()
    private val renderMutex = Mutex()
    private var renderJob: Job? = null
    private var siteGenerationJob: Job? = null
    private var renderGeneration = 0L
    /** Identity of the last hillshade params that produced [_hillshadeBitmap]. */
    private var lastHillshadeCacheKey: HillshadeCacheKey? = null
    private var lastPreviewMaxSide: Int = Int.MAX_VALUE

    // Viewport persistence
    private val _viewportZoom = MutableStateFlow(1f)
    val viewportZoom: StateFlow<Float> = _viewportZoom.asStateFlow()
    private val _viewportPanX = MutableStateFlow(0f)
    val viewportPanX: StateFlow<Float> = _viewportPanX.asStateFlow()
    private val _viewportPanY = MutableStateFlow(0f)
    val viewportPanY: StateFlow<Float> = _viewportPanY.asStateFlow()
    // Bumped exactly once, right after loadSettings() finishes reading the persisted viewport,
    // so the terrain canvas can seed itself from the restored zoom/pan a single time rather than
    // fighting with live user interaction on every subsequent update.
    private val _viewportRestoreToken = MutableStateFlow(0)
    val viewportRestoreToken: StateFlow<Int> = _viewportRestoreToken.asStateFlow()

    private var saveSettingsJob: Job? = null

    private val _sweepX = MutableStateFlow(50f)
    val sweepX = _sweepX.asStateFlow()
    private val _sweepY = MutableStateFlow(50f)
    val sweepY = _sweepY.asStateFlow()

    private var allLoggedSignals: List<TargetSignal> = emptyList()
    private val _loggedSignals = MutableStateFlow<List<TargetSignal>>(emptyList())
    val loggedSignals = _loggedSignals.asStateFlow()

    /**
     * Per-type detection-confidence bias derived from every field-verified signal across every
     * dataset the user has ever logged (see [FeatureTypeCalibration]) - deliberately not scoped
     * to the active terrain like [loggedSignals], since the whole point is generalizing what the
     * user has confirmed/rejected beyond just the one site currently open.
     */
    private val _featureTypeCalibration = MutableStateFlow<Map<MetalDetectingTargetType, Float>>(emptyMap())
    val featureTypeCalibration: StateFlow<Map<MetalDetectingTargetType, Float>> = _featureTypeCalibration.asStateFlow()

    private val _analyzedDatasets = MutableStateFlow<List<AnalyzedDatasetEntity>>(emptyList())
    val analyzedDatasets: StateFlow<List<AnalyzedDatasetEntity>> = _analyzedDatasets.asStateFlow()
    private val _surveyLayers = MutableStateFlow<List<SurveyLayer>>(emptyList())
    val surveyLayers: StateFlow<List<SurveyLayer>> = _surveyLayers.asStateFlow()
    private var surveyLayerJob: Job? = null
    private val _breadcrumbTracks = MutableStateFlow<List<BreadcrumbTrack>>(emptyList())
    val breadcrumbTracks: StateFlow<List<BreadcrumbTrack>> = _breadcrumbTracks.asStateFlow()
    private val _plannedRoute = MutableStateFlow<OptimizedFieldRoute?>(null)
    val plannedRoute: StateFlow<OptimizedFieldRoute?> = _plannedRoute.asStateFlow()

    /** AI target the user is walking to; shown as a live HUD on the Terrain tab. */
    private val _navigationTarget = MutableStateFlow<NavigationTarget?>(null)
    val navigationTarget: StateFlow<NavigationTarget?> = _navigationTarget.asStateFlow()

    fun setNavigationTarget(target: NavigationTarget?) {
        _navigationTarget.value = target
    }
    fun setPlannedRoute(route: OptimizedFieldRoute?) {
        _plannedRoute.value = route
    }
    private val _isBreadcrumbRecording = MutableStateFlow(false)
    val isBreadcrumbRecording: StateFlow<Boolean> = _isBreadcrumbRecording.asStateFlow()
    private var breadcrumbTrackJob: Job? = null
    private var recordingBreadcrumbTrack: BreadcrumbTrack? = null
    private val _excavationLogs = MutableStateFlow<List<ExcavationLogEntry>>(emptyList())
    val excavationLogs: StateFlow<List<ExcavationLogEntry>> = _excavationLogs.asStateFlow()
    private var excavationLogJob: Job? = null
    private val _surveyBoundaries = MutableStateFlow<List<SurveyBoundary>>(emptyList())
    val surveyBoundaries: StateFlow<List<SurveyBoundary>> = _surveyBoundaries.asStateFlow()
    private var surveyBoundaryJob: Job? = null
    private val _pendingSyncEntries = MutableStateFlow<List<PendingSyncEntry>>(emptyList())
    val pendingSyncEntries: StateFlow<List<PendingSyncEntry>> = _pendingSyncEntries.asStateFlow()
    private val _pendingSyncCount = MutableStateFlow(0)
    val pendingSyncCount: StateFlow<Int> = _pendingSyncCount.asStateFlow()

    private val _activeGeoMetadata = MutableStateFlow(
        GeoSpatialLibrary.localGrid(name = "No terrain loaded", columns = 2, rows = 2),
    )
    val activeGeoMetadata: StateFlow<GeoSpatialMetadata> = _activeGeoMetadata.asStateFlow()
    /** Ground-quality scorecard for Home / Terrain banners; null when no real grid is loaded. */
    private val _terrainQuality = MutableStateFlow<TerrainQuality?>(null)
    val terrainQuality: StateFlow<TerrainQuality?> = _terrainQuality.asStateFlow()
    private val _currentLat = MutableStateFlow<Double?>(null)
    val currentLat: StateFlow<Double?> = _currentLat.asStateFlow()
    private val _currentLon = MutableStateFlow<Double?>(null)
    val currentLon: StateFlow<Double?> = _currentLon.asStateFlow()

    private val locationTracker = LocationTracker(application)
    private val compassHeadingTracker = CompassHeadingTracker(application)
    private val _gpsEnabled = MutableStateFlow(false)
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()
    private val _hasLocationPermission = MutableStateFlow(locationTracker.hasLocationPermission())
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()
    private val _deviceGridPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val deviceGridPosition: StateFlow<Pair<Float, Float>?> = _deviceGridPosition.asStateFlow()
    private val _deviceLocationAccuracyMeters = MutableStateFlow<Float?>(null)
    val deviceLocationAccuracyMeters: StateFlow<Float?> = _deviceLocationAccuracyMeters.asStateFlow()
    private val _deviceLatitude = MutableStateFlow<Double?>(null)
    val deviceLatitude: StateFlow<Double?> = _deviceLatitude.asStateFlow()
    private val _deviceLongitude = MutableStateFlow<Double?>(null)
    val deviceLongitude: StateFlow<Double?> = _deviceLongitude.asStateFlow()
    private val _deviceLocationRecordedAtMillis = MutableStateFlow<Long?>(null)
    private var locationJob: Job? = null
    private val _compassHeadingDegrees = MutableStateFlow<Float?>(null)
    val compassHeadingDegrees: StateFlow<Float?> = _compassHeadingDegrees.asStateFlow()
    private var compassHeadingJob: Job? = null

    // Bumped after successful refine / show-whole so the canvas forces zoom=1 + pan=0
    // against the new high-res (or full) bitmap.
    private val _viewportResetKey = MutableStateFlow(0)
    val viewportResetKey: StateFlow<Int> = _viewportResetKey.asStateFlow()

    // Zoom threshold for auto-rendering
    private val AUTO_RENDER_ZOOM_THRESHOLD = 2.5f
    private val MAX_MARKER_GPS_AGE_MILLIS = 60_000L

    init {
        observeSurveyLayers(_activeTerrainKey.value)
        observeOfflineBasemapRegions(_activeTerrainKey.value)
        observeBreadcrumbTracks(_activeTerrainKey.value)
        observeExcavationLogs(_activeTerrainKey.value)
        observeSurveyBoundaries(_activeTerrainKey.value)
        // loadSettings must finish before the first scheduleRender — scheduleRender saves the
        // *current* StateFlow values back to disk, and if that runs while loadSettings' reads are
        // still in flight, it stomps the just-persisted settings with hardcoded defaults on every
        // single app start. Awaiting it here (rather than firing both as separate launches) is
        // what actually fixes that.
        viewModelScope.launch {
            loadSettings()
            updateCoordinates()
            scheduleRender(immediate = true)
            if (restoreImportedTerrainOnStart) restoreLastCachedTerrain()
        }
        viewModelScope.launch {
            signalDao.observeAll().collect { stored ->
                allLoggedSignals = stored.map { it.toDomain() }
                _featureTypeCalibration.value = FeatureTypeCalibration.derive(allLoggedSignals)
                refreshVisibleSignals()
            }
        }
        viewModelScope.launch {
            analyzedDatasetDao.observeAll().collect { stored ->
                _analyzedDatasets.value = stored
            }
        }
        viewModelScope.launch {
            pendingSyncDao.observeAll().collect { stored ->
                val entries = stored.map { it.toDomain() }
                _pendingSyncEntries.value = entries
                _pendingSyncCount.value = entries.size
            }
        }
    }

    /** Persists a snapshot of this dataset's targets so it can later be cross-compared with another. */
    fun saveDatasetSnapshot(entity: AnalyzedDatasetEntity) {
        viewModelScope.launch { analyzedDatasetDao.upsert(entity) }
    }

    /**
     * The durable record of a dataset's targets.
     *
     * The derived-layer cache lives in the cache directory, which Android is free to purge under
     * storage pressure. This snapshot is in the database and survives that, so it can stand in for
     * the ranked targets when the cache is gone.
     */
    suspend fun savedDatasetSnapshot(datasetKey: String): AnalyzedDatasetEntity? =
        analyzedDatasetDao.getByKey(datasetKey)

    /**
     * Loads the AI tab's cached derived terrain layers for the current grid, when a local
     * analysis has already been run for this exact terrain signature. Used by the homesite
     * probability overlay on the Terrain tab; a cache miss returns null instead of starting
     * the expensive analysis path from a surface that only wants a cheap overlay.
     */
    suspend fun cachedDerivedLayers(): TerrainDerivedLayers? {
        val grid = _elevationGrid.value
        if (grid.width <= 2 || grid.height <= 2) return null
        return terrainDerivedLayerCache.get(TerrainIntelligenceEngine.terrainSignature(grid)).layers
    }

    /**
     * Forgets one dataset's saved targets.
     *
     * Snapshots are restored onto the map when the derived-layer cache is gone, so a stale one
     * keeps resurfacing candidates the user has moved on from. Re-analysing the same dataset
     * overwrites its snapshot, but nothing else could remove one.
     */
    fun deleteDatasetSnapshot(datasetKey: String) {
        viewModelScope.launch { analyzedDatasetDao.deleteByKey(datasetKey) }
    }

    /** Called by the UI after a runtime permission dialog resolves. */
    fun onLocationPermissionResult(granted: Boolean) {
        _hasLocationPermission.value = granted || locationTracker.hasLocationPermission()
        if ((_gpsEnabled.value || _isBreadcrumbRecording.value) && _hasLocationPermission.value) {
            startLocationUpdates()
        }
    }

    fun toggleGpsTracking(enabled: Boolean) {
        _gpsEnabled.value = enabled
        viewModelScope.launch { settingsRepo.saveBoolean(SettingsRepository.Keys.GPS_ENABLED, enabled) }
        if (enabled && _hasLocationPermission.value) {
            startLocationUpdates()
        } else if (!enabled && !_isBreadcrumbRecording.value) {
            stopLocationUpdates()
        }
    }

    /** Starts the compass only while the saved-target field-navigation card is open. */
    fun setCompassNavigationActive(active: Boolean) {
        if (active) {
            if (compassHeadingJob?.isActive == true) return
            compassHeadingJob = viewModelScope.launch {
                compassHeadingTracker.headings()
                    .catch { _compassHeadingDegrees.value = null }
                    .collect { heading -> _compassHeadingDegrees.value = heading }
            }
        } else {
            compassHeadingJob?.cancel()
            compassHeadingJob = null
            _compassHeadingDegrees.value = null
        }
    }

    /** Starts a persisted field trail for the currently open terrain project. */
    fun startBreadcrumbRecording() {
        if (_isBreadcrumbRecording.value) return
        val now = System.currentTimeMillis()
        val existing = _breadcrumbTracks.value.firstOrNull { it.isRecording }
        val track = existing?.copy(isRecording = true, updatedAtMillis = now) ?: BreadcrumbTrack(
            id = UUID.randomUUID().toString(),
            terrainKey = _activeTerrainKey.value,
            displayName = "GPS trail",
            points = emptyList(),
            isRecording = true,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        recordingBreadcrumbTrack = track
        _isBreadcrumbRecording.value = true
        viewModelScope.launch {
            breadcrumbTrackDao.upsert(track.toEntity())
            enqueuePendingSync(
                entityType = SyncEntityType.BREADCRUMB_TRACK,
                entityId = track.id,
                operation = SyncOperation.UPSERT,
                payload = "points=${track.points.size};terrain=${track.terrainKey}",
            )
        }
        if (_hasLocationPermission.value) startLocationUpdates()
    }

    /** Pauses the active trail without discarding its previous GPS fixes. */
    fun pauseBreadcrumbRecording() {
        val track = recordingBreadcrumbTrack ?: _breadcrumbTracks.value.firstOrNull { it.isRecording }
            ?: return
        val paused = track.copy(isRecording = false, updatedAtMillis = System.currentTimeMillis())
        recordingBreadcrumbTrack = null
        _isBreadcrumbRecording.value = false
        viewModelScope.launch {
            breadcrumbTrackDao.upsert(paused.toEntity())
            enqueuePendingSync(
                entityType = SyncEntityType.BREADCRUMB_TRACK,
                entityId = paused.id,
                operation = SyncOperation.UPSERT,
                payload = "points=${paused.points.size};terrain=${paused.terrainKey};paused=1",
            )
        }
        if (!_gpsEnabled.value) stopLocationUpdates()
    }

    fun deleteBreadcrumbTrack(track: BreadcrumbTrack) {
        if (track.id == recordingBreadcrumbTrack?.id) pauseBreadcrumbRecording()
        viewModelScope.launch {
            breadcrumbTrackDao.deleteById(track.id)
            enqueuePendingSync(
                entityType = SyncEntityType.BREADCRUMB_TRACK,
                entityId = track.id,
                operation = SyncOperation.DELETE,
                payload = "",
            )
        }
    }

    fun clearBreadcrumbTracks() {
        if (_isBreadcrumbRecording.value) pauseBreadcrumbRecording()
        val terrainKey = _activeTerrainKey.value
        val tracks = _breadcrumbTracks.value
        viewModelScope.launch {
            breadcrumbTrackDao.deleteByTerrainKey(terrainKey)
            tracks.forEach { track ->
                enqueuePendingSync(
                    entityType = SyncEntityType.BREADCRUMB_TRACK,
                    entityId = track.id,
                    operation = SyncOperation.DELETE,
                    payload = "",
                )
            }
        }
    }

    /** Persists a dig/check log tied to a target and the active terrain project. */
    fun saveExcavationLog(entry: ExcavationLogEntry) {
        val scoped = entry.copy(terrainKey = entry.terrainKey ?: _activeTerrainKey.value)
        viewModelScope.launch {
            excavationLogDao.upsert(scoped.toEntity())
            enqueuePendingSync(
                entityType = SyncEntityType.EXCAVATION_LOG,
                entityId = scoped.id,
                operation = SyncOperation.UPSERT,
                payload = "target=${scoped.targetId};depth=${scoped.depthCentimeters ?: ""};finds=${scoped.findsCount}",
            )
        }
    }

    fun deleteExcavationLog(entry: ExcavationLogEntry) {
        viewModelScope.launch {
            excavationLogDao.deleteById(entry.id)
            enqueuePendingSync(
                entityType = SyncEntityType.EXCAVATION_LOG,
                entityId = entry.id,
                operation = SyncOperation.DELETE,
                payload = "",
            )
        }
    }

    /**
     * Starts a new open dig log for [targetId] on the active terrain. Completing is a separate
     * save so partial visit notes survive process restart.
     */
    fun startExcavationLog(targetId: Long): ExcavationLogEntry {
        val now = System.currentTimeMillis()
        val entry = ExcavationLogEntry(
            id = UUID.randomUUID().toString(),
            targetId = targetId,
            terrainKey = _activeTerrainKey.value,
            startedAtMillis = now,
            completedAtMillis = null,
            depthCentimeters = null,
            soilNotes = "",
            findsDescription = "",
            findsCount = 0,
            photoUris = emptyList(),
            voiceNoteUris = emptyList(),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveExcavationLog(entry)
        return entry
    }

    /** Saves a survey boundary polygon for the active terrain project. */
    fun saveSurveyBoundary(boundary: SurveyBoundary) {
        val scoped = boundary.copy(terrainKey = _activeTerrainKey.value)
        viewModelScope.launch {
            surveyBoundaryDao.upsert(scoped.toEntity())
            enqueuePendingSync(
                entityType = SyncEntityType.SURVEY_BOUNDARY,
                entityId = scoped.id,
                operation = SyncOperation.UPSERT,
                payload = "name=${scoped.displayName};vertices=${scoped.vertices.size}",
            )
        }
    }

    fun deleteSurveyBoundary(boundary: SurveyBoundary) {
        viewModelScope.launch {
            surveyBoundaryDao.deleteById(boundary.id)
            enqueuePendingSync(
                entityType = SyncEntityType.SURVEY_BOUNDARY,
                entityId = boundary.id,
                operation = SyncOperation.DELETE,
                payload = "",
            )
        }
    }

    /**
     * Builds a survey boundary from a recorded GPS trail (needs ≥3 points). Used so the
     * walked perimeter becomes the project search area without a separate drawing mode.
     */
    fun createSurveyBoundaryFromTrail(track: BreadcrumbTrack, displayName: String = "Search boundary"): SurveyBoundary? {
        if (track.points.size < 3) return null
        val now = System.currentTimeMillis()
        val boundary = SurveyBoundary(
            id = UUID.randomUUID().toString(),
            terrainKey = _activeTerrainKey.value,
            displayName = displayName.ifBlank { "Search boundary" },
            vertices = track.points.map { BoundaryVertex(it.latitude, it.longitude) },
            createdAtMillis = now,
        )
        saveSurveyBoundary(boundary)
        return boundary
    }

    /**
     * Builds a simple square boundary around the current device GPS fix when no trail is available.
     * [halfSideMeters] is half the square's side length (default 50 m → 100 m box).
     */
    fun createSurveyBoundaryAroundGps(
        displayName: String = "GPS search area",
        halfSideMeters: Double = 50.0,
    ): SurveyBoundary? {
        val lat = _deviceLatitude.value ?: return null
        val lon = _deviceLongitude.value ?: return null
        val latOffset = halfSideMeters / 111_000.0
        val lonOffset = halfSideMeters / (111_000.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.2))
        val now = System.currentTimeMillis()
        val boundary = SurveyBoundary(
            id = UUID.randomUUID().toString(),
            terrainKey = _activeTerrainKey.value,
            displayName = displayName.ifBlank { "GPS search area" },
            vertices = listOf(
                BoundaryVertex(lat - latOffset, lon - lonOffset),
                BoundaryVertex(lat - latOffset, lon + lonOffset),
                BoundaryVertex(lat + latOffset, lon + lonOffset),
                BoundaryVertex(lat + latOffset, lon - lonOffset),
            ),
            createdAtMillis = now,
        )
        saveSurveyBoundary(boundary)
        return boundary
    }

    /**
     * Offline sync queue: coalesce local field mutations for later replay. No cloud endpoint yet
     * (Phase 9); entries stay durable and never silently drop. [markPendingSyncSent] clears a
     * successfully delivered entry; [markPendingSyncFailed] keeps it with diagnostics.
     */
    private suspend fun enqueuePendingSync(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
    ) {
        val now = System.currentTimeMillis()
        val existing = pendingSyncDao.all().map { it.toDomain() }
        val queue = FieldSyncQueue(existing).enqueue(
            entityType = entityType,
            entityId = entityId,
            operation = operation,
            payload = payload,
            queuedAtMillis = now,
        )
        // Persist the coalesced entity state: drop prior row for this entity, write the new one.
        pendingSyncDao.deleteByEntity(entityType.name, entityId)
        val latest = queue.pendingFor(entityType, entityId) ?: return
        pendingSyncDao.upsert(latest.toEntity())
    }

    fun markPendingSyncSent(entryId: Long) {
        viewModelScope.launch { pendingSyncDao.deleteById(entryId) }
    }

    fun markPendingSyncFailed(entryId: Long, error: String) {
        viewModelScope.launch {
            val existing = pendingSyncDao.all().map { it.toDomain() }
            val updated = FieldSyncQueue(existing).markFailed(entryId, error, System.currentTimeMillis())
            updated.entries.firstOrNull { it.id == entryId }?.let { pendingSyncDao.upsert(it.toEntity()) }
        }
    }

    /** Clears the entire offline sync queue after the operator confirms delivery is not needed. */
    fun clearPendingSyncQueue() {
        viewModelScope.launch {
            _pendingSyncEntries.value.forEach { pendingSyncDao.deleteById(it.id) }
        }
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            locationTracker.locationUpdates()
                .catch { /* provider unavailable or a platform SecurityException — stop tracking */ }
                .collect { fix ->
                    _deviceLocationAccuracyMeters.value = fix.accuracyMeters
                    _deviceLatitude.value = fix.latitude
                    _deviceLongitude.value = fix.longitude
                    _deviceLocationRecordedAtMillis.value = fix.recordedAtMillis
                    _deviceGridPosition.value =
                        GeoSpatialLibrary.geographicToGrid(fix.latitude, fix.longitude, _activeGeoMetadata.value)
                    appendBreadcrumbFix(fix.latitude, fix.longitude, fix.accuracyMeters)
                    refreshBoundaryProximity(fix.latitude, fix.longitude)
                }
        }
    }

    private fun appendBreadcrumbFix(latitude: Double, longitude: Double, accuracyMeters: Float) {
        val activeTrack = recordingBreadcrumbTrack ?: return
        if (activeTrack.terrainKey != _activeTerrainKey.value || !accuracyMeters.isFinite() || accuracyMeters > 100f) return
        val point = BreadcrumbPoint(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            recordedAtMillis = System.currentTimeMillis(),
        )
        val updated = activeTrack.withPoint(point)
        if (updated === activeTrack) return
        recordingBreadcrumbTrack = updated
        viewModelScope.launch {
            breadcrumbTrackDao.upsert(updated.toEntity())
            // Throttle sync enqueue: only on every 10th point so high-rate GPS does not flood the queue.
            if (updated.points.size % 10 == 0) {
                enqueuePendingSync(
                    entityType = SyncEntityType.BREADCRUMB_TRACK,
                    entityId = updated.id,
                    operation = SyncOperation.UPSERT,
                    payload = "points=${updated.points.size};terrain=${updated.terrainKey}",
                )
            }
        }
    }

    private fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
        _deviceGridPosition.value = null
        _deviceLocationAccuracyMeters.value = null
        _deviceLatitude.value = null
        _deviceLongitude.value = null
        _deviceLocationRecordedAtMillis.value = null
        _boundaryProximityAlert.value = null
    }

    private fun refreshBoundaryProximity(lat: Double?, lon: Double?) {
        if (lat == null || lon == null) {
            _boundaryProximityAlert.value = null
            return
        }
        _boundaryProximityAlert.value = BoundaryProximity.evaluate(lat, lon, _surveyBoundaries.value)
    }

    private val _heatmapEnabled = MutableStateFlow(false)
    val heatmapEnabled: StateFlow<Boolean> = _heatmapEnabled.asStateFlow()

    fun setHeatmapEnabled(enabled: Boolean) {
        _heatmapEnabled.value = enabled
        viewModelScope.launch { settingsRepo.saveBoolean(SettingsRepository.Keys.HEATMAP_ENABLED, enabled) }
    }

    private val basemapTileRepository = BasemapTileRepository(application)
    private val _basemapEnabled = MutableStateFlow(false)
    val basemapEnabled: StateFlow<Boolean> = _basemapEnabled.asStateFlow()
    private val _basemapOpacity = MutableStateFlow(0.6f)
    val basemapOpacity: StateFlow<Float> = _basemapOpacity.asStateFlow()
    private val _basemapBitmap = MutableStateFlow<Bitmap?>(null)
    val basemapBitmap: StateFlow<Bitmap?> = _basemapBitmap.asStateFlow()
    private val _basemapStatus = MutableStateFlow<String?>(null)
    val basemapStatus: StateFlow<String?> = _basemapStatus.asStateFlow()
    private var basemapJob: Job? = null
    private val _offlineBasemapRegions = MutableStateFlow<List<OfflineBasemapRegion>>(emptyList())
    val offlineBasemapRegions: StateFlow<List<OfflineBasemapRegion>> = _offlineBasemapRegions.asStateFlow()
    private val _offlineBasemapPlan = MutableStateFlow<BasemapPlan?>(null)
    val offlineBasemapPlan: StateFlow<BasemapPlan?> = _offlineBasemapPlan.asStateFlow()
    private val _offlineBasemapProgress = MutableStateFlow<BasemapDownloadProgress?>(null)
    val offlineBasemapProgress: StateFlow<BasemapDownloadProgress?> = _offlineBasemapProgress.asStateFlow()
    private val _offlineBasemapMessage = MutableStateFlow<String?>(null)
    val offlineBasemapMessage: StateFlow<String?> = _offlineBasemapMessage.asStateFlow()
    private val _offlineBasemapDownloading = MutableStateFlow(false)
    val offlineBasemapDownloading: StateFlow<Boolean> = _offlineBasemapDownloading.asStateFlow()
    private var offlineBasemapRegionJob: Job? = null
    private var offlineBasemapDownloadJob: Job? = null
    private var activeOfflineDownloadId: String? = null

    fun setBasemapEnabled(enabled: Boolean) {
        _basemapEnabled.value = enabled
        viewModelScope.launch { settingsRepo.saveBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, enabled) }
        if (enabled) {
            refreshBasemapTiles()
        } else {
            basemapJob?.cancel()
            _basemapBitmap.value = null
            _basemapStatus.value = null
        }
    }

    fun setBasemapOpacity(value: Float) {
        _basemapOpacity.value = value.coerceIn(0.1f, 1f)
        viewModelScope.launch { settingsRepo.saveFloat(SettingsRepository.Keys.BASEMAP_OPACITY, _basemapOpacity.value) }
    }

    private fun refreshBasemapTiles() {
        basemapJob?.cancel()
        basemapJob = null
        val bounds = _activeGeoMetadata.value.bounds
        if (bounds == null) {
            _basemapBitmap.value = null
            _basemapStatus.value = "This terrain has no geographic coordinates — basemap unavailable."
            return
        }
        basemapJob = viewModelScope.launch {
            val offline = _offlineBasemapRegions.value.firstOrNull {
                it.status == OfflineBasemapStatus.READY
            }
            _basemapStatus.value = if (offline != null) {
                "Opening saved offline basemap…"
            } else {
                "Loading basemap tiles…"
            }
            val result = runCatching {
                if (offline != null) {
                    basemapTileRepository.loadBasemap(
                        bounds = offline.bounds,
                        fixedZoom = offline.zoom,
                        maxTiles = offline.tileCount,
                        allowNetwork = false,
                    )
                } else {
                    basemapTileRepository.loadBasemap(bounds)
                }
            }.getOrNull()
            _basemapBitmap.value = result?.bitmap
            _basemapStatus.value = when {
                result?.bitmap != null && offline != null &&
                    result.loadedTiles == result.expectedTiles -> "Saved offline basemap ready."
                result?.bitmap != null -> null
                result?.blockedByServer == true ->
                    "The USGS Topo service rejected these requests — basemap unavailable here."
                else -> "Couldn't load basemap tiles — showing terrain view only."
            }
        }
    }

    private fun scheduleRender(immediate: Boolean = false, currentZoom: Float? = null) {
        // Skip render if zoom is below threshold and not immediate
        if (currentZoom != null && currentZoom < AUTO_RENDER_ZOOM_THRESHOLD && !immediate) {
            return
        }

        val generation = ++renderGeneration
        saveSettings()
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            val mode = _visualizationMode.value
            val debounceMs = hillshadeDebounceMs(mode, immediate)
            if (debounceMs > 0L) delay(debounceMs)

            val grid = _elevationGrid.value
            val zoom = _viewportZoom.value
            val previewMaxSide = previewMaxSideForZoom(zoom, maxOf(grid.width, grid.height))
            val cacheKey = HillshadeCacheKey(
                gridIdentity = System.identityHashCode(grid),
                width = grid.width,
                height = grid.height,
                sunAzimuth = _sunAzimuth.value,
                sunAltitude = _sunAltitude.value,
                vegetationFilter = _vegetationFilter.value,
                palette = _paletteType.value,
                contrast = _contrast.value,
                visualizationMode = mode,
                overlayType = _overlayType.value,
                overlayOpacity = _overlayOpacity.value,
                zScale = _zScale.value,
                featureScaleMeters = _featureScaleMeters.value,
                analysisSensitivity = _analysisSensitivity.value,
                contourIntervalMeters = _contourIntervalMeters.value,
                previewMaxSide = previewMaxSide,
            )
            // Skip work when sliders settle on the same values and the bitmap is still valid.
            // Keep the previous bitmap on screen (do not clear it) so upgrades/refines feel instant.
            if (cacheKey == lastHillshadeCacheKey && _hillshadeBitmap.value != null) {
                if (generation == renderGeneration) _isRendering.value = false
                return@launch
            }

            _isRendering.value = true
            try {
                renderMutex.withLock {
                    val liveGrid = _elevationGrid.value
                    val liveKey = cacheKey.copy(
                        gridIdentity = System.identityHashCode(liveGrid),
                        width = liveGrid.width,
                        height = liveGrid.height,
                        previewMaxSide = previewMaxSideForZoom(
                            _viewportZoom.value,
                            maxOf(liveGrid.width, liveGrid.height),
                        ),
                    )
                    val bitmap = withContext(Dispatchers.Default) {
                        val renderGrid = gridForHillshadePreview(liveGrid, liveKey.previewMaxSide)
                        renderGrid.renderHillshade(
                            sunAzimuth = liveKey.sunAzimuth,
                            sunAltitude = liveKey.sunAltitude,
                            vegetationFilter = liveKey.vegetationFilter,
                            palette = liveKey.palette,
                            contrast = liveKey.contrast,
                            visualizationMode = liveKey.visualizationMode,
                            overlayType = liveKey.overlayType,
                            overlayOpacity = liveKey.overlayOpacity,
                            zScale = liveKey.zScale,
                            featureScaleMeters = liveKey.featureScaleMeters,
                            analysisSensitivity = liveKey.analysisSensitivity,
                            contourIntervalMeters = liveKey.contourIntervalMeters,
                            // The render loop never suspends, so cancelling this coroutine cannot
                            // stop it. Dragging a slider would otherwise run every superseded
                            // frame to completion while holding renderMutex, making the frame the
                            // user is waiting on queue behind work whose result is thrown away.
                            shouldContinue = { generation == renderGeneration },
                        )
                    }
                    if (generation == renderGeneration) {
                        // Swap only when the new frame is ready — prior hillshade stays visible.
                        _hillshadeBitmap.value = bitmap
                        lastHillshadeCacheKey = liveKey
                        lastPreviewMaxSide = liveKey.previewMaxSide
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (generation == renderGeneration) _isRendering.value = false
            }
        }
    }

    fun setCustomTerrain(
        result: DemGenerator.TerrainLoadResult,
        source: TerrainImportSource? = null,
    ) {
        terrainSource = source
        setActiveTerrainKey(
            source?.let { "lidar:${it.uri}" }
                ?: "custom:${com.example.analysis.TerrainIntelligenceEngine.terrainSignature(result.grid)}",
        )
        overviewTerrain = result.takeIf { source != null }
        currentSourceBounds = NormalizedRasterBounds.Full
        _canRefineTerrain.value = source != null
        _isDetailedTerrain.value = false
        _terrainDetailMessage.value = null
        if (source != null) {
            _activeGroundMode.value = source.options.groundMode
            _activeClassPreset.value = presetFromAllowedClasses(source.options.allowedClasses)
        }
        source?.uri?.let { uriString ->
            val uri = Uri.parse(uriString)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let { path -> LazSpatialIndex.ensureBuiltAsync(File(path)) }
            }
        }
        // Durable session pointer so cold start reopens this LAZ even if the decode cache was purged.
        source?.let(terrainSessionStore::save)
        applyCustomTerrain(result, resetViewport = true)
    }

    private fun applyCustomTerrain(result: DemGenerator.TerrainLoadResult, resetViewport: Boolean = false) {
        siteGenerationJob?.cancel()
        val grid = result.grid
        customGrid = result.grid
        _elevationGrid.value = result.grid
        _currentSiteIndex.value = 3
        // Invalidate hillshade memo so the new grid always repaints; the previous bitmap stays
        // visible until the new frame is ready (no blanking).
        lastHillshadeCacheKey = null
        _activeGeoMetadata.value = result.geoMetadata ?: GeoSpatialLibrary.localGrid(
            name = "Custom imported layer",
            columns = grid.width,
            rows = grid.height,
            resolutionMeters = grid.cellSizeMeters.toDouble(),
        )
        _activeTerrainSummary.value = result.summary
        publishTerrainQuality(grid, _activeGeoMetadata.value, result.summary)
        updateCoordinates()
        scheduleRender(immediate = true)
        if (_basemapEnabled.value) refreshBasemapTiles()
        if (resetViewport) {
            _viewportResetKey.value = _viewportResetKey.value + 1
        }
    }

    private fun publishTerrainQuality(
        grid: ElevationGrid,
        metadata: GeoSpatialMetadata,
        summary: String,
    ) {
        _terrainQuality.value = if (grid.width > 2 && grid.height > 2) {
            TerrainQuality.from(grid, metadata, summary)
        } else {
            null
        }
    }

    fun recommendedAiRefineResolution(): Int {
        val activityManager = getApplication<Application>()
            .getSystemService(ActivityManager::class.java)
        val memoryClass = activityManager?.memoryClass ?: 256
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)
        val totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).toInt()
        val processors = Runtime.getRuntime().availableProcessors()
        return chooseAiRefineResolution(
            memoryClassMb = memoryClass,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
            availableProcessors = processors,
            totalRamMb = totalRamMb,
        )
    }

    fun refineTerrain(viewport: NormalizedRasterBounds, rasterResolution: Int = 1_024) {
        val source = terrainSource ?: return
        if (_isRefiningTerrain.value) return
        val requestedViewport = viewport.sanitized()
        if (currentSourceBounds == NormalizedRasterBounds.Full && isEffectivelyWholeTerrain(requestedViewport)) {
            // The overview is already a raster of the entire original point cloud. Reopening a
            // compressed LAZ at 1x scans every return but cannot reveal a smaller source area, so
            // it only makes the user wait (and can even request a lower adaptive resolution).
            _terrainRefinementProgress.value = TerrainRefinementProgress(
                fraction = 1f,
                message = "Full source hillshade is already loaded",
            )
            _terrainDetailMessage.value =
                "Full source hillshade is already loaded. Zoom into an area, then Refine for extra local detail."
            return
        }
        // Cropped refinement always re-reads the requested viewport from the original point cloud.
        val absoluteBounds = requestedViewport.inside(currentSourceBounds)
        val options = source.options.copy(
            rasterResolution = rasterResolution,
            focusBounds = absoluteBounds,
        ).sanitized()
        val sourceUri = Uri.parse(source.uri)
        val sourceFile = sourceUri.takeIf { it.scheme.equals("file", ignoreCase = true) }
            ?.path
            ?.let(::File)
            ?.takeIf(File::isFile)
        _isRefiningTerrain.value = true
        _terrainRefinementProgress.value = TerrainRefinementProgress(
            fraction = 0.03f,
            message = "Checking ${options.rasterResolution} px detail cache…",
        )
        _terrainDetailMessage.value =
            "Opening ${options.rasterResolution} px source detail for this viewport…"
        viewModelScope.launch(Dispatchers.IO) {
            var decodedNow = false
            var loadedFromCache = false
            val result = runCatching {
                sourceFile?.let { file ->
                    refinementMemoryCache.get(file, options)?.also { loadedFromCache = true }
                        ?: refinementDiskCache.get(file, options)?.also {
                            refinementMemoryCache.put(file, options, it)
                            loadedFromCache = true
                        }
                        ?: run {
                            // Zooming to a small viewport asks the decoder for a tiny fraction of
                            // a file that can hold hundreds of millions of returns. A spatial
                            // index lets it seek past whole compressed chunks outside that
                            // viewport instead of decompressing every point just to discard it.
                            // This one-time pass only reads X/Y, so it costs far less than a real
                            // decode, and every later zoom on this file reuses the sidecar it
                            // writes rather than paying it again.
                            if (!LazSpatialIndex.exists(file)) {
                                _terrainRefinementProgress.value = TerrainRefinementProgress(
                                    fraction = 0.04f,
                                    message = "Indexing source for fast zoomed reads (one-time)…",
                                )
                                LazSpatialIndex.build(
                                    file,
                                    onProgress = { indexed, totalPoints ->
                                        val indexFraction = if (totalPoints > 0L) {
                                            indexed.toFloat() / totalPoints.toFloat()
                                        } else {
                                            0f
                                        }
                                        _terrainRefinementProgress.value = TerrainRefinementProgress(
                                            fraction = 0.04f + indexFraction.coerceIn(0f, 1f) * 0.04f,
                                            message = "Indexing source for fast zoomed reads · " +
                                                "${(indexFraction * 100f).toInt().coerceIn(0, 100)}%",
                                        )
                                    },
                                )
                            }
                            LazTerrainReader.read(file, options) { decoded, total ->
                                val decodedFraction = if (total > 0L) decoded.toFloat() / total.toFloat() else 0f
                                _terrainRefinementProgress.value = TerrainRefinementProgress(
                                    fraction = 0.08f + decodedFraction.coerceIn(0f, 1f) * 0.82f,
                                    message = "Decoding ${options.rasterResolution} px source detail · " +
                                        "${(decodedFraction * 100f).toInt().coerceIn(0, 100)}%",
                                )
                            }?.let { laz ->
                                DemGenerator.TerrainLoadResult(
                                    grid = laz.grid,
                                    summary = laz.note,
                                    isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                                )
                            }?.also {
                                refinementMemoryCache.put(file, options, it)
                                decodedNow = true
                            }
                        }
                } ?: getApplication<Application>().contentResolver.openInputStream(sourceUri)?.buffered()?.use { input ->
                    _terrainRefinementProgress.value = TerrainRefinementProgress(
                            fraction = 0.08f,
                            message = "Opening the original point cloud at ${options.rasterResolution} px…",
                    )
                    DemGenerator.parseFromStreamDetailed(
                        source.displayName,
                        input,
                        options,
                    ) { decoded, total ->
                        val decodedFraction = if (total > 0L) {
                            decoded.toFloat() / total.toFloat()
                        } else {
                            0f
                        }
                        _terrainRefinementProgress.value = TerrainRefinementProgress(
                            fraction = 0.08f + decodedFraction.coerceIn(0f, 1f) * 0.82f,
                            message = if (total > 0L) {
                                "Decoding ${options.rasterResolution} px source detail · " +
                                    "${(decodedFraction * 100f).toInt().coerceIn(0, 100)}%"
                            } else {
                                "Decoding source detail…"
                            },
                        )
                    }?.also {
                        sourceFile?.let { file -> refinementMemoryCache.put(file, options, it) }
                        decodedNow = true
                    }
                }
            }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                if (result == null) {
                    _terrainRefinementProgress.value = TerrainRefinementProgress(
                        fraction = 1f,
                        message = "Refinement failed",
                    )
                    _isRefiningTerrain.value = false
                    _terrainDetailMessage.value = "Could not load detail from the original LAZ/LAS document."
                } else {
                    _terrainRefinementProgress.value = TerrainRefinementProgress(
                        fraction = if (loadedFromCache) 0.96f else 0.92f,
                        message = if (loadedFromCache) "Opening cached detail…" else "Rendering refined terrain…",
                    )
                    currentSourceBounds = absoluteBounds
                    _isDetailedTerrain.value = true
                    _terrainDetailMessage.value = if (loadedFromCache) {
                        "${options.rasterResolution} px detailed viewport opened from cache."
                    } else {
                        "${options.rasterResolution} px detailed viewport loaded from the original point cloud."
                    }
                    applyCustomTerrain(result, resetViewport = false)
                    _terrainRefinementProgress.value = TerrainRefinementProgress(
                        fraction = 1f,
                        message = "Refinement complete",
                    )
                    _isRefiningTerrain.value = false
                }
            }
            if (decodedNow && result != null && sourceFile != null) {
                // The image is already visible. Persist the result afterward so disk I/O does not
                // extend the user-visible Refine wait.
                runCatching { refinementDiskCache.put(sourceFile, options, result) }
            }
        }
    }

    fun showWholeTerrain() {
        val overview = overviewTerrain ?: return
        currentSourceBounds = NormalizedRasterBounds.Full
        _isDetailedTerrain.value = false
        _terrainDetailMessage.value = "Showing the complete point-cloud footprint."
        applyCustomTerrain(overview, resetViewport = true)
    }

    /**
     * Re-rasterize the open LiDAR with a different ground-surface mode (classified ground,
     * auto-lowest, or first-return surface model). Full footprint only — dual surface is not
     * a zoomed refine.
     */
    fun setGroundSurfaceMode(mode: GroundSurfaceMode) {
        _activeGroundMode.value = mode
        reloadTerrainWithOptions { options -> options.copy(groundMode = mode) }
    }

    /**
     * Re-rasterize the open LiDAR with an ASPRS class filter preset (or all returns).
     */
    fun setPointClassPreset(preset: PointClassPreset) {
        _activeClassPreset.value = preset
        reloadTerrainWithOptions { options -> options.copy(allowedClasses = preset.classes) }
    }

    /**
     * Clip-refine the active LiDAR into a survey boundary polygon (by id, or first boundary).
     * Uses [BoundaryFocusMapper] so refine re-reads the original point cloud for that AOI.
     */
    fun refineToSurveyBoundary(boundaryId: String? = null) {
        val boundary = when {
            boundaryId != null -> _surveyBoundaries.value.firstOrNull { it.id == boundaryId }
            else -> _surveyBoundaries.value.firstOrNull()
        }
        if (boundary == null) {
            _terrainDetailMessage.value = "No survey boundary available for clip refine."
            return
        }
        val metadata = overviewTerrain?.geoMetadata ?: _activeGeoMetadata.value
        val bounds = BoundaryFocusMapper.toNormalizedBounds(boundary, metadata)
        if (bounds == null) {
            _terrainDetailMessage.value =
                "Could not map the survey boundary onto this terrain (georeferenced LiDAR required)."
            return
        }
        // Boundary focus is in full-footprint normalized space; nest against Full, not a prior refine.
        currentSourceBounds = NormalizedRasterBounds.Full
        refineTerrain(bounds, recommendedAiRefineResolution())
    }

    /**
     * Clip-refine to an arbitrary normalized map rectangle (viewport or user AOI), not only a
     * survey polygon. Full-footprint space; nests against Full.
     */
    fun refineToNormalizedRect(bounds: NormalizedRasterBounds) {
        if (!_canRefineTerrain.value) {
            _terrainDetailMessage.value = "Open a LAZ/LAS source before clip refine."
            return
        }
        currentSourceBounds = NormalizedRasterBounds.Full
        refineTerrain(bounds.sanitized(), recommendedAiRefineResolution())
        _terrainDetailMessage.value = "Refining map-rectangle AOI from source LiDAR…"
    }

    /** Ordered multi-stop navigation playlist (e.g. from AI NAV_TARGET tags). */
    private val _navPlaylistIds = MutableStateFlow<List<Long>>(emptyList())
    val navPlaylistIds: StateFlow<List<Long>> = _navPlaylistIds.asStateFlow()
    private val _navPlaylistIndex = MutableStateFlow(0)
    val navPlaylistIndex: StateFlow<Int> = _navPlaylistIndex.asStateFlow()

    fun setNavPlaylist(ids: List<Long>) {
        val unique = ids.distinct()
        _navPlaylistIds.value = unique
        _navPlaylistIndex.value = 0
        activateNavPlaylistStop(0)
    }

    fun clearNavPlaylist() {
        _navPlaylistIds.value = emptyList()
        _navPlaylistIndex.value = 0
    }

    fun navPlaylistNext() {
        val ids = _navPlaylistIds.value
        if (ids.isEmpty()) return
        val next = (_navPlaylistIndex.value + 1).coerceAtMost(ids.lastIndex)
        _navPlaylistIndex.value = next
        activateNavPlaylistStop(next)
    }

    fun navPlaylistSkip() = navPlaylistNext()

    private fun activateNavPlaylistStop(index: Int) {
        val id = _navPlaylistIds.value.getOrNull(index) ?: return
        val signal = allLoggedSignals.firstOrNull { it.id == id } ?: return
        val lat = signal.latitude ?: signal.gpsLatitude ?: return
        val lon = signal.longitude ?: signal.gpsLongitude ?: return
        setNavigationTarget(
            NavigationTarget(
                label = signal.metalType.label,
                latitude = lat,
                longitude = lon,
            ),
        )
    }

    /**
     * Relative bare-earth surface context under a find. Not buried-object depth or metal identity.
     */
    fun surfaceZForSignal(signal: TargetSignal): SurfaceZSample? {
        val lat = signal.latitude ?: signal.gpsLatitude ?: return null
        val lon = signal.longitude ?: signal.gpsLongitude ?: return null
        return SiteSurfaceSampler.sample(
            grid = _elevationGrid.value,
            metadata = _activeGeoMetadata.value,
            latitude = lat,
            longitude = lon,
        )
    }

    /**
     * Applies confirmed AI metal/outcome/status/notes suggestions to an existing logged find.
     * Only non-null fields are written; notes are appended when both sides are non-blank.
     * Returns false when the signal id is not found.
     */
    fun applyAiFindSuggestions(
        signalId: Long,
        metalType: MetalType?,
        outcome: VerificationOutcome?,
        status: String?,
        notes: String?,
    ): Boolean {
        val existing = allLoggedSignals.firstOrNull { it.id == signalId }
            ?: _loggedSignals.value.firstOrNull { it.id == signalId }
            ?: return false
        val mergedNotes = when {
            notes.isNullOrBlank() -> existing.notes
            existing.notes.isBlank() -> notes.trim()
            else -> existing.notes.trimEnd() + "\n" + notes.trim()
        }
        val updated = existing.copy(
            metalType = metalType ?: existing.metalType,
            outcome = outcome ?: existing.outcome,
            status = status?.takeIf { it.isNotBlank() } ?: existing.status,
            notes = mergedNotes,
        )
        updateLoggedSignal(updated)
        return true
    }

    private fun reloadTerrainWithOptions(transform: (LidarImportOptions) -> LidarImportOptions) {
        val source = terrainSource
        if (source == null) {
            _surfaceReloadMessage.value = "Open a LiDAR project to change surface mode or class filter."
            return
        }
        if (_isReloadingSurface.value || _isRefiningTerrain.value) return

        // Dual surface / class filter always reopen the full footprint (not a refined crop).
        val options = transform(source.options).copy(focusBounds = null).sanitized()
        val sourceUri = Uri.parse(source.uri)
        val sourceFile = sourceUri.takeIf { it.scheme.equals("file", ignoreCase = true) }
            ?.path
            ?.let(::File)
            ?.takeIf(File::isFile)

        surfaceReloadJob?.cancel()
        _isReloadingSurface.value = true
        _surfaceReloadMessage.value =
            "Reloading surface · ${options.groundMode.name} · ${presetFromAllowedClasses(options.allowedClasses).label}…"

        surfaceReloadJob = viewModelScope.launch(Dispatchers.IO) {
            var decodedNow = false
            var loadedFromCache = false
            try {
                val preservedGeo = overviewTerrain?.geoMetadata
                    ?: _activeGeoMetadata.value.takeIf { it.isGeoreferenced }
                val result = runCatching {
                    sourceFile?.let { file ->
                        refinementMemoryCache.get(file, options)?.also { loadedFromCache = true }
                            ?: refinementDiskCache.get(file, options)?.also {
                                refinementMemoryCache.put(file, options, it)
                                loadedFromCache = true
                            }
                            ?: run {
                                if (!LazSpatialIndex.exists(file)) {
                                    LazSpatialIndex.build(file)
                                }
                                LazTerrainReader.read(file, options)?.let { laz ->
                                    DemGenerator.TerrainLoadResult(
                                        grid = laz.grid,
                                        summary = laz.note,
                                        isBareEarth = laz.appliedGroundMode != GroundSurfaceMode.SURFACE_MODEL,
                                    )
                                }?.also {
                                    refinementMemoryCache.put(file, options, it)
                                    decodedNow = true
                                }
                            }
                    } ?: getApplication<Application>().contentResolver.openInputStream(sourceUri)
                        ?.buffered()?.use { input ->
                            DemGenerator.parseFromStreamDetailed(
                                source.displayName,
                                input,
                                options,
                            )?.also {
                                sourceFile?.let { file -> refinementMemoryCache.put(file, options, it) }
                                decodedNow = true
                            }
                        }
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    withContext(Dispatchers.Main.immediate) {
                        _surfaceReloadMessage.value =
                            "Surface reload failed: ${error.localizedMessage ?: "decode error"}"
                    }
                    null
                }

                withContext(Dispatchers.Main.immediate) {
                    if (result == null) {
                        if (_surfaceReloadMessage.value?.startsWith("Surface reload failed") != true) {
                            _surfaceReloadMessage.value =
                                "Could not reload the LiDAR surface with the selected options."
                        }
                        return@withContext
                    }
                    val withGeo = if (result.geoMetadata == null && preservedGeo != null) {
                        result.copy(
                            geoMetadata = preservedGeo.copy(
                                columns = result.grid.width,
                                rows = result.grid.height,
                                resolutionMeters = result.grid.cellSizeMeters.toDouble(),
                            ),
                        )
                    } else {
                        result
                    }
                    val updatedSource = source.copy(options = options)
                    terrainSource = updatedSource
                    overviewTerrain = withGeo
                    currentSourceBounds = NormalizedRasterBounds.Full
                    _isDetailedTerrain.value = false
                    _canRefineTerrain.value = true
                    _activeGroundMode.value = options.groundMode
                    _activeClassPreset.value = presetFromAllowedClasses(options.allowedClasses)
                    terrainSessionStore.save(updatedSource)
                    applyCustomTerrain(withGeo, resetViewport = false)
                    _surfaceReloadMessage.value = if (loadedFromCache) {
                        "Surface loaded from cache · ${options.groundMode.name} · " +
                            presetFromAllowedClasses(options.allowedClasses).label
                    } else {
                        "Surface reloaded · ${options.groundMode.name} · " +
                            presetFromAllowedClasses(options.allowedClasses).label
                    }
                    _terrainDetailMessage.value = _surfaceReloadMessage.value
                }
                if (decodedNow && result != null && sourceFile != null) {
                    runCatching { refinementDiskCache.put(sourceFile, options, result) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                withContext(NonCancellable) {
                    if (_isReloadingSurface.value) {
                        _isReloadingSurface.value = false
                    }
                }
            }
        }
    }

    private fun presetFromAllowedClasses(allowed: Set<Int>?): PointClassPreset {
        if (allowed == null || allowed.isEmpty()) return PointClassPreset.ALL
        return PointClassPreset.entries.firstOrNull { preset ->
            preset.classes != null && preset.classes == allowed
        } ?: PointClassPreset.ALL
    }

    fun setCustomGrid(grid: ElevationGrid) {
        setCustomTerrain(
            DemGenerator.TerrainLoadResult(
                grid = grid,
                summary = "Custom ${grid.width}×${grid.height} elevation grid",
                isBareEarth = true,
            ),
        )
    }

    fun updateSunAzimuth(value: Float) {
        _sunAzimuth.value = value.coerceIn(0f, 360f)
        scheduleRender()
    }
    fun rotateSunAzimuth(deltaDegrees: Float) {
        val value = _sunAzimuth.value + deltaDegrees
        _sunAzimuth.value = ((value % 360f) + 360f) % 360f
        scheduleRender()
    }
    fun updateSunAltitude(value: Float) { _sunAltitude.value = value.coerceIn(5f, 85f); scheduleRender() }
    fun updateVegetationFilter(value: Float) { _vegetationFilter.value = value.coerceIn(0f, 1f); scheduleRender() }
    fun updatePalette(value: Int) { _paletteType.value = value.coerceIn(0, 2); scheduleRender() }
    fun updateContrast(value: Float) { _contrast.value = value.coerceIn(1f, 2.5f); scheduleRender() }
    fun updateVisualizationMode(value: Int) { _visualizationMode.value = value.coerceIn(0, 8); scheduleRender() }
    fun updateOverlayType(value: Int) { _overlayType.value = value.coerceIn(0, 2); scheduleRender() }
    fun updateOverlayOpacity(value: Float) { _overlayOpacity.value = value.coerceIn(0.1f, 0.9f); scheduleRender() }
    fun updateGridSpacing(value: Float) { _gridSpacing.value = value.coerceIn(0f, 10f) }
    fun updateZScale(value: Float) { _zScale.value = value.coerceIn(0.5f, 4f); scheduleRender() }
    fun updateFeatureScale(value: Float) {
        _featureScaleMeters.value = value.coerceIn(1f, 40f)
        scheduleRender()
    }
    fun updateAnalysisSensitivity(value: Float) {
        _analysisSensitivity.value = value.coerceIn(0.4f, 2.5f)
        scheduleRender()
    }
    fun updateContourInterval(value: Float) {
        _contourIntervalMeters.value = value.coerceIn(0f, 5f)
        scheduleRender()
    }

    fun setSweepPosition(x: Float, y: Float) {
        _sweepX.value = x.coerceIn(0f, 100f)
        _sweepY.value = y.coerceIn(0f, 100f)
        updateCoordinates()
    }

    // Update viewport zoom and pan. Persists (debounced). Hillshade only re-runs when the
    // zoom-aware preview LOD side changes — pan never invalidates the bitmap.
    fun updateViewport(zoom: Float, panX: Float, panY: Float) {
        val grid = _elevationGrid.value
        val previousSide = previewMaxSideForZoom(_viewportZoom.value, maxOf(grid.width, grid.height))
        val nextSide = previewMaxSideForZoom(zoom, maxOf(grid.width, grid.height))
        _viewportZoom.value = zoom
        _viewportPanX.value = panX
        _viewportPanY.value = panY
        saveSettings()
        if (nextSide != previousSide) {
            scheduleRender(immediate = false)
        }
    }

    private fun updateCoordinates() {
        val coordinate = GeoSpatialLibrary.gridToGeographic(
            _sweepX.value,
            _sweepY.value,
            _activeGeoMetadata.value,
        )
        _currentLat.value = coordinate?.first
        _currentLon.value = coordinate?.second
    }

    fun logCurrentSignal() {
        val markerTime = System.currentTimeMillis()
        val hasFreshDeviceFix = _deviceLocationRecordedAtMillis.value?.let { fixTime ->
            markerTime - fixTime in 0L..MAX_MARKER_GPS_AGE_MILLIS
        } == true
        val signal = TargetSignal(
            gridX = _sweepX.value,
            gridY = _sweepY.value,
            metalType = MetalType.MANUAL_MARKER,
            signalStrength = 0f,
            depthCm = null,
            latitude = _currentLat.value,
            longitude = _currentLon.value,
            gpsLatitude = _deviceLatitude.value.takeIf { hasFreshDeviceFix },
            gpsLongitude = _deviceLongitude.value.takeIf { hasFreshDeviceFix },
            gpsAccuracyMeters = _deviceLocationAccuracyMeters.value
                ?.takeIf { hasFreshDeviceFix && it.isFinite() && it >= 0f },
            source = DetectionSource.MANUAL,
            timestamp = markerTime,
            terrainKey = _activeTerrainKey.value,
        )
        viewModelScope.launch {
            signalDao.upsert(signal.toEntity())
            enqueuePendingSync(
                entityType = SyncEntityType.TARGET_SIGNAL,
                entityId = signal.id.toString(),
                operation = SyncOperation.UPSERT,
                payload = "terrain=${signal.terrainKey};status=${signal.status}",
            )
        }
    }

    fun updateLoggedSignal(signal: TargetSignal) {
        viewModelScope.launch {
            signalDao.upsert(signal.toEntity())
            enqueuePendingSync(
                entityType = SyncEntityType.TARGET_SIGNAL,
                entityId = signal.id.toString(),
                operation = SyncOperation.UPSERT,
                payload = "terrain=${signal.terrainKey};status=${signal.status};outcome=${signal.outcome.name}",
            )
        }
    }

    fun deleteLoggedSignal(signal: TargetSignal) {
        viewModelScope.launch {
            signalDao.deleteById(signal.id)
            enqueuePendingSync(
                entityType = SyncEntityType.TARGET_SIGNAL,
                entityId = signal.id.toString(),
                operation = SyncOperation.DELETE,
                payload = "",
            )
        }
    }

    fun clearLoggedSignals() {
        val terrainKey = _activeTerrainKey.value
        val signals = _loggedSignals.value
        viewModelScope.launch {
            signalDao.deleteByTerrainKey(terrainKey)
            signals.forEach { signal ->
                enqueuePendingSync(
                    entityType = SyncEntityType.TARGET_SIGNAL,
                    entityId = signal.id.toString(),
                    operation = SyncOperation.DELETE,
                    payload = "",
                )
            }
        }
    }

    private fun setActiveTerrainKey(terrainKey: String) {
        if (_isBreadcrumbRecording.value && terrainKey != _activeTerrainKey.value) {
            pauseBreadcrumbRecording()
        }
        _activeTerrainKey.value = terrainKey
        refreshVisibleSignals()
        observeSurveyLayers(terrainKey)
        observeOfflineBasemapRegions(terrainKey)
        observeBreadcrumbTracks(terrainKey)
        observeExcavationLogs(terrainKey)
        observeSurveyBoundaries(terrainKey)
        _offlineBasemapPlan.value = null
        _offlineBasemapMessage.value = null
    }

    private fun observeSurveyLayers(terrainKey: String) {
        surveyLayerJob?.cancel()
        surveyLayerJob = viewModelScope.launch {
            surveyLayerDao.observeByTerrainKey(terrainKey).collect { stored ->
                _surveyLayers.value = stored.mapNotNull { it.toDomain() }
            }
        }
    }

    private fun observeExcavationLogs(terrainKey: String) {
        excavationLogJob?.cancel()
        excavationLogJob = viewModelScope.launch {
            excavationLogDao.observeByTerrainKey(terrainKey).collect { stored ->
                _excavationLogs.value = stored.map { it.toDomain() }
            }
        }
    }

    private fun observeSurveyBoundaries(terrainKey: String) {
        surveyBoundaryJob?.cancel()
        surveyBoundaryJob = viewModelScope.launch {
            surveyBoundaryDao.observeByTerrainKey(terrainKey).collect { stored ->
                _surveyBoundaries.value = stored.map { it.toDomain() }
                refreshBoundaryProximity(_deviceLatitude.value, _deviceLongitude.value)
            }
        }
    }

    private fun observeBreadcrumbTracks(terrainKey: String) {
        breadcrumbTrackJob?.cancel()
        recordingBreadcrumbTrack = null
        _isBreadcrumbRecording.value = false
        breadcrumbTrackJob = viewModelScope.launch {
            breadcrumbTrackDao.observeByTerrainKey(terrainKey).collect { stored ->
                val tracks = stored.map { it.toDomain() }
                _breadcrumbTracks.value = tracks
                val active = tracks.firstOrNull { it.isRecording }
                if (active != null) {
                    recordingBreadcrumbTrack = active
                    _isBreadcrumbRecording.value = true
                    if (_hasLocationPermission.value) startLocationUpdates()
                } else if (recordingBreadcrumbTrack?.terrainKey == terrainKey) {
                    recordingBreadcrumbTrack = null
                    _isBreadcrumbRecording.value = false
                    if (!_gpsEnabled.value) stopLocationUpdates()
                }
            }
        }
    }

    fun importSurveyLayer(layer: SurveyLayer) {
        val terrainKey = _activeTerrainKey.value
        viewModelScope.launch {
            surveyLayerDao.upsert(layer.toEntity(terrainKey))
        }
    }

    fun deleteSurveyLayer(layer: SurveyLayer) {
        viewModelScope.launch { surveyLayerDao.deleteById(layer.id) }
    }

    suspend fun buildProjectExportFiles(): ProjectExportFiles = renderMutex.withLock {
        withContext(Dispatchers.Default) {
            // A refined viewport replaces the active grid, but project export must still cover the
            // complete source footprint. overviewTerrain retains that full raster for LAZ imports.
            val fullResult = overviewTerrain
            val exportGrid = fullResult?.grid ?: _elevationGrid.value
            val exportMetadata = (fullResult?.geoMetadata ?: _activeGeoMetadata.value).copy(
                columns = exportGrid.width,
                rows = exportGrid.height,
                resolutionMeters = exportGrid.cellSizeMeters.toDouble(),
            )
            val bitmap = exportGrid.renderHillshade(
                sunAzimuth = _sunAzimuth.value,
                sunAltitude = _sunAltitude.value,
                vegetationFilter = _vegetationFilter.value,
                palette = _paletteType.value,
                contrast = _contrast.value,
                visualizationMode = _visualizationMode.value,
                overlayType = _overlayType.value,
                overlayOpacity = _overlayOpacity.value,
                zScale = _zScale.value,
                featureScaleMeters = _featureScaleMeters.value,
                analysisSensitivity = _analysisSensitivity.value,
                contourIntervalMeters = _contourIntervalMeters.value,
            )
            ProjectExportRenderer.build(
                ProjectExportSnapshot(
                    projectName = exportMetadata.siteName,
                    terrainKey = _activeTerrainKey.value,
                    summary = fullResult?.summary ?: _activeTerrainSummary.value,
                    metadata = exportMetadata,
                    terrainBitmap = bitmap,
                    visualizationLabel = visualizationLabel(_visualizationMode.value),
                    targets = _loggedSignals.value,
                    surveyLayers = _surveyLayers.value,
                    digCount = _excavationLogs.value.size,
                    boundaryCount = _surveyBoundaries.value.size,
                    trailCount = _breadcrumbTracks.value.size,
                    ethicsFooter = DEFAULT_ETHICS_FOOTER,
                    scorecardLines = buildScorecardLines(),
                ),
            )
        }
    }

    /**
     * Terrain / ground-quality scorecard lines for PDF and site package.
     * LiDAR language only — never claims metal identity or dig depth from elevation.
     */
    private fun buildScorecardLines(): List<String> {
        val fullResult = overviewTerrain
        val grid = fullResult?.grid ?: _elevationGrid.value
        val metadata = fullResult?.geoMetadata ?: _activeGeoMetadata.value
        val bareEarthLabel = when (fullResult?.isBareEarth) {
            true -> "Bare-earth terrain model"
            false -> "First-return / canopy surface model"
            null -> null
        }
        return listOfNotNull(
            (fullResult?.summary ?: _activeTerrainSummary.value).takeIf { it.isNotBlank() },
            "Grid: ${grid.width}×${grid.height}",
            "CRS: ${metadata.crs}",
            String.format(Locale.US, "Cell size: %.2f m", grid.cellSizeMeters),
            bareEarthLabel?.let { "Surface: $it" },
            "Ground mode: ${_activeGroundMode.value.name}",
            "Class filter: ${_activeClassPreset.value.label}",
            if (metadata.isGeoreferenced) "Georeferenced: yes" else "Georeferenced: no (local grid)",
            "LiDAR is terrain context only — not metal identity or dig depth.",
        )
    }

    /**
     * Surface-sample LAS (not original pulse returns) clipped to [normalizedBounds], the first
     * survey-boundary focus when bounds are omitted, or the full footprint.
     */
    fun buildClippedLasBytes(normalizedBounds: NormalizedRasterBounds? = null): ByteArray {
        val fullResult = overviewTerrain
        val exportGrid = fullResult?.grid ?: _elevationGrid.value
        val exportMetadata = (fullResult?.geoMetadata ?: _activeGeoMetadata.value).copy(
            columns = exportGrid.width,
            rows = exportGrid.height,
            resolutionMeters = exportGrid.cellSizeMeters.toDouble(),
        )
        val bounds = normalizedBounds
            ?: _surveyBoundaries.value.firstOrNull()?.let { boundary ->
                BoundaryFocusMapper.toNormalizedBounds(boundary, exportMetadata)
            }
            ?: NormalizedRasterBounds.Full
        return ClippedLasWriter.writeFromElevationGrid(
            grid = exportGrid,
            metadata = exportMetadata,
            normalizedBounds = bounds,
        )
    }

    /**
     * Full site package zip: project export (PNG/PDF), digs/boundaries/trails, optional clipped LAS.
     */
    suspend fun buildSitePackageBytes(includeClippedLas: Boolean = true): ByteArray {
        val projectFiles = buildProjectExportFiles()
        val clippedLas = if (includeClippedLas) {
            runCatching { buildClippedLasBytes() }.getOrNull()
        } else {
            null
        }
        val fullResult = overviewTerrain
        val exportGrid = fullResult?.grid ?: _elevationGrid.value
        val exportMetadata = fullResult?.geoMetadata ?: _activeGeoMetadata.value
        val crsBanner = buildString {
            append(exportMetadata.crs)
            append(" · ")
            append(String.format(Locale.US, "%.2f m cells", exportGrid.cellSizeMeters))
            append(if (exportMetadata.isGeoreferenced) " · georeferenced" else " · local grid")
        }
        val bytes = SitePackageExporter.build(
            SitePackageInput(
                projectName = exportMetadata.siteName,
                terrainKey = _activeTerrainKey.value,
                summary = fullResult?.summary ?: _activeTerrainSummary.value,
                crsBanner = crsBanner,
                scorecardLines = buildScorecardLines(),
                targets = _loggedSignals.value,
                digs = _excavationLogs.value,
                boundaries = _surveyBoundaries.value,
                trails = _breadcrumbTracks.value,
                terrainPng = projectFiles.terrainPng,
                reportPdf = projectFiles.reportPdf,
                clippedLas = clippedLas,
            ),
        )
        _lastExportMessage.value = "Site package ready" +
            if (includeClippedLas && clippedLas != null) " (with clipped LAS surface sample)" else ""
        return bytes
    }

    /**
     * Standalone GeoTIFF of the full-source bare-earth grid. Null when the terrain has no
     * real geographic bounds (local-only grids cannot be placed safely in GIS).
     */
    suspend fun buildGeoTiffBytes(): ByteArray? = renderMutex.withLock {
        withContext(Dispatchers.Default) {
            val fullResult = overviewTerrain
            val exportGrid = fullResult?.grid ?: _elevationGrid.value
            val metadata = fullResult?.geoMetadata ?: _activeGeoMetadata.value
            val bounds = metadata.bounds ?: return@withContext null
            if (exportGrid.width < 2 || exportGrid.height < 2) return@withContext null
            GeoTiffWriter.writeElevation(
                grid = exportGrid,
                westLongitude = bounds.minLon,
                northLatitude = bounds.maxLat,
                cellWidthDegrees = (bounds.maxLon - bounds.minLon) / (exportGrid.width - 1),
                cellHeightDegrees = (bounds.maxLat - bounds.minLat) / (exportGrid.height - 1),
            )
        }
    }

    /**
     * QGIS-ready bundle: GeoTIFF of the full-source bare-earth grid, the targets as a
     * shapefile, and a .qgs that opens both. Null when the terrain has no real bounds.
     */
    suspend fun buildQgisBundleBytes(): ByteArray? = renderMutex.withLock {
        withContext(Dispatchers.Default) {
            val fullResult = overviewTerrain
            val exportGrid = fullResult?.grid ?: _elevationGrid.value
            val metadata = fullResult?.geoMetadata ?: _activeGeoMetadata.value
            val bounds = metadata.bounds ?: return@withContext null
            if (exportGrid.width < 2 || exportGrid.height < 2) return@withContext null
            val geoTiff = GeoTiffWriter.writeElevation(
                grid = exportGrid,
                westLongitude = bounds.minLon,
                northLatitude = bounds.maxLat,
                cellWidthDegrees = (bounds.maxLon - bounds.minLon) / (exportGrid.width - 1),
                cellHeightDegrees = (bounds.maxLat - bounds.minLat) / (exportGrid.height - 1),
            )
            buildQgisBundle(metadata.siteName, geoTiff, _loggedSignals.value)
        }
    }

    /**
     * Portable project archive: one self-describing zip (manifest + targets in every text
     * format + annotated PNG + PDF report + QGIS bundle when bounds allow) that moves a
     * project between devices without data loss.
     */
    suspend fun buildProjectArchiveBytes(): ByteArray {
        val projectFiles = buildProjectExportFiles()
        val signals = _loggedSignals.value
        val entries = mutableListOf(
            ProjectArchiveFile("targets.csv", buildCsv(signals).toByteArray()),
            ProjectArchiveFile("targets.gpx", buildGpx(signals).toByteArray()),
            ProjectArchiveFile("targets.kml", buildKml(signals).toByteArray()),
            ProjectArchiveFile("targets.geojson", buildGeoJson(signals).toByteArray()),
            ProjectArchiveFile("terrain-annotated.png", projectFiles.terrainPng),
            ProjectArchiveFile("field-report.pdf", projectFiles.reportPdf),
        )
        buildQgisBundleBytes()?.let { entries.add(ProjectArchiveFile("qgis-bundle.zip", it)) }
        return ProjectArchiveWriter.write(
            projectName = _activeGeoMetadata.value.siteName,
            files = entries,
            createdAtMillis = System.currentTimeMillis(),
        )
    }

    /** Minimal GeoPackage of logged finds (attributes + gpkg_contents). */
    suspend fun buildGeoPackageBytes(): ByteArray = withContext(Dispatchers.Default) {
        GeoPackageWriter.writeFinds(
            projectName = _activeGeoMetadata.value.siteName,
            signals = _loggedSignals.value,
        )
    }

    /** Zip of annotated terrain PNG + README for field handoff. */
    suspend fun buildAnnotatedMapBundleBytes(): ByteArray {
        val projectFiles = buildProjectExportFiles()
        return AnnotatedMapBundle.write(
            projectName = _activeGeoMetadata.value.siteName,
            annotatedPng = projectFiles.terrainPng,
        )
    }

    private fun observeOfflineBasemapRegions(terrainKey: String) {
        offlineBasemapRegionJob?.cancel()
        offlineBasemapRegionJob = viewModelScope.launch {
            offlineBasemapRegionDao.observeByTerrainKey(terrainKey).collect { stored ->
                val regions = stored.map { it.toDomain() }
                _offlineBasemapRegions.value = regions
                regions.filter {
                    it.status == OfflineBasemapStatus.DOWNLOADING && it.id != activeOfflineDownloadId
                }.forEach { interrupted ->
                    offlineBasemapRegionDao.upsert(
                        interrupted.copy(
                            status = OfflineBasemapStatus.CANCELED,
                            lastError = "Download was interrupted. Retry keeps completed tiles.",
                            updatedAtMillis = System.currentTimeMillis(),
                        ).toEntity(),
                    )
                }
                if (_basemapEnabled.value &&
                    stored.any { it.status == OfflineBasemapStatus.READY.name } &&
                    _basemapBitmap.value == null
                ) {
                    refreshBasemapTiles()
                }
            }
        }
    }

    fun estimateOfflineBasemapRegion() {
        val bounds = _activeGeoMetadata.value.bounds
        if (bounds == null) {
            _offlineBasemapPlan.value = null
            _offlineBasemapMessage.value =
                "This terrain has no real geographic bounds, so an offline map cannot be placed safely."
            return
        }
        val plan = basemapTileRepository.planOfflineRegion(bounds)
        _offlineBasemapPlan.value = plan
        _offlineBasemapMessage.value = null
    }

    fun downloadOfflineBasemapRegion(displayName: String? = null) {
        if (_offlineBasemapDownloading.value) return
        val plan = _offlineBasemapPlan.value ?: run {
            estimateOfflineBasemapRegion()
            _offlineBasemapPlan.value
        } ?: return
        val now = System.currentTimeMillis()
        val region = OfflineBasemapRegion(
            id = UUID.randomUUID().toString(),
            terrainKey = _activeTerrainKey.value,
            displayName = displayName?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "${_activeGeoMetadata.value.siteName} offline map",
            bounds = plan.bounds,
            zoom = plan.zoom,
            tileCount = plan.tileCount,
            completedTiles = plan.cachedTiles,
            estimatedBytes = plan.estimatedDownloadBytes,
            storedBytes = plan.cachedBytes,
            status = OfflineBasemapStatus.PLANNED,
            lastError = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        startOfflineBasemapDownload(region, plan)
    }

    fun retryOfflineBasemapRegion(region: OfflineBasemapRegion) {
        if (_offlineBasemapDownloading.value) return
        val plan = basemapTileRepository.planOfflineRegion(region.bounds, fixedZoom = region.zoom)
        startOfflineBasemapDownload(
            region.copy(
                completedTiles = plan.cachedTiles,
                storedBytes = plan.cachedBytes,
                estimatedBytes = plan.estimatedDownloadBytes,
                lastError = null,
                updatedAtMillis = System.currentTimeMillis(),
            ),
            plan,
        )
    }

    private fun startOfflineBasemapDownload(region: OfflineBasemapRegion, plan: BasemapPlan) {
        offlineBasemapDownloadJob?.cancel()
        activeOfflineDownloadId = region.id
        offlineBasemapDownloadJob = viewModelScope.launch {
            _offlineBasemapDownloading.value = true
            _offlineBasemapProgress.value = BasemapDownloadProgress(
                completedTiles = plan.cachedTiles,
                totalTiles = plan.tileCount,
                downloadedBytes = 0L,
            )
            var current = region.copy(
                status = OfflineBasemapStatus.DOWNLOADING,
                updatedAtMillis = System.currentTimeMillis(),
            )
            offlineBasemapRegionDao.upsert(current.toEntity())
            try {
                val result = basemapTileRepository.downloadOfflineRegion(plan) { progress ->
                    _offlineBasemapProgress.value = progress
                }
                val ready = result.failedTiles == 0 && result.completedTiles == plan.tileCount
                current = current.copy(
                    completedTiles = result.completedTiles,
                    storedBytes = result.storedBytes,
                    status = if (ready) OfflineBasemapStatus.READY else OfflineBasemapStatus.FAILED,
                    lastError = when {
                        ready -> null
                        result.blockedByServer -> "The tile server rejected one or more requests."
                        else -> "${result.failedTiles} tile download(s) failed. Retry to fetch only missing tiles."
                    },
                    updatedAtMillis = System.currentTimeMillis(),
                )
                offlineBasemapRegionDao.upsert(current.toEntity())
                if (ready) {
                    _offlineBasemapMessage.value = "Offline map saved. It can now reopen without service."
                    _basemapEnabled.value = true
                    settingsRepo.saveBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, true)
                    refreshBasemapTiles()
                } else {
                    _offlineBasemapMessage.value = current.lastError
                }
            } catch (cancelled: CancellationException) {
                current = current.copy(
                    status = OfflineBasemapStatus.CANCELED,
                    lastError = "Download canceled. Retry keeps completed tiles.",
                    updatedAtMillis = System.currentTimeMillis(),
                )
                withContext(NonCancellable) {
                    offlineBasemapRegionDao.upsert(current.toEntity())
                }
                throw cancelled
            } finally {
                if (activeOfflineDownloadId == region.id) {
                    activeOfflineDownloadId = null
                    _offlineBasemapDownloading.value = false
                    _offlineBasemapProgress.value = null
                }
            }
        }
    }

    fun cancelOfflineBasemapDownload() {
        offlineBasemapDownloadJob?.cancel()
    }

    fun openOfflineBasemapRegion(region: OfflineBasemapRegion) {
        if (region.status != OfflineBasemapStatus.READY) return
        _basemapEnabled.value = true
        viewModelScope.launch {
            settingsRepo.saveBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, true)
            val result = basemapTileRepository.loadBasemap(
                bounds = region.bounds,
                fixedZoom = region.zoom,
                maxTiles = region.tileCount,
                allowNetwork = false,
            )
            _basemapBitmap.value = result.bitmap
            _basemapStatus.value = if (
                result.bitmap != null && result.loadedTiles == result.expectedTiles
            ) {
                "Saved offline basemap ready."
            } else {
                "Saved region is incomplete. Retry its missing tiles."
            }
        }
    }

    fun deleteOfflineBasemapRegion(region: OfflineBasemapRegion) {
        viewModelScope.launch(Dispatchers.IO) {
            if (activeOfflineDownloadId == region.id) offlineBasemapDownloadJob?.cancel()
            val retainedEntities = offlineBasemapRegionDao.getAll().filterNot { it.id == region.id }
            val retained = retainedEntities.map {
                SlippyTileMath.boundsToTileRange(
                    GeoSpatialLibrary.GeographicBounds(it.minLat, it.maxLat, it.minLon, it.maxLon),
                    it.zoom,
                )
            }
            basemapTileRepository.deleteTilesUsedOnlyBy(
                SlippyTileMath.boundsToTileRange(region.bounds, region.zoom),
                retained,
            )
            offlineBasemapRegionDao.deleteById(region.id)
            if (region.terrainKey == _activeTerrainKey.value &&
                retainedEntities.none {
                    it.terrainKey == region.terrainKey && it.status == OfflineBasemapStatus.READY.name
                }
            ) {
                _basemapBitmap.value = null
                _basemapStatus.value = "Offline region removed."
            }
        }
    }

    private fun refreshVisibleSignals() {
        _loggedSignals.value = targetsForTerrain(allLoggedSignals, _activeTerrainKey.value)
    }

    private fun visualizationLabel(mode: Int): String = when (mode) {
        0 -> "Standard hillshade"
        1 -> "Multi-directional hillshade"
        2 -> "Slope"
        3 -> "Local relief"
        4 -> "Curvature"
        5 -> "Disturbance screening"
        6 -> "Aspect"
        7 -> "Elevation"
        8 -> "Canopy height"
        else -> "Terrain"
    }

    private suspend fun loadSettings() {
        _sunAzimuth.value = settingsRepo.getFloat(SettingsRepository.Keys.SUN_AZIMUTH, 315f)
        _sunAltitude.value = settingsRepo.getFloat(SettingsRepository.Keys.SUN_ALTITUDE, 35f)
        _vegetationFilter.value = settingsRepo.getFloat(SettingsRepository.Keys.VEGETATION_FILTER, 0.8f)
        _paletteType.value = settingsRepo.getInt(SettingsRepository.Keys.PALETTE_TYPE, 1)
        _contrast.value = settingsRepo.getFloat(SettingsRepository.Keys.CONTRAST, 1.5f)
        _visualizationMode.value = settingsRepo.getInt(SettingsRepository.Keys.VISUALIZATION_MODE, 0)
        _overlayType.value = settingsRepo.getInt(SettingsRepository.Keys.OVERLAY_TYPE, 0)
        _overlayOpacity.value = settingsRepo.getFloat(SettingsRepository.Keys.OVERLAY_OPACITY, 0.4f)
        _gridSpacing.value = settingsRepo.getFloat(SettingsRepository.Keys.GRID_SPACING, 0f)
        _zScale.value = settingsRepo.getFloat(SettingsRepository.Keys.Z_SCALE, 1f)
        _featureScaleMeters.value = settingsRepo.getFloat(SettingsRepository.Keys.FEATURE_SCALE_METERS, 6f)
        _analysisSensitivity.value = settingsRepo.getFloat(SettingsRepository.Keys.ANALYSIS_SENSITIVITY, 1.2f)
        _contourIntervalMeters.value = settingsRepo.getFloat(SettingsRepository.Keys.CONTOUR_INTERVAL_METERS, 0f)
        
        // Always try to restore the last imported LiDAR; no Homestead/Fort/Villa demos.
        val recoveryPreferences = getApplication<Application>().getSharedPreferences(
            "terrain_recovery",
            0,
        )
        restoreImportedTerrainOnStart = true
        recoveryPreferences.edit().putBoolean("checked_cached_terrain_v1", true).apply()
        _currentSiteIndex.value = 3
        _elevationGrid.value = ElevationGrid(
            width = 2,
            height = 2,
            bareEarth = FloatArray(4),
            canopySpikes = FloatArray(4),
        )
        _activeGeoMetadata.value = GeoSpatialLibrary.localGrid(
            name = "No terrain loaded",
            columns = 2,
            rows = 2,
        )
        _activeTerrainSummary.value = "Import a LAZ/LAS tile to begin"
        setActiveTerrainKey("empty")

        _sweepX.value = settingsRepo.getFloat(SettingsRepository.Keys.SWEEP_X, 50f)
        _sweepY.value = settingsRepo.getFloat(SettingsRepository.Keys.SWEEP_Y, 50f)
        _gpsEnabled.value = settingsRepo.getBoolean(SettingsRepository.Keys.GPS_ENABLED, false)
        _heatmapEnabled.value = settingsRepo.getBoolean(SettingsRepository.Keys.HEATMAP_ENABLED, false)
        _basemapEnabled.value = settingsRepo.getBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, false)
        _basemapOpacity.value = settingsRepo.getFloat(SettingsRepository.Keys.BASEMAP_OPACITY, 0.6f)

        // Load viewport settings
        _viewportZoom.value = settingsRepo.getFloat(SettingsRepository.Keys.VIEWPORT_ZOOM, 1f)
        _viewportPanX.value = settingsRepo.getFloat(SettingsRepository.Keys.VIEWPORT_PAN_X, 0f)
        _viewportPanY.value = settingsRepo.getFloat(SettingsRepository.Keys.VIEWPORT_PAN_Y, 0f)
        _viewportRestoreToken.value = _viewportRestoreToken.value + 1

        // Mark settings as loaded so subsequent saveSettings() calls are permitted
        isSettingsLoaded = true

        if (_gpsEnabled.value && _hasLocationPermission.value) startLocationUpdates()
        if (_basemapEnabled.value) refreshBasemapTiles()
    }

    /**
     * Restores the last opened LiDAR after process death.
     *
     * Order: session pointer → durable filesDir decode cache → re-decode from the saved LAZ file.
     * Source LAZ files live under [AppTerrainStorage.lidarStore]; decode cache under
     * [AppTerrainStorage.decodedTerrainCache] (filesDir, not purgeable cacheDir).
     */
    private suspend fun restoreLastCachedTerrain() {
        val application = getApplication<Application>()
        _activeTerrainSummary.value = "Restoring last LiDAR project…"
        val session = withContext(Dispatchers.IO) { terrainSessionStore.load() }
        val store = withContext(Dispatchers.IO) { AppTerrainStorage.lidarStore(application) }
        val file = session?.file?.takeIf { it.isFile }
            ?: withContext(Dispatchers.IO) { store.list().firstOrNull()?.file }
            ?: run {
                _activeTerrainSummary.value = "Import a LAZ/LAS tile to begin"
                return
            }
        val displayName = session?.displayName ?: file.name
        val preferredOptions = session?.options ?: LidarImportOptions(
            groundMode = GroundSurfaceMode.SOURCE_CLASSIFIED,
            rasterResolution = LidarImportOptions.DEFAULT_OVERVIEW_RESOLUTION,
            smoothingRadius = 0,
        )
        val optionCandidates = buildList {
            add(preferredOptions)
            listOf(1_024, 1_536).forEach { resolution ->
                val candidate = preferredOptions.copy(rasterResolution = resolution)
                if (candidate != preferredOptions) add(candidate)
            }
        }
        val diskCache = AppTerrainStorage.decodedTerrainCache(application)
        val cached = withContext(Dispatchers.IO) {
            optionCandidates.firstNotNullOfOrNull { options ->
                diskCache.get(file, options)?.let { terrain -> options to terrain }
            }
        }
        if (cached != null) {
            val (options, terrain) = cached
            val scene = withContext(Dispatchers.Default) {
                TerrainGpuSceneBuilder.build(terrain.grid)
            }
            TerrainPerformanceSession.publish(scene)
            setCustomTerrain(
                result = terrain,
                source = TerrainImportSource(
                    uri = Uri.fromFile(file).toString(),
                    displayName = displayName,
                    options = options,
                ),
            )
            return
        }

        // Cache miss: re-decode from the durable source LAZ so closing the app never loses the project.
        _activeTerrainSummary.value = "Rebuilding terrain from saved LAZ…"
        try {
            val terrainCache = LazTerrainCache(LazTerrainMemoryCache(), diskCache)
            val coordinator = TerrainDecodeCoordinator(terrainCache)
            val outcome = coordinator.decode(
                file = file,
                displayName = displayName,
                options = preferredOptions,
                onStage = { stage ->
                    withContext(Dispatchers.Main.immediate) {
                        _activeTerrainSummary.value = stage
                    }
                },
            )
            TerrainPerformanceSession.publish(outcome.gpuScene)
            setCustomTerrain(
                result = outcome.terrain,
                source = TerrainImportSource(
                    uri = Uri.fromFile(file).toString(),
                    displayName = displayName,
                    options = preferredOptions,
                ),
            )
        } catch (error: Throwable) {
            _activeTerrainSummary.value =
                "Could not restore ${displayName}: ${error.localizedMessage ?: "decode failed"}"
        }
    }

    private fun saveSettings() {
        // Prevent saving defaults over user settings until loadSettings() completes
        if (!isSettingsLoaded) return

        saveSettingsJob?.cancel() // Cancel pending save
        saveSettingsJob = viewModelScope.launch {
            delay(500) // Debounce delay
            settingsRepo.saveFloat(SettingsRepository.Keys.SUN_AZIMUTH, _sunAzimuth.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.SUN_ALTITUDE, _sunAltitude.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.VEGETATION_FILTER, _vegetationFilter.value)
            settingsRepo.saveInt(SettingsRepository.Keys.PALETTE_TYPE, _paletteType.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.CONTRAST, _contrast.value)
            settingsRepo.saveInt(SettingsRepository.Keys.VISUALIZATION_MODE, _visualizationMode.value)
            settingsRepo.saveInt(SettingsRepository.Keys.OVERLAY_TYPE, _overlayType.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.OVERLAY_OPACITY, _overlayOpacity.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.GRID_SPACING, _gridSpacing.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.Z_SCALE, _zScale.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.FEATURE_SCALE_METERS, _featureScaleMeters.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.ANALYSIS_SENSITIVITY, _analysisSensitivity.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.CONTOUR_INTERVAL_METERS, _contourIntervalMeters.value)
            settingsRepo.saveInt(SettingsRepository.Keys.CURRENT_SITE_INDEX, _currentSiteIndex.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.SWEEP_X, _sweepX.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.SWEEP_Y, _sweepY.value)
            settingsRepo.saveBoolean(SettingsRepository.Keys.GPS_ENABLED, _gpsEnabled.value)
            settingsRepo.saveBoolean(SettingsRepository.Keys.HEATMAP_ENABLED, _heatmapEnabled.value)
            settingsRepo.saveBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, _basemapEnabled.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.BASEMAP_OPACITY, _basemapOpacity.value)

            // Save viewport settings
            settingsRepo.saveFloat(SettingsRepository.Keys.VIEWPORT_ZOOM, _viewportZoom.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.VIEWPORT_PAN_X, _viewportPanX.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.VIEWPORT_PAN_Y, _viewportPanY.value)
        }
    }

    override fun onCleared() {
        renderJob?.cancel()
        locationJob?.cancel()
        compassHeadingJob?.cancel()
        basemapJob?.cancel()
        surveyLayerJob?.cancel()
        breadcrumbTrackJob?.cancel()
        excavationLogJob?.cancel()
        surveyBoundaryJob?.cancel()
        offlineBasemapRegionJob?.cancel()
        offlineBasemapDownloadJob?.cancel()
        surfaceReloadJob?.cancel()
        saveSettingsJob?.cancel()
        super.onCleared()
    }
}

/** Memo key for skipping redundant hillshade work when sliders settle on the same values. */
private data class HillshadeCacheKey(
    val gridIdentity: Int,
    val width: Int,
    val height: Int,
    val sunAzimuth: Float,
    val sunAltitude: Float,
    val vegetationFilter: Float,
    val palette: Int,
    val contrast: Float,
    val visualizationMode: Int,
    val overlayType: Int,
    val overlayOpacity: Float,
    val zScale: Float,
    val featureScaleMeters: Float,
    val analysisSensitivity: Float,
    val contourIntervalMeters: Float,
    val previewMaxSide: Int,
)
