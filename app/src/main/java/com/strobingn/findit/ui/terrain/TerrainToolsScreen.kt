package com.strobingn.findit.ui.terrain

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.power.BatteryOptimizer
import com.strobingn.findit.data.terrain.TerrainAnalyzer
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class TerrainMode { HILLSHADE, SVF, OPENNESS, DISTURBANCE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerrainToolsScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val settings by repository.settings.collectAsStateWithLifecycle()
  var mode by remember { mutableStateOf(TerrainMode.DISTURBANCE) }
  val side = BatteryOptimizer.maxTerrainGridSide(settings)
  val maps =
    remember(side) {
      val dem = TerrainAnalyzer.demoDem(side, side)
      TerrainAnalyzer.analyze(dem, side, side)
    }
  val bitmap =
    remember(maps, mode) {
      val data =
        when (mode) {
          TerrainMode.HILLSHADE -> maps.hillshade
          TerrainMode.SVF -> maps.skyViewFactor
          TerrainMode.OPENNESS -> maps.openness
          TerrainMode.DISTURBANCE -> maps.disturbance
        }
      rasterToBitmap(data, maps.width, maps.height)
    }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Terrain tools") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier
        .padding(padding)
        .padding(16.dp)
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        "Sky View Factor, Openness, multi-style hillshade, and auto ground-disturbance " +
          "highlight pits/mounds better than plain basemap shading. Runs fully offline on a local DEM.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerrainMode.entries.forEach { m ->
          FilterChip(
            selected = mode == m,
            onClick = { mode = m },
            label = { Text(m.name.lowercase().replaceFirstChar { it.titlecase() }) },
          )
        }
      }
      Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = mode.name,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
      )
      Text(
        when (mode) {
          TerrainMode.HILLSHADE -> "Hillshade — rotate light later for multi-style relief."
          TerrainMode.SVF -> "Low SVF (dark) = depressions: privies, cellars, hollows."
          TerrainMode.OPENNESS -> "Openness — banks, berms, and free sky ridges pop."
          TerrainMode.DISTURBANCE -> "Disturbance — residual highs/lows vs local mean (foundations)."
        },
        style = MaterialTheme.typography.bodySmall,
      )
      if (settings.lowPowerMode) {
        Text(
          "Low power: ${side}×${side} DEM · refresh ${BatteryOptimizer.refreshIntervalMs(settings)} ms",
          color = MaterialTheme.colorScheme.tertiary,
        )
      }
    }
  }
}

private fun rasterToBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
  val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val pixels = IntArray(data.size)
  for (i in data.indices) {
    val v = (data[i].coerceIn(0f, 1f) * 255).toInt()
    // warm metal-detector palette: dark pits → bright mounds
    val r = (40 + v * 0.85).toInt().coerceIn(0, 255)
    val g = (30 + v * 0.7).toInt().coerceIn(0, 255)
    val b = (20 + v * 0.35).toInt().coerceIn(0, 255)
    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }
  bmp.setPixels(pixels, 0, width, 0, 0, width, height)
  return bmp
}
