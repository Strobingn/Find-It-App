package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.CustomFileLoader
import com.example.ui.components.TargetLoggerPanel

private data class AppTab(val label: String, val icon: ImageVector)

/**
 * Three destinations, matching the redesign's nav model minus Assist — this build has no AI
 * analysis workspace to put behind that tab. Map merges what used to be the Terrain tab's canvas
 * and controls; Data is the old Import tab; Finds is the target list.
 */
private val tabs = listOf(
    AppTab("Map", Icons.Default.Layers),
    AppTab("Data", Icons.Default.Storage),
    AppTab("Finds", Icons.Default.Flag),
)

private const val TAB_MAP = 0
private const val TAB_DATA = 1
private const val TAB_FINDS = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: HillshadeViewModel, modifier: Modifier = Modifier) {
    val selectedTab = rememberSaveable { mutableIntStateOf(TAB_MAP) }
    val terrainFocusMode = rememberSaveable { mutableStateOf(false) }
    // Home (screen `1d`) fronts the app on launch; picking a destination — from its own "Open
    // workspace" button, a terrain source row, or the shared bottom nav below — drops into the
    // usual tabbed workspace and stays there for the rest of the session.
    val showHome = rememberSaveable { mutableStateOf(true) }
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (!terrainFocusMode.value) Column {
                HorizontalDivider(thickness = 1.dp, color = Color.White.copy(alpha = 0.08f))
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            // While Home is showing, no tab has truly been entered yet — Map reads
                            // as selected (matching the `1d` mockup) since it's where nav lands.
                            selected = if (showHome.value) index == TAB_MAP else selectedTab.intValue == index,
                            onClick = {
                                selectedTab.intValue = index
                                showHome.value = false
                            },
                            icon = {
                                if (index == TAB_FINDS && signals.isNotEmpty()) {
                                    BadgedBox(badge = { Badge { Text("${signals.size}") } }) {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = null)
                                }
                            },
                            label = {
                                Text(
                                    tab.label,
                                    fontWeight = if (selectedTab.intValue == index) {
                                        FontWeight.Medium
                                    } else {
                                        FontWeight.Normal
                                    },
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (showHome.value) {
            HomeScreen(
                viewModel = viewModel,
                onOpenWorkspace = {
                    selectedTab.intValue = TAB_MAP
                    showHome.value = false
                },
                modifier = Modifier.padding(padding),
            )
        } else {
            when (selectedTab.intValue) {
                TAB_MAP -> MapWorkspace(
                    viewModel = viewModel,
                    padding = padding,
                    focusMode = terrainFocusMode.value,
                    onFocusModeChanged = { terrainFocusMode.value = it },
                )

                TAB_FINDS -> FindsTab(viewModel, padding)

                else -> DataTab(viewModel, padding) { selectedTab.intValue = TAB_MAP }
            }
        }
    }
}

@Composable
private fun FindsTab(viewModel: HillshadeViewModel, padding: PaddingValues) {
    val signals by viewModel.loggedSignals.collectAsStateWithLifecycle()
    val x by viewModel.sweepX.collectAsStateWithLifecycle()
    val y by viewModel.sweepY.collectAsStateWithLifecycle()
    TargetLoggerPanel(
        loggedSignals = signals,
        currentSweepX = x,
        currentSweepY = y,
        onLogSignal = viewModel::logCurrentSignal,
        onDeleteSignal = viewModel::deleteLoggedSignal,
        onUpdateSignal = viewModel::updateLoggedSignal,
        onClearAll = viewModel::clearLoggedSignals,
        modifier = Modifier.fillMaxSize().padding(padding),
    )
}

@Composable
private fun DataTab(viewModel: HillshadeViewModel, padding: PaddingValues, onImported: () -> Unit) {
    val summary by viewModel.activeTerrainSummary.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                Text("Data", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            CustomFileLoader(
                onCustomTerrainLoaded = { result, source ->
                    viewModel.setCustomTerrain(result, source)
                    onImported()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
