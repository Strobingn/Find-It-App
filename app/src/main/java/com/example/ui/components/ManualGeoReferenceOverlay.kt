package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.NormalizedRasterBounds
import com.example.geospatial.GeoSpatialLibrary
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.math.cos
import com.example.geospatial.MeasurementFormat

/**
 * Manual geo-reference mode for LAZ files without GPS coordinates.
 * Shows Google Maps with a draggable/scaleable overlay that the user can position manually.
 */
@Composable
fun ManualGeoReferenceOverlay(
    bitmap: Bitmap?,
    isRendering: Boolean,
    metadata: GeoSpatialLibrary.GeoSpatialMetadata,
    overlayOpacity: Float,
    onOverlayOpacityChanged: (Float) -> Unit,
    onGeoReferenceSet: (GeoSpatialLibrary.GeoSpatialMetadata) -> Unit,
    onViewportChanged: (NormalizedRasterBounds, Float) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val cameraPositionState = rememberCameraPositionState()
    var mapType by remember { mutableStateOf(MapType.HYBRID) }

    // Manual positioning state
    var overlayCenter by remember { mutableStateOf(LatLng(40.7128, -74.0060)) } // Default to NYC
    var overlayScaleMeters by remember { mutableFloatStateOf(100f) } // 100m default
    var overlayRotation by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }

    val overlayImage = remember(bitmap) {
        bitmap
            ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.let { BitmapDescriptorFactory.fromBitmap(it) }
    }

    // Calculate overlay bounds from center + scale
    val overlayBounds = remember(overlayCenter, overlayScaleMeters, overlayRotation) {
        val halfSize = overlayScaleMeters / 2.0
        val latOffset = halfSize / 111_000.0 // rough meters to degrees
        val lonOffset = halfSize / (111_000.0 * cos(Math.toRadians(overlayCenter.latitude)))
        com.google.android.gms.maps.model.LatLngBounds(
            LatLng(overlayCenter.latitude - latOffset, overlayCenter.longitude - lonOffset),
            LatLng(overlayCenter.latitude + latOffset, overlayCenter.longitude + lonOffset),
        )
    }

    // Report viewport for detail loading
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.position }
            .distinctUntilChanged()
            .collect { position ->
                val zoom = position.zoom
                val normalized = NormalizedRasterBounds(
                    left = 0.0, top = 0.0, right = 1.0, bottom = 1.0
                )
                val relativeZoom = (zoom - 14f).coerceIn(0.5f, 32f)
                onViewportChanged(normalized, relativeZoom)
            }
    }

    Box(modifier = modifier.fillMaxSize().testTag("manual_geo_overlay")) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = mapType,
                isBuildingEnabled = false,
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                compassEnabled = true,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
            ),
        ) {
            if (overlayImage != null) {
                GroundOverlay(
                    position = GroundOverlayPosition.create(overlayBounds),
                    image = overlayImage,
                    transparency = (1f - overlayOpacity).coerceIn(0f, 0.95f),
                    clickable = false,
                )
            }
        }

        // Drag overlay layer
        if (overlayImage != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                dragStart = offset
                            },
                            onDragEnd = { isDragging = false },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                // Convert drag pixels to lat/lng offset
                                val projection = cameraPositionState.projection
                                if (projection != null) {
                                    val startPoint = android.graphics.Point(
                                        change.position.x.toInt(),
                                        change.position.y.toInt()
                                    )
                                    val endPoint = android.graphics.Point(
                                        (change.position.x - dragAmount.x).toInt(),
                                        (change.position.y - dragAmount.y).toInt()
                                    )
                                    val startLatLng = projection.fromScreenLocation(startPoint)
                                    val endLatLng = projection.fromScreenLocation(endPoint)
                                    val dLat = startLatLng.latitude - endLatLng.latitude
                                    val dLng = startLatLng.longitude - endLatLng.longitude
                                    overlayCenter = LatLng(
                                        overlayCenter.latitude + dLat,
                                        overlayCenter.longitude + dLng
                                    )
                                }
                            }
                        )
                    }
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 4.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Manual Positioning — Drag overlay to align",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "Center: %.5f, %.5f".format(overlayCenter.latitude, overlayCenter.longitude),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "Hybrid" to MapType.HYBRID,
                            "Sat" to MapType.SATELLITE,
                            "Road" to MapType.NORMAL,
                        ).forEach { (label, type) ->
                            FilterChip(
                                selected = mapType == type,
                                onClick = { mapType = type },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            // Scale slider
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 4.dp,
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "Overlay size: ${MeasurementFormat.length(overlayScaleMeters)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = overlayScaleMeters,
                        onValueChange = { overlayScaleMeters = it },
                        valueRange = 10f..1000f,
                    )
                }
            }

            // Save button
            Button(
                onClick = {
                    val geoMetadata = GeoSpatialLibrary.GeoSpatialMetadata(
                        siteName = metadata.siteName + " (manual)",
                        bounds = GeoSpatialLibrary.GeographicBounds(
                            minLat = (overlayBounds.southwest?.latitude ?: overlayCenter.latitude - 0.001),
                            maxLat = (overlayBounds.northeast?.latitude ?: overlayCenter.latitude + 0.001),
                            minLon = (overlayBounds.southwest?.longitude ?: overlayCenter.longitude - 0.001),
                            maxLon = (overlayBounds.northeast?.longitude ?: overlayCenter.longitude + 0.001),
                        ),
                        crs = "WGS 84 (manual)",
                        datum = "WGS 84",
                        resolutionMeters = metadata.resolutionMeters,
                        columns = metadata.columns,
                        rows = metadata.rows,
                    )
                    onGeoReferenceSet(geoMetadata)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("✓ Save Geo-Reference")
            }
        }

        if (isRendering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
