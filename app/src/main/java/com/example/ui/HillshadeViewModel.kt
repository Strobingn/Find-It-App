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

    private val _currentSiteIndex = MutableStateFlow(0)
    val currentSiteIndex: StateFlow<Int> = _currentSiteIndex.asStateFlow()
    private val _elevationGrid = MutableStateFlow(DemGenerator.generateSite(0))
    val elevationGrid: StateFlow<ElevationGrid> = _elevationGrid.asStateFlow()
    private var customGrid: ElevationGrid? = null

    private val _sunAzimuth = MutableStateFlow(315f)
    val sunAzimuth = _sunAzimuth.asStateFlow()
    private val _sunAltitude = MutableStateFlow(45f)          // brighter default
    val sunAltitude = _sunAltitude.asStateFlow()
    private val _vegetationFilter = MutableStateFlow(0.8f)
    val vegetationFilter = _vegetationFilter.asStateFlow()
    private val _paletteType = MutableStateFlow(1)
    val paletteType = _paletteType.asStateFlow()
    private val _contrast = MutableStateFlow(1.65f)
    val contrast = _contrast.asStateFlow()
    private val _visualizationMode = MutableStateFlow(0)
    val visualizationMode = _visualizationMode.asStateFlow()
    private val _overlayType = MutableStateFlow(0)
    val overlayType = _overlayType.asStateFlow()
    private val _overlayOpacity = MutableStateFlow(0.4f)
    val overlayOpacity = _overlayOpacity.asStateFlow()
    private val _gridSpacing = MutableStateFlow(0f)
    val gridSpacing = _gridSpacing.asStateFlow()
    private val _zScale = MutableStateFlow(1.4f)
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


    init {
        updateCoordinates()
        scheduleRender(immediate = true)
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
        applyCustomTerrain(result)
    }

    private fun applyCustomTerrain(result: DemGenerator.TerrainLoadResult) {
        val grid = result.grid
        customGrid = result.grid
        _elevationGrid.value = result.grid
        _currentSiteIndex.value = 3
        _activeGeoMetadata.value = result.geoMetadata ?: GeoSpatialLibrary.localGrid(
            name = "Your imported terrain",
            columns = grid.width,
            rows = grid.height,
            resolutionMeters = grid.cellSizeMeters.toDouble(),
        )
        _activeTerrainSummary.value = result.summary
        _vegetationFilter.value = if (result.isBareEarth) 1f else 0.15f
        // Human-friendly brighter defaults so the map is not a black void
        _visualizationMode.value = 1          // multi-directional hillshade (clearer)
        _contrast.value = 1.9f
        _paletteType.value = 1                // warmer palette, not pure gray
        _sunAltitude.value = 48f              // higher sun = less dark shadows
        _sunAzimuth.value = 315f
        _zScale.value = 1.8f
        updateCoordinates()
        scheduleRender(immediate = true)
    }


    fun refineTerrain(viewport: NormalizedRasterBounds) {
        val source = terrainSource ?: return
        if (_isRefiningTerrain.value) return
        val absoluteBounds = viewport.sanitized().inside(currentSourceBounds)
        val widthFraction = absoluteBounds.right - absoluteBounds.left
        val heightFraction = absoluteBounds.bottom - absoluteBounds.top
        if (widthFraction >= 0.98 && heightFraction >= 0.98) {
            _terrainDetailMessage.value = "Zoom in a bit more first, then load detail."
            return
        }
        _isRefiningTerrain.value = true
        _terrainDetailMessage.value = "Loading higher detail for this area…"
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
                    _terrainDetailMessage.value = "Could not load extra detail from the original file."
                } else {
                    currentSourceBounds = absoluteBounds
                    _isDetailedTerrain.value = true
                    _terrainDetailMessage.value = "Higher detail loaded for this area."
                    applyCustomTerrain(result)
                }
            }
        }
    }

    fun showWholeTerrain() {
        val overview = overviewTerrain ?: return
        currentSourceBounds = NormalizedRasterBounds.Full
        _isDetailedTerrain.value = false
        _terrainDetailMessage.value = "Showing the full survey area."
        applyCustomTerrain(overview)
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


    override fun onCleared() {
        renderJob?.cancel()
        super.onCleared()
    }

}
