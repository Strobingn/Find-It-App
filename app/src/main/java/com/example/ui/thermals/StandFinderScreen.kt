package com.example.ui.thermals

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.geospatial.GeoSpatialLibrary
import com.example.thermals.StandFinderViewModel
import com.example.thermals.StandPick
import com.example.thermals.ThermalMapGeometry
import com.example.thermals.ThermalWindEngine
import com.example.thermals.ThermalWindViewModel
import com.example.ui.ios.IosDivider
import com.example.ui.ios.IosIconRowDividerInset
import com.example.ui.ios.IosRow
import com.example.ui.ios.IosSection
import com.example.ui.ios.IosSheet
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

private val StandPinColor = Color(0xFFFF9F0A)
private val AreaPinColor = Color(0xFF30D158)

/**
 * AI stand finder on a real Google Map: numbered pins for the best stand sites, circles for the
 * best hunting areas, a red line showing which way scent drifts from each stand during its best
 * sit. Tap a pin (or a card) for the recommendation; long-press the map to search somewhere else.
 */
@Composable
fun StandFinderScreen(
    padding: PaddingValues,
    siteMetadata: GeoSpatialLibrary.GeoSpatialMetadata?,
    modifier: Modifier = Modifier,
    thermalViewModel: ThermalWindViewModel = viewModel(),
    finderViewModel: StandFinderViewModel = viewModel(),
) {
    val thermal by thermalViewModel.state.collectAsStateWithLifecycle()
    val finder by finderViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(siteMetadata?.bounds) {
        val bounds = siteMetadata?.bounds ?: return@LaunchedEffect
        thermalViewModel.offerSiteLocation((bounds.minLat + bounds.maxLat) / 2.0, (bounds.minLon + bounds.maxLon) / 2.0, siteMetadata.siteName)
    }

    val lat = thermal.latitude
    val lon = thermal.longitude
    val cameraPositionState = rememberCameraPositionState {
        position = if (lat != null && lon != null) {
            CameraPosition.fromLatLngZoom(LatLng(lat, lon), 15f)
        } else {
            CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3.5f)
        }
    }
    LaunchedEffect(lat, lon) {
        if (lat != null && lon != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15f), 800)
        }
    }
    LaunchedEffect(finder.selectedRank) {
        finder.selected?.let { pick ->
            cameraPositionState.animate(CameraUpdateFactory.newLatLng(LatLng(pick.latitude, pick.longitude)), 500)
        }
    }

    fun runSearch() {
        val la = thermal.latitude ?: return
        val lo = thermal.longitude ?: return
        finderViewModel.find(
            lat = la,
            lon = lo,
            inputs = ThermalWindViewModel.buildInputs(thermal),
            forecast = thermal.forecast,
            locationLabel = thermal.locationLabel,
            dateLabel = ThermalWindViewModel.dateLabel(thermal),
        )
    }

    Box(modifier.fillMaxSize().padding(padding).testTag("stand_finder_screen")) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.HYBRID),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = true),
            onMapLongClick = { latLng -> thermalViewModel.useLocation(latLng.latitude, latLng.longitude, "Map pin") },
        ) {
            // Search center.
            if (lat != null && lon != null) {
                val centerIcon = remember { dotIcon(Color.White, 22) }
                Marker(
                    state = remember(lat, lon) { MarkerState(position = LatLng(lat, lon)) },
                    title = "Search center",
                    snippet = "Long-press the map to search somewhere else",
                    icon = centerIcon,
                    anchor = androidx.compose.ui.geometry.Offset(0.5f, 0.5f),
                )
            }
            finder.picks.forEach { pick -> key(pick.rank, pick.latitude, pick.longitude) {
                val position = LatLng(pick.latitude, pick.longitude)
                if (pick.kind == StandPick.Kind.AREA) {
                    Circle(
                        center = position,
                        radius = 140.0,
                        fillColor = AreaPinColor.copy(alpha = 0.22f),
                        strokeColor = AreaPinColor,
                        strokeWidth = 4f,
                    )
                }
                pick.scentToward?.let { toward ->
                    val (eLat, eLon) = ThermalMapGeometry.destination(pick.latitude, pick.longitude, toward, 180.0)
                    Polyline(
                        points = listOf(position, LatLng(eLat, eLon)),
                        color = ScentColor,
                        width = 8f,
                        pattern = listOf(Dash(24f), Gap(14f)),
                    )
                }
                val icon = remember(pick.rank, pick.kind) {
                    numberedPin(pick.rank, if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor)
                }
                Marker(
                    state = remember(pick.latitude, pick.longitude) { MarkerState(position = position) },
                    title = "${pick.rank}. ${pick.title}",
                    snippet = pick.bestTime,
                    icon = icon,
                    onClick = {
                        finderViewModel.select(pick.rank)
                        true
                    },
                )
            } }
        }

        // Top: search button + status.
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassPanel {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Best stands & hunt areas", style = MaterialTheme.typography.titleMedium)
                            Text(
                                when {
                                    lat == null || lon == null -> "Long-press the map on your property, or set a location in Thermals."
                                    finder.running -> finder.stage ?: "Working…"
                                    finder.source != null -> "${finder.picks.size} picks · chosen by ${finder.source}"
                                    else -> "Scans 1.6 km of terrain, thermals and wind, then the AI picks spots."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (finder.running) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Button(
                                onClick = ::runSearch,
                                enabled = lat != null && lon != null,
                                modifier = Modifier.testTag("stand_find_button"),
                            ) { Text(if (finder.picks.isEmpty()) "Find" else "Again") }
                        }
                    }
                    finder.error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Bottom: swipeable pick cards.
        if (finder.picks.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp).testTag("stand_pick_cards"),
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(finder.picks, key = { it.rank }) { pick ->
                    PickCard(pick, selected = finder.selectedRank == pick.rank) { finderViewModel.select(pick.rank) }
                }
            }
        }
    }

    finder.selected?.let { pick ->
        IosSheet(title = "${pick.rank}. ${pick.title}", onDismiss = { finderViewModel.select(null) }, peek = true) {
            IosSection(header = if (pick.kind == StandPick.Kind.AREA) "Hunting area" else "Stand", footer = finder.source?.let { "Picked by $it" }) {
                if (pick.bestTime.isNotBlank()) IosRow(title = "When", value = pick.bestTime)
                if (pick.wind.isNotBlank()) {
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Wind", subtitle = pick.wind)
                }
                pick.scentToward?.let {
                    IosDivider(startInset = 16.dp)
                    IosRow(title = "Scent drifts", value = "→ ${ThermalWindEngine.compass(it)}")
                }
            }
            if (pick.why.isNotBlank()) {
                IosSection(header = "Why here") {
                    Text(pick.why, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            if (pick.entry.isNotBlank()) {
                IosSection(header = "Getting in and out") {
                    Text(pick.entry, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            IosSection {
                IosRow(
                    title = "Navigate here",
                    icon = Icons.Default.Directions,
                    showChevron = true,
                    onClick = {
                        val uri = Uri.parse("google.navigation:q=${pick.latitude},${pick.longitude}&mode=w")
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${pick.latitude},${pick.longitude}?q=${pick.latitude},${pick.longitude}"))
                        runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(fallback) } }
                    },
                )
                IosDivider(startInset = IosIconRowDividerInset)
                IosRow(
                    title = "Use as my stand",
                    subtitle = "Thermals and Wind Map switch to this spot",
                    icon = Icons.Default.Place,
                    showChevron = true,
                    onClick = {
                        thermalViewModel.useLocation(pick.latitude, pick.longitude, "${pick.rank}. ${pick.title}")
                        finderViewModel.select(null)
                    },
                )
                IosDivider(startInset = IosIconRowDividerInset)
                IosRow(
                    title = "%.5f, %.5f".format(pick.latitude, pick.longitude),
                    icon = Icons.Default.Forest,
                    titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GlassPanel(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun PickCard(pick: StandPick, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (selected) 1f else 0.9f),
        border = if (selected) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.width(230.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .padding(0.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(shape = RoundedCornerShape(11.dp), color = if (pick.kind == StandPick.Kind.AREA) AreaPinColor else StandPinColor) {
                        Text(
                            "${pick.rank}",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(pick.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (pick.bestTime.isNotBlank()) {
                Text(pick.bestTime, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(pick.why, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round pin with a rank number, drawn once per rank. */
private fun numberedPin(number: Int, color: Color): BitmapDescriptor {
    val size = 84
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = color.toArgb()
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 6f, paint)
    paint.color = android.graphics.Color.BLACK
    paint.textSize = 40f
    paint.typeface = Typeface.DEFAULT_BOLD
    paint.textAlign = Paint.Align.CENTER
    val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(number.toString(), size / 2f, y, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}

private fun dotIcon(color: Color, sizePx: Int): BitmapDescriptor {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.BLACK
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, paint)
    paint.color = color.toArgb()
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, paint)
    return BitmapDescriptorFactory.fromBitmap(bitmap)
}
