package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.data.ElevationGrid
import com.example.geospatial.GeoSpatialLibrary
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.GroundOverlay
import com.google.android.gms.maps.model.GroundOverlayOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import kotlin.math.cos

@Composable
fun TerrainGoogleMapScreen(
    terrainBitmap: Bitmap?,
    grid: ElevationGrid,
    metadata: GeoSpatialLibrary.GeoSpatialMetadata,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = rememberManagedMapView()
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var overlay by remember { mutableStateOf<GroundOverlay?>(null) }
    var cameraCenter by remember { mutableStateOf(LatLng(39.5, -98.35)) }
    var manualBounds by remember(metadata.siteName) {
        mutableStateOf<GeoSpatialLibrary.GeographicBounds?>(null)
    }
    var opacity by rememberSaveable { mutableFloatStateOf(0.72f) }
    var mapType by rememberSaveable { mutableStateOf(GoogleMap.MAP_TYPE_HYBRID) }
    var editBounds by rememberSaveable { mutableStateOf(false) }
    val activeBounds = manualBounds ?: metadata.bounds

    DisposableEffect(mapView) {
        mapView.getMapAsync { map ->
            googleMap = map
            map.mapType = mapType
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isMapToolbarEnabled = false
            map.uiSettings.isZoomControlsEnabled = false
            map.setOnCameraIdleListener { cameraCenter = map.cameraPosition.target }
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasLocationPermission) {
                runCatching {
                    map.isMyLocationEnabled = true
                    map.uiSettings.isMyLocationButtonEnabled = true
                }
            }
        }
        onDispose {
            overlay?.remove()
            googleMap = null
        }
    }

    LaunchedEffect(googleMap, mapType) {
        googleMap?.mapType = mapType
    }

    LaunchedEffect(googleMap, terrainBitmap, activeBounds, opacity) {
        val map = googleMap ?: return@LaunchedEffect
        overlay?.remove()
        overlay = null
        val bitmap = terrainBitmap?.takeIf { !it.isRecycled } ?: return@LaunchedEffect
        val bounds = activeBounds ?: return@LaunchedEffect
        val latLngBounds = bounds.toLatLngBounds()
        overlay = map.addGroundOverlay(
            GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .positionFromBounds(latLngBounds)
                .transparency(1f - opacity.coerceIn(0.1f, 1f))
                .zIndex(4f),
        )
        mapView.post {
            runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, 72)) }
                .onFailure { map.moveCamera(CameraUpdateFactory.newLatLngZoom(latLngBounds.center, 16f)) }
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        OverlayHeader(
            mapType = mapType,
            onMapTypeChanged = { mapType = it },
            status = when {
                BuildConfig.MAPS_API_KEY.isBlank() -> "MAPS_API_KEY is missing from .env/local.properties"
                terrainBitmap == null -> "Render or import a terrain layer first"
                activeBounds == null -> "Move the map, then place the LAZ image at the map center"
                manualBounds != null -> "Manually aligned on Google Maps"
                else -> "Using geographic bounds from the terrain file"
            },
            isError = BuildConfig.MAPS_API_KEY.isBlank(),
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(0.96f),
        )

        OverlayControls(
            opacity = opacity,
            onOpacityChanged = { opacity = it },
            canPlace = terrainBitmap != null,
            canRestoreFileBounds = metadata.bounds != null && manualBounds != null,
            onPlaceAtCenter = {
                manualBounds = boundsCenteredAt(
                    center = cameraCenter,
                    widthMeters = (grid.width - 1).coerceAtLeast(1) * grid.cellSizeMeters,
                    heightMeters = (grid.height - 1).coerceAtLeast(1) * grid.cellSizeMeters,
                )
            },
            onEditBounds = { editBounds = true },
            onRestoreFileBounds = { manualBounds = null },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(0.96f),
        )
    }

    if (editBounds) {
        BoundsEditorDialog(
            initial = activeBounds,
            onDismiss = { editBounds = false },
            onApply = {
                manualBounds = it
                editBounds = false
            },
        )
    }
}

