package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DemGenerator
import com.example.data.DetectionSource
import com.example.data.ElevationGrid
import com.example.data.MetalType
import com.example.data.NormalizedRasterBounds
import com.example.data.TerrainImportSource
import com.example.data.TargetSignal
import com.example.data.local.AppDatabase
import com.example.data.local.SettingsRepository
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import com.example.geospatial.LocationTracker
import com.example.geospatial.OsmTileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class HillshadeViewModel(application: Application) : AndroidViewModel(application) {
    private val signalDao = AppDatabase.get(application).targetSignalDao()
    private val settingsRepo = SettingsRepository(AppDatabase.get(application).settingDao())

    private val _currentSiteIndex = MutableStateFlow(0)
    val currentSiteIndex: StateFlow<Int> = _currentSiteIndex.asStateFlow()
    private val _elevationGrid = MutableStateFlow(DemGenerator.generateSite(0))
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
    private val _activeTerrainSummary = MutableStateFlow("Built-in demonstration terrain")
    val activeTerrainSummary = _activeTerrainSummary.asStateFlow()
    private val _canRefineTerrain = MutableStateFlow(false)
    val canRefineTerrain = _canRefineTerrain.asStateFlow()
    private val _isRefiningTerrain = MutableStateFlow(false)
    val isRefiningTerrain = _isRefiningTerrain.asStateFlow()
    private val _isDetailedTerrain = MutableStateFlow(false)
    val isDetailedTerrain = _isDetailedTerrain.asStateFlow()
    private val _terrainDetailMessage = MutableStateFlow<String?>(null)
    val terrainDetailMessage = _terrainDetailMessage.asStateFlow()
    private var terrainSource: TerrainImportSource? = null
    private var overviewTerrain: DemGenerator.TerrainLoadResult? = null
    private var currentSourceBounds = NormalizedRasterBounds.Full

    private val _hillshadeBitmap = MutableStateFlow<Bitmap?>(null)
    val hillshadeBitmap = _hillshadeBitmap.asStateFlow()
    private val _isRendering = MutableStateFlow(false)
    val isRendering = _isRendering.asStateFlow()
    private val renderMutex = Mutex()
    private var renderJob: Job? = null
    private var renderGeneration = 0L

    private val _sweepX = MutableStateFlow(50f)
    val sweepX = _sweepX.asStateFlow()
    private val _sweepY = MutableStateFlow(50f)
    val sweepY = _sweepY.asStateFlow()

    private val _loggedSignals = MutableStateFlow<List<TargetSignal>>(emptyList())
    val loggedSignals = _loggedSignals.asStateFlow()

    private val _activeGeoMetadata = MutableStateFlow(GeoSpatialLibrary.SITES_METADATA.first())
    val activeGeoMetadata: StateFlow<GeoSpatialMetadata> = _activeGeoMetadata.asStateFlow()
    private val _currentLat = MutableStateFlow<Double?>(null)
    val currentLat: StateFlow<Double?> = _currentLat.asStateFlow()
    private val _currentLon = MutableStateFlow<Double?>(null)
    val currentLon: StateFlow<Double?> = _currentLon.asStateFlow()

    private val locationTracker = LocationTracker(application)
    private val _gpsEnabled = MutableStateFlow(false)
    val gpsEnabled: StateFlow<Boolean> = _gpsEnabled.asStateFlow()
    private val _hasLocationPermission = MutableStateFlow(locationTracker.hasLocationPermission())
    val hasLocationPermission: StateFlow<Boolean> = _hasLocationPermission.asStateFlow()
    private val _deviceGridPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val deviceGridPosition: StateFlow<Pair<Float, Float>?> = _deviceGridPosition.asStateFlow()
    private val _deviceLocationAccuracyMeters = MutableStateFlow<Float?>(null)
    val deviceLocationAccuracyMeters: StateFlow<Float?> = _deviceLocationAccuracyMeters.asStateFlow()
    private var locationJob: Job? = null

    // Bumped after successful refine / show-whole so the canvas forces zoom=1 + pan=0
    // against the new high-res (or full) bitmap.
    private val _viewportResetKey = MutableStateFlow(0)
    val viewportResetKey: StateFlow<Int> = _viewportResetKey.asStateFlow()

    init {
        // loadSettings must finish before the first scheduleRender — scheduleRender saves the
        // *current* StateFlow values back to disk, and if that runs while loadSettings' reads are
        // still in flight, it stomps the just-persisted settings with hardcoded defaults on every
        // single app start. Awaiting it here (rather than firing both as separate launches) is
        // what actually fixes that.
        viewModelScope.launch {
            loadSettings()
            updateCoordinates()
            scheduleRender(immediate = true)
        }
        viewModelScope.launch {
            signalDao.observeAll().collect { stored ->
                _loggedSignals.value = stored.map { it.toDomain() }
            }
        }
    }

    /** Called by the UI after a runtime permission dialog resolves. */
    fun onLocationPermissionResult(granted: Boolean) {
        _hasLocationPermission.value = granted || locationTracker.hasLocationPermission()
        if (_gpsEnabled.value && _hasLocationPermission.value) startLocationUpdates()
    }

    fun toggleGpsTracking(enabled: Boolean) {
        _gpsEnabled.value = enabled
        viewModelScope.launch { settingsRepo.saveBoolean(SettingsRepository.Keys.GPS_ENABLED, enabled) }
        if (enabled && _hasLocationPermission.value) {
            startLocationUpdates()
        } else if (!enabled) {
            stopLocationUpdates()
        }
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            locationTracker.locationUpdates()
                .catch { /* provider unavailable or a platform SecurityException — stop tracking, don't crash */ }
                .collect { fix ->
                    _deviceLocationAccuracyMeters.value = fix.accuracyMeters
                    _deviceGridPosition.value =
                        GeoSpatialLibrary.geographicToGrid(fix.latitude, fix.longitude, _activeGeoMetadata.value)
                }
        }
    }

    private fun stopLocationUpdates() {
        locationJob?.cancel()
        locationJob = null
        _deviceGridPosition.value = null
        _deviceLocationAccuracyMeters.value = null
    }

    private val _heatmapEnabled = MutableStateFlow(false)
    val heatmapEnabled: StateFlow<Boolean> = _heatmapEnabled.asStateFlow()

    fun setHeatmapEnabled(enabled: Boolean) {
        _heatmapEnabled.value = enabled
        viewModelScope.launch { settingsRepo.saveBoolean(SettingsRepository.Keys.HEATMAP_ENABLED, enabled) }
    }

    private val osmTileRepository = OsmTileRepository(application)
    private val _basemapEnabled = MutableStateFlow(false)
    val basemapEnabled: StateFlow<Boolean> = _basemapEnabled.asStateFlow()
    private val _basemapOpacity = MutableStateFlow(0.6f)
    val basemapOpacity: StateFlow<Float> = _basemapOpacity.asStateFlow()
    private val _basemapBitmap = MutableStateFlow<Bitmap?>(null)
    val basemapBitmap: StateFlow<Bitmap?> = _basemapBitmap.asStateFlow()
    private val _basemapStatus = MutableStateFlow<String?>(null)
    val basemapStatus: StateFlow<String?> = _basemapStatus.asStateFlow()
    private var basemapJob: Job? = null

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
            _basemapStatus.value = "Loading basemap tiles…"
            val result = runCatching { osmTileRepository.loadBasemap(bounds) }.getOrNull()
            _basemapBitmap.value = result?.bitmap
            _basemapStatus.value = when {
                result?.bitmap != null -> null
                result?.blockedByServer == true ->
                    "OpenStreetMap's tile server rejected these requests — basemap unavailable here."
                else -> "Couldn't load basemap tiles — showing terrain view only."
            }
        }
    }

    private fun scheduleRender(immediate: Boolean = false) {
        val generation = ++renderGeneration
        saveSettings()
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            if (!immediate) delay(80)
            _isRendering.value = true
            try {
                renderMutex.withLock {
                    val grid = _elevationGrid.value
                    val bitmap = withContext(Dispatchers.Default) {
                        grid.renderHillshade(
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
                    }
                    if (generation == renderGeneration) _hillshadeBitmap.value = bitmap
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (generation == renderGeneration) _isRendering.value = false
            }
        }
    }

    fun selectSite(index: Int) {
        if (index !in 0..3 || index == 3 && customGrid == null) return
        _currentSiteIndex.value = index
        if (index in 0..2) {
            _elevationGrid.value = DemGenerator.generateSite(index)
            _activeGeoMetadata.value = GeoSpatialLibrary.SITES_METADATA[index]
            _activeTerrainSummary.value = "Built-in simulated terrain"
        } else {
            _elevationGrid.value = requireNotNull(customGrid)
        }
        updateCoordinates()
        scheduleRender(immediate = true)
        if (_basemapEnabled.value) refreshBasemapTiles()
    }

    fun setCustomTerrain(
        result: DemGenerator.TerrainLoadResult,
        source: TerrainImportSource? = null,
    ) {
        terrainSource = source
        overviewTerrain = result.takeIf { source != null }
        currentSourceBounds = NormalizedRasterBounds.Full
        _canRefineTerrain.value = source != null
        _isDetailedTerrain.value = false
        _terrainDetailMessage.value = null
        applyCustomTerrain(result, resetViewport = true)
    }

    private fun applyCustomTerrain(result: DemGenerator.TerrainLoadResult, resetViewport: Boolean = false) {
        val grid = result.grid
        customGrid = result.grid
        _elevationGrid.value = result.grid
        _currentSiteIndex.value = 3
        _activeGeoMetadata.value = result.geoMetadata ?: GeoSpatialLibrary.localGrid(
            name = "Custom imported layer",
            columns = grid.width,
            rows = grid.height,
            resolutionMeters = grid.cellSizeMeters.toDouble(),
        )
        _activeTerrainSummary.value = result.summary
        // IMPORTANT: do NOT force sun/palette/contrast/viz/zScale here.
        // That was wiping every user preference on every import and every refine.
        // User settings from Room are already loaded and must stick.
        updateCoordinates()
        scheduleRender(immediate = true)
        if (_basemapEnabled.value) refreshBasemapTiles()
        if (resetViewport) {
            _viewportResetKey.value = _viewportResetKey.value + 1
        }
    }

    fun refineTerrain(viewport: NormalizedRasterBounds) {
        val source = terrainSource ?: return
        if (_isRefiningTerrain.value) return
        val absoluteBounds = viewport.sanitized().inside(currentSourceBounds)
        val widthFraction = absoluteBounds.right - absoluteBounds.left
        val heightFraction = absoluteBounds.bottom - absoluteBounds.top
        if (widthFraction >= 0.98 && heightFraction >= 0.98) {
            _terrainDetailMessage.value = "Zoom farther in before loading detail."
            return
        }
        _isRefiningTerrain.value = true
        _terrainDetailMessage.value = "Reading original returns for this viewport…"
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                getApplication<Application>().contentResolver.openInputStream(Uri.parse(source.uri))?.buffered()?.use { input ->
                    DemGenerator.parseFromStreamDetailed(
                        fileName = source.displayName,
                        inputStream = input,
                        options = source.options.copy(
                            rasterResolution = 1_024,
                            focusBounds = absoluteBounds,
                        ),
                    )
                }
            }.getOrNull()
            withContext(Dispatchers.Main.immediate) {
                _isRefiningTerrain.value = false
                if (result == null) {
                    _terrainDetailMessage.value = "Could not load detail from the original LAZ/LAS document."
                } else {
                    currentSourceBounds = absoluteBounds
                    _isDetailedTerrain.value = true
                    _terrainDetailMessage.value = "Detailed viewport loaded from the original point cloud."
                    // The new bitmap *is* the previous viewport at full resolution.
                    // Reset zoom/pan so the user sees exactly that area at 1× of the new high-res raster.
                    applyCustomTerrain(result, resetViewport = true)
                }
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
    fun updateGridSpacing(value: Float) { _gridSpacing.value = value.coerceIn(0f, 20f) }
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
        val signal = TargetSignal(
            gridX = _sweepX.value,
            gridY = _sweepY.value,
            metalType = MetalType.MANUAL_MARKER,
            signalStrength = 0f,
            depthCm = null,
            latitude = _currentLat.value,
            longitude = _currentLon.value,
            source = DetectionSource.MANUAL,
        )
        viewModelScope.launch { signalDao.upsert(signal.toEntity()) }
    }

    fun updateLoggedSignal(signal: TargetSignal) {
        viewModelScope.launch { signalDao.upsert(signal.toEntity()) }
    }

    fun deleteLoggedSignal(signal: TargetSignal) {
        viewModelScope.launch { signalDao.deleteById(signal.id) }
    }

    fun clearLoggedSignals() {
        viewModelScope.launch { signalDao.deleteAll() }
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
        val site = settingsRepo.getInt(SettingsRepository.Keys.CURRENT_SITE_INDEX, 0)
        _currentSiteIndex.value = site
        if (site in 0..2) {
            _elevationGrid.value = DemGenerator.generateSite(site)
            _activeGeoMetadata.value = GeoSpatialLibrary.SITES_METADATA[site]
            _activeTerrainSummary.value = "Built-in simulated terrain"
        }
        // site == 3 (custom) is lost across process death — customGrid is in-memory only
        _sweepX.value = settingsRepo.getFloat(SettingsRepository.Keys.SWEEP_X, 50f)
        _sweepY.value = settingsRepo.getFloat(SettingsRepository.Keys.SWEEP_Y, 50f)
        _gpsEnabled.value = settingsRepo.getBoolean(SettingsRepository.Keys.GPS_ENABLED, false)
        _heatmapEnabled.value = settingsRepo.getBoolean(SettingsRepository.Keys.HEATMAP_ENABLED, false)
        _basemapEnabled.value = settingsRepo.getBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, false)
        _basemapOpacity.value = settingsRepo.getFloat(SettingsRepository.Keys.BASEMAP_OPACITY, 0.6f)
        if (_gpsEnabled.value && _hasLocationPermission.value) startLocationUpdates()
        if (_basemapEnabled.value) refreshBasemapTiles()
    }

    private fun saveSettings() {
        viewModelScope.launch {
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
            // also persist the ones that were only saved on toggle so a bulk save never loses them
            settingsRepo.saveBoolean(SettingsRepository.Keys.GPS_ENABLED, _gpsEnabled.value)
            settingsRepo.saveBoolean(SettingsRepository.Keys.HEATMAP_ENABLED, _heatmapEnabled.value)
            settingsRepo.saveBoolean(SettingsRepository.Keys.BASEMAP_ENABLED, _basemapEnabled.value)
            settingsRepo.saveFloat(SettingsRepository.Keys.BASEMAP_OPACITY, _basemapOpacity.value)
        }
    }

    override fun onCleared() {
        renderJob?.cancel()
        locationJob?.cancel()
        basemapJob?.cancel()
        super.onCleared()
    }

}
