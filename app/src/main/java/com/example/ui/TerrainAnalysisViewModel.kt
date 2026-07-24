package com.example.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.analysis.AiTerrainInterpreter
import com.example.analysis.AnalysisPalette
import com.example.analysis.TerrainAnalysisEngine
import com.example.analysis.TerrainAnalysisLayer
import com.example.analysis.TerrainAnalysisOptions
import com.example.analysis.TerrainAnalysisRenderer
import com.example.analysis.TerrainAnalysisType
import com.example.analysis.TerrainRenderOptions
import com.example.data.ElevationGrid
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerrainAnalysisViewModel(application: Application) : AndroidViewModel(application) {
    private val aiInterpreter = AiTerrainInterpreter()

    private val _selectedType = MutableStateFlow(TerrainAnalysisType.MULTI_HILLSHADE)
    val selectedType: StateFlow<TerrainAnalysisType> = _selectedType.asStateFlow()

    private val _options = MutableStateFlow(TerrainAnalysisOptions())
    val options: StateFlow<TerrainAnalysisOptions> = _options.asStateFlow()

    private val _renderOptions = MutableStateFlow(TerrainRenderOptions())
    val renderOptions: StateFlow<TerrainRenderOptions> = _renderOptions.asStateFlow()

    private val _layer = MutableStateFlow<TerrainAnalysisLayer?>(null)
    val layer: StateFlow<TerrainAnalysisLayer?> = _layer.asStateFlow()

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    val bitmap: StateFlow<Bitmap?> = _bitmap.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _status = MutableStateFlow("Choose a Phase 1 layer, then run it on the active terrain.")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _cacheEntryCount = MutableStateFlow(0)
    val cacheEntryCount: StateFlow<Int> = _cacheEntryCount.asStateFlow()

    private val _lastResultWasCached = MutableStateFlow(false)
    val lastResultWasCached: StateFlow<Boolean> = _lastResultWasCached.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _aiInterpretation = MutableStateFlow<String?>(null)
    val aiInterpretation: StateFlow<String?> = _aiInterpretation.asStateFlow()

    private val _isAiRunning = MutableStateFlow(false)
    val isAiRunning: StateFlow<Boolean> = _isAiRunning.asStateFlow()

    private var analysisJob: Job? = null
    private var renderJob: Job? = null
    private var aiJob: Job? = null
    private var exportJob: Job? = null
    private var requestGeneration = 0L

    private data class CacheKey(
        val gridIdentity: Int,
        val width: Int,
        val height: Int,
        val cellSizeBits: Int,
        val type: TerrainAnalysisType,
        val options: TerrainAnalysisOptions,
        val renderOptions: TerrainRenderOptions,
    )

    private data class CachedResult(
        val layer: TerrainAnalysisLayer,
        val bitmap: Bitmap,
    )

    private val resultCache = object : LinkedHashMap<CacheKey, CachedResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedResult>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    fun selectType(type: TerrainAnalysisType) {
        if (type !in TerrainAnalysisType.phaseOneEntries || _selectedType.value == type) return
        analysisJob?.cancel()
        requestGeneration++
        _isRunning.value = false
        _selectedType.value = type
        _aiInterpretation.value = null
        _exportStatus.value = null
        _lastResultWasCached.value = false
        _status.value = "${type.title} selected. Run analysis to calculate this layer."
    }

    fun updateLocalRadius(value: Float) {
        _options.value = _options.value.copy(localRadiusMeters = value.coerceIn(1f, 100f))
        _lastResultWasCached.value = false
    }

    fun updateHorizonRadius(value: Float) {
        _options.value = _options.value.copy(horizonRadiusMeters = value.coerceIn(5f, 250f))
        _lastResultWasCached.value = false
    }

    fun updateDirectionCount(value: Int) {
        _options.value = _options.value.copy(directionCount = value.coerceIn(8, 24))
        _lastResultWasCached.value = false
    }

    fun updateErosionIterations(value: Int) {
        _options.value = _options.value.copy(erosionIterations = value.coerceIn(1, 100))
    }

    fun updateRainfallFactor(value: Float) {
        _options.value = _options.value.copy(rainfallFactor = value.coerceIn(0.1f, 5f))
    }

    fun updateAnalysisPalette(palette: AnalysisPalette) {
        if (_renderOptions.value.palette == palette) return
        _renderOptions.value = _renderOptions.value.copy(palette = palette)
        rerenderCurrentLayer()
    }

    fun updateAnalysisContrast(value: Float) {
        val contrast = value.coerceIn(0.5f, 3f)
        if (_renderOptions.value.contrast == contrast) return
        _renderOptions.value = _renderOptions.value.copy(contrast = contrast)
        rerenderCurrentLayer()
    }

    fun updateAnalysisBrightness(value: Float) {
        val brightness = value.coerceIn(0.5f, 1.75f)
        if (_renderOptions.value.brightness == brightness) return
        _renderOptions.value = _renderOptions.value.copy(brightness = brightness)
        rerenderCurrentLayer()
    }

    fun updateAnalysisOpacity(value: Float) {
        val opacity = value.coerceIn(0.1f, 1f)
        if (_renderOptions.value.opacity == opacity) return
        _renderOptions.value = _renderOptions.value.copy(opacity = opacity)
        rerenderCurrentLayer()
    }

    fun setAnalysisPaletteInverted(inverted: Boolean) {
        if (_renderOptions.value.inverted == inverted) return
        _renderOptions.value = _renderOptions.value.copy(inverted = inverted)
        rerenderCurrentLayer()
    }

    private fun rerenderCurrentLayer() {
        val currentLayer = _layer.value ?: return
        val settings = _renderOptions.value.sanitized()
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            _status.value = "Updating ${currentLayer.type.title} visualization…"
            val rendered = withContext(Dispatchers.Default) {
                TerrainAnalysisRenderer.render(currentLayer, settings)
            }
            if (_layer.value === currentLayer && _renderOptions.value.sanitized() == settings) {
                _bitmap.value = rendered
                _lastResultWasCached.value = false
                _status.value = "${currentLayer.summary} Visualization updated without recalculating terrain."
            }
        }
    }

    fun runAnalysis(grid: ElevationGrid) {
        analysisJob?.cancel()
        renderJob?.cancel()
        aiJob?.cancel()
        _aiInterpretation.value = null
        _exportStatus.value = null

        val type = _selectedType.value
        val normalizedOptions = _options.value.normalized(grid.cellSizeMeters)
        val currentRenderOptions = _renderOptions.value.sanitized()
        val cacheKey = CacheKey(
            gridIdentity = System.identityHashCode(grid),
            width = grid.width,
            height = grid.height,
            cellSizeBits = grid.cellSizeMeters.toBits(),
            type = type,
            options = normalizedOptions,
            renderOptions = currentRenderOptions,
        )
        val generation = ++requestGeneration

        synchronized(resultCache) { resultCache[cacheKey] }?.let { cached ->
            _layer.value = cached.layer
            _bitmap.value = cached.bitmap
            _lastResultWasCached.value = true
            _status.value = "${cached.layer.summary} Loaded instantly from analysis cache."
            return
        }

        analysisJob = viewModelScope.launch {
            _isRunning.value = true
            _lastResultWasCached.value = false
            _status.value = "Calculating ${type.title} locally…"
            try {
                val result = withContext(Dispatchers.Default) {
                    TerrainAnalysisEngine.analyze(grid, type, normalizedOptions)
                }
                val rendered = withContext(Dispatchers.Default) {
                    TerrainAnalysisRenderer.render(result, currentRenderOptions)
                }
                if (generation != requestGeneration || _selectedType.value != type) return@launch

                synchronized(resultCache) {
                    resultCache[cacheKey] = CachedResult(result, rendered)
                    _cacheEntryCount.value = resultCache.size
                }
                _layer.value = result
                _bitmap.value = rendered
                _status.value = result.summary
            } catch (cancelled: CancellationException) {
                if (generation == requestGeneration) _status.value = "${type.title} calculation cancelled."
                throw cancelled
            } catch (error: Exception) {
                if (generation == requestGeneration) {
                    _status.value = error.message ?: "Terrain analysis failed."
                }
            } finally {
                if (generation == requestGeneration) _isRunning.value = false
            }
        }
    }

    fun cancelAnalysis() {
        requestGeneration++
        analysisJob?.cancel()
        analysisJob = null
        _isRunning.value = false
        _status.value = "Analysis cancelled."
    }

    fun clearCache() {
        synchronized(resultCache) {
            resultCache.clear()
            _cacheEntryCount.value = 0
        }
        _lastResultWasCached.value = false
        _status.value = "Analysis cache cleared."
    }

    fun exportCurrentPng() {
        val currentLayer = _layer.value
        val currentBitmap = _bitmap.value
        if (currentLayer == null || currentBitmap == null) {
            _exportStatus.value = "Run an analysis before exporting."
            return
        }
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _isExporting.value = true
            _exportStatus.value = "Saving ${currentLayer.type.title} PNG…"
            try {
                val fileName = buildString {
                    append("find-it-")
                    append(currentLayer.type.name.lowercase(Locale.US).replace('_', '-'))
                    append('-')
                    append(System.currentTimeMillis())
                    append(".png")
                }
                withContext(Dispatchers.IO) { saveBitmapToPictures(currentBitmap, fileName) }
                _exportStatus.value = "Saved $fileName to Pictures/Find It."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _exportStatus.value = "PNG export failed: ${error.message ?: "unknown error"}"
            } finally {
                _isExporting.value = false
            }
        }
    }

    private fun saveBitmapToPictures(bitmap: Bitmap, fileName: String) {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Find It")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val uri = checkNotNull(resolver.insert(collection, values)) {
            "Android could not create the export file."
        }
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Bitmap compression failed."
                }
            } ?: error("Android could not open the export file.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    fun requestAiInterpretation(terrainSummary: String) {
        val currentLayer = _layer.value ?: run {
            _status.value = "Run a terrain analysis before requesting AI interpretation."
            return
        }
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            _isAiRunning.value = true
            _aiInterpretation.value = null
            try {
                aiInterpreter.interpret(
                    layer = currentLayer,
                    terrainSummary = terrainSummary,
                ).onSuccess { interpretation ->
                    _aiInterpretation.value = interpretation
                }.onFailure { error ->
                    _aiInterpretation.value = "AI interpretation unavailable: ${error.message ?: "Unknown error"}"
                }
            } finally {
                _isAiRunning.value = false
            }
        }
    }

    override fun onCleared() {
        analysisJob?.cancel()
        renderJob?.cancel()
        aiJob?.cancel()
        exportJob?.cancel()
        synchronized(resultCache) { resultCache.clear() }
        super.onCleared()
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 12
    }
}
