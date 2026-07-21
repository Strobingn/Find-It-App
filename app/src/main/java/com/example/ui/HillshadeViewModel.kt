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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _autoRefineTerrain = MutableStateFlow(true)
    val autoRefineTerrain = _autoRefineTerrain.asStateFlow()
    private val _autoRefineMinZoom = MutableStateFlow(1.5f)
    val autoRefineMinZoom = _autoRefineMinZoom.asStateFlow()
    private var terrainSource: TerrainImportSource? = null
    private var overviewTerrain: DemGenerator.TerrainLoadResult? = null
    private var currentSourceBounds = NormalizedRasterBounds.Full
    /** Last absolute source bounds we successfully detailed (for skip-if-same). */
    private var lastDetailedBounds: NormalizedRasterBounds? = null
    private var lastDetailedZoom: Float = 0f

    private val _hillshadeBitmap = MutableStateFlow<Bitmap?>(null)
    val hillshadeBitmap = _hillshadeBitmap.asStateFlow()
    private val _isRendering = MutableStateFlow(false)
    val isRendering = _isRendering.asStateFlow()
    private val renderMutex = Mutex()
    private var renderJob: Job? = null
    private var renderGeneration = 0L
    private var saveSettingsJob: Job? = null
    private var autoRefineJob: Job? = null
    private var refineJob: Job? = null
    private var settingsReady = false

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

    init {
        viewModelScope.launch {
            loadSettings()
            settingsReady = true
            updateCoordinates()
            // Re-apply built-in site only if saved index is a demo site (not custom).
            val site = _currentSiteIndex.value
            if (site in 0..2) {
                _elevationGrid.value = DemGenerator.generateSite(site)
                _activeGeoMetadata.value = GeoSpatialLibrary.SITES_METADATA[site]
            }
            scheduleRender(immediate = true)
        }
        viewModelScope.launch {
            signalDao.observeAll().collect { stored ->
                _loggedSignals.value = stored.map { it.toDomain() }
            }
        }
    }

    private fun scheduleRender(immediate: Boolean = false) {
        val generation = ++renderGeneration
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

    private fun scheduleSaveSettings() {
        if (!settingsReady) return
        saveSettingsJob?.cancel()
        saveSettingsJob = viewModelScope.launch {
            delay(350)
            persistSettings()
        }
    }

    fun selectSite(index: Int) {
        if (index !in 0..3 || index == 3 && customGrid == null) return
        _currentSiteIndex.value = index
        scheduleSaveSettings()
        if (index in 0..2) {
            _elevationGrid.value = DemGenerator.generateSite(index)
            _activeGeoMetadata.value = GeoSpatialLibrary.SITES_METADATA[index]
            _activeTerrainSummary.value = "Built-in simulated terrain"
            _canRefineTerrain.value = false
            _isDetailedTerrain.value = false
            terrainSource = null
            overviewTerrain = null
        } else {
            _elevationGrid.value = requireNotNull(customGrid)
        }
        updateCoordinates()
        scheduleRender(immediate = true)
    }

    fun setCustomTerrain(
        result: DemGenerator.TerrainLoadResult,
        source: TerrainImportSource? = null,
    ) {
        terrainSource = source
        overviewTerrain = result.takeIf { source != null }
        currentSourceBounds = NormalizedRasterBounds.Full
        lastDetailedBounds = null
        lastDetailedZoom = 0f
        _canRefineTerrain.value = source != null
        _isDetailedTerrain.value = false
        _terrainDetailMessage.value = if (source != null) {
            if (_autoRefineTerrain.value) {
                "Pinch to zoom — detail reloads from the original file automatically."
            } else {
                "Pinch to zoom, then Load detail (or enable Auto detail)."
            }
        } else {
            null
        }
        // Keep the user's saved viz settings — never hardcode sun/palette/etc. on import.
        applyTerrainSurface(result)
    }

    /**
     * Applies elevation grid + geo metadata only.
     * Visualization knobs stay at whatever the user last set (persisted in Room).
     */
    private fun applyTerrainSurface(result: DemGenerator.TerrainLoadResult) {
        val grid = result.grid
        customGrid = result.grid
        _elevationGrid.value = result.grid
        _currentSiteIndex.value = 3
        scheduleSaveSettings()
        _activeGeoMetadata.value = result.geoMetadata ?: GeoSpatialLibrary.localGrid(
            name = "Custom imported layer",
            columns = grid.width,
            rows = grid.height,
            resolutionMeters = grid.cellSizeMeters.toDouble(),
        )
        _activeTerrainSummary.value = result.summary
        updateCoordinates()
        scheduleRender(immediate = true)
    }

    fun setAutoRefineTerrain(enabled: Boolean) {
        _autoRefineTerrain.value = enabled
        scheduleSaveSettings()
        _terrainDetailMessage.value = if (enabled) {
            "Auto detail on — zoom/pan reloads the source file for this viewport."
        } else {
            "Auto detail off — use Load detail when ready."
        }
    }

    fun setAutoRefineMinZoom(value: Float) {
        _autoRefineMinZoom.value = value.coerceIn(1.2f, 8f)
        scheduleSaveSettings()
    }

    /**
     * Called from the map as the user pans/zooms. Debounced auto re-rasterize from the
     * original LAZ/LAS/GeoTIFF (or any [TerrainImportSource]) for the visible bounds.
     */
    fun onExploreViewportChanged(viewport: NormalizedRasterBounds, zoom: Float) {
        if (!_autoRefineTerrain.value || terrainSource == null) return
        autoRefineJob?.cancel()
        autoRefineJob = viewModelScope.launch {
            delay(AUTO_REFINE_DEBOUNCE_MS)
            val minZoom = _autoRefineMinZoom.value
            if (zoom < minZoom) {
                // Zoomed back out far enough → restore full-file overview once.
                if (_isDetailedTerrain.value && zoom <= 1.15f) {
                    showWholeTerrain()
                }
                return@launch
            }
            refineTerrain(viewport, fromAuto = true, zoom = zoom)
        }
    }

    fun refineTerrain(viewport: NormalizedRasterBounds) {
        refineTerrain(viewport, fromAuto = false, zoom = null)
    }

    private fun refineTerrain(
        viewport: NormalizedRasterBounds,
        fromAuto: Boolean,
        zoom: Float?,
    ) {
        val source = terrainSource ?: return
        if (_isRefiningTerrain.value) return
        val absoluteBounds = viewport.sanitized().inside(currentSourceBounds)
        val widthFraction = absoluteBounds.right - absoluteBounds.left
        val heightFraction = absoluteBounds.bottom - absoluteBounds.top
        if (widthFraction >= 0.98 && heightFraction >= 0.98) {
            if (!fromAuto) {
                _terrainDetailMessage.value = "Zoom farther in before loading detail."
            }
            return
        }
        // Skip redundant work when auto-refine fires for nearly the same view.
        if (fromAuto && lastDetailedBounds != null && zoom != null) {
            val prev = lastDetailedBounds!!
            val sameBounds = boundsSimilar(prev, absoluteBounds, 0.08)
            val sameZoom = kotlin.math.abs(zoom - lastDetailedZoom) < 0.35f
            if (sameBounds && sameZoom) return
        }

        refineJob?.cancel()
        _isRefiningTerrain.value = true
        _terrainDetailMessage.value = if (fromAuto) {
            "Auto-loading detail for this zoom…"
        } else {
            "Reading original returns for this viewport…"
        }
        refineJob = viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                getApplication<Application>().contentResolver
                    .openInputStream(Uri.parse(source.uri))
                    ?.buffered()
                    ?.use { input ->
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
                    _terrainDetailMessage.value =
                        "Could not load detail from the original ${source.displayName}."
                } else {
                    currentSourceBounds = absoluteBounds
                    lastDetailedBounds = absoluteBounds
                    lastDetailedZoom = zoom ?: lastDetailedZoom
                    _isDetailedTerrain.value = true
                    _terrainDetailMessage.value = if (fromAuto) {
                        "Auto detail · ${result.grid.width}×${result.grid.height} · ${"%.2f".format(result.grid.cellSizeMeters)} m/cell"
                    } else {
                        "Detailed viewport loaded from the original point cloud."
                    }
                    // Preserve sun/palette/contrast/etc.
                    applyTerrainSurface(result)
                }
            }
        }
    }

    fun showWholeTerrain() {
        val overview = overviewTerrain ?: return
        autoRefineJob?.cancel()
        refineJob?.cancel()
        currentSourceBounds = NormalizedRasterBounds.Full
        lastDetailedBounds = null
        lastDetailedZoom = 0f
        _isDetailedTerrain.value = false
        _isRefiningTerrain.value = false
        _terrainDetailMessage.value = if (_autoRefineTerrain.value) {
            "Whole file. Zoom in to auto-load higher detail."
        } else {
            "Whole file. Zoom in, then Load detail for higher resolution."
        }
        applyTerrainSurface(overview)
    }

    private fun boundsSimilar(
        a: NormalizedRasterBounds,
        b: NormalizedRasterBounds,
        maxDelta: Double,
    ): Boolean {
        return kotlin.math.abs(a.left - b.left) <= maxDelta &&
            kotlin.math.abs(a.top - b.top) <= maxDelta &&
            kotlin.math.abs(a.right - b.right) <= maxDelta &&
            kotlin.math.abs(a.bottom - b.bottom) <= maxDelta
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
        scheduleSaveSettings()
        scheduleRender()
    }
    fun rotateSunAzimuth(deltaDegrees: Float) {
        val value = _sunAzimuth.value + deltaDegrees
        _sunAzimuth.value = ((value % 360f) + 360f) % 360f
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateSunAltitude(value: Float) {
        _sunAltitude.value = value.coerceIn(5f, 85f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateVegetationFilter(value: Float) {
        _vegetationFilter.value = value.coerceIn(0f, 1f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updatePalette(value: Int) {
        _paletteType.value = value.coerceIn(0, 2)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateContrast(value: Float) {
        _contrast.value = value.coerceIn(1f, 2.5f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateVisualizationMode(value: Int) {
        _visualizationMode.value = value.coerceIn(0, 8)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateOverlayType(value: Int) {
        _overlayType.value = value.coerceIn(0, 2)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateOverlayOpacity(value: Float) {
        _overlayOpacity.value = value.coerceIn(0.1f, 0.9f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateGridSpacing(value: Float) {
        _gridSpacing.value = value.coerceIn(0f, 20f)
        scheduleSaveSettings()
    }
    fun updateZScale(value: Float) {
        _zScale.value = value.coerceIn(0.5f, 4f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateFeatureScale(value: Float) {
        _featureScaleMeters.value = value.coerceIn(1f, 40f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateAnalysisSensitivity(value: Float) {
        _analysisSensitivity.value = value.coerceIn(0.4f, 2.5f)
        scheduleSaveSettings()
        scheduleRender()
    }
    fun updateContourInterval(value: Float) {
        _contourIntervalMeters.value = value.coerceIn(0f, 5f)
        scheduleSaveSettings()
        scheduleRender()
    }

    fun setSweepPosition(x: Float, y: Float) {
        _sweepX.value = x.coerceIn(0f, 100f)
        _sweepY.value = y.coerceIn(0f, 100f)
        scheduleSaveSettings()
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
        // Room values win; numeric defaults apply only on first install (key missing).
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
        _currentSiteIndex.value = settingsRepo.getInt(SettingsRepository.Keys.CURRENT_SITE_INDEX, 0).coerceIn(0, 3)
        _sweepX.value = settingsRepo.getFloat(SettingsRepository.Keys.SWEEP_X, 50f)
        _sweepY.value = settingsRepo.getFloat(SettingsRepository.Keys.SWEEP_Y, 50f)
        _autoRefineTerrain.value = settingsRepo.getBoolean(SettingsRepository.Keys.AUTO_REFINE_TERRAIN, true)
        _autoRefineMinZoom.value = settingsRepo
            .getFloat(SettingsRepository.Keys.AUTO_REFINE_MIN_ZOOM, 1.5f)
            .coerceIn(1.2f, 8f)
    }

    private suspend fun persistSettings() {
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
        settingsRepo.saveBoolean(SettingsRepository.Keys.AUTO_REFINE_TERRAIN, _autoRefineTerrain.value)
        settingsRepo.saveFloat(SettingsRepository.Keys.AUTO_REFINE_MIN_ZOOM, _autoRefineMinZoom.value)
    }

    override fun onCleared() {
        renderJob?.cancel()
        saveSettingsJob?.cancel()
        autoRefineJob?.cancel()
        refineJob?.cancel()
        super.onCleared()
    }

    companion object {
        private const val AUTO_REFINE_DEBOUNCE_MS = 480L
    }
}
