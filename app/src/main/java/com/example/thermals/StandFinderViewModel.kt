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
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Elevation of the search square, kept so the map can draw terrain-aware wind. */
    val grid: ElevationGrid? = null,
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
            val imageJob = async { service.fetchSatellite(lat, lon) }
            val obstaclesJob = async { service.fetchObstacles(lat, lon) }
            val candidates: List<StandCandidate>
            val obstacles: MappedObstacles
            try {
                val grid = service.fetchGrid(lat, lon)
                _state.update { it.copy(grid = grid) }
                // Roads/houses are best-effort: don't let a slow Overpass hold up the picks.
                obstacles = withTimeoutOrNull(OBSTACLE_WAIT_MS) { obstaclesJob.await() } ?: run {
                    obstaclesJob.cancel()
                    MappedObstacles.NONE
                }
                val field = inputs?.let { ThermalWindEngine.flowField(it.copy(latitude = lat, longitude = lon)) }
                val prevailingFrom = forecast?.hour(8)?.let { ThermalWindEngine.normalize(it.windTowardDegrees + 180.0) }
                candidates = StandFinder.findCandidates(grid, field, prevailingFrom, obstacles = obstacles)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                imageJob.cancel()
                obstaclesJob.cancel()
                val message = error.localizedMessage ?: "Couldn't read the terrain"
                _state.update {
                    it.copy(running = false, stage = null, error = "Terrain: " + StandFinderService.explainNetworkFailure(message))
                }
                return@launch
            }

            val aiAvailable = TerrainAiGateway.preferredProvider(getApplication()) != null
            val local = StandFinder.localPicks(candidates)
            _state.update {
                it.copy(
                    candidates = candidates,
                    picks = local,
                    source = "On-device",
                    stage = if (aiAvailable) "Asking AI to pick the best spots…" else null,
                    running = aiAvailable,
                    error = if (candidates.isEmpty()) "No stand-worthy terrain found here — try a hillier spot." else null,
                )
            }
            if (!aiAvailable) {
                imageJob.cancel()
                return@launch
            }

            try {
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
                    obstacles = obstacles,
                )
                _state.update { it.copy(picks = picks, source = source, running = false, stage = null, selectedRank = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        running = false,
                        stage = null,
                        error = StandFinderService.explainNetworkFailure(error.localizedMessage ?: "AI pick failed") +
                            if (it.picks.isNotEmpty()) " — showing on-device picks." else "",
                    )
                }
            }
        }
    }

    fun select(rank: Int?) = _state.update { it.copy(selectedRank = rank) }

    private companion object {
        /** Extra wait for OpenStreetMap after the terrain is in (they're fetched in parallel). */
        const val OBSTACLE_WAIT_MS = 4_000L
    }
}
