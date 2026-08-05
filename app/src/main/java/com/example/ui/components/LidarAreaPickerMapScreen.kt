package com.example.ui.components

import android.graphics.Point
import android.os.Bundle
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.geospatial.GeoSpatialLibrary
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A map dedicated to choosing the exact footprint for a public LiDAR search.
 *
 * This deliberately owns neither a terrain bitmap nor any terrain-alignment controls. The terrain
 * Map tab remains reserved for positioning and tuning an imported overlay.
 */
@Composable
fun LidarAreaPickerMapScreen(
    onAreaSelected: (GeoSpatialLibrary.GeographicBounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mapView = rememberLidarPickerMapView()
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var drawingBox by rememberSaveable { mutableStateOf(false) }
    var selectionStart by remember { mutableStateOf<LatLng?>(null) }
    var selectionEnd by remember { mutableStateOf<LatLng?>(null) }
    var selectionPolygon by remember { mutableStateOf<Polygon?>(null) }
    var instruction by remember { mutableStateOf("Pan or zoom to your site, then choose Draw box.") }

    fun clearSelection() {
        selectionPolygon?.remove()
        selectionPolygon = null
        selectionStart = null
        selectionEnd = null
    }

    fun mapPointAt(offset: Offset): LatLng? = googleMap?.projection?.fromScreenLocation(
        Point(offset.x.roundToInt(), offset.y.roundToInt()),
    )

    fun redrawSelection() {
        val first = selectionStart ?: return
        val second = selectionEnd ?: return
        val bounds = lidarSelectionBounds(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude,
        ) ?: return
        val corners = listOf(
            LatLng(bounds.minLat, bounds.minLon),
            LatLng(bounds.minLat, bounds.maxLon),
            LatLng(bounds.maxLat, bounds.maxLon),
            LatLng(bounds.maxLat, bounds.minLon),
        )
        val map = googleMap ?: return
        val polygon = selectionPolygon ?: map.addPolygon(
            PolygonOptions()
                // Maps rejects an empty PolygonOptions object at runtime, so seed it with all
                // four corners before the mutable [Polygon.points] update below.
                .add(corners[0])
                .add(corners[1])
                .add(corners[2])
                .add(corners[3])
                .strokeColor(android.graphics.Color.rgb(31, 111, 235))
                .fillColor(android.graphics.Color.argb(44, 31, 111, 235))
                .strokeWidth(6f)
                .zIndex(10f),
        ).also { selectionPolygon = it }
        polygon.points = corners
    }

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            googleMap = map
            map.mapType = GoogleMap.MAP_TYPE_HYBRID
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isMapToolbarEnabled = false
            map.uiSettings.isZoomControlsEnabled = false
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_LIDAR_SEARCH_CENTER, 4f))
        }
        onDispose {
            selectionPolygon?.remove()
            googleMap = null
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag("lidar_area_map")) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        if (drawingBox) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("lidar_box_drawing")
                    .pointerInput(googleMap) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                clearSelection()
                                selectionStart = mapPointAt(offset)
                                selectionEnd = selectionStart
                                instruction = "Drag to surround the LiDAR area. Lift your finger to find matching tiles."
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                if (selectionStart == null) return@detectDragGestures
                                selectionEnd = mapPointAt(change.position)
                                redrawSelection()
                            },
                            onDragCancel = {
                                clearSelection()
                                drawingBox = false
                                instruction = "Box selection cancelled. Pan or zoom, then choose Draw box."
                            },
                            onDragEnd = {
                                val first = selectionStart
                                val second = selectionEnd
                                val bounds = if (first != null && second != null) {
                                    lidarSelectionBounds(
                                        first.latitude,
                                        first.longitude,
                                        second.latitude,
                                        second.longitude,
                                    )
                                } else {
                                    null
                                }
                                clearSelection()
                                if (bounds == null) {
                                    instruction = "Draw a larger box that does not cross the date line."
                                } else {
                                    drawingBox = false
                                    onAreaSelected(bounds)
                                }
                            },
                        )
                    },
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .fillMaxWidth(0.94f),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Select a LiDAR area", style = MaterialTheme.typography.titleMedium)
                Text(instruction, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = 10.dp)) {
                    if (drawingBox) {
                        OutlinedButton(
                            onClick = {
                                clearSelection()
                                drawingBox = false
                                instruction = "Box selection cancelled. Pan or zoom, then choose Draw box."
                            },
                        ) { Text("Cancel") }
                    } else {
                        Button(
                            onClick = {
                                clearSelection()
                                drawingBox = true
                                instruction = "Drag one box around the ground you want to search."
                            },
                            modifier = Modifier.testTag("begin_lidar_box"),
                        ) { Text("Draw box") }
                    }
                }
            }
        }
    }
}

/**
 * Normalizes two screen-picked corners to the bounds format used by the USGS LiDAR request.
 * A geographic bounds object cannot represent a small box crossing the antimeridian, so that
 * gesture is rejected instead of searching almost the entire world.
 */
internal fun lidarSelectionBounds(
    firstLatitude: Double,
    firstLongitude: Double,
    secondLatitude: Double,
    secondLongitude: Double,
): GeoSpatialLibrary.GeographicBounds? {
    if (
        !firstLatitude.isFinite() || !firstLongitude.isFinite() ||
        !secondLatitude.isFinite() || !secondLongitude.isFinite() ||
        firstLatitude !in -90.0..90.0 || secondLatitude !in -90.0..90.0 ||
        firstLongitude !in -180.0..180.0 || secondLongitude !in -180.0..180.0 ||
        abs(firstLongitude - secondLongitude) > 180.0
    ) {
        return null
    }
    val minLat = minOf(firstLatitude, secondLatitude)
    val maxLat = maxOf(firstLatitude, secondLatitude)
    val minLon = minOf(firstLongitude, secondLongitude)
    val maxLon = maxOf(firstLongitude, secondLongitude)
    if (minLat >= maxLat || minLon >= maxLon) return null
    return GeoSpatialLibrary.GeographicBounds(minLat, maxLat, minLon, maxLon)
}

private val DEFAULT_LIDAR_SEARCH_CENTER = LatLng(39.5, -98.35)

@Composable
private fun rememberLidarPickerMapView(): MapView {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) { MapView(context).apply { onCreate(Bundle()) } }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }
    return mapView
}