@Composable
private fun OverlayHeader(
    mapType: Int,
    onMapTypeChanged: (Int) -> Unit,
    status: String,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Rendered LiDAR overlay", fontWeight = FontWeight.Bold)
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    "Map" to GoogleMap.MAP_TYPE_NORMAL,
                    "Satellite" to GoogleMap.MAP_TYPE_SATELLITE,
                    "Hybrid" to GoogleMap.MAP_TYPE_HYBRID,
                    "Terrain" to GoogleMap.MAP_TYPE_TERRAIN,
                ).forEach { (label, type) ->
                    FilterChip(
                        selected = mapType == type,
                        onClick = { onMapTypeChanged(type) },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayControls(
    opacity: Float,
    onOpacityChanged: (Float) -> Unit,
    canPlace: Boolean,
    canRestoreFileBounds: Boolean,
    onPlaceAtCenter: () -> Unit,
    onEditBounds: () -> Unit,
    onRestoreFileBounds: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Overlay opacity", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text("${(opacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
            Slider(value = opacity, onValueChange = onOpacityChanged, valueRange = 0.1f..1f)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onPlaceAtCenter, enabled = canPlace) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Place at map center")
                }
                OutlinedButton(onClick = onEditBounds) {
                    Icon(Icons.Default.EditLocationAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Exact bounds")
                }
                if (canRestoreFileBounds) {
                    OutlinedButton(onClick = onRestoreFileBounds) {
                        Icon(Icons.Default.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Use file bounds")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoundsEditorDialog(
    initial: GeoSpatialLibrary.GeographicBounds?,
    onDismiss: () -> Unit,
    onApply: (GeoSpatialLibrary.GeographicBounds) -> Unit,
) {
    var south by remember(initial) { mutableStateOf(initial?.minLat?.toString().orEmpty()) }
    var north by remember(initial) { mutableStateOf(initial?.maxLat?.toString().orEmpty()) }
    var west by remember(initial) { mutableStateOf(initial?.minLon?.toString().orEmpty()) }
    var east by remember(initial) { mutableStateOf(initial?.maxLon?.toString().orEmpty()) }
    val bounds = remember(south, north, west, east) {
        val s = south.toDoubleOrNull()
        val n = north.toDoubleOrNull()
        val w = west.toDoubleOrNull()
        val e = east.toDoubleOrNull()
        if (s != null && n != null && w != null && e != null &&
            s in -90.0..90.0 && n in -90.0..90.0 &&
            w in -180.0..180.0 && e in -180.0..180.0 && n > s && e > w
        ) GeoSpatialLibrary.GeographicBounds(s, n, w, e) else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Align LAZ overlay") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the WGS84 south, north, west, and east footprint for the LAZ image.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField("South", south, { south = it }, Modifier.weight(1f))
                    CoordinateField("North", north, { north = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField("West", west, { west = it }, Modifier.weight(1f))
                    CoordinateField("East", east, { east = it }, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { bounds?.let(onApply) }, enabled = bounds != null) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CoordinateField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(16)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun rememberManagedMapView(): MapView {
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

private fun GeoSpatialLibrary.GeographicBounds.toLatLngBounds(): LatLngBounds =
    LatLngBounds(LatLng(minLat, minLon), LatLng(maxLat, maxLon))

private fun boundsCenteredAt(
    center: LatLng,
    widthMeters: Float,
    heightMeters: Float,
): GeoSpatialLibrary.GeographicBounds {
    val halfLat = heightMeters.coerceAtLeast(1f) / 111_320.0 / 2.0
    val metersPerLongitudeDegree = (111_320.0 * cos(Math.toRadians(center.latitude))).coerceAtLeast(10_000.0)
    val halfLon = widthMeters.coerceAtLeast(1f) / metersPerLongitudeDegree / 2.0
    return GeoSpatialLibrary.GeographicBounds(
        minLat = (center.latitude - halfLat).coerceAtLeast(-90.0),
        maxLat = (center.latitude + halfLat).coerceAtMost(90.0),
        minLon = (center.longitude - halfLon).coerceAtLeast(-180.0),
        maxLon = (center.longitude + halfLon).coerceAtMost(180.0),
    )
}
