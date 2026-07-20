package com.strobingn.findit

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.ui.ar.ArOverlayScreen
import com.strobingn.findit.ui.export.ExportScreen
import com.strobingn.findit.ui.finds.FindDetailScreen
import com.strobingn.findit.ui.finds.FindListScreen
import com.strobingn.findit.ui.finds.LogFindScreen
import com.strobingn.findit.ui.grid.GridPlannerScreen
import com.strobingn.findit.ui.historical.HistoricalMapsScreen
import com.strobingn.findit.ui.home.HomeScreen
import com.strobingn.findit.ui.settings.SettingsScreen
import com.strobingn.findit.ui.team.TeamVisibilityScreen
import com.strobingn.findit.ui.terrain.TerrainToolsScreen

@Composable
fun MainNavigation() {
  val context = LocalContext.current
  val app = context.applicationContext as FindItApp
  val repository: HuntRepository = app.repository
  val backStack = rememberNavBackStack(Home)

  fun pop() {
    backStack.removeLastOrNull()
  }

  NavDisplay(
    backStack = backStack,
    onBack = { pop() },
    entryProvider =
      entryProvider {
        entry<Home> {
          HomeScreen(
            repository = repository,
            onNavigate = { key -> backStack.add(key) },
          )
        }
        entry<Finds> {
          FindListScreen(
            repository = repository,
            onBack = { pop() },
            onOpen = { id -> backStack.add(FindDetail(id)) },
            onLog = { backStack.add(LogFind) },
          )
        }
        entry<FindDetail> { key ->
          FindDetailScreen(
            repository = repository,
            findId = key.findId,
            onBack = { pop() },
          )
        }
        entry<LogFind> {
          LogFindScreen(
            repository = repository,
            onBack = { pop() },
            onSaved = { pop() },
          )
        }
        entry<GridPlanner> {
          GridPlannerScreen(repository = repository, onBack = { pop() })
        }
        entry<TerrainTools> {
          TerrainToolsScreen(repository = repository, onBack = { pop() })
        }
        entry<HistoricalMaps> {
          HistoricalMapsScreen(repository = repository, onBack = { pop() })
        }
        entry<TeamVisibility> {
          TeamVisibilityScreen(repository = repository, onBack = { pop() })
        }
        entry<ArOverlay> {
          ArOverlayScreen(repository = repository, onBack = { pop() })
        }
        entry<Export> {
          ExportScreen(repository = repository, onBack = { pop() })
        }
        entry<Settings> {
          SettingsScreen(repository = repository, onBack = { pop() })
        }
      },
  )
}
