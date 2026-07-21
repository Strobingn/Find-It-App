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
import com.example.data.ElevationGrid
import com.example.data.MetalType
import com.example.data.TargetSignal
import com.example.sensor.MagnetometerMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class HillshadeViewModel(application: Application) : AndroidViewModel(application) {

    // 1. Lidar Terrain & Rendering States
    private val _currentSiteIndex = MutableStateFlow(0) // 0 = Homestead, 1 = Fort, 2 = Villa, 3 = Custom
    val currentSiteIndex: StateFlow<Int> = _currentSiteIndex.asStateFlow()

    private val _elevationGrid = MutableStateFlow<ElevationGrid>(DemGenerator.generateSite(0))
    val elevationGrid: StateFlow<ElevationGrid> = _elevationGrid.asStateFlow()

    private val _sunAzimuth = MutableStateFlow(315f) // Sun in North-West by default casts beautiful shadows
    val sunAzimuth: StateFlow<Float> = _sunAzimuth.asStateFlow()

    private val _sunAltitude = MutableStateFlow(35f) // Mid-low sun casts longer, easier-to-see shadows
    val sunAltitude: StateFlow<Float> = _sunAltitude.asStateFlow()

    private val _vegetationFilter = MutableStateFlow(0.8f) // 80% filtered by default
    val vegetationFilter: StateFlow<Float> = _vegetationFilter.asStateFlow()

    private val _paletteType = MutableStateFlow(1) // 0 = Clay, 1 = Copper, 2 = Terra
    val paletteType: StateFlow<Int> = _paletteType.asStateFlow()

    private val _contrast = MutableStateFlow(1.5f)
    val contrast: StateFlow<Float> = _contrast.asStateFlow()

    private val _hillshadeBitmap = MutableStateFlow<Bitmap?>(null)
    val hillshadeBitmap: StateFlow<Bitmap?> = _hillshadeBitmap.asStateFlow()

    private val _isRendering = MutableStateFlow(false)
    val isRendering: StateFlow<Boolean> = _isRendering.asStateFlow()

    private val _visualizationMode = MutableStateFlow(0) // 0 = Standard, 1 = Multi-Directional, 2 = Slope Map
    val visualizationMode: StateFlow<Int> = _visualizationMode.asStateFlow()

    private val _overlayType = MutableStateFlow(0) // 0 = None, 1 = 1880s Plat, 2 = 1940s Contours
    val overlayType: StateFlow<Int> = _overlayType.asStateFlow()

    private val _overlayOpacity = MutableStateFlow(0.4f)
    val overlayOpacity: StateFlow<Float> = _overlayOpacity.asStateFlow()

    private val _gridSpacing = MutableStateFlow(0f) // 0 = disabled, else grid cell %
    val gridSpacing: StateFlow<Float> = _gridSpacing.asStateFlow()

    private val _zScale = MutableStateFlow(1.0f) // 1.0 = normal, 0.5 to 4.0 vertical exaggeration
    val zScale: StateFlow<Float> = _zScale.asStateFlow()

    // 2. Simulated/Physical Metal Detecting Sweeper State
    private val _sweepX = MutableStateFlow(50f) // Current scan head coordinate (0 to 100)
    val sweepX: StateFlow<Float> = _sweepX.asStateFlow()

    private val _sweepY = MutableStateFlow(50f)
    val sweepY: StateFlow<Float> = _sweepY.asStateFlow()

    private val _isSweeping = MutableStateFlow(false)
    val isSweeping: StateFlow<Boolean> = _isSweeping.asStateFlow()

    // Logged/Marked Target Signals
    private val _loggedSignals = MutableStateFlow<List<TargetSignal>>(emptyList())
    val loggedSignals: StateFlow<List<TargetSignal>> = _loggedSignals.asStateFlow()

    // Geo-Spatial Layer states
    private val _activeGeoMetadata = MutableStateFlow(com.example.geospatial.GeoSpatialLibrary.SITES_METADATA[0])
    val activeGeoMetadata: StateFlow<com.example.geospatial.GeoSpatialLibrary.GeoSpatialMetadata> = _activeGeoMetadata.asStateFlow()

    private val _currentLat = MutableStateFlow(43.1205)
    val currentLat: StateFlow<Double> = _currentLat.asStateFlow()

    private val _currentLon = MutableStateFlow(-124.4082)
    val currentLon: StateFlow<Double> = _currentLon.asStateFlow()

    // 3. Sensor & Simulation Integrations
    private val magnetometerMonitor = MagnetometerMonitor(application)
    val isPhysicalSensorAvailable: StateFlow<Boolean> = magnetometerMonitor.isSensorAvailable

    private val _usePhysicalSensor = MutableStateFlow(false)
    val usePhysicalSensor: StateFlow<Boolean> = _usePhysicalSensor.asStateFlow()

    // Combines physical sensor values or simulation algorithms to get current detector strength (0-100)
    private val _detectorSignalStrength = MutableStateFlow(0f)
    val detectorSignalStrength: StateFlow<Float> = _detectorSignalStrength.asStateFlow()

    // The metal type currently under the scan head (if any)
    private val _detectedMetalType = MutableStateFlow<MetalType?>(null)
    val detectedMetalType: StateFlow<MetalType?> = _detectedMetalType.asStateFlow()

    // Audio / Haptic settings
    private val _audioPingEnabled = MutableStateFlow(true)
    val audioPingEnabled: StateFlow<Boolean> = _audioPingEnabled.asStateFlow()

    private val _vibrationEnabled = MutableStateFlow(true)
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    // Tone Generator for real-time audio pings
    private var toneGenerator: ToneGenerator? = null
    private var audioJob: Job? = null
    private val vibrator = application.getSystemService(Vibrator::class.java)
    private var renderJob: Job? = null
    private var renderGeneration = 0L

    // Preloaded "Hidden Target Treasures" for each site
    // Keeps track of where the metal objects are so we can trigger proximity alerts
    private val homesteadTargets = listOf(
        SimulatedTarget(25f, 65f, MetalType.GOLD, 95f, 12),     // Near Well
        SimulatedTarget(42f, 44f, MetalType.SILVER, 85f, 22),   // Cellar wall corner
        SimulatedTarget(45f, 39f, MetalType.BRONZE, 72f, 30),   // Chimney base
        SimulatedTarget(58f, 50f, MetalType.IRON, 60f, 8)       // Surrounding trash heap
    )

    private val fortTargets = listOf(
        SimulatedTarget(50f, 52f, MetalType.BRONZE, 90f, 38),   // Inside the gun circular parapet
        SimulatedTarget(48f, 35f, MetalType.SILVER, 78f, 18),   // Officer quarters corner
        SimulatedTarget(35f, 60f, MetalType.IRON, 92f, 10),     // Inside defensive trench
        SimulatedTarget(66f, 40f, MetalType.IRON, 55f, 25)      // Scattered canister shot
    )

    private val villaTargets = listOf(
        SimulatedTarget(42f, 45f, MetalType.GOLD, 98f, 16),     // Inside villa peristyle
        SimulatedTarget(68f, 30f, MetalType.BRONZE, 82f, 28),   // Round shrine base
        SimulatedTarget(22f, 35f, MetalType.SILVER, 88f, 10),   // Roman road side ditch
        SimulatedTarget(49f, 41f, MetalType.IRON, 50f, 35)      // Structural nail
    )

    init {
        // Safe creation of ToneGenerator
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sync rendering whenever options change
        viewModelScope.launch {
            _currentSiteIndex.collectLatest { renderCurrentSite() }
        }
        viewModelScope.launch { _sunAzimuth.collectLatest { triggerRender() } }
        viewModelScope.launch { _sunAltitude.collectLatest { triggerRender() } }
        viewModelScope.launch { _vegetationFilter.collectLatest { triggerRender() } }
        viewModelScope.launch { _paletteType.collectLatest { triggerRender() } }
        viewModelScope.launch { _contrast.collectLatest { triggerRender() } }
        viewModelScope.launch { _visualizationMode.collectLatest { triggerRender() } }
        viewModelScope.launch { _overlayType.collectLatest { triggerRender() } }
        viewModelScope.launch { _overlayOpacity.collectLatest { triggerRender() } }
        viewModelScope.launch { _zScale.collectLatest { triggerRender() } }

        // Core background sweep loop to handle sensor simulation & continuous sound pings
        startDetectorLoop()
    }

    private fun renderCurrentSite() {
        val siteIdx = _currentSiteIndex.value
        if (siteIdx in 0..2) {
            _elevationGrid.value = DemGenerator.generateSite(siteIdx)
            val meta = com.example.geospatial.GeoSpatialLibrary.SITES_METADATA[siteIdx]
            _activeGeoMetadata.value = meta
            val coords = com.example.geospatial.GeoSpatialLibrary.gridToGeographic(_sweepX.value, _sweepY.value, meta)
            _currentLat.value = coords.first
            _currentLon.value = coords.second
        }
        triggerRender()
    }

    private fun triggerRender() {
        val generation = ++renderGeneration
        renderJob?.cancel()
        _isRendering.value = true
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val grid = _elevationGrid.value
                val bmp = grid.renderHillshade(
                    sunAzimuth = _sunAzimuth.value,
                    sunAltitude = _sunAltitude.value,
                    vegetationFilter = _vegetationFilter.value,
                    palette = _paletteType.value,
                    contrast = _contrast.value,
                    visualizationMode = _visualizationMode.value,
                    overlayType = _overlayType.value,
                    overlayOpacity = _overlayOpacity.value,
                    zScale = _zScale.value
                )

                if (generation == renderGeneration) {
                    _hillshadeBitmap.value = bmp
                }
            } finally {
                if (generation == renderGeneration) {
                    _isRendering.value = false
                }
            }
        }
    }
    // Setters
    fun updateZScale(scale: Float) {
        _zScale.value = scale
    }

    fun selectSite(index: Int) {
        if (index in 0..3) {
            _currentSiteIndex.value = index
        }
    }

    fun updateVisualizationMode(mode: Int) {
        _visualizationMode.value = mode
    }

    fun updateOverlayType(type: Int) {
        _overlayType.value = type
    }

    fun updateOverlayOpacity(opacity: Float) {
        _overlayOpacity.value = opacity
    }

    fun updateGridSpacing(spacing: Float) {
        _gridSpacing.value = spacing
    }

    fun updateLoggedSignal(updatedSignal: TargetSignal) {
        _loggedSignals.value = _loggedSignals.value.map {
            if (it.id == updatedSignal.id) updatedSignal else it
        }
    }

    fun setCustomGrid(grid: ElevationGrid) {
        _elevationGrid.value = grid
        _currentSiteIndex.value = 3 // custom
        val meta = com.example.geospatial.GeoSpatialLibrary.SITES_METADATA[3]
        _activeGeoMetadata.value = meta
        val coords = com.example.geospatial.GeoSpatialLibrary.gridToGeographic(_sweepX.value, _sweepY.value, meta)
        _currentLat.value = coords.first
        _currentLon.value = coords.second
        triggerRender()
    }

    fun updateSunAzimuth(az: Float) {
        _sunAzimuth.value = az
    }

    fun updateSunAltitude(alt: Float) {
        _sunAltitude.value = alt
    }

    fun updateVegetationFilter(filter: Float) {
        _vegetationFilter.value = filter
    }

    fun updatePalette(palette: Int) {
        _paletteType.value = palette
    }

    fun updateContrast(ct: Float) {
        _contrast.value = ct
    }

    fun setSweepPosition(x: Float, y: Float) {
        val cx = x.coerceIn(0f, 100f)
        val cy = y.coerceIn(0f, 100f)
        _sweepX.value = cx
        _sweepY.value = cy
        _isSweeping.value = true
        
        val coords = com.example.geospatial.GeoSpatialLibrary.gridToGeographic(cx, cy, _activeGeoMetadata.value)
        _currentLat.value = coords.first
        _currentLon.value = coords.second
    }

    fun stopSweeping() {
        _isSweeping.value = false
        _detectorSignalStrength.value = 0f
        _detectedMetalType.value = null
    }

    fun togglePhysicalSensor(enabled: Boolean) {
        val shouldEnable = enabled && isPhysicalSensorAvailable.value
        _usePhysicalSensor.value = shouldEnable
        if (shouldEnable) {
            magnetometerMonitor.startListening()
        } else {
            magnetometerMonitor.stopListening()
            _detectorSignalStrength.value = 0f
            _detectedMetalType.value = null
        }
    }

    fun toggleAudioPing(enabled: Boolean) {
        _audioPingEnabled.value = enabled
    }

    fun toggleVibration(enabled: Boolean) {
        _vibrationEnabled.value = enabled
    }

    fun calibrateMagnetometer() {
        magnetometerMonitor.calibrateBaseline()
    }

    /**
     * Records/logs the metal target currently under the sweeper coil.
     */
    fun logCurrentSignal() {
        val signalStrength = _detectorSignalStrength.value
        val metal = _detectedMetalType.value

        if (signalStrength > 10f && metal != null) {
            // Strong metal signal detected! Log it
            val depth = (10 + (100f - signalStrength) * 0.4f).toInt().coerceIn(4, 50)
            val newSignal = TargetSignal(
                gridX = _sweepX.value,
                gridY = _sweepY.value,
                metalType = metal,
                signalStrength = signalStrength,
                depthCm = depth
            )
            _loggedSignals.value = _loggedSignals.value + newSignal
            triggerVibe(100) // sharp confirmation buzz
        } else {
            // Manual marker place: let the user place a generic marker
            val newSignal = TargetSignal(
                gridX = _sweepX.value,
                gridY = _sweepY.value,
                metalType = MetalType.GOLD,
                signalStrength = 100f,
                depthCm = 15
            )
            _loggedSignals.value = _loggedSignals.value + newSignal
            triggerVibe(50)
        }
    }

    fun deleteLoggedSignal(signal: TargetSignal) {
        _loggedSignals.value = _loggedSignals.value.filter { it.id != signal.id }
    }

    fun clearLoggedSignals() {
        _loggedSignals.value = emptyList()
    }

    /**
     * Loops continuously in the background to calculate target proximities,
     * updates the magnetometer dials, and makes high-speed metal detector beeping noises.
     */
    private fun startDetectorLoop() {
        audioJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                val usePhysical = _usePhysicalSensor.value
                val x = _sweepX.value
                val y = _sweepY.value
                val isSw = _isSweeping.value

                var strength = 0f
                var type: MetalType? = null

                if (usePhysical) {
                    // 1. PHYSICAL HARDWARE SENSOR CALCULATIONS
                    val rawStrength = magnetometerMonitor.magneticFieldStrength.value
                    val baseline = magnetometerMonitor.ambientBaseline.value
                    // Compute absolute deviation from baseline
                    val deviation = Math.abs(rawStrength - baseline)
                    
                    // Map a 1.0 uT to 40.0 uT deviation to a 0% to 100% signal strength
                    strength = ((deviation / 25f) * 100f).coerceIn(0f, 100f)
                    
                    // Guess metal type based on polarity:
                    // Large negative dip is usually ferrous/iron, high spike is usually precious metal
                    type = if (rawStrength < baseline - 2.5f) {
                        MetalType.IRON
                    } else if (deviation > 10f) {
                        MetalType.GOLD
                    } else if (deviation > 4f) {
                        MetalType.SILVER
                    } else if (deviation > 1.5f) {
                        MetalType.BRONZE
                    } else {
                        null
                    }
                } else if (isSw) {
                    // 2. SIMULATED GEOGRAPHIC TREASURE HUNTER
                    // Find the hidden targets for the active preloaded site
                    val targets = when (_currentSiteIndex.value) {
                        0 -> homesteadTargets
                        1 -> fortTargets
                        2 -> villaTargets
                        else -> emptyList()
                    }

                    var maxSimStrength = 0f
                    var matchedType: MetalType? = null

                    for (target in targets) {
                        val dx = x - target.x
                        val dy = y - target.y
                        val dist = sqrt(dx * dx + dy * dy)
                        val triggerRadius = 8.5f // Grid cells radius of coil sweep reach

                        if (dist < triggerRadius) {
                            // Quadratic decay: closer = exponentially stronger
                            val ratio = (1.0f - dist / triggerRadius).coerceIn(0f, 1f)
                            val simStrength = (ratio * ratio) * target.baseStrength
                            
                            if (simStrength > maxSimStrength) {
                                maxSimStrength = simStrength
                                matchedType = target.type
                            }
                        }
                    }

                    strength = maxSimStrength
                    type = matchedType
                }

                // Update flows
                _detectorSignalStrength.value = strength
                _detectedMetalType.value = type

                // 3. SOUND PING & HAPTIC LOOPS
                if (strength > 10f) {
                    // Vibrational alert when metal signal is hot
                    if (_vibrationEnabled.value) {
                        triggerVibe((strength * 0.3f).toLong().coerceAtLeast(10))
                    }

                    // Audio Ping Tone generator beep frequency.
                    // Stronger signal -> Higher pitch and faster pings!
                    if (_audioPingEnabled.value) {
                        val duration = (30 + (strength * 0.8f)).toInt() // 30ms to 110ms

                        // Play audio beep
                        try {
                            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, duration)
                        } catch (e: Exception) {
                            // safe fallback
                        }
                    }

                    // Speed of beeps increases as we get closer to the center of metal!
                    // Delay is short (high frequency) when strength is high, long when weak.
                    val sweepDelay = (350 - (strength * 2.8f)).toLong().coerceIn(40, 400)
                    delay(sweepDelay)
                } else {
                    // Idle ambient low hum / static rate of delay
                    delay(200)
                }
            }
        }
    }

    private fun triggerVibe(ms: Long) {
        val service = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                service.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                service.vibrate(ms)
            }
        } catch (_: SecurityException) {
            // Vibration is optional; continue without haptic feedback if the service rejects it.
        }
    }

    override fun onCleared() {
        super.onCleared()
        magnetometerMonitor.stopListening()
        audioJob?.cancel()
        renderJob?.cancel()
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private data class SimulatedTarget(
        val x: Float,
        val y: Float,
        val type: MetalType,
        val baseStrength: Float,
        val depth: Int
    )
}
