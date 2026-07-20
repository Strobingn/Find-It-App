package com.strobingn.findit.ui.ar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.power.BatteryOptimizer

/**
 * AR camera overlay shell (roadmap #5).
 * CameraX + ARCore grounding will attach here; UI shell is live now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArOverlayScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val settings by repository.settings.collectAsStateWithLifecycle()
  val fps = BatteryOptimizer.arTargetFps(settings)
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("AR ground overlay") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Box(
      Modifier
        .padding(padding)
        .fillMaxSize()
        .background(
          Brush.verticalGradient(listOf(Color(0xFF1B2A1B), Color(0xFF0D1A0D))),
        ),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(
          Icons.Default.CameraAlt,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.height(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("AR preview shell", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
          "Next: CameraX feed + project terrain disturbance / grid cells onto the ground plane " +
            "while you swing. Target $fps fps" +
            if (settings.lowPowerMode) " (low power)." else ".",
          style = MaterialTheme.typography.bodyMedium,
          color = Color.White.copy(alpha = 0.8f),
        )
      }
    }
  }
}
