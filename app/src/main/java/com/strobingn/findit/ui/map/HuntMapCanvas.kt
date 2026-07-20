package com.strobingn.findit.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.strobingn.findit.data.model.FindRecord
import com.strobingn.findit.data.model.SearchGrid
import com.strobingn.findit.data.model.TeamMember

/**
 * Offline hunt map: projects lat/lng into a local canvas (no Maps key required).
 * Grids, finds, and team members all render for field use.
 */
@Composable
fun HuntMapCanvas(
  finds: List<FindRecord>,
  grids: List<SearchGrid>,
  team: List<TeamMember>,
  showTeam: Boolean,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer()
  val surface = MaterialTheme.colorScheme.surfaceVariant
  val onSurface = MaterialTheme.colorScheme.onSurface
  val primary = MaterialTheme.colorScheme.primary
  val tertiary = MaterialTheme.colorScheme.tertiary
  val secondary = MaterialTheme.colorScheme.secondary

  Box(modifier.background(surface)) {
    Canvas(Modifier.fillMaxSize()) {
      val bounds = boundsOf(finds, grids, team)
      fun project(lat: Double, lng: Double): Offset {
        val x = ((lng - bounds.minLng) / bounds.lngSpan).toFloat() * size.width
        val y = (1f - ((lat - bounds.minLat) / bounds.latSpan).toFloat()) * size.height
        return Offset(x, y)
      }

      // grid cells
      grids.forEach { g ->
        val sw = project(g.sw.lat, g.sw.lng)
        val ne = project(g.ne.lat, g.ne.lng)
        val left = minOf(sw.x, ne.x)
        val top = minOf(sw.y, ne.y)
        val w = kotlin.math.abs(ne.x - sw.x)
        val h = kotlin.math.abs(ne.y - sw.y)
        drawRect(
          color = primary.copy(alpha = 0.15f),
          topLeft = Offset(left, top),
          size = Size(w, h),
        )
        drawRect(
          color = primary.copy(alpha = 0.7f),
          topLeft = Offset(left, top),
          size = Size(w, h),
          style = Stroke(width = 3f),
        )
        val rows = g.rowCount().coerceAtMost(40)
        val cols = g.colCount().coerceAtMost(40)
        if (rows > 0 && cols > 0 && w > 0 && h > 0) {
          val cellW = w / cols
          val cellH = h / rows
          for (r in 0 until rows) {
            for (c in 0 until cols) {
              val id = g.cellId(r, c)
              if (id in g.coveredCellIds) {
                drawRect(
                  color = secondary.copy(alpha = 0.35f),
                  topLeft = Offset(left + c * cellW, top + r * cellH),
                  size = Size(cellW, cellH),
                )
              }
            }
          }
          // light grid lines
          for (c in 1 until cols) {
            val x = left + c * cellW
            drawLine(primary.copy(alpha = 0.25f), Offset(x, top), Offset(x, top + h), strokeWidth = 1f)
          }
          for (r in 1 until rows) {
            val y = top + r * cellH
            drawLine(primary.copy(alpha = 0.25f), Offset(left, y), Offset(left + w, y), strokeWidth = 1f)
          }
        }
      }

      // finds
      finds.forEach { f ->
        val p = project(f.location.lat, f.location.lng)
        drawCircle(color = tertiary, radius = 14f, center = p)
        drawCircle(color = Color.White, radius = 14f, center = p, style = Stroke(3f))
        val label =
          measurer.measure(
            text = f.title.take(18),
            style = TextStyle(color = onSurface, fontSize = 11.sp),
          )
        drawText(label, topLeft = Offset(p.x + 16f, p.y - 8f))
      }

      // team
      if (showTeam) {
        team.forEach { m ->
          val p = project(m.location.lat, m.location.lng)
          drawCircle(color = primary, radius = 10f, center = p)
          drawCircle(
            color = primary.copy(alpha = 0.35f),
            radius = 28f,
            center = p,
            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
          )
          val label =
            measurer.measure(
              text = m.name,
              style = TextStyle(color = onSurface, fontSize = 11.sp),
            )
          drawText(label, topLeft = Offset(p.x + 14f, p.y + 10f))
        }
        // visibility links
        for (i in team.indices) {
          for (j in i + 1 until team.size) {
            val a = project(team[i].location.lat, team[i].location.lng)
            val b = project(team[j].location.lat, team[j].location.lng)
            drawLine(primary.copy(alpha = 0.4f), a, b, strokeWidth = 2f)
          }
        }
      }
    }
  }
}

private data class Bounds(
  val minLat: Double,
  val maxLat: Double,
  val minLng: Double,
  val maxLng: Double,
) {
  val latSpan: Double get() = (maxLat - minLat).takeIf { it > 1e-9 } ?: 0.002
  val lngSpan: Double get() = (maxLng - minLng).takeIf { it > 1e-9 } ?: 0.002
}

private fun boundsOf(
  finds: List<FindRecord>,
  grids: List<SearchGrid>,
  team: List<TeamMember>,
): Bounds {
  val lats = mutableListOf<Double>()
  val lngs = mutableListOf<Double>()
  finds.forEach {
    lats += it.location.lat
    lngs += it.location.lng
  }
  grids.forEach {
    lats += it.sw.lat
    lats += it.ne.lat
    lngs += it.sw.lng
    lngs += it.ne.lng
  }
  team.forEach {
    lats += it.location.lat
    lngs += it.location.lng
  }
  if (lats.isEmpty()) {
    return Bounds(39.827, 39.830, -98.581, -98.578)
  }
  val padLat = ((lats.max() - lats.min()).coerceAtLeast(0.0005)) * 0.2
  val padLng = ((lngs.max() - lngs.min()).coerceAtLeast(0.0005)) * 0.2
  return Bounds(
    minLat = lats.min() - padLat,
    maxLat = lats.max() + padLat,
    minLng = lngs.min() - padLng,
    maxLng = lngs.max() + padLng,
  )
}
