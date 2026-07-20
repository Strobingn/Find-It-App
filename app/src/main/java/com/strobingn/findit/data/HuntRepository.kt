package com.strobingn.findit.data

import android.content.Context
import com.strobingn.findit.data.historical.HistoricalOverlayCatalog
import com.strobingn.findit.data.model.AppSettings
import com.strobingn.findit.data.model.FindRecord
import com.strobingn.findit.data.model.GeoPoint
import com.strobingn.findit.data.model.MetalType
import com.strobingn.findit.data.model.OverlayLayer
import com.strobingn.findit.data.model.SearchGrid
import com.strobingn.findit.data.model.TeamMember
import com.strobingn.findit.data.offline.OfflineCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Offline-first hunt data store (finds, grids, team, overlays, settings).
 */
class HuntRepository(context: Context) {
  private val cache = OfflineCache(context)
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
  private val mutex = Mutex()

  private val _finds = MutableStateFlow<List<FindRecord>>(emptyList())
  private val _grids = MutableStateFlow<List<SearchGrid>>(emptyList())
  private val _team = MutableStateFlow<List<TeamMember>>(emptyList())
  private val _overlays = MutableStateFlow(HistoricalOverlayCatalog.defaultLayers)
  private val _settings = MutableStateFlow(AppSettings())

  val finds: StateFlow<List<FindRecord>> = _finds.asStateFlow()
  val grids: StateFlow<List<SearchGrid>> = _grids.asStateFlow()
  val team: StateFlow<List<TeamMember>> = _team.asStateFlow()
  val overlays: StateFlow<List<OverlayLayer>> = _overlays.asStateFlow()
  val settings: StateFlow<AppSettings> = _settings.asStateFlow()
  val offlineCache: OfflineCache get() = cache

  suspend fun load() =
    mutex.withLock {
      _finds.value = readList(cache.findsFile, FindRecord.serializer())
      _grids.value = readList(cache.gridsFile, SearchGrid.serializer())
      _team.value = readList(cache.teamFile, TeamMember.serializer())
      cache.readTextOrNull(cache.settingsFile)?.let {
        runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull()?.let { s ->
          _settings.value = s
        }
      }
      if (_finds.value.isEmpty()) seedDemo()
    }

  private fun seedDemo() {
    val origin = GeoPoint(39.8283, -98.5795) // geographic center US demo
    _finds.value =
      listOf(
        FindRecord(
          title = "Seated Liberty half",
          metalType = MetalType.SILVER,
          depthInches = 6.0,
          notes = "Near old fence line",
          location = GeoPoint(origin.lat + 0.0003, origin.lng - 0.0002),
        ),
        FindRecord(
          title = "Square nail",
          metalType = MetalType.IRON_JUNK,
          depthInches = 3.0,
          notes = "Homestead scatter",
          location = GeoPoint(origin.lat - 0.0002, origin.lng + 0.0004),
        ),
      )
    _grids.value =
      listOf(
        SearchGrid(
          name = "North pasture grid",
          sw = GeoPoint(origin.lat - 0.001, origin.lng - 0.001),
          ne = GeoPoint(origin.lat + 0.001, origin.lng + 0.001),
          cellSizeMeters = 10.0,
        ),
      )
    _team.value =
      listOf(
        TeamMember(name = "You", location = origin),
        TeamMember(
          name = "Partner",
          location = GeoPoint(origin.lat + 0.0008, origin.lng + 0.0005),
        ),
      )
  }

  suspend fun upsertFind(record: FindRecord) =
    mutex.withLock {
      _finds.update { list ->
        val without = list.filterNot { it.id == record.id }
        (without + record).sortedByDescending { it.detectedAtEpochMs }
      }
      persistFinds()
    }

  suspend fun deleteFind(id: String) =
    mutex.withLock {
      _finds.update { it.filterNot { f -> f.id == id } }
      persistFinds()
    }

  suspend fun upsertGrid(grid: SearchGrid) =
    mutex.withLock {
      _grids.update { list -> list.filterNot { it.id == grid.id } + grid }
      persistGrids()
    }

  suspend fun markGridCell(gridId: String, cellId: String, covered: Boolean = true) =
    mutex.withLock {
      _grids.update { list ->
        list.map { g ->
          if (g.id != gridId) g
          else {
            val cells = g.coveredCellIds.toMutableSet()
            if (covered) cells.add(cellId) else cells.remove(cellId)
            g.copy(coveredCellIds = cells)
          }
        }
      }
      persistGrids()
    }

  suspend fun upsertTeamMember(member: TeamMember) =
    mutex.withLock {
      _team.update { list -> list.filterNot { it.id == member.id } + member }
      persistTeam()
    }

  suspend fun setOverlayEnabled(id: String, enabled: Boolean) {
    _overlays.update { layers -> layers.map { if (it.id == id) it.copy(enabled = enabled) else it } }
  }

  suspend fun updateSettings(transform: (AppSettings) -> AppSettings) =
    mutex.withLock {
      _settings.update(transform)
      cache.writeText(cache.settingsFile, json.encodeToString(AppSettings.serializer(), _settings.value))
    }

  private fun persistFinds() {
    cache.writeText(
      cache.findsFile,
      json.encodeToString(ListSerializer(FindRecord.serializer()), _finds.value),
    )
  }

  private fun persistGrids() {
    cache.writeText(
      cache.gridsFile,
      json.encodeToString(ListSerializer(SearchGrid.serializer()), _grids.value),
    )
  }

  private fun persistTeam() {
    cache.writeText(
      cache.teamFile,
      json.encodeToString(ListSerializer(TeamMember.serializer()), _team.value),
    )
  }

  private fun <T> readList(
    file: java.io.File,
    serializer: kotlinx.serialization.KSerializer<T>,
  ): List<T> {
    val text = cache.readTextOrNull(file) ?: return emptyList()
    return runCatching {
        json.decodeFromString(ListSerializer(serializer), text)
      }
      .getOrDefault(emptyList())
  }

  companion object {
    @Volatile private var instance: HuntRepository? = null

    fun get(context: Context): HuntRepository {
      return instance
        ?: synchronized(this) {
          instance
            ?: HuntRepository(context.applicationContext).also { instance = it }
        }
    }
  }
}
