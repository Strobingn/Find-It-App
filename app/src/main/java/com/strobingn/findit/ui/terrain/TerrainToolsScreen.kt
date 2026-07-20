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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strobingn.findit.data.HuntRepository
import com.strobingn.findit.data.backend.SharedBackendClient
import com.strobingn.findit.data.backend.toFloatRaster
import com.strobingn.findit.data.power.BatteryOptimizer
import com.strobingn.findit.data.terrain.TerrainAnalyzer

private enum class TerrainMode { HILLSHADE, SVF, OPENNESS, DISTURBANCE }

private data class RasterMaps(
  val hillshade: FloatArray,
  val skyViewFactor: FloatArray,
  val openness: FloatArray,
  val disturbance: FloatArray,
  val width: Int,
  val height: Int,
  val source: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerrainToolsScreen(
  repository: HuntRepository,
  onBack: () -> Unit,
) {
  val settings by repository.settings.collectAsStateWithLifecycle()
  val team by repository.team.collectAsStateWithLifecycle()
  var mode by remember { mutableStateOf(TerrainMode.DISTURBANCE) }
  var status by remember { mutableStateOf("Loading terrain…") }
  var maps by remember { mutableStateOf<RasterMaps?>(null) }

  val side = BatteryOptimizer.maxTerrainGridSide(settings)
  val center =
    team.find { it.name.equals("You", ignoreCase = true) }?.location
      ?: com.strobingn.findit.data.model.GeoPoint(41.503, -74.01)

  LaunchedEffect(settings.useCloudBackend, settings.backendUrl, settings.lowPowerMode, center.lat, center.lng) {
    val local: () -> RasterMaps = {
      val dem = TerrainAnalyzer.demoDem(side, side)
      val a = TerrainAnalyzer.analyze(dem, side, side)
      RasterMaps(
        hillshade = a.hillshade,
        skyViewFactor = a.skyViewFactor,
        openness = a.openness,
        disturbance = a.disturbance,
        width = a.width,
        height = a.height,
        source = "local-demo",
      )
    }
    if (!settings.useCloudBackend) {
      maps = local()
      status = "Local demo DEM (${side}×${side})"
      return@LaunchedEffect
    }
    status = "Cloud: ${settings.backendUrl}…"
    try {
      val client = SharedBackendClient(settings.backendUrl)
      val cell = if (settings.lowPowerMode) 30.0 else 20.0
      val half = if (settings.lowPowerMode) 400.0 else 600.0
      val remote =
        client.analyzeTerrain(
          centerLat = center.lat,
          centerLon = center.lng,
          halfSizeM = half,
          cellSizeM = cell,
          mode = "all",
        )
      val w = remote.width
      val h = remote.height
      val hs = remote.hillshade?.toFloatRaster() ?: FloatArray(w * h)
      val svf = remote.svf?.toFloatRaster() ?: FloatArray(w * h) { 1f }
      val dist = remote.disturbance?.toFloatRaster() ?: FloatArray(w * h) { 0.5f }
      // Openness not on backend yet — derive from SVF
      val open = FloatArray(svf.size) { i -> (svf[i] * 1.15f - 0.05f).coerceIn(0f, 1f) }
      maps =
        RasterMaps(
          hillshade = hs,
          skyViewFactor = svf,
          openness = open,
          disturbance = dist,
          width = w,
          height = h,
          source = remote.source,
        )
      status = "Cloud terrain · ${remote.source} · ${w}×${h} @ ${"%.4f".format(center.lat)}, ${"%.4f".format(center.lng)}"
    } catch (e: Exception) {
      maps = local()
      status = "Cloud failed (${e.message?.take(40)}) — local demo"
    }
  }

  val bitmap =
    remember(maps, mode) {
      val m = maps ?: return@remember null
      val data =
        when (mode) {
          TerrainMode.HILLSHADE -> m.hillshade
          TerrainMode.SVF -> m.skyViewFactor
          TerrainMode.OPENNESS -> m.openness
          TerrainMode.DISTURBANCE -> m.disturbance
        }
      if (data.isEmpty() || m.width <= 0) null
      else rasterToBitmap(data, m.width, m.height)
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
        "SVF, Openness, hillshade, and ground-disturbance for spotting pits/mounds. " +
          "Uses shared Oracle backend when enabled (same host as Viewshade).",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TerrainMode.entries.forEach { m ->
          FilterChip(
            selected = mode == m,
            onClick = { mode = m },
            label = {
              Text(
                when (m) {
                  TerrainMode.HILLSHADE -> "Hillshade"
                  TerrainMode.SVF -> "SVF"
                  TerrainMode.OPENNESS -> "Openness"
                  TerrainMode.DISTURBANCE -> "Disturb"
                },
              )
            },
          )
        }
      }
      if (bitmap != null) {
        Image(
          bitmap = bitmap.asImageBitmap(),
          contentDescription = mode.name,
          contentScale = ContentScale.FillBounds,
          modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        )
      } else {
        Text("Rendering…", style = MaterialTheme.typography.bodySmall)
      }
      Text(
        when (mode) {
          TerrainMode.HILLSHADE -> "Hillshade — relief under light from NW."
          TerrainMode.SVF -> "Low SVF (dark) = depressions: privies, cellars, hollows."
          TerrainMode.OPENNESS -> "Openness — banks, berms, free sky (from SVF on cloud)."
          TerrainMode.DISTURBANCE -> "Disturbance — residual highs/lows (foundations)."
        },
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

private fun rasterToBitmap(data: FloatArray, width: Int, height: Int): Bitmap {
  val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val pixels = IntArray(data.size.coerceAtMost(width * height))
  val n = minOf(data.size, width * height)
  for (i in 0 until n) {
    val v = (data[i].coerceIn(0f, 1f) * 255).toInt()
    val r = (40 + v * 0.85).toInt().coerceIn(0, 255)
    val g = (30 + v * 0.7).toInt().coerceIn(0, 255)
    val b = (20 + v * 0.35).toInt().coerceIn(0, 255)
    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
  }
  bmp.setPixels(pixels, 0, width, 0, 0, width, height)
  return bmp
}
