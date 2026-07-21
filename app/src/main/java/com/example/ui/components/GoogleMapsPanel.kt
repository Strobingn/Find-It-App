package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.geospatial.GeoSpatialLibrary
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * Full-screen Google Map for field context (replaces the Finds tab).
 * Centers on the active terrain site when georeferenced.
 */
@Composable
fun GoogleMapsPanel(
    metadata: GeoSpatialLibrary.GeoSpatialMetadata,
    modifier: Modifier = Modifier,
) {
    val bounds = metadata.bounds
    val center = remember(bounds, metadata.siteName) {
        if (bounds != null) {
            LatLng(
                (bounds.minLat + bounds.maxLat) / 2.0,
                (bounds.minLon + bounds.maxLon) / 2.0,
            )
        } else {
            // Default: Pacific Northwest demo corridor used by built-in sites.
            LatLng(43.1205, -124.4085)
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(center, if (bounds != null) 16f else 12f)
    }

    var mapType by remember { mutableStateOf(MapType.HYBRID) }

    LaunchedEffect(center, bounds) {
        if (bounds != null) {
            val llBounds = LatLngBounds(
                LatLng(bounds.minLat, bounds.minLon),
                LatLng(bounds.maxLat, bounds.maxLon),
            )
            cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(llBounds, 80))
        } else {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(center, 12f))
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("google_maps_panel")) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapType = mapType,
                isBuildingEnabled = true,
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = true,
                compassEnabled = true,
                mapToolbarEnabled = true,
                myLocationButtonEnabled = false,
            ),
        ) {
            Marker(
                state = MarkerState(position = center),
                title = metadata.siteName,
                snippet = if (metadata.isGeoreferenced) {
                    metadata.crs
                } else {
                    "Local grid — no geographic bounds on this layer"
                },
            )
            if (bounds != null) {
                Polygon(
                    points = listOf(
                        LatLng(bounds.minLat, bounds.minLon),
                        LatLng(bounds.minLat, bounds.maxLon),
                        LatLng(bounds.maxLat, bounds.maxLon),
                        LatLng(bounds.maxLat, bounds.minLon),
                    ),
                    fillColor = androidx.compose.ui.graphics.Color(0x332196F3),
                    strokeColor = androidx.compose.ui.graphics.Color(0xFF2196F3),
                    strokeWidth = 3f,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!BuildConfig.HAS_MAPS_API_KEY) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                ) {
                    Text(
                        "No Maps API key. Add MAPS_API_KEY to secrets.properties (or CI secret) and rebuild.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 4.dp,
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        metadata.siteName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    Text(
                        if (metadata.isGeoreferenced) {
                            "Showing terrain footprint on Google Maps"
                        } else {
                            "Imported layer is local-grid only — map shows default area"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MapTypeChip("Hybrid", MapType.HYBRID, mapType) { mapType = it }
                        MapTypeChip("Terrain", MapType.TERRAIN, mapType) { mapType = it }
                        MapTypeChip("Sat", MapType.SATELLITE, mapType) { mapType = it }
                        MapTypeChip("Road", MapType.NORMAL, mapType) { mapType = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapTypeChip(
    label: String,
    type: MapType,
    selected: MapType,
    onSelect: (MapType) -> Unit,
) {
    FilterChip(
        selected = selected == type,
        onClick = { onSelect(type) },
        label = { Text(label) },
    )
}
