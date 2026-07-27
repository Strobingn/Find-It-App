package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.TerrainPerformanceSession
import com.example.ui.FindItAppRootWired
import com.example.ui.HillshadeViewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collect

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen()
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme(darkTheme = true) {
        val vm: HillshadeViewModel = viewModel()

        // Large LAZ files open with a fast, uniformly distributed preview. When the lossless
        // all-return pass finishes, replace that preview only if the same source is still active
        // and the user has not already opened a more detailed viewport.
        LaunchedEffect(vm) {
          TerrainPerformanceSession.exactTerrainUpdates.collect { update ->
            val sameSourceIsActive = vm.activeTerrainKey.value == "lidar:${update.source.uri}"
            val viewportStillUsesOverview =
              !vm.isDetailedTerrain.value && !vm.isRefiningTerrain.value
            if (sameSourceIsActive && viewportStillUsesOverview) {
              TerrainPerformanceSession.publish(update.gpuScene)
              vm.setCustomTerrain(update.terrain, update.source)
            }
          }
        }

        FindItAppRootWired(viewModel = vm)
      }
    }
  }
}
