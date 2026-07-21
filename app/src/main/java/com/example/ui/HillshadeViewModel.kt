package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DemGenerator
import com.example.data.DetectionSource
import com.example.data.ElevationGrid
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.data.local.AppDatabase
import com.example.data.local.toDomain
import com.example.data.local.toEntity
import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata
import com.example.sensor.MagnetometerMonitor
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
import kotlin.math.abs
import kotlin.math.sqrt

class HillshadeViewModel(application: Application) : AndroidViewModel(application) {
    private val signalDao = AppDatabase.get(application).targetSignalDao()

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
    private val _isSweeping = MutableStateFlow(false)
    val isSweeping = _isSweeping.asStateFlow()

    private val _loggedSignals = MutableStateFlow<List<TargetSignal>>(emptyList())
    val loggedSignals = _loggedSignals.asStateFlow()

    private val _activeGeoMetadata = MutableStateFlow(GeoSpatialLibrary.SITES_METADATA.first())
    val activeGeoMetadata: StateFlow<GeoSpatialMetadata> = _activeGeoMetadata.asStateFlow()
    private val _currentLat = MutableStateFlow<Double?>(null)
    val currentLat: StateFlow<Double?> = _currentLat.asStateFlow()
    private val _currentLon = MutableStateFlow<Double?>(null)
    val currentLon: StateFlow<Double?> = _currentLon.asStateFlow()

    private val magnetometerMonitor = MagnetometerMonitor(application)
    val isPhysicalSensorAvailable = magnetometerMonitor.isSensorAvailable
    private val _usePhysicalSensor = MutableStateFlow(false)
    val usePhysicalSensor = _usePhysicalSensor.asStateFlow()
    private val _detectorSignalStrength = MutableStateFlow(0f)
    val detectorSignalStrength = _detectorSignalStrength.asStateFlow()
    private val _detectedMetalType = MutableStateFlow<MetalType?>(null)
    val detectedMetalType = _detectedMetalType.asStateFlow()
    private val _detectedDepthCm = MutableStateFlow<Int?>(null)
    val detectedDepthCm = _detectedDepthCm.asStateFlow()

    private val _audioPingEnabled = MutableStateFlow(true)
    val audioPingEnabled = _audioPingEnabled.asStateFlow()
    private val _vibrationEnabled = MutableStateFlow(true)
    val vibrationEnabled = _vibrationEnabled.asStateFlow()
    private val vibrator = application.getSystemService(Application.VIBRATOR_SERVICE) as Vibrator
    private var toneGenerator: ToneGenerator? = null
    private var detectorJob: Job? = null

    private val simulatedTargets = listOf(
        listOf(
            SimulatedTarget(25f, 65f, MetalType.GOLD, 95f, 12),
            SimulatedTarget(42f, 44f, MetalType.SILVER, 85f, 22),
            SimulatedTarget(45f, 39f, MetalType.BRONZE, 72f, 30),
            SimulatedTarget(58f, 50f, MetalType.IRON, 60f, 8),
        ),
        listOf(
            SimulatedTarget(50f, 52f, MetalType.BRONZE, 90f, 38),
            SimulatedTarget(48f, 35f, MetalType.SILVER, 78f, 18),
            SimulatedTarget(35f, 60f, MetalType.IRON, 92f, 10),
            SimulatedTarget(66f, 40f, MetalType.IRON, 55f, 25),
        ),
        listOf(
            SimulatedTarget(42f, 45f, MetalType.GOLD, 98f, 16),
            SimulatedTarget(68f, 30f, MetalType.BRONZE, 82f, 28),
            SimulatedTarget(22f, 35f, MetalType.SILVER, 88f, 10),
            SimulatedTarget(49f, 41f, MetalType.IRON, 50f, 35),
        ),
    )

