package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.NormalizedRasterBounds
import com.example.data.TargetSignal
import com.example.geospatial.GeoSpatialLibrary
import com.example.geospatial.GeoSpatialLibrary.GeographicBounds
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.flow.filter

@Composable
fun GeoreferencedTerrainMap(
    bitmap: Bitmap?,
    isRendering: Boolean,
    bounds: GeographicBounds,
    loggedSignals: List<TargetSignal>,
    gridSpacing: Float,
    viewportResetKey: Int,
    onViewportChanged: (NormalizedRasterBounds, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapBounds = remember(bounds) {
        LatLngBounds(
            LatLng(bounds.minLat, bounds.minLon),
            LatLng(bounds.maxLat, bounds.maxLon),
        )
    }
    val overlayImage = remember(bitmap) {
        bitmap
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.let(BitmapDescriptorFactory::fromBitmap)
    }
    val cameraPositionState = rememberCameraPositionState()
    var mapLoaded by remember { mutableStateOf(false) }
    val gridLines = remember(bounds, gridSpacing) { buildGridLines(bounds, gridSpacing) }
    val overlayMetadata = remember(bounds) {
        GeoSpatialLibrary.GeoSpatialMetadata(siteName = "Terrain layer", bounds = bounds)
    }

    LaunchedEffect(mapLoaded, mapBounds, viewportResetKey) {
        if (mapLoaded) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(mapBounds, 64))
        }
    }
    LaunchedEffect(cameraPositionState, bounds) {
        snapshotFlow { cameraPositionState.isMoving }
            .filter { !it }
            .collect {
                val visible = cameraPositionState.projection?.visibleRegion?.latLngBounds
                    ?: return@collect
                val visibleBounds = GeographicBounds(
                    minLat = visible.southwest.latitude,
                    maxLat = visible.northeast.latitude,
                    minLon = visible.southwest.longitude,
                    maxLon = visible.northeast.longitude,
                )
                val (viewport, zoom) = normalizedViewportFor(bounds, visibleBounds)
                onViewportChanged(viewport, zoom)
            }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1C1D21))
            .testTag("georeferenced_terrain_map"),
    ) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = remember { MapProperties(mapType = MapType.HYBRID) },
            uiSettings = remember {
                MapUiSettings(
                    compassEnabled = true,
                    mapToolbarEnabled = false,
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                )
            },
            onMapLoaded = { mapLoaded = true },
        ) {
            overlayImage?.let { image ->
                GroundOverlay(
                    position = GroundOverlayPosition.create(mapBounds),
                    image = image,
                    transparency = 0.18f,
                    zIndex = 1f,
                )
            }
            gridLines.forEach { points ->
                Polyline(
                    points = points,
                    color = Color(0xFF29B6F6).copy(alpha = 0.55f),
                    width = 2f,
                    zIndex = 2f,
                )
            }
            loggedSignals.forEach { signal ->
                GeoSpatialLibrary.gridToGeographic(signal.gridX, signal.gridY, overlayMetadata)
                    ?.let { (latitude, longitude) ->
                        Marker(
                            state = rememberUpdatedMarkerState(
                                position = LatLng(latitude, longitude),
                            ),
                            title = signal.metalType.label,
                            snippet = "Strength ${signal.signalStrength.toInt()}%",
                            zIndex = 3f,
                        )
                    }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        ) {
            Text(
                text = "Satellite + terrain",
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        if (isRendering || bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}


private fun buildGridLines(
    bounds: GeographicBounds,
    gridSpacing: Float,
): List<List<LatLng>> {
    if (gridSpacing < 1f) return emptyList()
    val lines = mutableListOf<List<LatLng>>()
    var percent = gridSpacing
    var count = 0
    while (percent < 100f && count < 50) {
        val fraction = percent / 100.0
        val longitude = bounds.minLon + fraction * (bounds.maxLon - bounds.minLon)
        val latitude = bounds.maxLat - fraction * (bounds.maxLat - bounds.minLat)
        lines += listOf(
            LatLng(bounds.minLat, longitude),
            LatLng(bounds.maxLat, longitude),
        )
        lines += listOf(
            LatLng(latitude, bounds.minLon),
            LatLng(latitude, bounds.maxLon),
        )
        percent += gridSpacing
        count++
    }
    return lines
}
