package com.example.thermals

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.TerrainAiGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StandFinderState(
    val running: Boolean = false,
    val stage: String? = null,
    val error: String? = null,
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val candidates: List<StandCandidate> = emptyList(),
    val picks: List<StandPick> = emptyList(),
    val source: String? = null,
    val selectedRank: Int? = null,
) {
    val selected: StandPick? get() = picks.firstOrNull { it.rank == selectedRank }
}

class StandFinderViewModel(application: Application) : AndroidViewModel(application) {
    private val service = StandFinderService(application)
    private val _state = MutableStateFlow(StandFinderState())
    val state: StateFlow<StandFinderState> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * Reads the terrain around ([lat], [lon]), shows on-device picks right away, then replaces them
     * with the AI's picks when (and if) a cloud provider answers.
     */
    fun find(lat: Double, lon: Double, inputs: ThermalInputs?, forecast: ThermalForecast?, locationLabel: String, dateLabel: String) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update {
                StandFinderState(running = true, stage = "Reading the terrain…", centerLat = lat, centerLon = lon)
            }
            try {
                val imageJob = async { service.fetchSatellite(lat, lon) }
                val grid = service.fetchGrid(lat, lon)
                val field = inputs?.let { ThermalWindEngine.flowField(it.copy(latitude = lat, longitude = lon)) }
                val prevailingFrom = forecast?.hour(8)?.let { ThermalWindEngine.normalize(it.windTowardDegrees + 180.0) }
                val candidates = StandFinder.findCandidates(grid, field, prevailingFrom)
                val local = StandFinder.localPicks(candidates)
                _state.update {
                    it.copy(
                        candidates = candidates,
                        picks = local,
                        source = "On-device",
                        stage = if (TerrainAiGateway.preferredProvider(getApplication()) != null) "Asking AI to pick the best spots…" else null,
                        running = TerrainAiGateway.preferredProvider(getApplication()) != null,
                        error = if (candidates.isEmpty()) "No stand-worthy terrain found here — try a hillier spot." else null,
                    )
                }
                if (TerrainAiGateway.preferredProvider(getApplication()) == null) return@launch

                val image = imageJob.await()
                val (picks, source) = service.aiPicks(
                    candidates = candidates,
                    forecast = forecast,
                    image = image,
                    centerLat = lat,
                    centerLon = lon,
                    locationLabel = locationLabel,
                    dateLabel = dateLabel,
                    onStage = { stage -> _state.update { it.copy(stage = stage) } },
                )
                _state.update { it.copy(picks = picks, source = source, running = false, stage = null, selectedRank = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        stage = null,
                        error = (error.localizedMessage ?: "Stand search failed") +
                            if (it.picks.isNotEmpty()) " — showing on-device picks." else "",
                    )
                }
            }
        }
    }

    fun select(rank: Int?) = _state.update { it.copy(selectedRank = rank) }
}