    init {
        toneGenerator = try {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (_: RuntimeException) {
            null
        }
        updateCoordinates()
        scheduleRender(immediate = true)
        viewModelScope.launch {
            signalDao.observeAll().collect { stored ->
                _loggedSignals.value = stored.map { it.toDomain() }
            }
        }
        startDetectorLoop()
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

    fun setCustomTerrain(result: DemGenerator.TerrainLoadResult) {
        val grid = result.grid
        customGrid = result.grid
        _elevationGrid.value = result.grid
        _currentSiteIndex.value = 3
        _activeGeoMetadata.value = GeoSpatialLibrary.localGrid(
            name = "Custom imported layer",
            columns = grid.width,
            rows = grid.height,
            resolutionMeters = grid.cellSizeMeters.toDouble(),
        )
        _activeTerrainSummary.value = result.summary
        _vegetationFilter.value = if (result.isBareEarth) 1f else 0f
        _visualizationMode.value = if (result.isBareEarth) 3 else 1
        _contrast.value = 1.85f
        _paletteType.value = 0
        _sunAltitude.value = 28f
        _sunAzimuth.value = 315f
        _zScale.value = 2f
        updateCoordinates()
        scheduleRender(immediate = true)
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
    fun updateVisualizationMode(value: Int) { _visualizationMode.value = value.coerceIn(0, 5); scheduleRender() }
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
        _isSweeping.value = true
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

    fun stopSweeping() {
        _isSweeping.value = false
        if (!_usePhysicalSensor.value) {
            _detectorSignalStrength.value = 0f
            _detectedMetalType.value = null
            _detectedDepthCm.value = null
        }
    }

    fun togglePhysicalSensor(enabled: Boolean) {
        val shouldEnable = enabled && isPhysicalSensorAvailable.value
        _usePhysicalSensor.value = shouldEnable
        if (shouldEnable) magnetometerMonitor.startListening() else magnetometerMonitor.stopListening()
        _detectorSignalStrength.value = 0f
        _detectedMetalType.value = null
        _detectedDepthCm.value = null
    }

    fun toggleAudioPing(enabled: Boolean) { _audioPingEnabled.value = enabled }
    fun toggleVibration(enabled: Boolean) { _vibrationEnabled.value = enabled }
    fun calibrateMagnetometer() { if (_usePhysicalSensor.value) magnetometerMonitor.calibrateBaseline() }

    fun logCurrentSignal() {
        val strength = _detectorSignalStrength.value
        val detected = _detectedMetalType.value
        val source = when {
            strength > 10f && _usePhysicalSensor.value -> DetectionSource.MAGNETOMETER
            strength > 10f && detected != null -> DetectionSource.SIMULATED
            else -> DetectionSource.MANUAL
        }
        val signal = TargetSignal(
            gridX = _sweepX.value,
            gridY = _sweepY.value,
            metalType = detected?.takeIf { strength > 10f } ?: MetalType.MANUAL_MARKER,
            signalStrength = strength.takeIf { strength > 10f } ?: 0f,
            depthCm = _detectedDepthCm.value.takeIf { source == DetectionSource.SIMULATED },
            latitude = _currentLat.value,
            longitude = _currentLon.value,
            source = source,
        )
        viewModelScope.launch { signalDao.upsert(signal.toEntity()) }
        if (_vibrationEnabled.value) triggerVibe(if (source == DetectionSource.MANUAL) 50 else 100)
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

    private fun startDetectorLoop() {
        detectorJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                var strength = 0f
                var type: MetalType? = null
                var depth: Int? = null
                if (_usePhysicalSensor.value) {
                    val deviation = abs(
                        magnetometerMonitor.magneticFieldStrength.value -
                            magnetometerMonitor.ambientBaseline.value,
                    )
                    strength = ((deviation / 25f) * 100f).coerceIn(0f, 100f)
                    if (strength >= 6f) type = MetalType.MAGNETIC_ANOMALY
                } else if (_isSweeping.value) {
                    val targets = simulatedTargets.getOrNull(_currentSiteIndex.value).orEmpty()
                    var bestStrength = 0f
                    for (target in targets) {
                        val dx = _sweepX.value - target.x
                        val dy = _sweepY.value - target.y
                        val distance = sqrt(dx * dx + dy * dy)
                        if (distance < 8.5f) {
                            val ratio = (1f - distance / 8.5f).coerceIn(0f, 1f)
                            val candidate = ratio * ratio * target.baseStrength
                            if (candidate > bestStrength) {
                                bestStrength = candidate
                                type = target.type
                                depth = target.depth
                            }
                        }
                    }
                    strength = bestStrength
                }

                _detectorSignalStrength.value = strength
                _detectedMetalType.value = type
                _detectedDepthCm.value = depth
                if (strength > 10f) {
                    val duration = (30 + strength * 0.8f).toInt().coerceIn(20, 120)
                    withContext(Dispatchers.Main.immediate) {
                        if (_vibrationEnabled.value) triggerVibe((strength * 0.3f).toLong().coerceAtLeast(10))
                        if (_audioPingEnabled.value) {
                            try { toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, duration) } catch (_: RuntimeException) { }
                        }
                    }
                    delay((350 - strength * 2.8f).toLong().coerceIn(60, 400))
                } else {
                    delay(150)
                }
            }
        }
    }

    private fun triggerVibe(milliseconds: Long) {
        try {
            val duration = milliseconds.coerceIn(1, 200)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (_: RuntimeException) { }
    }

    override fun onCleared() {
        magnetometerMonitor.stopListening()
        detectorJob?.cancel()
        renderJob?.cancel()
        try { toneGenerator?.release() } catch (_: RuntimeException) { }
        super.onCleared()
    }

    private data class SimulatedTarget(
        val x: Float,
        val y: Float,
        val type: MetalType,
        val baseStrength: Float,
        val depth: Int,
    )
}
